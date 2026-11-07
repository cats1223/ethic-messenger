package com.ethic.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethic.messenger.data.Repo

val Amber = Color(0xFFF0B232)

/** Banned or deleted: covers everything, and the only way out is signing out. */
@Composable
fun EndedScreen() {
    val ended by Repo.ended.collectAsState()
    val e = ended ?: return
    Notice(Icons.Default.Block, Red, if (e.deleted) "Аккаунт удалён" else "Аккаунт заблокирован", e.reason, e.until?.let { "Блокировка до ${fmtUntil(it)}" },
        "Выйти из аккаунта", Red, Color.White) { Repo.leaveEnded() }
}

/** A warning from staff: stays until «Понятно», then the next one if there are more. */
@Composable
fun WarningScreen() {
    val list by Repo.warnings.collectAsState()
    val w = list.firstOrNull() ?: return
    val left = when (w.left) {
        0 -> null
        1 -> "Ещё 1 предупреждение — и аккаунт будет заблокирован"
        else -> "Ещё ${w.left} предупреждения — и аккаунт будет заблокирован"
    }
    Notice(Icons.Default.Warning, Amber, "Вам выдано предупреждение", w.reason, left, "Понятно", Color.White, Color.Black) { Repo.warningSeen() }
}

@Composable
private fun Notice(
    icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, reason: String?, note: String?,
    button: String, buttonColor: Color, buttonText: Color, onClick: () -> Unit,
) {
    Dialog({}, DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(20.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(56.dp).background(tint.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(30.dp))
                }
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(reason?.let { "Причина: $it" } ?: "Причина не указана", color = Muted, textAlign = TextAlign.Center)
                note?.let { Text(it, color = Amber, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center) }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick,
                    Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = buttonText),
                ) { Text(button, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
