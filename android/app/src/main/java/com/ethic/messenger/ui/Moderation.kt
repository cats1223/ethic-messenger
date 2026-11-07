package com.ethic.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Message
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.User
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Ethic+ chat themes: the colour of your own messages and the chat background ----------
data class ChatTheme(val bubble: Color, val bg: Color)

val CHAT_THEMES = listOf(null to "Обычная", "ocean" to "Океан", "sunset" to "Закат", "forest" to "Лес", "violet" to "Фиалка", "rose" to "Роза", "gold" to "Золото")

fun chatTheme(id: String?): ChatTheme? = when (id) {
    "ocean" -> ChatTheme(Color(0xFF2563EB), Color(0xFF0B2545))
    "sunset" -> ChatTheme(Color(0xFFEA580C), Color(0xFF3B1408))
    "forest" -> ChatTheme(Color(0xFF16A34A), Color(0xFF082A17))
    "violet" -> ChatTheme(Color(0xFF7C3AED), Color(0xFF21093F))
    "rose" -> ChatTheme(Color(0xFFDB2777), Color(0xFF3A0A24))
    "gold" -> ChatTheme(Color(0xFFCA8A04), Color(0xFF2E2204))
    else -> null
}

/** The theme in effect for this person: only with Ethic+. */
fun myTheme(me: User?): ChatTheme? = if (me?.plus == true) chatTheme(me.theme) else null

// ---------- ban and mute lengths ----------
val DURATIONS = listOf<Pair<Int?, String>>(1 to "1 час", 24 to "1 день", 72 to "3 дня", 168 to "7 дней", 720 to "30 дней", null to "Навсегда")

fun fmtUntil(ms: Long): String = SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(ms))

@Composable
fun DurationPicker(selected: Int?, onPick: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DURATIONS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (h, label) ->
                    FilterChip(
                        selected = selected == h, onClick = { onPick(h) }, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.White, selectedLabelColor = Color.Black),
                    )
                }
            }
        }
    }
}

/** Muted: instead of the message field, a line saying until when and why. */
@Composable
fun MuteBar(m: User.Mute) {
    LaunchedEffect(m.until) {
        val until = m.until ?: return@LaunchedEffect
        delay((until - System.currentTimeMillis() + 500).coerceAtLeast(0))
        Repo.me.update { it?.copy(mute = null) }
    }
    Text(
        (m.until?.let { "Вам запрещено писать до ${fmtUntil(it)}" } ?: "Вам запрещено писать") + ". " + (m.reason?.let { "Причина: $it" } ?: "Причина не указана"),
        color = Amber, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().background(Surface1).navigationBarsPadding().padding(12.dp)
            .background(Amber.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(14.dp),
    )
}

// ---------- reports ----------
val REPORT_KINDS = listOf("spam" to "Спам или реклама", "insult" to "Оскорбления", "scam" to "Мошенничество", "nsfw" to "Неприличное", "other" to "Другое")

fun reportKind(k: String) = REPORT_KINDS.firstOrNull { it.first == k }?.second ?: "Другое"

@Composable
fun ReportDialog(m: Message, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("spam") }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Пожаловаться на сообщение") },
        text = {
            Column {
                Text("Жалобу увидит только администрация. Автор не узнает, кто её отправил.", color = Muted, fontSize = 13.sp)
                REPORT_KINDS.forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { kind = v }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(kind == v, { kind = v })
                        Text(label)
                    }
                }
                OutlinedTextField(reason, { reason = it.take(200) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Подробнее, необязательно") })
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    runCatching { Api.call("/api/reports", "POST", mapOf("messageId" to m.id, "kind" to kind, "reason" to reason.trim())) }
                        .onSuccess { Repo.toast("Жалоба отправлена") }.onFailure { Repo.toast(it.message ?: "Ошибка") }
                    onDone()
                }
            }) { Text("Отправить", color = Red) }
        },
        dismissButton = { TextButton(onDone) { Text("Отмена") } },
    )
}
