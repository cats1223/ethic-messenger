package com.ethic.messenger.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.ethic.messenger.call.CallManager
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Chat
import com.ethic.messenger.data.Device
import com.ethic.messenger.data.Folder
import com.ethic.messenger.data.JoinInfo
import com.ethic.messenger.data.LinkPreview
import com.ethic.messenger.data.Message
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.User
import com.ethic.messenger.media.Sounds
import kotlinx.coroutines.launch

/** The address people see and share: without a port. The app itself still talks to the server on 8443. */
const val PUBLIC_URL = "https://ethicmessenger.duckdns.org:8443"

// ---------- links and @mentions in message text ----------
private val URL_RE = Regex("""(https?://[^\s<]+|www\.[^\s<]+)""")
private val MENTION_RE = Regex("""(^|[\s(])@([A-Za-z0-9_.]{2,32})""")

/** «…/join/CODE» of our own server: joining a group, not a web page. */
fun joinCode(url: String): String? = Regex("""^https?://[^/\s]+/join/([\w-]{4,32})/?$""").find(url)
    ?.takeIf { url.startsWith(Api.BASE) || url.contains("ethicmessenger") }?.groupValues?.get(1)

fun firstUrl(text: String): String? = URL_RE.find(text)?.value?.let { if (it.startsWith("www.")) "https://$it" else it }

fun linkify(text: String, link: Color): AnnotatedString = buildAnnotatedString {
    data class Span(val range: IntRange, val value: String, val mention: Boolean)
    val spans = URL_RE.findAll(text).map { Span(it.range, it.value, false) }.toMutableList()
    MENTION_RE.findAll(text).forEach { m ->
        val g = m.groups[2]!!
        val r = (g.range.first - 1)..g.range.last
        if (spans.none { it.range.first <= r.last && r.first <= it.range.last }) spans += Span(r, g.value, true)
    }
    var i = 0
    for (s in spans.sortedBy { it.range.first }) {
        append(text.substring(i, s.range.first))
        val seg = text.substring(s.range.first, s.range.last + 1)
        if (s.mention) withStyle(SpanStyle(color = link, fontWeight = FontWeight.SemiBold)) { append(seg) }
        else {
            val url = if (s.value.startsWith("www.")) "https://${s.value}" else s.value
            val style = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
            if (joinCode(url) != null) withLink(LinkAnnotation.Clickable("join", style) { Repo.pendingLink.value = url }) { append(seg) }
            else withLink(LinkAnnotation.Url(url, style)) { append(seg) }
        }
        i = s.range.last + 1
    }
    append(text.substring(i))
}

