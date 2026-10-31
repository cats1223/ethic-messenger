package com.ethic.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.User
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class Ticket(
    val id: Long, val title: String = "", val category: String = "other", val status: String = "open",
    val createdAt: Long = 0, val updatedAt: Long = 0, val user: User? = null, val awaitingStaff: Boolean = false,
)

@Serializable
data class TicketMsg(val id: Long, val ticketId: Long = 0, val userId: Long = 0, val user: User? = null, val text: String = "", val createdAt: Long = 0)

private val CATS = listOf("bug" to "Ошибка", "question" to "Вопрос", "idea" to "Предложение", "complaint" to "Жалоба", "other" to "Другое")
private fun catName(c: String) = CATS.firstOrNull { it.first == c }?.second ?: "Другое"

@Composable
private fun StatusPill(t: Ticket) {
    val (text, color) = when {
        t.status == "closed" -> "Закрыто" to Muted
        t.awaitingStaff -> "Ждёт ответа" to Blue
        else -> "Есть ответ" to Green
    }
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** Support tickets: own tickets for everyone; all tickets for support / staff. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(staff: Boolean) {
    val scope = rememberCoroutineScope()
    val version by Repo.ticketsVersion.collectAsState()
    var open by remember { mutableStateOf<Long?>(null) }
    var creating by remember { mutableStateOf(false) }
    val list = remember { mutableStateListOf<Ticket>() }

    LaunchedEffect(version, open, creating) {
        if (open != null || creating) return@LaunchedEffect
        runCatching {
            val r = Api.call("/api/tickets" + if (staff) "?all=1" else "")
            list.clear(); list.addAll(Api.json.decodeFromJsonElement(ListSerializer(Ticket.serializer()), r["tickets"]!!))
        }.onFailure { Repo.toast(it.message ?: "") }
    }

    when {
        open != null -> { TicketThread(open!!) { open = null }; return }
        creating -> { NewTicket({ creating = false }) { id -> creating = false; open = id }; return }
    }
    Scaffold(containerColor = Bg, topBar = {
        TopAppBar(
            title = { Text(if (staff) "Обращения" else "Поддержка", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton({ Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            actions = { if (!staff) IconButton({ creating = true }) { Icon(Icons.Default.Add, "Новое обращение") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (list.isEmpty()) item {
                Column(Modifier.padding(24.dp)) {
                    Text(if (staff) "Обращений нет" else "У вас пока нет обращений. Опишите проблему — мы ответим здесь же.", color = Muted)
                    if (!staff) Button({ creating = true }, Modifier.padding(top = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Новое обращение") }
                }
            }
            items(list, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { open = t.id }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (staff) { Avatar(t.user, 40.dp); Spacer(Modifier.padding(6.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text("#${t.id} · ${t.title}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(catName(t.category) + (if (staff) " · ${t.user?.displayName.orEmpty()}" else "") + " · ${shortWhen(t.updatedAt)}", color = Muted, fontSize = 13.sp)
                    }
                    StatusPill(t)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTicket(onBack: () -> Unit, onCreated: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("bug") }
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Scaffold(containerColor = Bg, topBar = {
        TopAppBar(title = { Text("Новое обращение", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg))
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(title, { title = it.take(100) }, "Тема")
            ExposedDropdownMenuBox(menu, { menu = it }) {
                OutlinedTextField(catName(cat), {}, Modifier.fillMaxWidth().menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    readOnly = true, label = { Text("Категория") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) }, shape = RoundedCornerShape(14.dp))
                ExposedDropdownMenu(menu, { menu = false }) {
                    CATS.forEach { (v, l) -> DropdownMenuItem({ Text(l) }, { cat = v; menu = false }) }
                }
            }
            OutlinedTextField(text, { text = it.take(4000) }, Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text("Описание") },
                placeholder = { Text("Что делали, что ожидали, что произошло. Укажите телефон.") }, shape = RoundedCornerShape(14.dp))
            Button({
                busy = true
                scope.launch {
                    runCatching { Api.call("/api/tickets", "POST", mapOf("title" to title.trim(), "category" to cat, "text" to text.trim())) }
                        .onSuccess { r -> Repo.toast("Обращение отправлено"); onCreated(Api.json.decodeFromJsonElement(Ticket.serializer(), r["ticket"]!!).id) }
                        .onFailure { Repo.toast(it.message ?: "") }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Отправить") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TicketThread(id: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val version by Repo.ticketsVersion.collectAsState()
    var ticket by remember { mutableStateOf<Ticket?>(null) }
    val msgs = remember { mutableStateListOf<TicketMsg>() }
    var reply by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(id, version) {
        runCatching {
            val r = Api.call("/api/tickets/$id")
            ticket = Api.json.decodeFromJsonElement(Ticket.serializer(), r["ticket"]!!)
            if (ticket?.status == "closed") { onBack(); return@LaunchedEffect }
            msgs.clear(); msgs.addAll(Api.json.decodeFromJsonElement(ListSerializer(TicketMsg.serializer()), r["messages"]!!))
            if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1)
        }.onFailure { Repo.toast(it.message ?: ""); onBack() }
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    val t = ticket
    Scaffold(containerColor = Bg, topBar = {
        TopAppBar(
            title = { Column { Text(t?.title ?: "Обращение #$id", fontWeight = FontWeight.Bold, maxLines = 1); t?.let { Text(catName(it.category) + " · " + it.user?.displayName.orEmpty(), color = Muted, fontSize = 12.sp) } } },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            actions = {
                if (t != null) TextButton({
                    scope.launch {
                        runCatching { Api.call("/api/tickets/$id/status", "POST", mapOf("status" to "closed")) }
                            .onSuccess { Repo.ticketsVersion.value++; onBack() }.onFailure { Repo.toast(it.message ?: "") }
                    }
                }) { Text("Закрыть") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface1),
        )
    }, bottomBar = {
        Row(Modifier.background(Surface1).navigationBarsPadding().imePadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(reply, { reply = it.take(4000) }, Modifier.weight(1f).heightIn(max = 160.dp),
                placeholder = { Text("Ответ…") }, shape = RoundedCornerShape(20.dp))
            IconButton({
                val text = reply.trim()
                if (text.isEmpty()) return@IconButton
                reply = ""
                scope.launch {
                    runCatching { Api.call("/api/tickets/$id/messages", "POST", mapOf("text" to text)) }
                        .onSuccess { Repo.ticketsVersion.value++ }.onFailure { Repo.toast(it.message ?: ""); reply = text }
                }
            }) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs, key = { it.id }) { m ->
                val fromStaff = m.userId != t?.user?.id
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromStaff) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 300.dp).background(if (fromStaff) Color.White else Surface2, RoundedCornerShape(16.dp)).padding(10.dp)) {
                        Text((m.user?.displayName ?: "") + if (fromStaff) " · поддержка" else "", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (fromStaff) Color.DarkGray else Muted)
                        Text(m.text, color = if (fromStaff) Color.Black else Color.White)
                        Text(timeOf(m.createdAt), fontSize = 11.sp, color = if (fromStaff) Color.Gray else Muted, modifier = Modifier.align(Alignment.End))
                    }
                }
            }
        }
    }
}
