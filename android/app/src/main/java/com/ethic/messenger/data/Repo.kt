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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
    val shopVersion = MutableStateFlow(0)
    /** The set is complete: ask whether to build the scooter. */
    val scooterAsk = MutableStateFlow(false)
    val events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 32)

    @Volatile var activeChat: Long? = null
    @Volatile var foreground = false
    var emailEnabled = false
    var inviteRequired = false

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("ethic", Context.MODE_PRIVATE)
        Api.deviceId = p.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }
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
    fun chatName(chat: Chat) = if (chat.isSaved) "Избранное" else if (chat.isGroup) chat.title ?: "Группа" else other(chat)?.displayName ?: "Чат"

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
            stt = it["stt"]?.jsonPrimitive?.contentOrNull == "true"
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
    /** Ethic OS: signs in from the system account. Not after the person signed out of the messenger themselves. */
    suspend fun signInWithSystem(ctx: android.content.Context, manual: Boolean = false): Boolean {
        if (!manual && prefs.getBoolean("systemSignedOut", false)) return false
        val token = com.ethic.messenger.SystemAccount.token(ctx) ?: return false
        Api.token = token
        prefs.edit().putString("token", token).putString("systemAccount", com.ethic.messenger.SystemAccount.username(ctx)).remove("systemSignedOut").apply()
        return restore()
    }

    /** Ethic OS: the system account this session came from left the phone or changed, so the session ends too. */
    suspend fun dropIfSystemAccountGone(ctx: android.content.Context) {
        val was = prefs.getString("systemAccount", null) ?: return
        if (com.ethic.messenger.SystemAccount.username(ctx) == was) return
        if (Api.token != null) { com.ethic.messenger.Push.unregister(); runCatching { Api.call("/api/logout", "POST") } }
        clearSession()
    }

    suspend fun restore(): Boolean {
        if (Api.token == null) return false
        return try {
            val res = Api.call("/api/me")
            start(Api.json.decodeFromJsonElement(res["user"]!!))
            true
        } catch (e: ApiError) {
            // Banned or deleted: the token stays, so the window comes back at every start until the person signs out.
            if (e.status == 401) { if (ended.value == null) clearSession(); false } else {
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
        com.ethic.messenger.Push.register()
        if (user.dev) scope.launch { runCatching { Api.call("/api/admin/stats") }.onSuccess { reports.value = it["reports"]?.jsonPrimitive?.int ?: 0 } }
        // Warnings given while the person was offline.
        scope.launch { runCatching { Api.call("/api/warnings") }.onSuccess { queueWarnings(Api.json.decodeFromJsonElement(it["warnings"]!!)) } }
        scope.launch { runCatching { ensureSaved() }; runCatching { loadFolders() } }
        // Not tied to the caller: the login screen (and its coroutines) goes away right after this.
        scope.launch { runCatching { loadChats() }.onFailure { toast(it.message ?: "Не удалось загрузить чаты") } }
    }

    fun logout() {
        val t = Api.token
        prefs.edit().putBoolean("systemSignedOut", true).apply()
        scope.launch {
            if (t != null) { com.ethic.messenger.Push.unregister(); runCatching { Api.call("/api/logout", "POST") } }
            clearSession()
        }
    }

    fun onUnauthorized() { scope.launch { clearSession() } }

    /** Banned or deleted: the window with the reason covers everything until the person signs out. */
    data class Ended(val deleted: Boolean, val reason: String?, val until: Long? = null)
    val ended = MutableStateFlow<Ended?>(null)

    /** Open reports on messages, for the staff badge. */
    val reports = MutableStateFlow(0)

    fun onEnded(state: String, reason: String?, until: Long? = null) {
        if (ended.value != null) return
        ended.value = Ended(state == "deleted", reason, until)
        ws?.close(1000, null); ws = null
    }

    /** Warnings from staff, shown one at a time until «Понятно». */
    @kotlinx.serialization.Serializable
    data class Warning(val id: Long, val reason: String? = null, val left: Int = 0)
    val warnings = MutableStateFlow<List<Warning>>(emptyList())

    private fun queueWarnings(list: List<Warning>) {
        warnings.update { cur -> cur + list.filter { w -> cur.none { it.id == w.id } } }
    }

    fun warningSeen() {
        val w = warnings.value.firstOrNull() ?: return
        warnings.update { it.drop(1) }
        scope.launch { runCatching { Api.call("/api/warnings/${w.id}/seen", "POST") } }
    }

    // ---------- social: reactions, forwarding, search, groups, folders, devices ----------
    var stt = false
    val folders = MutableStateFlow<List<Folder>>(emptyList())
    /** A link opened from outside or tapped in a chat: ethic://qr/… or …/join/CODE. */
    val pendingLink = MutableStateFlow<String?>(null)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun chatOf(o: JsonObject): Chat = Api.json.decodeFromJsonElement<Chat>(o["chat"]!!).also { upsertChat(it) }

    fun react(m: Message, emoji: String) = send(mapOf("type" to "react", "chatId" to m.chatId, "messageId" to m.id, "emoji" to emoji))
    fun forward(m: Message, toChat: Long) = send(mapOf("type" to "forward", "chatId" to toChat, "messageId" to m.id, "clientId" to "f${System.currentTimeMillis()}"))
    suspend fun search(q: String, chatId: Long?): List<Message> =
        Api.json.decodeFromJsonElement(Api.call("/api/search?q=${enc(q)}" + (chatId?.let { "&chatId=$it" } ?: ""))["messages"]!!)
    suspend fun patchChat(id: Long, body: Map<String, Any?>) = chatOf(Api.call("/api/chats/$id", "PATCH", body))
    suspend fun setAdmin(chatId: Long, userId: Long, admin: Boolean) = chatOf(Api.call("/api/chats/$chatId/admins", "POST", mapOf("userId" to userId, "admin" to admin)))
    suspend fun kick(chatId: Long, userId: Long) = chatOf(Api.call("/api/chats/$chatId/members/$userId", "DELETE"))
    suspend fun invite(chatId: Long, off: Boolean = false) = chatOf(Api.call("/api/chats/$chatId/invite", "POST", mapOf("off" to off)))
    suspend fun joinInfo(code: String): JoinInfo = Api.json.decodeFromJsonElement(Api.call("/api/join/${enc(code)}")["chat"]!!)
    suspend fun join(code: String) = chatOf(Api.call("/api/join/${enc(code)}", "POST"))
    suspend fun ensureSaved() = chatOf(Api.call("/api/chats/saved", "POST"))
    suspend fun createChannel(title: String, ids: List<Long>) = chatOf(Api.call("/api/chats/group", "POST", mapOf("title" to title, "memberIds" to ids, "channel" to true)))
    suspend fun loadFolders() { folders.value = Api.json.decodeFromJsonElement(Api.call("/api/folders")["folders"]!!) }
    suspend fun saveFolder(id: Long?, name: String, chatIds: List<Long>) {
        val body = mapOf("name" to name, "chatIds" to chatIds)
        folders.value = Api.json.decodeFromJsonElement((if (id == null) Api.call("/api/folders", "POST", body) else Api.call("/api/folders/$id", "PATCH", body))["folders"]!!)
    }
    suspend fun deleteFolder(id: Long) { folders.value = Api.json.decodeFromJsonElement(Api.call("/api/folders/$id", "DELETE")["folders"]!!) }
    suspend fun setBlocked(userId: Long, blocked: Boolean) = Api.call("/api/users/$userId/block", "POST", mapOf("blocked" to blocked))
    suspend fun isBlocked(userId: Long) = Api.call("/api/users/$userId")["blocked"]?.jsonPrimitive?.contentOrNull == "true"
    suspend fun blocks(): List<User> = Api.json.decodeFromJsonElement(Api.call("/api/blocks")["users"]!!)
    suspend fun devices(): List<Device> = Api.json.decodeFromJsonElement(Api.call("/id/v1/devices")["devices"]!!)
    suspend fun endDevice(id: String) = Api.call("/id/v1/devices/${enc(id)}", "DELETE")
    suspend fun endOtherDevices() = Api.call("/id/v1/devices/logout-others", "POST")
    suspend fun approveQr(token: String) = Api.call("/api/qr/${enc(token)}/approve", "POST")["device"]?.jsonPrimitive?.contentOrNull
    suspend fun preview(url: String): LinkPreview? = Api.call("/api/preview?url=${enc(url)}")["preview"]
        ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { Api.json.decodeFromJsonElement(it) }
    suspend fun transcribe(m: Message) {
        val text = Api.call("/api/messages/${m.id}/transcript", "POST")["text"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "(тишина)" }
        editMessages(m.chatId) { l -> l.map { if (it.id == m.id) it.copy(transcript = text) else it } }
    }

    // Drafts and per-chat sounds live on the phone.
    fun draft(chatId: Long) = prefs.getString("draft:$chatId", "").orEmpty()
    fun setDraft(chatId: Long, text: String) = prefs.edit().apply { if (text.isBlank()) remove("draft:$chatId") else putString("draft:$chatId", text) }.apply()
    fun chatMsgSound(chatId: Long) = prefs.getString("chatMsg:$chatId", null)
    fun chatRing(chatId: Long) = prefs.getString("chatRing:$chatId", null)
    fun setChatSounds(chatId: Long, msg: String?, ring: String?) = prefs.edit().apply {
        if (msg == null) remove("chatMsg:$chatId") else putString("chatMsg:$chatId", msg)
        if (ring == null) remove("chatRing:$chatId") else putString("chatRing:$chatId", ring)
    }.apply()

    fun leaveEnded() {
        // Not straight back in from the Ethic OS account either.
        prefs.edit().putBoolean("systemSignedOut", true).apply()
        ended.value = null
        clearSession()
    }

    private fun clearSession() {
        Api.token = null
        prefs.edit().remove("token").remove("systemAccount").apply()
        ws?.close(1000, null); ws = null
        warnings.value = emptyList()
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

    class ShopState(val coins: Int, val offer: Boolean, val canBuild: Boolean, val gifts: List<com.ethic.messenger.ui.ShopGift>)
    suspend fun loadShopState(): ShopState {
        val r = Api.call("/api/shop")
        return ShopState(
            r["coins"]!!.jsonPrimitive.int,
            r["offer"]?.jsonPrimitive?.boolean == true,
            r["canBuild"]?.jsonPrimitive?.boolean == true,
            Api.json.decodeFromJsonElement(r["gifts"]!!),
        )
    }

    suspend fun searchUsers(q: String, contacts: Boolean = false): List<User> {
        val res = Api.call("/api/users?" + (if (contacts) "contacts=1&" else "") + "q=" + java.net.URLEncoder.encode(q, "UTF-8"))
        return Api.json.decodeFromJsonElement<List<User>>(res["users"]!!).also { remember(it) }
    }

    suspend fun fetchUser(id: Long): User {
        val u: User = Api.json.decodeFromJsonElement(Api.call("/api/users/$id")["user"]!!)
        remember(listOf(u))
        return u
    }

    suspend fun fetchMe() {
        val u: User = Api.json.decodeFromJsonElement(Api.call("/api/me")["user"]!!)
        me.value = u
        remember(listOf(u))
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
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket, 0)
        })
    }

    private fun closed(socket: WebSocket, code: Int, reason: String = "") {
        scope.launch {
            if (socket != ws) return@launch
            connected.value = false
            if (code == 4001) {
                // Banned or deleted: one more request brings the window with the reason.
                if (reason == "banned") runCatching { Api.call("/api/me") }
                if (ended.value == null) { toast("Сессия завершена"); clearSession() }
                return@launch
            }
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
            "shop", "coins" -> shopVersion.value++
            "scooter:ready" -> scooterAsk.value = true
            "notice" -> ev["text"]?.jsonPrimitive?.contentOrNull?.let { toast(it) }
            "warning" -> ev["warning"]?.let { queueWarnings(listOf(Api.json.decodeFromJsonElement(it))) }
            "reactions" -> {
                val list: List<Reaction> = j.decodeFromJsonElement(ev["reactions"]!!)
                editMessages(ev.long("chatId")) { l -> l.map { if (it.id == ev.long("messageId")) it.copy(reactions = list) else it } }
            }
            "transcript" -> {
                val text = ev["text"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "(тишина)" }
                editMessages(ev.long("chatId")) { l -> l.map { if (it.id == ev.long("messageId")) it.copy(transcript = text) else it } }
            }
            "mention" -> if (ev.long("chatId") != activeChat) toast("${ev["from"]?.jsonPrimitive?.contentOrNull.orEmpty()} упомянул(а) вас в «${ev["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}»")
            "mute" -> {
                val m = ev["mute"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { Api.json.decodeFromJsonElement<User.Mute>(it) }
                me.update { it?.copy(mute = m) }
            }
            "reports:count" -> {
                val n = ev["open"]?.jsonPrimitive?.int ?: 0
                if (n > reports.value) toast("Новая жалоба на сообщение")
                reports.value = n
            }
            "gift" -> toast("${ev["from"]?.jsonPrimitive?.contentOrNull.orEmpty()} дарит вам ${ev["name"]?.jsonPrimitive?.contentOrNull.orEmpty()} №${ev["serial"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
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
            Sounds.received(chatMsgSound(m.chatId))
            if (!foreground) com.ethic.messenger.Notifications.message(m, chat)
        }
        if (visible) markRead(m.chatId)
    }
}