fun mentionsMe(text: String): Boolean {
    val me = Repo.me.value?.username ?: return false
    return Regex("""@(${Regex.escape(me)}|all)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

// ---------- reactions ----------
val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "👎", "🎉")

@Composable
fun ReactionsRow(m: Message, mine: Boolean) {
    if (m.reactions.isEmpty()) return
    val meId = Repo.me.value?.id
    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        m.reactions.forEach { r ->
            val on = meId in r.users
            Text(
                "${r.emoji} ${r.users.size}", fontSize = 13.sp, color = if (mine && !on) Color.Black else Color.White,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (on) Blue.copy(alpha = 0.45f) else if (mine) Color(0x22000000) else Color(0x22FFFFFF))
                    .clickable { Repo.react(m, r.emoji) }.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun ReactionPicker(m: Message, onDone: () -> Unit) {
    Dialog(onDone) {
        Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Surface1).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            REACTIONS.forEach { e -> Text(e, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable { Repo.react(m, e); onDone() }.padding(6.dp)) }
        }
    }
}

// ---------- forwarding ----------
@Composable
fun ForwardDialog(m: Message, onDone: () -> Unit) {
    val chats by Repo.chats.collectAsState()
    val meId = Repo.me.value?.id
    val list = chats.values.filter { it.request == null && (!it.isChannel || it.isAdmin(meId)) }.sortedByDescending { it.sortKey }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Переслать") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { Repo.forward(m, c.id); Repo.toast("Переслано"); onDone() }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(c, 36.dp, if (c.isGroup || c.isSaved) null else Repo.other(c))
                        Spacer(Modifier.width(10.dp))
                        Text(Repo.chatName(c), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDone) { Text("Отмена") } },
    )
}

// ---------- search ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchDialog(chatId: Long?, onDone: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var here by remember { mutableStateOf(chatId != null) }
    val found = remember { mutableStateListOf<Message>() }
    LaunchedEffect(q, here) {
        kotlinx.coroutines.delay(300)
        if (q.trim().length < 2) { found.clear(); return@LaunchedEffect }
        runCatching { Repo.search(q.trim(), if (here) chatId else null) }.onSuccess { found.clear(); found.addAll(it) }
    }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(containerColor = Bg, topBar = {
            TopAppBar(
                title = { Text("Поиск по сообщениям", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                Box(Modifier.padding(horizontal = 16.dp)) { Field(q, { q = it }, "Что найти") }
                if (chatId != null) Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("В этом чате", here) { here = true }
                    Chip("Во всех чатах", !here) { here = false }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(found, key = { it.id }) { m ->
                        Column(Modifier.fillMaxWidth().clickable { onDone(); Nav.openChat(m.chatId) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            val c = Repo.chats.value[m.chatId]
                            Text("${c?.let { Repo.chatName(it) } ?: "Чат"} · ${Repo.userName(m.userId)} · ${timeOf(m.createdAt)}", color = Muted, fontSize = 12.sp)
                            Text(m.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (q.trim().length >= 2 && found.isEmpty()) item { Text("Ничего не найдено", color = Muted, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected, onClick = onClick, label = { Text(label) },
    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.White, selectedLabelColor = Color.Black),
)

// ---------- one button opens everything about a chat ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatOptionsSheet(chat: Chat, onDismiss: () -> Unit) {
    val meId = Repo.me.value?.id
    var search by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(false) }
    var sounds by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(true) }
    if (sheet) ModalBottomSheet({ onDismiss() }, containerColor = Surface1) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            if (chat.isGroup) SheetItem(Icons.Default.Group, if (chat.isChannel) "Участники канала" else "Участники") { onDismiss(); Nav.push(Route.GroupR(chat.id)) }
            SheetItem(Icons.Default.Search, "Поиск по сообщениям") { sheet = false; search = true }
            if (chat.type == "group") SheetItem(Icons.Default.HeadsetMic, "Голосовая комната") { onDismiss(); CallManager.start(chat.id, false, voiceRoom = true) }
            if (!chat.isSaved) SheetItem(Icons.Default.MusicNote, "Звуки чата") { sheet = false; sounds = true }
            if (chat.type == "direct") SheetItem(Icons.Default.Timer, "Исчезающие сообщения") { sheet = false; timer = true }
            if (chat.isGroup && chat.isAdmin(meId)) SheetItem(Icons.Default.Settings, if (chat.isChannel) "Настройки канала" else "Настройки группы") { sheet = false; settings = true }
        }
    }
    if (search) SearchDialog(chat.id) { onDismiss() }
    if (settings) GroupSettingsDialog(chat) { onDismiss() }
    if (timer) TimerDialog(chat) { onDismiss() }
    if (sounds) SoundsDialog(chat) { onDismiss() }
}

val TIMERS = listOf(0 to "Выключены", 3600 to "Через 1 час", 86400 to "Через 1 день", 604800 to "Через 7 дней")

@Composable
fun TimerDialog(chat: Chat, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Исчезающие сообщения") },
        text = {
            Column {
                Text("Новые сообщения удалятся у всех через выбранное время.", color = Muted, fontSize = 13.sp)
                TIMERS.forEach { (s, label) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        scope.launch { runCatching { Repo.patchChat(chat.id, mapOf("disappear" to s)) }.onSuccess { Repo.toast("Сохранено") }.onFailure { Repo.toast(it.message ?: "") }; onDone() }
                    }, verticalAlignment = Alignment.CenterVertically) { RadioButton(chat.disappear == s, null); Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onDone) { Text("Закрыть") } },
    )
}

@Composable
fun SoundsDialog(chat: Chat, onDone: () -> Unit) {
    var msg by remember { mutableStateOf(Repo.chatMsgSound(chat.id)) }
    var ring by remember { mutableStateOf(Repo.chatRing(chat.id)) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Звуки чата") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Новое сообщение", color = Muted, fontSize = 13.sp)
                (listOf<Pair<String?, String>>(null to "Как обычно") + Sounds.messageKinds).forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clickable { msg = k; Sounds.received(k) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(msg == k, null); Text(label) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Звонок", color = Muted, fontSize = 13.sp)
                (listOf<Pair<String?, String>>(null to "Как обычно") + Sounds.ringtones.map { it.id to it.name }).forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clickable { ring = k; if (k != null) Sounds.ring(k, preview = true) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(ring == k, null); Text(label) }
                }
            }
        },
        confirmButton = { TextButton({ Sounds.stop(); Repo.setChatSounds(chat.id, msg, ring); Repo.toast("Звуки сохранены"); onDone() }) { Text("Сохранить") } },
        dismissButton = { TextButton({ Sounds.stop(); onDone() }) { Text("Отмена") } },
    )
}

@Composable
fun GroupSettingsDialog(chat: Chat, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val chats by Repo.chats.collectAsState()
    val c = chats[chat.id] ?: chat
    var title by remember { mutableStateOf(c.title.orEmpty()) }
    fun act(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onFailure { Repo.toast(it.message ?: "Ошибка") } }
    val link = c.inviteCode?.let { "$PUBLIC_URL/join/$it" }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (c.isChannel) "Настройки канала" else "Настройки группы") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(64) }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
                TextButton({ act { Repo.patchChat(c.id, mapOf("title" to title.trim())); Repo.toast("Сохранено") } }) { Text("Сохранить название") }
                HorizontalDivider(color = Line)
                Text("Ссылка-приглашение", color = Muted, fontSize = 13.sp)
                if (link == null) {
                    Text("По ссылке человек сам вступит, без вашего приглашения.", fontSize = 13.sp)
                    OutlinedButton({ act { Repo.invite(c.id) } }) { Text("Создать ссылку") }
                } else {
                    Text(link, fontSize = 13.sp, modifier = Modifier.background(Surface2, RoundedCornerShape(8.dp)).padding(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton({ scope.launch { clipboard.setClipEntry(android.content.ClipData.newPlainText("link", link).toClipEntry()) }; Repo.toast("Скопировано") }) { Text("Копировать") }
                        OutlinedButton({ act { Repo.invite(c.id) } }) { Text("Новая") }
                    }
                    TextButton({ act { Repo.invite(c.id, off = true) } }) { Text("Выключить ссылку", color = Red) }
                }
                HorizontalDivider(color = Line)
                Text("Исчезающие сообщения", color = Muted, fontSize = 13.sp)
                TIMERS.forEach { (s, label) ->
                    Row(Modifier.fillMaxWidth().clickable { act { Repo.patchChat(c.id, mapOf("disappear" to s)) } }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(c.disappear == s, null); Text(label)
                    }
                }
                Text("Администраторов назначают и исключают участников в списке участников: кнопка ⋯ у человека.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onDone) { Text("Готово") } },
    )
}

// ---------- links from outside or from a chat: joining a group, signing in a computer ----------
@Composable
fun LinkDialogs() {
    val link by Repo.pendingLink.collectAsState()
    val me by Repo.me.collectAsState()
    val url = link ?: return
    if (me == null) return
    val scope = rememberCoroutineScope()
    val qr = Regex("""^ethic://qr/([\w-]+)$""").find(url)?.groupValues?.get(1)
    val code = joinCode(url)
    fun done() { Repo.pendingLink.value = null }
    if (qr != null) {
        AlertDialog(
            onDismissRequest = ::done,
            title = { Text("Войти на компьютере?") },
            text = { Text("Компьютер, на экране которого вы отсканировали код, войдёт в ваш аккаунт. Подтверждайте, только если это ваш компьютер.") },
            confirmButton = { TextButton({
                done()
                scope.launch { runCatching { Repo.approveQr(qr) }.onSuccess { Repo.toast("Вход выполнен: ${it ?: "компьютер"}") }.onFailure { Repo.toast(it.message ?: "Ошибка") } }
            }) { Text("Войти") } },
            dismissButton = { TextButton(::done) { Text("Отмена") } },
        )
        return
    }
    if (code == null) { done(); return }
    val info by produceState<JoinInfo?>(null, code) { value = runCatching { Repo.joinInfo(code) }.onFailure { Repo.toast(it.message ?: "Ссылка недействительна"); done() }.getOrNull() }
    val i = info ?: return
    LaunchedEffect(i) { if (i.joined) { done(); Nav.openChat(i.id) } }
    if (i.joined) return
    AlertDialog(
        onDismissRequest = ::done,
        title = { Text("Вступить в «${i.title.orEmpty()}»?") },
        text = { Text("${if (i.type == "channel") "Канал" else "Группа"} · ${i.members} участн.") },
        confirmButton = { TextButton({
            done()
            scope.launch { runCatching { Repo.join(code) }.onSuccess { Nav.openChat(it.id) }.onFailure { Repo.toast(it.message ?: "Ошибка") } }
        }) { Text("Вступить") } },
        dismissButton = { TextButton(::done) { Text("Отмена") } },
    )
}

// ---------- folders ----------
@Composable
fun FolderTabs(selected: String, onSelect: (String) -> Unit) {
    val folders by Repo.folders.collectAsState()
    var edit by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf("all" to "Все", "direct" to "Личные", "groups" to "Группы") + folders.map { "${it.id}" to it.name }).forEach { (id, name) ->
            Chip(name, selected == id) { onSelect(id) }
        }
        Chip("⋯", false) { edit = true }
    }
    if (edit) FoldersDialog { edit = false }
}

