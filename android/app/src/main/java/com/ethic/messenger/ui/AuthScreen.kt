package com.ethic.messenger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ethic.messenger.R
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Mode { LOGIN, REGISTER, RESET }

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value, onChange, modifier, label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
    )
}

/** "Получить код" with a one-minute cooldown. */
@Composable
fun CodeButton(enabled: Boolean = true, request: suspend () -> Boolean) {
    val scope = rememberCoroutineScope()
    var left by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(left) { if (left > 0) { delay(1000); left-- } }
    FilledTonalButton(
        onClick = { scope.launch { busy = true; if (request()) left = 60; busy = false } },
        enabled = enabled && left == 0 && !busy,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.height(56.dp).padding(top = 6.dp),
    ) { Text(if (left > 0) "Ещё раз ($left)" else "Получить код") }
}

@Composable
fun AuthScreen() {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Mode.LOGIN) }
    var login by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Repo.loadConfig(); config = true }

    fun run(block: suspend () -> Unit) = scope.launch {
        error = ""; busy = true
        try { block() } catch (e: Exception) { error = e.message ?: "Ошибка" }
        busy = false
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.logo), null, Modifier.size(110.dp))
        Text(
            when (mode) { Mode.LOGIN -> "С возвращением!"; Mode.REGISTER -> "Создать аккаунт"; Mode.RESET -> "Сброс пароля" },
            fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
        )
        Text(
            when (mode) { Mode.LOGIN -> "Рады видеть вас снова в Ethic"; Mode.REGISTER -> "Добро пожаловать в Ethic"; Mode.RESET -> "Пришлём код на почту, привязанную к аккаунту" },
            color = Muted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        val emailOn = Repo.emailEnabled
        when (mode) {
            Mode.LOGIN -> {
                Field(login, { login = it }, if (emailOn) "Логин или почта" else "Логин")
                Field(password, { password = it }, "Пароль", password = true)
                if (emailOn) TextButton({ mode = Mode.RESET; error = ""; hint = "" }, Modifier.align(Alignment.End)) { Text("Забыли пароль?", color = Muted) }
            }
            Mode.REGISTER -> {
                Field(login, { login = it }, "Логин")
                Field(name, { name = it }, "Отображаемое имя")
                if (emailOn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Field(email, { email = it }, "Почта", keyboard = KeyboardType.Email, modifier = Modifier.weight(1f))
                        Spacer(Modifier.size(8.dp))
                        CodeButton(email.isNotBlank()) {
                            try { Api.call("/api/register/code", "POST", mapOf("email" to email.trim())); hint = "Код отправлен на ${email.trim()}"; true }
                            catch (e: Exception) { error = e.message ?: ""; false }
                        }
                    }
                    Field(code, { code = it.filter(Char::isDigit).take(6) }, "Код из письма", keyboard = KeyboardType.NumberPassword)
                }
                Field(password, { password = it }, "Пароль (от 6 символов)", password = true)
                if (Repo.inviteRequired) Field(invite, { invite = it }, "Код приглашения")
            }
            Mode.RESET -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(login, { login = it }, "Логин или почта", modifier = Modifier.weight(1f))
                    Spacer(Modifier.size(8.dp))
                    CodeButton(login.isNotBlank()) {
                        try {
                            val r = Api.call("/api/password/forgot", "POST", mapOf("login" to login.trim()))
                            hint = "Код отправлен на ${r["email"]?.toString()?.trim('"')}"; true
                        } catch (e: Exception) { error = e.message ?: ""; false }
                    }
                }
                Field(code, { code = it.filter(Char::isDigit).take(6) }, "Код из письма", keyboard = KeyboardType.NumberPassword)
                Field(password, { password = it }, "Новый пароль", password = true)
            }
        }
        if (hint.isNotEmpty()) Text(hint, color = Muted, fontSize = 13.sp)
        if (error.isNotEmpty()) Text(error, color = Red, fontSize = 14.sp, textAlign = TextAlign.Center)
        Button(
            onClick = {
                run {
                    when (mode) {
                        Mode.LOGIN -> Repo.auth("/api/login", mapOf("username" to login.trim(), "password" to password))
                        Mode.REGISTER -> Repo.auth("/api/register", mapOf(
                            "username" to login.trim(), "displayName" to name.trim(), "password" to password,
                            "invite" to invite.trim(), "email" to email.trim(), "emailCode" to code,
                        ))
                        Mode.RESET -> Repo.auth("/api/password/reset", mapOf("login" to login.trim(), "code" to code, "password" to password))
                    }
                }
            },
            enabled = !busy && config,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp)
            else Text(when (mode) { Mode.LOGIN -> "Войти"; Mode.REGISTER -> "Создать аккаунт"; Mode.RESET -> "Сменить пароль и войти" }, fontWeight = FontWeight.Bold)
        }
        TextButton({
            mode = if (mode == Mode.LOGIN) Mode.REGISTER else Mode.LOGIN
            error = ""; hint = ""; code = ""
        }) {
            Text(
                when (mode) { Mode.LOGIN -> "Нет аккаунта? Зарегистрироваться"; Mode.REGISTER -> "Уже есть аккаунт? Войти"; Mode.RESET -> "Назад ко входу" },
                color = Color.White,
            )
        }
    }
}
