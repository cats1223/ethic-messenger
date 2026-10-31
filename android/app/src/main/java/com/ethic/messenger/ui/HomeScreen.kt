package com.ethic.messenger.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethic.messenger.call.CallManager
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Chat
import com.ethic.messenger.data.Message
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.User
import com.ethic.messenger.media.Sounds
import kotlinx.coroutines.launch

// ---------- navigation ----------
enum class PickMode { GROUP, ADD_MEMBER, ADD_TO_CALL }

sealed interface Route {
    data class ChatR(val id: Long) : Route
    data class UserR(val id: Long) : Route
    data class GroupR(val id: Long) : Route
    data object EditProfile : Route
    data class Picker(val mode: PickMode, val chatId: Long = 0) : Route
    data class ImageR(val url: String) : Route
    data object Admin : Route
    data object Plus : Route
    data class Support(val staff: Boolean) : Route
}

object Nav {
    val stack = mutableStateListOf<Route>()
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun openChat(id: Long) { stack.removeAll { it is Route.ChatR }; push(Route.ChatR(id)) }
}

fun previewText(m: Message?): String = when {
    m == null -> "Нет сообщений"
    m.text.isNotBlank() -> m.text
    m.image != null -> "Фото"
    m.file?.kind == "voice" -> "Голосовое сообщение"
    m.file?.kind == "video" -> "Видео"
    m.file != null -> m.file.name
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    val chats by Repo.chats.collectAsState()
    val unread = chats.values.sumOf { if (it.id == Repo.activeChat) 0 else it.unread }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Surface1, tonalElevation = 0.dp) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.Black, indicatorColor = Color.White,
                    selectedTextColor = Color.White, unselectedIconColor = Muted, unselectedTextColor = Muted,
                )
                NavigationBarItem(tab == 0, { tab = 0 }, {
                    BadgedBox({ if (unread > 0) Badge(containerColor = Blue) { Text(if (unread > 99) "99+" else "$unread") } }) {
                        Icon(if (tab == 0) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline, null)
                    }
                }, label = { Text("Чаты") }, colors = colors)
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(if (tab == 1) Icons.Filled.People else Icons.Outlined.PeopleOutline, null) }, label = { Text("Люди") }, colors = colors)
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(if (tab == 2) Icons.Filled.Settings else Icons.Outlined.Settings, null) }, label = { Text("Настройки") }, colors = colors)
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ChatsTab()
                1 -> PeopleTab()
                else -> SettingsTab()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabScaffold(title: String, actions: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Bg,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, fontWeight = FontWeight.ExtraBold) },
                actions = { actions() },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, scrolledContainerColor = Surface1),
            )
        },
        content = content,
    )
}