fun inFolder(chat: Chat, folder: String, folders: List<Folder>): Boolean = when (folder) {
    "all" -> true
    "direct" -> chat.type == "direct"
    "groups" -> chat.isGroup
    else -> folders.firstOrNull { "${it.id}" == folder }?.chatIds?.contains(chat.id) ?: true
}

@Composable
fun FoldersDialog(onDone: () -> Unit) {
    val folders by Repo.folders.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Folder?>(null) }
    var creating by remember { mutableStateOf(false) }
    if (editing != null || creating) { FolderEditDialog(editing) { editing = null; creating = false }; return }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Папки") },
        text = {
            Column {
                if (folders.isEmpty()) Text("Своих папок пока нет. «Личные» и «Группы» есть всегда.", color = Muted, fontSize = 13.sp)
                folders.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f.name} · ${f.chatIds.size}", Modifier.weight(1f))
                        TextButton({ editing = f }) { Text("Изменить") }
                        TextButton({ scope.launch { runCatching { Repo.deleteFolder(f.id) } } }) { Text("Удалить", color = Red) }
                    }
                }
            }
        },
        confirmButton = { TextButton({ creating = true }) { Text("Новая папка") } },
        dismissButton = { TextButton(onDone) { Text("Готово") } },
    )
}

@Composable
private fun FolderEditDialog(folder: Folder?, onDone: () -> Unit) {
    val chats by Repo.chats.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(folder?.name.orEmpty()) }
    val picked = remember { mutableStateMapOf<Long, Boolean>().apply { folder?.chatIds?.forEach { put(it, true) } } }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (folder == null) "Новая папка" else "Изменить папку") },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(chats.values.sortedByDescending { it.sortKey }, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth().clickable { picked[c.id] = picked[c.id] != true }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(picked[c.id] == true, null)
                            Text(Repo.chatName(c), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({
            scope.launch { runCatching { Repo.saveFolder(folder?.id, name.trim(), picked.filterValues { it }.keys.toList()) }.onSuccess { onDone() }.onFailure { Repo.toast(it.message ?: "") } }
        }, enabled = name.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onDone) { Text("Отмена") } },
    )
}

