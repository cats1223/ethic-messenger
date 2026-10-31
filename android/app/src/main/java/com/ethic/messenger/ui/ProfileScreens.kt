package com.ethic.messenger.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ethic.messenger.call.CallManager
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleTop(title: String, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = { IconButton({ Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
    )
}

@Composable
private fun ProfileHeader(u: User) {
    Box(Modifier.fillMaxWidth().height(200.dp)) {
        Banner(u, Modifier.fillMaxWidth().height(140.dp))
        Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp).border(5.dp, Bg, CircleShape)) { Avatar(u, 110.dp) }
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        NameLine(u.displayName, u, fontSize = 24, weight = FontWeight.ExtraBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("@${u.username}", color = Muted)
            BadgeLabel(u)
        }
        Spacer(Modifier.height(6.dp))
        Text(lastSeenText(u), color = if (u.online) Green else Muted, fontSize = 14.sp)
        u.bio?.let { Spacer(Modifier.height(10.dp)); Text(it, fontSize = 15.sp) }
    }
}

@Composable
fun UserScreen(id: Long) {
    val users by Repo.users.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) { runCatching { Repo.fetchUser(id) } }
    val u = users[id] ?: Repo.user(id)
    Scaffold(containerColor = Bg, topBar = { SimpleTop("") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            if (u == null) return@Column
            ProfileHeader(u)
            if (u.id != Repo.me.value?.id) Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                fun open(after: (Long) -> Unit) = scope.launch {
                    runCatching { Repo.openDirect(u.id) }.onSuccess { after(it.id) }.onFailure { Repo.toast(it.message ?: "") }
                }
                ActionTile(Icons.AutoMirrored.Filled.Chat, "Написать", Modifier.weight(1f)) { open { Nav.openChat(it) } }
                ActionTile(Icons.Default.Call, "Звонок", Modifier.weight(1f)) { open { CallManager.start(it, false) } }
                ActionTile(Icons.Default.Videocam, "Видео", Modifier.weight(1f)) { open { CallManager.start(it, true) } }
            }
        }
    }
}

@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Surface2).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 13.sp)
    }
}

@Composable
fun GroupScreen(id: Long) {
    val chats by Repo.chats.collectAsState()
    val users by Repo.users.collectAsState()
    val scope = rememberCoroutineScope()
    val chat = chats[id] ?: return
    var leave by remember { mutableStateOf(false) }
    Scaffold(containerColor = Bg, topBar = { SimpleTop("Группа") }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ChatAvatar(chat, 96.dp, null)
                    Spacer(Modifier.height(12.dp))
                    Text(chat.title ?: "Группа", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${chat.members.size} участн.", color = Muted)
                }
                Section {
                    SettingsRow(Icons.Default.PersonAdd, "Добавить участника", trailing = {}) { Nav.push(Route.Picker(PickMode.ADD_MEMBER, id)) }
                    HorizontalDivider(color = Line)
                    SettingsRow(Icons.AutoMirrored.Filled.ExitToApp, "Покинуть группу", tint = Red, trailing = {}) { leave = true }
                }
                Text("Участники", color = Muted, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
            }
            items(chat.members, key = { it.id }) { m -> UserRow(users[m.id] ?: m) { Nav.push(Route.UserR(m.id)) } }
        }
    }
    if (leave) AlertDialog(
        onDismissRequest = { leave = false },
        title = { Text("Покинуть группу?") },
        confirmButton = { TextButton({
            leave = false
            scope.launch { runCatching { Repo.leaveChat(id) }.onSuccess { Nav.stack.clear() }.onFailure { Repo.toast(it.message ?: "") } }
        }) { Text("Покинуть", color = Red) } },
        dismissButton = { TextButton({ leave = false }) { Text("Отмена") } },
    )
}

private val swatches = listOf("#5865f2", "#3ba55c", "#faa61a", "#ed4245", "#eb459e", "#1abc9c", "#9b59b6", "#202225", "#ffffff")