@Composable
fun SearchField(q: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        q, onChange,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatsTab() {
    val chats by Repo.chats.collectAsState()
    val users by Repo.users.collectAsState()
    val typing by Repo.typing.collectAsState()
    val connected by Repo.connected.collectAsState()
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<Chat?>(null) }
    val list = chats.values.sortedByDescending { it.sortKey }
        .filter { q.isBlank() || Repo.chatName(it).contains(q.trim(), ignoreCase = true) }

    TabScaffold(if (connected) "Чаты" else "Соединение…", actions = {
        IconButton({ Nav.push(Route.Picker(PickMode.GROUP)) }) { Icon(Icons.Default.GroupAdd, "Новая группа") }
    }) { pad ->
        PullToRefreshBox(refreshing, { scope.launch { refreshing = true; runCatching { Repo.loadChats() }; refreshing = false } }, Modifier.padding(pad)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { SearchField(q, { q = it }, "Поиск чатов") }
                if (list.isEmpty()) item {
                    Text("Пока нет чатов. Найдите друга во вкладке «Люди».", color = Muted, modifier = Modifier.padding(32.dp))
                }
                items(list, key = { it.id }) { chat ->
                    val other = if (chat.isGroup) null else Repo.other(chat)?.let { users[it.id] ?: it }
                    val typers = Repo.typingNames(chat.id, typing)
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { Nav.openChat(chat.id) }, onLongClick = { leaving = chat })
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .animateItem(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(chat, 54.dp, other)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NameLine(Repo.chatName(chat), other, Modifier.weight(1f))
                                if (chat.call?.participants?.isNotEmpty() == true) Icon(Icons.Default.Call, null, tint = Green, modifier = Modifier.size(16.dp).padding(start = 2.dp))
                                chat.lastMessage?.let { Text(shortWhen(it.createdAt), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp)) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val last = chat.lastMessage
                                val prefix = when {
                                    last == null -> ""
                                    last.userId == Repo.me.value?.id -> "Вы: "
                                    chat.isGroup -> "${Repo.userName(last.userId)}: "
                                    else -> ""
                                }
                                Text(
                                    when {
                                        chat.request != null -> if (chat.request.from == Repo.me.value?.id) "Заявка отправлена" else "Хочет написать вам"
                                        typers.isNotEmpty() -> "печатает…"
                                        else -> prefix + previewText(last)
                                    },
                                    color = if (typers.isNotEmpty()) Blue else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp, modifier = Modifier.weight(1f),
                                )
                                if (chat.unread > 0) Box(
                                    Modifier.padding(start = 6.dp).height(20.dp).background(Blue, CircleShape).padding(horizontal = 7.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text("${chat.unread}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
    }
    leaving?.let { chat ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text(if (chat.isGroup) "Покинуть группу?" else "Удалить чат?") },
            text = { Text(if (chat.isGroup) "Вы больше не будете получать сообщения из «${chat.title}»." else "Чат исчезнет у вас. Если собеседник тоже его удалит, переписка удалится навсегда.") },
            confirmButton = { TextButton({ leaving = null; scope.launch { runCatching { Repo.leaveChat(chat.id) }.onFailure { Repo.toast(it.message ?: "") } } }) { Text(if (chat.isGroup) "Покинуть" else "Удалить", color = Red) } },
            dismissButton = { TextButton({ leaving = null }) { Text("Отмена") } },
        )
    }
}

@Composable
fun UserRow(u: User, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(u, 46.dp, online = u.online)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            NameLine(u.displayName, u)
            Text("@${u.username} · ${lastSeenText(u)}", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun PeopleTab() {
    var q by remember { mutableStateOf("") }
    val found = remember { mutableStateListOf<User>() }
    val users by Repo.users.collectAsState()
    LaunchedEffect(q) {
        kotlinx.coroutines.delay(250)
        runCatching { Repo.searchUsers(q.trim()) }.onSuccess { found.clear(); found.addAll(it) }
    }
    TabScaffold("Люди") { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { SearchField(q, { q = it }, "Имя или логин") }
            items(found, key = { it.id }) { u -> UserRow(users[u.id] ?: u) { Nav.push(Route.UserR(u.id)) } }
        }
    }
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = Color.White, trailing: @Composable () -> Unit = { Icon(Icons.Default.ChevronRight, null, tint = Muted) }, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, fontSize = 16.sp)
            subtitle?.let { Text(it, color = Muted, fontSize = 13.sp) }
        }
        trailing()
    }
}

@Composable
fun Section(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(Surface1, RoundedCornerShape(18.dp)).padding(vertical = 4.dp)) { content() }
}