// ---------- link previews ----------
private val previewCache = mutableMapOf<String, LinkPreview?>()

@Composable
fun LinkPreviewCard(text: String, mine: Boolean) {
    val url = firstUrl(text)?.takeIf { joinCode(it) == null } ?: return
    val ctx = LocalContext.current
    val p by produceState(previewCache[url], url) {
        if (!previewCache.containsKey(url)) value = runCatching { Repo.preview(url) }.getOrNull().also { previewCache[url] = it }
    }
    val card = p ?: return
    Column(
        Modifier.padding(top = 6.dp).widthIn(max = 280.dp).clip(RoundedCornerShape(8.dp))
            .background(if (mine) Color(0x14000000) else Color(0x14FFFFFF)).clickable { openUrl(ctx, card.url) }.padding(8.dp),
    ) {
        card.image?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop) }
        Text(card.site, color = Blue, fontSize = 12.sp)
        Text(card.title, color = if (mine) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        card.description?.let { Text(it, color = if (mine) Color(0xFF555555) else Muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

// ---------- round video messages ----------
@Composable
fun CircleVideo(url: String) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var playing by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(1f) }
    val side = 220.dp
    Box(Modifier.size(side).clip(CircleShape).background(Color(0xFF111111)).clickable { playing = !playing }, contentAlignment = Alignment.Center) {
        if (playing) {
            val player = remember { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(url)); repeatMode = Player.REPEAT_MODE_ONE; prepare(); play() } }
            DisposableEffect(Unit) {
                val l = object : Player.Listener {
                    override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0 && v.height > 0) aspect = v.width.toFloat() / v.height }
                }
                player.addListener(l)
                onDispose { player.removeListener(l); player.release() }
            }
            // Crop to the circle: the shorter side fills it.
            val w = if (aspect >= 1f) side * aspect else side
            val h = if (aspect >= 1f) side else side / aspect
            AndroidView({ TextureView(it).also { v -> player.setVideoTextureView(v) } }, Modifier.requiredSize(w, h))
        } else Icon(Icons.Default.PlayArrow, "Смотреть", tint = Color.White, modifier = Modifier.size(56.dp))
    }
}