@Composable
fun EditProfileScreen() {
    val me by Repo.me.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(me?.displayName.orEmpty()) }
    var bio by remember { mutableStateOf(me?.bio.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    fun uploadImage(uri: Uri, aspect: Float, maxW: Int, field: String) = scope.launch {
        busy = true
        runCatching {
            val (file, _, _) = withContext(Dispatchers.IO) { prepareImage(ctx, uri, maxW, aspect) }
            val (url, _, _) = Api.upload(ctx, Uri.fromFile(file), "image", "image.jpg")
            Repo.updateMe(mapOf(field to url))
        }.onFailure { Repo.toast(it.message ?: "Ошибка") }
        busy = false
    }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uploadImage(it, 1f, 512, "avatar") } }
    val pickBanner = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uploadImage(it, 3f, 1500, "banner") } }
    val u = me ?: return
    Scaffold(containerColor = Bg, topBar = { SimpleTop("Профиль") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            ProfileHeader(u)
            if (busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue)
            Section {
                SettingsRow(Icons.Default.PersonAdd, "Сменить аватар", trailing = {}) { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                if (u.avatar != null) { HorizontalDivider(color = Line); SettingsRow(Icons.Default.Close, "Удалить аватар", tint = Red, trailing = {}) { scope.launch { runCatching { Repo.updateMe(mapOf("avatar" to null)) } } } }
            }
            Text("Баннер", color = Muted, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                swatches.forEach { c ->
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(c)))
                        .border(2.dp, if (u.banner == c) Blue else Line, CircleShape)
                        .clickable { scope.launch { runCatching { Repo.updateMe(mapOf("banner" to c)) } } })
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ pickBanner.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Загрузить картинку") }
                TextButton({ scope.launch { runCatching { Repo.updateMe(mapOf("banner" to null)) } } }) { Text("Сбросить", color = Muted) }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(name, { name = it.take(48) }, "Отображаемое имя")
                androidx.compose.material3.OutlinedTextField(bio, { bio = it.take(190) }, Modifier.fillMaxWidth(), label = { Text("О себе") }, minLines = 2, maxLines = 5, shape = RoundedCornerShape(14.dp))
                Button(
                    { scope.launch { runCatching { Repo.updateMe(mapOf("displayName" to name.trim(), "bio" to bio.trim())) }.onSuccess { Repo.toast("Сохранено") }.onFailure { Repo.toast(it.message ?: "") } } },
                    Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("Сохранить", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Pick people: for a new group, adding a member, or ringing someone into the call. */
@Composable
fun PickerScreen(mode: PickMode, chatId: Long) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    val found = remember { mutableStateListOf<User>() }
    val picked = remember { mutableStateListOf<Long>() }
    val exclude = when (mode) {
        PickMode.ADD_MEMBER -> Repo.chats.value[chatId]?.members?.map { it.id }.orEmpty()
        PickMode.ADD_TO_CALL -> CallManager.state.value?.tiles?.map { it.userId }.orEmpty()
        else -> emptyList()
    }
    LaunchedEffect(q) {
        kotlinx.coroutines.delay(250)
        runCatching { Repo.searchUsers(q.trim()) }.onSuccess { found.clear(); found.addAll(it.filter { u -> u.id !in exclude }) }
    }
    val heading = when (mode) { PickMode.GROUP -> "Новая группа"; PickMode.ADD_MEMBER -> "Добавить участника"; PickMode.ADD_TO_CALL -> "Добавить в звонок" }
    Scaffold(containerColor = Bg, topBar = {
        SimpleTop(heading) {
            if (mode == PickMode.GROUP) TextButton({
                scope.launch {
                    runCatching { Repo.createGroup(title.trim(), picked.toList()) }
                        .onSuccess { Nav.pop(); Nav.openChat(it.id) }.onFailure { Repo.toast(it.message ?: "") }
                }
            }, enabled = title.isNotBlank()) { Text("Создать") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            if (mode == PickMode.GROUP) item { Box(Modifier.padding(16.dp)) { Field(title, { title = it.take(64) }, "Название группы") } }
            item { SearchField(q, { q = it }, "Имя или логин") }
            items(found, key = { it.id }) { u ->
                UserRow(u, trailing = {
                    if (mode == PickMode.GROUP) Icon(if (u.id in picked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (u.id in picked) Blue else Muted)
                }) {
                    when (mode) {
                        PickMode.GROUP -> if (u.id in picked) picked.remove(u.id) else picked.add(u.id)
                        PickMode.ADD_MEMBER -> scope.launch { runCatching { Repo.addMember(chatId, u.id) }.onSuccess { Repo.toast("${u.displayName} добавлен(а)"); Nav.pop() }.onFailure { Repo.toast(it.message ?: "") } }
                        PickMode.ADD_TO_CALL -> { CallManager.invite(u.id); Nav.pop(); CallManager.setMinimized(false) }
                    }
                }
            }
        }
    }
}

@Composable
fun ImageScreen(url: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
    }) {
        AsyncImage(url, null, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
        IconButton({ Nav.pop() }, Modifier.safeDrawingPadding().padding(8.dp).background(Color(0x66000000), CircleShape)) { Icon(Icons.Default.Close, "Закрыть", tint = Color.White) }
    }
}
