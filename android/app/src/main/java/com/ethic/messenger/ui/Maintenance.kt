package com.ethic.messenger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethic.messenger.R
import com.ethic.messenger.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The messenger is closed for maintenance: a whole page instead of the app, with the owner's reason.
 * «Вход для администрации» asks for the password the owner set; the owner's own account passes without it.
 */
@Composable
fun MaintenanceScreen() {
    val closed by Repo.maintenance.collectAsState()
    val unlocked by Repo.maintUnlocked.collectAsState()
    val denied by Repo.maintDenied.collectAsState()
    val me by Repo.me.collectAsState()
    // Ask at the start, and while the page is up ask again now and then, so the app comes back by itself.
    LaunchedEffect(closed != null) {
        while (true) { Repo.loadConfig(); if (Repo.maintenance.value == null) break; delay(30_000) }
    }
    val m = closed ?: return
    if (unlocked == m.id || me?.role == "owner") return
    // Signed in, profile still loading and nobody refused yet: wait instead of flashing the page at the owner.
    if (Repo.signedIn && me == null && !denied) return
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Dialog({}, DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(120.dp))
                Text("Здравствуйте, дорогой пользователь!", color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Text("Ethic Messenger в данный момент не работает.", color = Color(0xFFC9C9CF), fontSize = 17.sp, textAlign = TextAlign.Center)
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF0E0E10), RoundedCornerShape(18.dp)).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("ПРИЧИНА", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
                    Text(m.reason.ifBlank { "не указана" }, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
                if (m.back.isNotBlank()) Text("Откроемся: ${m.back}", color = Color(0xFFF0B232), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
            TextButton({ ask = true; password = ""; error = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                Text("Вход для администрации", color = Color(0xFF6A6A72), fontSize = 13.sp)
            }
        }
        if (ask) AlertDialog(
            onDismissRequest = { if (!busy) ask = false },
            title = { Text("Вход для администрации") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Введите пароль, который задан при закрытии мессенджера.", color = Muted, fontSize = 14.sp)
                    OutlinedTextField(password, { password = it.take(200); error = null }, Modifier.fillMaxWidth(), label = { Text("Пароль") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = error != null)
                    error?.let { Text(it, color = Red, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                TextButton({
                    busy = true
                    scope.launch {
                        runCatching { Repo.unlockMaintenance(password) }.onSuccess { ask = false }.onFailure { error = it.message ?: "Не удалось войти" }
                        busy = false
                    }
                }, enabled = !busy && password.isNotEmpty()) { Text("Войти") }
            },
            dismissButton = { TextButton({ ask = false }, enabled = !busy) { Text("Отмена") } },
        )
    }
}
