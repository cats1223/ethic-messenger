package com.ethic.messenger.data

import android.content.Context
import android.content.SharedPreferences
import com.ethic.messenger.media.Sounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID

/** Events the UI reacts to once (calls, toasts). */
sealed interface AppEvent {
    data class Ring(val ev: RingEvent) : AppEvent
    data class CallState(val chatId: Long, val call: CallInfo?) : AppEvent
    data class Declined(val chatId: Long, val userId: Long) : AppEvent
    data class Invited(val chatId: Long, val userId: Long, val by: Long, val name: String) : AppEvent
    data class Toast(val text: String) : AppEvent
}

/** App state: session, chats, messages, realtime socket. */
object Repo {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var prefs: SharedPreferences
        private set

    val me = MutableStateFlow<User?>(null)
    val chats = MutableStateFlow<Map<Long, Chat>>(emptyMap())
    val users = MutableStateFlow<Map<Long, User>>(emptyMap())
    val messages = MutableStateFlow<Map<Long, ChatMessages>>(emptyMap())
    val typing = MutableStateFlow<Map<Long, Map<Long, Long>>>(emptyMap())
    val connected = MutableStateFlow(false)
    val loggedOut = MutableStateFlow(false)
    /** Bumped on every support-ticket event, so open ticket screens reload. */
    val ticketsVersion = MutableStateFlow(0)
    val events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 32)

    @Volatile var activeChat: Long? = null
    @Volatile var foreground = false
    var emailEnabled = false
    var inviteRequired = false

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ethic", Context.MODE_PRIVATE)
        Api.token = prefs.getString("token", null)
    }

    fun toast(text: String) { events.tryEmit(AppEvent.Toast(text)) }

    // ---------- names ----------
    fun user(id: Long): User? = if (id == me.value?.id) me.value else users.value[id]
    fun userName(id: Long) = user(id)?.displayName ?: "Пользователь"
    fun other(chat: Chat): User? {
        val m = chat.members.firstOrNull { it.id != me.value?.id } ?: chat.members.firstOrNull()
        return m?.let { users.value[it.id] ?: it }
    }
    fun chatName(chat: Chat) = if (chat.isGroup) chat.title ?: "Группа" else other(chat)?.displayName ?: "Чат"

    fun remember(list: List<User>) {
        if (list.isEmpty()) return
        users.update { cur -> cur + list.associate { u -> u.id to merge(cur[u.id], u) } }
        me.value?.let { m -> list.firstOrNull { it.id == m.id }?.let { me.value = merge(m, it).copy(email = it.email ?: m.email) } }
    }
    private fun merge(old: User?, new: User) = if (old == null) new else new.copy(email = new.email ?: old.email)

    // ---------- session ----------
    suspend fun loadConfig() {
        runCatching { Api.call("/api/config") }.onSuccess {
            emailEnabled = it["emailEnabled"]?.jsonPrimitive?.contentOrNull == "true"
            inviteRequired = it["inviteRequired"]?.jsonPrimitive?.contentOrNull == "true"
        }
    }

    suspend fun auth(path: String, body: Map<String, Any?>) {
        val res = Api.call(path, "POST", body)
        val token = res["token"]!!.jsonPrimitive.content
        Api.token = token
        prefs.edit().putString("token", token).apply()
        start(Api.json.decodeFromJsonElement(res["user"]!!))
    }

    /** Restores the saved session; false when there is none or it expired. */
    suspend fun restore(): Boolean {
        if (Api.token == null) return false
        return try {
            val res = Api.call("/api/me")
            start(Api.json.decodeFromJsonElement(res["user"]!!))
            true
        } catch (e: ApiError) {
            if (e.status == 401) { clearSession(); false } else {
                // Offline: still let the app open, it will reconnect.
                connectWs(); true
            }
        }
    }

    private suspend fun start(user: User) {
        loggedOut.value = false
        me.value = user
        remember(listOf(user))
        connectWs()
        // Not tied to the caller: the login screen (and its coroutines) goes away right after this.
        scope.launch { runCatching { loadChats() }.onFailure { toast(it.message ?: "Не удалось загрузить чаты") } }
    }

    fun logout() {
        val t = Api.token
        scope.launch { if (t != null) runCatching { Api.call("/api/logout", "POST") } ; clearSession() }
    }

    fun onUnauthorized() { scope.launch { clearSession() } }

    private fun clearSession() {
        Api.token = null
        prefs.edit().remove("token").apply()
        ws?.close(1000, null); ws = null
        me.value = null; chats.value = emptyMap(); messages.value = emptyMap(); users.value = emptyMap()
        loggedOut.value = true
    }

    // ---------- chats ----------
    suspend fun loadChats() {
        val res = Api.call("/api/chats")
        val list: List<Chat> = Api.json.decodeFromJsonElement(res["chats"]!!)
        list.forEach { remember(it.members) }
        chats.value = list.associateBy { it.id }
    }

    fun upsertChat(chat: Chat) {
        remember(chat.members)
        chats.update { it + (chat.id to chat) }
    }

    fun updateChat(id: Long, f: (Chat) -> Chat) = chats.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    suspend fun openDirect(userId: Long): Chat {
        val res = Api.call("/api/chats/direct", "POST", mapOf("userId" to userId))
        return Api.json.decodeFromJsonElement<Chat>(res["chat"]!!).also { upsertChat(it) }
    }

    suspend fun createGroup(title: String, ids: List<Long>): Chat {
        val res = Api.call("/api/chats/group", "POST", mapOf("title" to title, "memberIds" to ids))
        return Api.json.decodeFromJsonElement<Chat>(res["chat"]!!).also { upsertChat(it) }
    }

    suspend fun addMember(chatId: Long, userId: Long) {
        val res = Api.call("/api/chats/$chatId/members", "POST", mapOf("userId" to userId))
        upsertChat(Api.json.decodeFromJsonElement(res["chat"]!!))
    }

    suspend fun acceptRequest(chatId: Long) = upsertChat(Api.json.decodeFromJsonElement(Api.call("/api/chats/$chatId/accept", "POST")["chat"]!!))
    suspend fun declineRequest(chatId: Long) {
        Api.call("/api/chats/$chatId/decline", "POST")
        chats.update { it - chatId }; messages.update { it - chatId }
    }

    suspend fun leaveChat(chatId: Long) {
        Api.call("/api/chats/$chatId/members/me", "DELETE")
        chats.update { it - chatId }
        messages.update { it - chatId }
    }

    suspend fun searchUsers(q: String): List<User> {
        val res = Api.call("/api/users?q=" + java.net.URLEncoder.encode(q, "UTF-8"))
        return Api.json.decodeFromJsonElement<List<User>>(res["users"]!!).also { remember(it) }
    }

    suspend fun fetchUser(id: Long): User {
        val u: User = Api.json.decodeFromJsonElement(Api.call("/api/users/$id")["user"]!!)
        remember(listOf(u))
        return u
    }

    suspend fun updateMe(body: Map<String, Any?>) {
        val u: User = Api.json.decodeFromJsonElement(Api.call("/api/me", "PATCH", body)["user"]!!)
        me.value = u
        remember(listOf(u))
    }

    // ---------- messages ----------
    suspend fun loadMessages(chatId: Long, older: Boolean = false) {
        val cur = messages.value[chatId]
        val before = if (older) cur?.list?.firstOrNull { !it.pending }?.id else null
        val res = Api.call("/api/chats/$chatId/messages" + (before?.let { "?before=$it" } ?: ""))
        val list: List<Message> = Api.json.decodeFromJsonElement(res["messages"]!!)
        val hasMore = res["hasMore"]?.jsonPrimitive?.contentOrNull == "true"
        messages.update { m ->
            val pending = m[chatId]?.list?.filter { it.pending }.orEmpty()
            val merged = if (older && cur != null) list + cur.list else list + pending
            m + (chatId to ChatMessages(merged, hasMore, true))
        }
    }

    private fun editMessages(chatId: Long, f: (List<Message>) -> List<Message>) =
        messages.update { m -> m[chatId]?.let { m + (chatId to it.copy(list = f(it.list))) } ?: m }

    fun markRead(chatId: Long) {
        if (!foreground || activeChat != chatId) return
        val last = messages.value[chatId]?.list?.lastOrNull { !it.pending } ?: return
        if ((chats.value[chatId]?.unread ?: 0) > 0) updateChat(chatId) { it.copy(unread = 0) }
        send(mapOf("type" to "read", "chatId" to chatId, "messageId" to last.id))
    }

    private var pendingSeq = -1L
    private fun pushPending(chatId: Long, replyTo: Message?, base: Message): Message {
        val cid = UUID.randomUUID().toString()
        val reply = replyTo?.let { ReplyRef(it.id, it.userId, it.text.take(140), it.kind, it.file?.name) }
        val p = base.copy(id = pendingSeq--, chatId = chatId, userId = me.value!!.id, clientId = cid, pending = true,
            createdAt = System.currentTimeMillis(), replyTo = reply, fresh = true)
        editMessages(chatId) { it + p }
        return p
    }

    fun sendText(chatId: Long, text: String, replyTo: Message?) {
        val p = pushPending(chatId, replyTo, Message(0, chatId, 0, text = text))
        Sounds.sent()
        send(mapOf("type" to "message", "chatId" to chatId, "text" to text, "clientId" to p.clientId, "replyTo" to replyTo?.id))
    }

    /** Uploads [uri] and posts it; [build] turns the upload result into the message fields. */
    fun sendUpload(
        ctx: Context, chatId: Long, uri: android.net.Uri, kind: String, name: String, text: String, replyTo: Message?,
        preview: Message, build: (url: String, size: Long, ext: String?) -> Map<String, Any?>,
    ) {
        val p = pushPending(chatId, replyTo, preview.copy(text = text, localUri = uri.toString(), progress = 0f))
        Sounds.sent()
        scope.launch {
            try {
                var last = 0f
                val (url, size, ext) = Api.upload(ctx, uri, kind, name) { pr ->
                    if (pr - last > 0.03f || pr >= 1f) {
                        last = pr
                        scope.launch { editMessages(chatId) { l -> l.map { if (it.clientId == p.clientId) it.copy(progress = pr) else it } } }
                    }
                }
                send(mapOf("type" to "message", "chatId" to chatId, "clientId" to p.clientId, "text" to text, "replyTo" to replyTo?.id) + build(url, size, ext))
            } catch (e: Exception) {
                toast("Не отправлено: ${e.message}")
                editMessages(chatId) { l -> l.filter { it.clientId != p.clientId } }
            }
        }
    }

    fun editMessage(m: Message, text: String) = send(mapOf("type" to "edit", "chatId" to m.chatId, "messageId" to m.id, "text" to text))
    fun deleteMessage(m: Message) = send(mapOf("type" to "delete", "chatId" to m.chatId, "messageId" to m.id))

    private var lastTyping = 0L
    fun sendTyping(chatId: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTyping < 2500) return
        lastTyping = now
        send(mapOf("type" to "typing", "chatId" to chatId))
    }

    fun typingNames(chatId: Long, map: Map<Long, Map<Long, Long>>): List<String> {
        val now = System.currentTimeMillis()
        return map[chatId].orEmpty().filter { it.value > now }.keys.map { userName(it) }
    }

    // ---------- websocket ----------
    private var ws: WebSocket? = null
    private var retry = 0

    fun connectWs() {
        val token = Api.token ?: return
        ws?.cancel()
        val req = Request.Builder().url("${Api.WS}?token=$token").build()
        ws = Api.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    if (webSocket != ws) return@launch
                    connected.value = true
                    if (retry > 0) runCatching {
                        loadChats()
                        messages.value.keys.forEach { loadMessages(it) }
                    }
                    retry = 0
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (webSocket == ws) runCatching { handle(Api.json.parseToJsonElement(text).jsonObject) } }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, code)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket, 0)
        })
    }

    private fun closed(socket: WebSocket, code: Int) {
        scope.launch {
            if (socket != ws) return@launch
            connected.value = false
            if (code == 4001) { toast("Сессия завершена"); clearSession(); return@launch }
            if (Api.token == null) return@launch
            retry++
            delay(minOf(1000L shl minOf(retry, 4), 15_000L))
            if (socket == ws) connectWs()
        }
    }

    /** Reconnect right away when the app comes back to the foreground. */
    fun onForeground() {
        if (Api.token != null && me.value != null && !connected.value) { retry = 1; connectWs() }
        activeChat?.let { markRead(it) }
    }

    fun send(obj: Map<String, Any?>): Boolean = ws?.send(Api.toJson(obj).toString()) ?: false

    private fun JsonObject.long(name: String) = this[name]?.jsonPrimitive?.longOrNull ?: 0L

    private fun handle(ev: JsonObject) {
        val j = Api.json
        when (ev["type"]?.jsonPrimitive?.content) {
            "message" -> onMessage(j.decodeFromJsonElement(ev["message"]!!), ev["clientId"]?.jsonPrimitive?.contentOrNull)
            "message:update" -> {
                val m: Message = j.decodeFromJsonElement(ev["message"]!!)
                editMessages(m.chatId) { l -> l.map { if (it.id == m.id) m else it } }
                updateChat(m.chatId) { if (it.lastMessage?.id == m.id) it.copy(lastMessage = m) else it }
            }
            "message:delete" -> {
                val cid = ev.long("chatId"); val mid = ev.long("messageId")
                editMessages(cid) { l ->
                    l.filter { it.id != mid }.map { if (it.replyTo?.id == mid) it.copy(replyTo = ReplyRef(mid, deleted = true)) else it }
                }
                val last = ev["lastMessage"]?.takeIf { it is JsonObject }?.let { j.decodeFromJsonElement<Message>(it) }
                updateChat(cid) { it.copy(lastMessage = last) }
            }
            "read" -> {
                val cid = ev.long("chatId")
                if (ev.long("userId") != me.value?.id) updateChat(cid) { it.copy(othersRead = maxOf(it.othersRead, ev.long("messageId"))) }
            }
            "chat" -> upsertChat(j.decodeFromJsonElement(ev["chat"]!!))
            "ticket", "tickets:count" -> ticketsVersion.value++
            "notice" -> ev["text"]?.jsonPrimitive?.contentOrNull?.let { toast(it) }
            "chat:removed" -> {
                val cid = ev.long("chatId")
                chats.update { it - cid }; messages.update { it - cid }
                ev["text"]?.jsonPrimitive?.contentOrNull?.let { toast(it) }
            }
            "user" -> {
                val u: User = j.decodeFromJsonElement(ev["user"]!!)
                remember(listOf(u))
                chats.update { m -> m.mapValues { (_, c) -> c.copy(members = c.members.map { if (it.id == u.id) u else it }) } }
            }
            "presence" -> {
                val id = ev.long("userId")
                val online = ev["online"]?.jsonPrimitive?.contentOrNull == "true"
                users.update { m -> m[id]?.let { m + (id to it.copy(online = online, lastSeen = ev["lastSeen"]?.jsonPrimitive?.longOrNull)) } ?: m }
            }
            "typing" -> setTyping(ev.long("chatId"), ev.long("userId"), true)
            "call:ring" -> events.tryEmit(AppEvent.Ring(j.decodeFromJsonElement(ev)))
            "call:state" -> {
                val cid = ev.long("chatId")
                val call = ev["call"]?.takeIf { it is JsonObject }?.let { j.decodeFromJsonElement<CallInfo>(it) }
                updateChat(cid) { it.copy(call = call) }
                events.tryEmit(AppEvent.CallState(cid, call))
            }
            "call:declined" -> events.tryEmit(AppEvent.Declined(ev.long("chatId"), ev.long("userId")))
            "call:invited" -> {
                val u = ev["user"]?.takeIf { it is JsonObject }?.let { j.decodeFromJsonElement<User>(it) }
                u?.let { remember(listOf(it)) }
                events.tryEmit(AppEvent.Invited(ev.long("chatId"), ev.long("userId"), ev.long("by"), u?.displayName ?: userName(ev.long("userId"))))
            }
        }
    }

    private fun setTyping(chatId: Long, userId: Long, on: Boolean) {
        typing.update { t ->
            val m = t[chatId].orEmpty().toMutableMap()
            if (on) m[userId] = System.currentTimeMillis() + 4000 else m.remove(userId)
            t + (chatId to m)
        }
        if (on) scope.launch { delay(4100); typing.update { it.toMap() } }
    }

    private fun onMessage(m: Message, clientId: String?) {
        val chat = chats.value[m.chatId]
        if (chat == null) { scope.launch { runCatching { loadChats() } }; return }
        val mine = m.userId == me.value?.id
        messages.update { all ->
            val st = all[m.chatId] ?: return@update all
            val i = if (clientId != null) st.list.indexOfFirst { it.clientId == clientId } else -1
            val list = if (i >= 0) st.list.toMutableList().also { it[i] = m.copy(localUri = st.list[i].localUri, fresh = true) }
            else st.list + m.copy(fresh = true)
            all + (m.chatId to st.copy(list = list))
        }
        setTyping(m.chatId, m.userId, false)
        val visible = m.chatId == activeChat && foreground
        updateChat(m.chatId) { it.copy(lastMessage = m, unread = if (!mine && !visible) it.unread + 1 else it.unread) }
        if (!mine) {
            Sounds.received()
            if (!foreground) com.ethic.messenger.Notifications.message(m, chat)
        }
        if (visible) markRead(m.chatId)
    }
}