@Composable
private fun SettingsTab() {
    val me by Repo.me.collectAsState()
    val ctx = LocalContext.current
    var ringDialog by remember { mutableStateOf(false) }
    var emailDialog by remember { mutableStateOf(false) }
    var msgSound by remember { mutableStateOf(Sounds.messageSounds) }
    var noise by remember { mutableStateOf(CallManager.noiseSuppression) }
    var confirmLogout by remember { mutableStateOf(false) }
    val u = me ?: return
    TabScaffold("Настройки") { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Section {
                Box(Modifier.fillMaxWidth().clickable { Nav.push(Route.EditProfile) }) {
                    Column {
                        Banner(u, Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 4.dp).background(Surface2, RoundedCornerShape(14.dp)))
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u, 64.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                NameLine(u.displayName, u, fontSize = 20, weight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("@${u.username}", color = Muted)
                                    BadgeLabel(u)
                                }
                            }
                            Icon(Icons.Default.Edit, null, tint = Muted)
                        }
                    }
                }
            }
            Section {
                if (Repo.emailEnabled || u.email != null) {
                    SettingsRow(Icons.Default.Email, "Почта", u.email ?: "Не привязана") { emailDialog = true }
                    HorizontalDivider(color = Line)
                }
                SettingsRow(Icons.Default.MusicNote, "Рингтон", Sounds.ringtones.firstOrNull { it.id == Sounds.ringtoneId }?.name) { ringDialog = true }
                HorizontalDivider(color = Line)
                SettingsRow(Icons.Default.NotificationsActive, "Звуки сообщений", trailing = {
                    Switch(msgSound, { msgSound = it; Repo.prefs.edit().putBoolean("msgSound", it).apply() }, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
                })
                HorizontalDivider(color = Line)
                SettingsRow(Icons.Default.GraphicEq, "Шумоподавление", "Убирает фоновый шум в звонках", trailing = {
                    Switch(noise, { noise = it; Repo.prefs.edit().putBoolean("noise", it).apply() }, colors = SwitchDefaults.colors(checkedTrackColor = Blue))
                })
            }
            Section {
                if (u.isStaff) {
                    SettingsRow(Icons.Default.AdminPanelSettings, "Админ-панель") { Nav.push(Route.Admin) }
                    HorizontalDivider(color = Line)
                }
                if (u.support || u.isStaff) {
                    SettingsRow(Icons.Default.Email, "Обращения пользователей") { Nav.push(Route.Support(true)) }
                    HorizontalDivider(color = Line)
                }
                if (u.plus || u.role == "owner") {
                    SettingsRow(Icons.Default.GraphicEq, "Подписка Ethic+", "Цвет ника, видео-баннер") { Nav.push(Route.Plus) }
                    HorizontalDivider(color = Line)
                }
                SettingsRow(Icons.Default.NotificationsActive, "Поддержка", "Написать нам о проблеме или идее") { Nav.push(Route.Support(false)) }
                HorizontalDivider(color = Line)
                SettingsRow(Icons.AutoMirrored.Filled.OpenInNew, "Открыть сайт", "Демонстрация экрана — на компьютере") { openUrl(ctx, Api.BASE) }
                HorizontalDivider(color = Line)
                SettingsRow(Icons.AutoMirrored.Filled.Logout, "Выйти", tint = Red, trailing = {}) { confirmLogout = true }
            }
            Text("Ethic Messenger ${com.ethic.messenger.BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        }
    }
    if (ringDialog) AlertDialog(
        onDismissRequest = { Sounds.stop(); ringDialog = false },
        title = { Text("Рингтон") },
        text = {
            Column {
                var sel by remember { mutableStateOf(Sounds.ringtoneId) }
                Sounds.ringtones.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable {
                        sel = r.id; Repo.prefs.edit().putString("ringtone", r.id).apply(); Sounds.ring(r.id, preview = true)
                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(sel == r.id, null)
                        Spacer(Modifier.width(8.dp)); Text(r.name)
                    }
                }
            }
        },
        confirmButton = { TextButton({ Sounds.stop(); ringDialog = false }) { Text("Готово") } },
    )
    if (emailDialog) EmailDialog { emailDialog = false }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("Выйти из аккаунта?") },
        confirmButton = { TextButton({ confirmLogout = false; CallManager.leave(); Repo.logout() }) { Text("Выйти", color = Red) } },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Отмена") } },
    )
}

@Composable
private fun EmailDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (Repo.me.value?.email == null) "Привязать почту" else "Сменить почту") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Repo.me.value?.email?.let { Text("Сейчас: $it", color = Muted) }
                Field(email, { email = it }, "Новая почта", keyboard = androidx.compose.ui.text.input.KeyboardType.Email)
                CodeButton(email.isNotBlank()) {
                    try { Api.call("/api/me/email/code", "POST", mapOf("email" to email.trim())); sent = true; error = ""; true }
                    catch (e: Exception) { error = e.message ?: ""; false }
                }
                if (sent) Field(code, { code = it.filter(Char::isDigit).take(6) }, "Код из письма", keyboard = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                if (error.isNotEmpty()) Text(error, color = Red, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    try {
                        val r = Api.call("/api/me/email", "POST", mapOf("email" to email.trim(), "code" to code))
                        Repo.me.value = Api.json.decodeFromJsonElement(User.serializer(), r["user"]!!)
                        Repo.toast("Почта привязана"); onClose()
                    } catch (e: Exception) { error = e.message ?: "" }
                }
            }, enabled = sent && code.length == 6) { Text("Подтвердить") }
        },
        dismissButton = { TextButton(onClose) { Text("Отмена") } },
    )
}

fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