// ---------- privacy and the block list ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen() {
    val me by Repo.me.collectAsState()
    val scope = rememberCoroutineScope()
    val blocked = remember { mutableStateListOf<User>() }
    LaunchedEffect(Unit) { runCatching { Repo.blocks() }.onSuccess { blocked.clear(); blocked.addAll(it) } }
    Scaffold(containerColor = Bg, topBar = {
        TopAppBar(title = { Text("Конфиденциальность", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton({ Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg))
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Text("Кто видит, что я в сети и когда был(а)", color = Muted, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
                Section {
                    listOf("all" to "Все", "contacts" to "Только те, с кем я переписываюсь", "nobody" to "Никто").forEach { (v, label) ->
                        Row(Modifier.fillMaxWidth().clickable { scope.launch { runCatching { Repo.updateMe(mapOf("lastSeenPrivacy" to v)) }.onFailure { Repo.toast(it.message ?: "") } } }
                            .padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton((me?.lastSeenPrivacy ?: "all") == v, null); Text(label)
                        }
                    }
                }
                Text("Чёрный список", color = Muted, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                Text("Эти люди не могут писать вам и звонить. Добавить человека — в его профиле, кнопка ⋮.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
                if (blocked.isEmpty()) Text("Никого", color = Muted, modifier = Modifier.padding(20.dp))
            }
            items(blocked, key = { it.id }) { u ->
                UserRow(u, trailing = {
                    TextButton({ scope.launch { runCatching { Repo.setBlocked(u.id, false) }.onSuccess { blocked.remove(u) } } }) { Text("Разблокировать") }
                }) { Nav.push(Route.UserR(u.id)) }
            }
        }
    }
}

// ---------- devices ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen() {
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf<Device>() }
    var ending by remember { mutableStateOf<Device?>(null) }
    var endAll by remember { mutableStateOf(false) }
    fun reload() = scope.launch { runCatching { Repo.devices() }.onSuccess { list.clear(); list.addAll(it) }.onFailure { Repo.toast(it.message ?: "") } }
    LaunchedEffect(Unit) { reload() }
    val scan = androidx.activity.compose.rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        val token = r.contents?.let { Regex("""^ethic://qr/([\w-]+)$""").find(it)?.groupValues?.get(1) }
        if (r.contents != null && token == null) Repo.toast("Это не код входа Ethic")
        token?.let { Repo.pendingLink.value = "ethic://qr/$it" }
    }
    Scaffold(containerColor = Bg, topBar = {
        TopAppBar(title = { Text("Устройства", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton({ Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg))
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Button({
                        scan.launch(com.journeyapps.barcodescanner.ScanOptions().setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                            .setPrompt("Наведите камеру на QR-код на экране компьютера").setBeepEnabled(false).setOrientationLocked(false))
                    }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                        Icon(Icons.Default.Checklist, null); Spacer(Modifier.width(8.dp)); Text("Привязать устройство", fontWeight = FontWeight.Bold)
                    }
                    Text("На компьютере откройте сайт или приложение Ethic и нажмите «Войти по QR-коду».", color = Muted, fontSize = 13.sp)
                }
            }
            items(list, key = { it.id }) { d ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Surface1).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (Regex("Android|iPhone").containsMatchIn(d.name) || d.platform == "android" || d.platform == "ios") "📱" else "💻", fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name + when (d.app) { "ethic-id" -> " · Ethic ID"; "clicker" -> " · Ethic Clicker"; else -> "" })
                        Text(if (d.current) "это устройство" else d.lastSeen?.let { "активно ${timeOf(it)}" } ?: "вход ${timeOf(d.createdAt)}",
                            color = if (d.current) Green else Muted, fontSize = 12.sp)
                    }
                    if (!d.current) TextButton({ ending = d }) { Text("Завершить", color = Red) }
                }
            }
            item { TextButton({ endAll = true }, Modifier.padding(12.dp)) { Text("Выйти на всех других устройствах", color = Red) } }
        }
    }
    ending?.let { d ->
        AlertDialog(onDismissRequest = { ending = null }, title = { Text("Завершить сеанс?") }, text = { Text("${d.name}: там придётся войти заново.") },
            confirmButton = { TextButton({ ending = null; scope.launch { runCatching { Repo.endDevice(d.id) }; reload() } }) { Text("Завершить", color = Red) } },
            dismissButton = { TextButton({ ending = null }) { Text("Отмена") } })
    }
    if (endAll) AlertDialog(onDismissRequest = { endAll = false }, title = { Text("Выйти на всех других?") },
        confirmButton = { TextButton({ endAll = false; scope.launch { runCatching { Repo.endOtherDevices() }; reload() } }) { Text("Выйти", color = Red) } },
        dismissButton = { TextButton({ endAll = false }) { Text("Отмена") } })
}
