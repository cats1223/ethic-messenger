package com.ethic.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.User
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AdminUser(
    val id: Long,
    val username: String = "",
    val displayName: String = "",
    val avatar: String? = null,
    val role: String = "user",
    val verified: Boolean = false,
    val badge: String? = null,
    val online: Boolean = false,
    val banned: Boolean = false,
    val grantedCheck: Boolean = false,
    val customBadge: String = "",
    val messages: Int = 0,
) {
    val asUser get() = User(id, username, displayName, avatar, role = role, verified = verified, badge = badge, online = online)
}

private val ROLES = listOf("user" to "Без роли", "tester" to "Тестер", "support" to "Поддержка", "dev" to "Разработчик")
private val STATS = listOf(
    "users" to "Пользователей", "online" to "В сети", "chats" to "Чатов", "messages" to "Сообщений", "messagesToday" to "За сутки",
    "photos" to "Фото", "files" to "Файлов", "activeCalls" to "Звонков", "banned" to "Заблокировано",
)

/** The admin panel, native (same server endpoints as the website's). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val me = Repo.me.value
    val owner = me?.role == "owner"
    var stats by remember { mutableStateOf<JsonObject?>(null) }
    val users = remember { mutableStateListOf<AdminUser>() }
    var q by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AdminUser?>(null) }
    var roleFor by remember { mutableStateOf<AdminUser?>(null) }
    var badgeFor by remember { mutableStateOf<AdminUser?>(null) }
    var banFor by remember { mutableStateOf<AdminUser?>(null) }
    var deleteFor by remember { mutableStateOf<AdminUser?>(null) }

    suspend fun reload() {
        runCatching {
            stats = Api.call("/api/admin/stats")
            val list = Api.call("/api/admin/users?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8"))["users"]!!
            users.clear(); users.addAll(Api.json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(AdminUser.serializer()), list))
        }.onFailure { Repo.toast(it.message ?: "Ошибка") }
    }
    fun act(path: String, body: Map<String, Any?>, ok: String? = null) = scope.launch {
        runCatching { Api.call(path, "POST", body) }.onSuccess { ok?.let(Repo::toast); reload() }.onFailure { Repo.toast(it.message ?: "Ошибка") }
    }
    LaunchedEffect(q) { kotlinx.coroutines.delay(250); reload() }

    Scaffold(containerColor = Bg, topBar = {
        TopAppBar(
            title = { Text("Админ-панель", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton({ Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            stats?.let { s ->
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        STATS.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (k, label) ->
                                    Column(Modifier.weight(1f).background(Surface1, RoundedCornerShape(14.dp)).padding(12.dp)) {
                                        Text(s[k]?.jsonPrimitive?.contentOrNull ?: "0", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                                        Text(label, color = Muted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    val code = s["inviteCode"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    Column(Modifier.padding(16.dp).fillMaxWidth().background(Surface1, RoundedCornerShape(16.dp)).padding(14.dp)) {
                        Text("Код приглашения", color = Muted, fontSize = 13.sp)
                        Text(code.ifEmpty { "не требуется" }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.padding(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (code.isNotEmpty()) OutlinedButton({
                                scope.launch { clipboard.setClipEntry(android.content.ClipData.newPlainText("code", code).toClipEntry()) }
                                Repo.toast("Скопировано")
                            }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Копировать") }
                            OutlinedButton({ act("/api/admin/invite", emptyMap(), "Новый код") }) { Text("Новый") }
                            OutlinedButton({ act("/api/admin/invite", mapOf("code" to ""), "Код отключён") }) { Text("Без кода") }
                        }
                    }
                }
            }
            item { SearchField(q, { q = it }, "Поиск пользователей") }
            items(users, key = { it.id }) { u ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = u }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(u.asUser, 44.dp, online = u.online)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NameLine(u.displayName, u.asUser, Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(6.dp)); BadgeLabel(u.asUser)
                            if (u.banned) Text("  заблокирован", color = Red, fontSize = 12.sp)
                        }
                        Text("ID ${u.id} · @${u.username} · ${u.messages} сообщ.", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    selected?.let { u ->
        ModalBottomSheet({ selected = null }, containerColor = Surface1) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(u.displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                if (owner && u.role != "owner") SheetItem(Icons.Default.ManageAccounts, "Роль: " + (ROLES.firstOrNull { it.first == u.role }?.second ?: u.role)) { roleFor = u; selected = null }
                if (owner && u.role != "owner" && u.role != "dev") SheetItem(Icons.Default.Verified, if (u.grantedCheck) "Снять галочку" else "Выдать галочку") {
                    act("/api/admin/users/${u.id}/verify", mapOf("verified" to !u.grantedCheck)); selected = null
                }
                if (owner) SheetItem(Icons.Default.Badge, "Надпись у имени…") { badgeFor = u; selected = null }
                val canBan = u.id != me?.id && u.role != "owner" && (u.role != "dev" || owner)
                if (canBan) SheetItem(Icons.Default.Block, if (u.banned) "Разблокировать" else "Заблокировать", if (u.banned) Color.White else Red) {
                    if (u.banned) act("/api/admin/users/${u.id}/ban", mapOf("banned" to false)) else banFor = u
                    selected = null
                }
                if (owner && u.role != "owner" && !u.username.startsWith("deleted_")) SheetItem(Icons.Default.Block, "Удалить аккаунт", Red) { deleteFor = u; selected = null }
            }
        }
    }
    roleFor?.let { u ->
        var role by remember(u.id) { mutableStateOf(u.role) }
        AlertDialog(
            onDismissRequest = { roleFor = null },
            title = { Text("Роль: ${u.displayName}") },
            text = {
                Column {
                    ROLES.forEach { (r, label) ->
                        Row(Modifier.fillMaxWidth().clickable { role = r }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(role == r, { role = r }); Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ act("/api/admin/users/${u.id}/role", mapOf("role" to role), "Роль изменена"); roleFor = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ roleFor = null }) { Text("Отмена") } },
        )
    }
    badgeFor?.let { u ->
        var text by remember(u.id) { mutableStateOf(u.customBadge) }
        AlertDialog(
            onDismissRequest = { badgeFor = null },
            title = { Text("Надпись у имени") },
            text = {
                Column {
                    Text("До 24 символов. Пусто — надпись роли.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.padding(4.dp))
                    Field(text, { text = it.take(24) }, "Надпись")
                }
            },
            confirmButton = { TextButton({ act("/api/admin/users/${u.id}/badge", mapOf("badge" to text.trim()), "Сохранено"); badgeFor = null }) { Text("Сохранить") } },
            dismissButton = { TextButton({ badgeFor = null }) { Text("Отмена") } },
        )
    }
    deleteFor?.let { u ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Удалить аккаунт ${u.displayName}?") },
            text = { Text("Логин, фото, почта и вход будут удалены навсегда, отменить нельзя. В чужих чатах он останется как «Удалённый аккаунт».") },
            confirmButton = { TextButton({ act("/api/admin/users/${u.id}/delete", emptyMap(), "Аккаунт удалён"); deleteFor = null }) { Text("Удалить навсегда", color = Red) } },
            dismissButton = { TextButton({ deleteFor = null }) { Text("Отмена") } },
        )
    }
    banFor?.let { u ->
        AlertDialog(
            onDismissRequest = { banFor = null },
            title = { Text("Заблокировать ${u.displayName}?") },
            text = { Text("Он сразу выйдет из аккаунта и не сможет войти.") },
            confirmButton = { TextButton({ act("/api/admin/users/${u.id}/ban", mapOf("banned" to true), "Заблокирован"); banFor = null }) { Text("Заблокировать", color = Red) } },
            dismissButton = { TextButton({ banFor = null }) { Text("Отмена") } },
        )
    }
}
