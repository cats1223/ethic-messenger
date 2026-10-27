package com.ethic.messenger.ui

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.ethic.messenger.call.CallManager
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Chat
import com.ethic.messenger.data.FileRef
import com.ethic.messenger.data.ImageRef
import com.ethic.messenger.data.Message
import com.ethic.messenger.data.Repo
import com.ethic.messenger.media.VoicePlayer
import com.ethic.messenger.media.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val BubbleMine = Color.White
private val BubbleOther = Color(0xFF1C1C1F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatId: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val chats by Repo.chats.collectAsState()
    val all by Repo.messages.collectAsState()
    val users by Repo.users.collectAsState()
    val typing by Repo.typing.collectAsState()
    val call by CallManager.state.collectAsState()
    val me by Repo.me.collectAsState()
    val chat = chats[chatId] ?: run { LaunchedEffect(Unit) { Nav.pop() }; return }
    val st = all[chatId]
    val other = if (chat.isGroup) null else Repo.other(chat)?.let { users[it.id] ?: it }

    var reply by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var menuFor by remember { mutableStateOf<Message?>(null) }
    var attachSheet by remember { mutableStateOf(false) }

    DisposableEffect(chatId) {
        Repo.activeChat = chatId
        com.ethic.messenger.Notifications.cancelChat(chatId)
        onDispose { if (Repo.activeChat == chatId) Repo.activeChat = null; VoicePlayer.stop() }
    }
    LaunchedEffect(chatId) { if (st?.loaded != true) runCatching { Repo.loadMessages(chatId) }.onFailure { Repo.toast(it.message ?: "") } }
    LaunchedEffect(st?.list?.lastOrNull()?.id) { Repo.markRead(chatId) }

    val list = st?.list.orEmpty()
    val listState = rememberLazyListState()
    // Newest at the bottom: the list is reversed.
    LaunchedEffect(list.lastOrNull()?.id) { if (listState.firstVisibleItemIndex < 3) listState.animateScrollToItem(0) }
    LaunchedEffect(listState, st?.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (st?.hasMore == true && last >= list.size - 3) runCatching { Repo.loadMessages(chatId, older = true) }
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton({ Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = {
                    Row(
                        Modifier.clickable { if (chat.isGroup) Nav.push(Route.GroupR(chat.id)) else other?.let { Nav.push(Route.UserR(it.id)) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(chat, 38.dp, other)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            NameLine(Repo.chatName(chat), other, fontSize = 17)
                            val typers = Repo.typingNames(chatId, typing)
                            Text(
                                when {
                                    typers.isNotEmpty() -> if (chat.isGroup) "${typers.joinToString()} печатает…" else "печатает…"
                                    chat.isGroup -> "${chat.members.size} участн."
                                    else -> lastSeenText(other)
                                },
                                color = if (typers.isNotEmpty() || other?.online == true) Blue else Muted, fontSize = 13.sp, maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    IconButton({ CallManager.start(chatId, false) }) { Icon(Icons.Default.Call, "Позвонить") }
                    IconButton({ CallManager.start(chatId, true) }) { Icon(Icons.Default.Videocam, "Видеозвонок") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface1),
            )
        },
        bottomBar = {
            chat.request?.let { req ->
                val mine = req.from == me?.id
                Column(Modifier.background(Surface1).navigationBarsPadding().padding(16.dp)) {
                    Text(if (mine) "Заявка отправлена" else "${Repo.chatName(chat)} хочет написать вам", fontWeight = FontWeight.Bold)
                    Text(if (mine) "Писать и звонить можно, когда собеседник её примет." else "Примите заявку, чтобы переписываться и созваниваться.", color = Muted, fontSize = 13.sp)
                    if (!mine) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton({ scope.launch { runCatching { Repo.declineRequest(chatId) }.onFailure { Repo.toast(it.message ?: "") } } }) { Text("Отклонить", color = Red) }
                        androidx.compose.material3.Button({ scope.launch { runCatching { Repo.acceptRequest(chatId) }.onFailure { Repo.toast(it.message ?: "") } } },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Принять") }
                    }
                }
                return@Scaffold
            }
            Composer(
                chat, reply, editing,
                onCancel = { reply = null; editing = null },
                onAttach = { attachSheet = true },
                onSent = { reply = null; editing = null },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val active = chat.call?.participants.orEmpty()
            if (active.isNotEmpty() && call?.chatId != chatId) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF0E2A18)).clickable { CallManager.start(chatId, false) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Call, null, tint = Green)
                    Spacer(Modifier.width(10.dp))
                    Text("Идёт звонок · ${active.size} участн.", Modifier.weight(1f))
                    Text("Присоединиться", color = Green, fontWeight = FontWeight.Bold)
                }
            }
            if (st == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            else LazyColumn(
                Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                val rev = list.asReversed()
                itemsIndexed(rev, key = { _, m -> m.clientId ?: m.id }) { i, m ->
                    val older = rev.getOrNull(i + 1)
                    val newer = rev.getOrNull(i - 1)
                    val firstOfGroup = older == null || older.userId != m.userId || m.createdAt - older.createdAt > 5 * 60_000 || !isSameDay(older.createdAt, m.createdAt)
                    val lastOfGroup = newer == null || newer.userId != m.userId || newer.createdAt - m.createdAt > 5 * 60_000 || !isSameDay(newer.createdAt, m.createdAt)
                    Column(Modifier.animateItem()) {
                        if (older == null || !isSameDay(older.createdAt, m.createdAt)) DayChip(dayLabel(m.createdAt))
                        Bubble(chat, m, m.userId == me?.id, firstOfGroup, lastOfGroup, onLong = { menuFor = m })
                    }
                }
            }
        }
    }

    menuFor?.let { m -> MessageMenu(m, onDismiss = { menuFor = null }, onReply = { reply = m; editing = null }, onEdit = { editing = m; reply = null }) }
    if (attachSheet) AttachSheet(chat, reply, onDismiss = { attachSheet = false }, onSent = { reply = null })
}

@Composable
private fun DayChip(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = Muted, modifier = Modifier.background(Surface1, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(chat: Chat, m: Message, mine: Boolean, first: Boolean, last: Boolean, onLong: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // Telegram-like pop-in for messages that just arrived or were just sent.
    val anim = remember { Animatable(if (m.fresh && System.currentTimeMillis() - m.createdAt < 3000) 0f else 1f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)) }
    val big = 18.dp; val small = 6.dp
    val shape = if (mine) RoundedCornerShape(big, if (first) big else small, if (last) small else small, big)
        .let { RoundedCornerShape(big, if (first) big else small, if (last) big else small, big) }
    else RoundedCornerShape(if (first) big else small, big, big, if (last) big else small)
    val fg = if (mine) Color.Black else Color.White
    val sub = if (mine) Color(0xFF555555) else Muted

    Row(
        Modifier.fillMaxWidth().padding(top = if (first) 6.dp else 2.dp).graphicsLayer {
            scaleX = 0.85f + 0.15f * anim.value; scaleY = scaleX; alpha = anim.value
            translationY = (1 - anim.value) * 40f
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (mine) 1f else 0f, 1f)
        },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (chat.isGroup && !mine) {
            if (last) Avatar(Repo.user(m.userId), 32.dp, Modifier.clickable { Nav.push(Route.UserR(m.userId)) }) else Spacer(Modifier.width(32.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(
            Modifier.widthIn(max = 300.dp).clip(shape).background(if (mine) BubbleMine else BubbleOther)
                .combinedClickable(onClick = {}, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLong() })
                .padding(if (m.image != null && m.text.isBlank()) 3.dp else 10.dp, if (m.image != null && m.text.isBlank()) 3.dp else 7.dp),
        ) {
            if (chat.isGroup && !mine && first) Text(Repo.userName(m.userId), color = colorOf(m.userId), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
            m.replyTo?.let { r ->
                Row(Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).background(if (mine) Color(0x14000000) else Color(0x14FFFFFF)).padding(6.dp)) {
                    Box(Modifier.width(3.dp).heightIn(min = 30.dp).background(Blue, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(if (r.deleted) "Сообщение удалено" else Repo.userName(r.userId ?: 0), color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (!r.deleted) Text(r.text.ifBlank { kindText(r.kind, r.fileName) }, color = sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            m.image?.let { PhotoContent(m, it) }
            m.file?.let { f ->
                if (f.kind == "voice") VoiceContent(m, f, mine) else FileContent(ctx, m, f, mine)
            }
            if (m.progress in 0f..0.999f && m.pending) LinearProgressIndicator(progress = { m.progress }, Modifier.fillMaxWidth().padding(top = 4.dp), color = Blue)
            if (m.text.isNotBlank()) Text(m.text, color = fg, fontSize = 16.sp, modifier = Modifier.padding(top = if (m.image != null) 6.dp else 0.dp, start = if (m.image != null) 6.dp else 0.dp))
            Row(Modifier.align(Alignment.End).padding(top = 2.dp, end = if (m.image != null && m.text.isBlank()) 6.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.editedAt != null) Text("изм. ", color = sub, fontSize = 11.sp)
                Text(timeOf(m.createdAt), color = sub, fontSize = 11.sp)
                if (mine) {
                    Spacer(Modifier.width(3.dp))
                    val (icon, tint) = when {
                        m.pending -> Icons.Default.AccessTime to sub
                        m.id <= chat.othersRead -> Icons.Default.DoneAll to Blue
                        else -> Icons.Default.Done to sub
                    }
                    Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

fun kindText(kind: String?, name: String?) = when (kind) { "photo" -> "Фото"; "voice" -> "Голосовое сообщение"; "video" -> "Видео"; null -> ""; else -> name ?: "Файл" }

@Composable
private fun PhotoContent(m: Message, img: ImageRef) {
    val ratio = if ((img.w ?: 0) > 0 && (img.h ?: 0) > 0) (img.w!!.toFloat() / img.h!!).coerceIn(0.5f, 2f) else 1f
    AsyncImage(
        m.localUri ?: Api.abs(img.url), null,
        Modifier.width(260.dp).aspectRatio(ratio).clip(RoundedCornerShape(15.dp)).background(Surface3)
            .clickable { if (!m.pending) Nav.push(Route.ImageR(Api.abs(img.url)!!)) },
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun VoiceContent(m: Message, f: FileRef, mine: Boolean) {
    val ctx = LocalContext.current
    val playing by VoicePlayer.playing.collectAsState()
    val pos by VoicePlayer.position.collectAsState()
    val url = m.localUri ?: Api.abs(f.url)!!
    val active = playing == url
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) { while (active) { delay(200); tick++ } }
    val fg = if (mine) Color.Black else Color.White
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(230.dp)) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(if (mine) Color.Black else Color.White).clickable { VoicePlayer.toggle(ctx, url); tick++ },
            contentAlignment = Alignment.Center,
        ) {
            @Suppress("UNUSED_EXPRESSION") tick
            Icon(if (active && !VoicePlayer.isPaused) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = if (mine) Color.White else Color.Black)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // Waveform-like bars derived from the message id.
            Row(Modifier.fillMaxWidth().heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val n = 28
                for (i in 0 until n) {
                    val h = 6 + ((m.id * 31 + i * 17) % 18).toInt().let { if (it < 0) -it else it }
                    // Each bar fills partially, so progress moves smoothly instead of bar by bar.
                    val f = if (active) (pos * n - i).coerceIn(0f, 1f) else 0f
                    Box(
                        Modifier.weight(1f).heightIn(min = h.dp, max = h.dp).clip(RoundedCornerShape(2.dp))
                            .background(fg.copy(alpha = 0.35f))
                            .drawBehind { drawRect(Blue, size = androidx.compose.ui.geometry.Size(size.width * f, size.height)) },
                    )
                }
            }
            Text(durText(f.duration), color = fg.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun FileContent(ctx: Context, m: Message, f: FileRef, mine: Boolean) {
    val fg = if (mine) Color.Black else Color.White
    Row(
        Modifier.width(240.dp).clickable(enabled = !m.pending) { download(ctx, f) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (mine) Color.Black else Color.White), contentAlignment = Alignment.Center) {
            Icon(if (f.kind == "video") Icons.Default.Videocam else Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = if (mine) Color.White else Color.Black)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(sizeText(f.size), color = fg.copy(alpha = 0.6f), fontSize = 12.sp)
        }
        if (!m.pending) Icon(Icons.Default.Download, null, tint = fg.copy(alpha = 0.7f))
    }
}

private fun download(ctx: Context, f: FileRef) {
    runCatching {
        val req = DownloadManager.Request(Uri.parse(Api.abs(f.url)))
            .setTitle(f.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, f.name)
        ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        Repo.toast("Скачивание: ${f.name}")
    }.onFailure { openUrl(ctx, Api.abs(f.url)!!) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageMenu(m: Message, onDismiss: () -> Unit, onReply: () -> Unit, onEdit: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val me = Repo.me.value
    val mine = m.userId == me?.id
    ModalBottomSheet(onDismiss, containerColor = Surface1) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            if (!m.pending) SheetItem(Icons.AutoMirrored.Filled.Reply, "Ответить") { onReply(); onDismiss() }
            if (m.text.isNotBlank()) SheetItem(Icons.Default.ContentCopy, "Копировать") {
                scope.launch { clipboard.setClipEntry(ClipData.newPlainText("text", m.text).toClipEntry()) }
                Repo.toast("Скопировано"); onDismiss()
            }
            if (mine && !m.pending && m.file?.kind != "voice") SheetItem(Icons.Default.Edit, "Изменить") { onEdit(); onDismiss() }
            if ((mine || me?.isStaff == true) && !m.pending) SheetItem(Icons.Default.Delete, "Удалить", Red) { Repo.deleteMessage(m); onDismiss() }
        }
    }
}

@Composable
fun SheetItem(icon: ImageVector, text: String, tint: Color = Color.White, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(18.dp))
        Text(text, color = tint, fontSize = 16.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(chat: Chat, reply: Message?, onDismiss: () -> Unit, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun sendMedia(uri: Uri) {
        onDismiss()
        scope.launch {
            val f = describe(ctx, uri)
            if (f.mime.startsWith("image/") && f.mime != "image/gif") {
                runCatching {
                    val (file, w, h) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { prepareImage(ctx, uri) }
                    val u = Uri.fromFile(file)
                    Repo.sendUpload(ctx, chat.id, u, "image", "photo.jpg", "", reply, Message(0, 0, 0, image = ImageRef(u.toString(), w, h))) { url, _, _ ->
                        mapOf("image" to mapOf("url" to url, "w" to w, "h" to h))
                    }
                }.onFailure { Repo.toast(it.message ?: "Ошибка") }
            } else if (f.mime == "image/gif") {
                Repo.sendUpload(ctx, chat.id, uri, "image", f.name, "", reply, Message(0, 0, 0, image = ImageRef(uri.toString()))) { url, _, _ -> mapOf("image" to mapOf("url" to url)) }
            } else sendFile(ctx, chat, f, reply)
            onSent()
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::sendMedia) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onDismiss(); sendFile(ctx, chat, describe(ctx, it), reply); onSent() }
    }
    var shot by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) shot?.let(::sendMedia) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            val f = File(ctx.cacheDir, "shot-${System.currentTimeMillis()}.jpg")
            shot = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            camera.launch(shot!!)
        } else Repo.toast("Нет доступа к камере")
    }
    ModalBottomSheet(onDismiss, containerColor = Surface1) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetItem(Icons.Default.Image, "Фото или видео") { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            SheetItem(Icons.Default.PhotoCamera, "Камера") { camPerm.launch(Manifest.permission.CAMERA) }
            SheetItem(Icons.Default.AttachFile, "Файл") { pickFile.launch("*/*") }
        }
    }
}

private fun sendFile(ctx: Context, chat: Chat, f: PickedFile, reply: Message?) {
    if (f.size > 50L * 1024 * 1024) return Repo.toast("Файл больше 50 МБ")
    val preview = Message(0, 0, 0, file = FileRef(f.uri.toString(), f.name, f.size, "file"))
    Repo.sendUpload(ctx, chat.id, f.uri, "file", f.name, "", reply, preview) { url, size, ext ->
        val media = ext in listOf("mp4", "webm", "ogg", "mp3", "wav")
        val kind = when { media && f.mime.startsWith("video/") -> "video"; media && f.mime.startsWith("audio/") -> "audio"; else -> "file" }
        mapOf("file" to mapOf("url" to url, "name" to f.name, "size" to size, "kind" to kind))
    }
}

@Composable
private fun Composer(chat: Chat, reply: Message?, editing: Message?, onCancel: () -> Unit, onAttach: () -> Unit, onSent: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember(chat.id) { mutableStateOf("") }
    LaunchedEffect(editing) { if (editing != null) text = editing.text }
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) { while (recording) { elapsed = recorder.elapsed; delay(200) } }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) runCatching { recorder.start(); recording = true }.onFailure { Repo.toast("Не удалось начать запись") }
        else Repo.toast("Нет доступа к микрофону")
    }
    fun finishVoice(send: Boolean) {
        recording = false
        val (file, dur) = recorder.stop(send) ?: return
        val uri = Uri.fromFile(file)
        Repo.sendUpload(ctx, chat.id, uri, "voice", "voice.m4a", "", reply,
            Message(0, 0, 0, file = FileRef(uri.toString(), "Голосовое сообщение", file.length(), "voice", dur))) { url, size, _ ->
            mapOf("file" to mapOf("url" to url, "name" to "Голосовое сообщение", "size" to size, "kind" to "voice", "duration" to dur))
        }
        onSent()
    }
    DisposableEffect(Unit) { onDispose { if (recording) recorder.stop(false) } }

    Column(Modifier.background(Surface1).navigationBarsPadding().imePadding()) {
        val bar = editing ?: reply
        if (bar != null) Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (editing != null) Icons.Default.Edit else Icons.AutoMirrored.Filled.Reply, null, tint = Blue)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (editing != null) "Редактирование" else "Ответ: ${Repo.userName(bar.userId)}", color = Blue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(bar.text.ifBlank { kindText(bar.kind, bar.file?.name) }, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }
            IconButton({ onCancel(); if (editing != null) text = "" }) { Icon(Icons.Default.Close, "Отмена", tint = Muted) }
        }
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom) {
            if (recording) {
                Row(Modifier.weight(1f).heightIn(min = 52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Red))
                    Spacer(Modifier.width(10.dp))
                    Text(durText(elapsed / 1000.0), fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton({ finishVoice(false) }) { Text("Отмена", color = Red) }
                }
                RoundAction(Icons.AutoMirrored.Filled.Send, Blue) { finishVoice(true) }
                return@Row
            }
            if (editing == null) IconButton(onAttach, Modifier.padding(bottom = 4.dp)) { Icon(Icons.Default.AttachFile, "Вложение", tint = Muted) }
            TextField(
                text, { text = it; if (it.isNotBlank()) Repo.sendTyping(chat.id) },
                Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp),
                placeholder = { Text("Сообщение") },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(6.dp))
            if (text.isNotBlank() || editing != null) RoundAction(if (editing != null) Icons.Default.Done else Icons.AutoMirrored.Filled.Send, Color.White, Color.Black) {
                val t = text.trim()
                if (editing != null) { if (t != editing.text) Repo.editMessage(editing, t) }
                else if (t.isNotEmpty()) Repo.sendText(chat.id, t, reply)
                text = ""
                onSent()
            } else RoundAction(Icons.Default.Mic, Surface3) { micPerm.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, bg: Color, fg: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.padding(bottom = 2.dp).size(48.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg)
    }
}
