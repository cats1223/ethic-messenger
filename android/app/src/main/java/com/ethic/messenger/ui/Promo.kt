package com.ethic.messenger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethic.messenger.R
import com.ethic.messenger.data.Repo
import kotlinx.coroutines.launch

/** Where the subscription is sold. */
const val PLUS_BUY_URL = "https://playerok.com/products/c28a9d3b0559-podpiska-ethic-v-ethic-messenger"

/** What Ethic+ gives, shown to everyone in Settings. */
val PLUS_PERKS = listOf(
    "Градиентный ник" to "Имя переливается цветом в чатах, в списке участников и в профиле.",
    "Видео-баннер" to "Вместо картинки в шапке профиля играет ваше видео.",
    "Темы чатов" to "Оформление чатов, доступное только подписчикам.",
    "Поддержка проекта" to "Ethic развивается без рекламы и без продажи ваших данных.",
)

/**
 * The QR code on the Ethic hoodie opens …/hoodie: a greeting page with one button.
 * Not signed in: the button leads to sign-in and the gift is given right after it. Signed in: the button gives it at once.
 */
@Composable
fun PromoScreen() {
    val promo by Repo.promo.collectAsState()
    val me by Repo.me.collectAsState()
    val accepted by Repo.promoAccepted.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun claim() {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { Repo.claimPromo() }
                .onSuccess { until -> if (until > 0) Repo.toast("Подарок получен: Ethic+ активен до ${fmtUntil(until)}") }
                .onFailure { Repo.toast(it.message ?: "Не удалось получить подарок") }
            busy = false
        }
    }
    // Pressed the button before signing in: give the gift as soon as the profile is there, without asking twice.
    LaunchedEffect(me != null, promo, accepted) { if (me != null && promo != null && accepted) claim() }
    if (promo == null || accepted) return
    Dialog({}, DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(120.dp))
                Text("Привет! Это Ethic Messenger", color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Text("Ты отсканировал QR-код и получишь подарок: 7 дней Ethic+ бесплатно.", color = Color(0xFFC9C9CF), fontSize = 17.sp, textAlign = TextAlign.Center)
                Button(
                    { if (me != null) claim() else Repo.promoAccepted.value = true },
                    Modifier.fillMaxWidth().height(52.dp), enabled = !busy, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text(if (me != null) "Получить подарок" else "Войти в аккаунт и получить", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            }
        }
    }
}
