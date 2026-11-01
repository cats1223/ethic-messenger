package com.ethic.messenger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.User
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ShopGift(
    val id: String, val name: String = "", val description: String = "", val image: String = "",
    val price: Int = 0, val total: Int? = null, val left: Int? = null, val owned: Boolean = false,
)

@Serializable
data class OwnedGift(
    val id: Long, val giftId: String = "", val name: String = "", val image: String = "", val serial: Int = 0, val total: Int? = null,
    val fromId: Long = 0, val fromName: String = "", val note: String? = null, val createdAt: Long = 0,
)

/** Someone in the gift or coin windows. `has` means this limited gift was already theirs. */
@Serializable
data class ShopPerson(
    val id: Long, val username: String = "", val displayName: String = "", val avatar: String? = null,
    val role: String = "user", val has: Boolean = false, val coins: Int = 0,
) {
    val asUser get() = User(id, username = username, displayName = displayName, avatar = avatar)
}

/** The gifts are black: a soft light behind them keeps them visible on the dark app. */
private val GiftGlow = Brush.radialGradient(listOf(Color(0xFF8A8A92), Color(0xFF2A2A2F)))
private val WhiteButton @Composable get() = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
private const val ENDLESS_COINS = 999_999_999
private fun coinText(n: Int) = if (n >= ENDLESS_COINS) "∞" else "$n"

@Composable
private fun CoinMark() {
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color.White, Color(0xFF9A9AA2), Color(0xFFE6E6EA)))),
        contentAlignment = Alignment.Center,
    ) { Text("E", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
}

@Composable
private fun ShopDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface1).imePadding().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

private suspend fun loadPeople(path: String): List<ShopPerson> =
    Api.json.decodeFromJsonElement(ListSerializer(ShopPerson.serializer()), Api.call(path)["users"]!!)

/** «Подарить»: friends who accepted the request; the owner sees everyone. Who already had this gift cannot get it. */
@Composable
private fun GiftToDialog(gift: ShopGift, first: String, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val people = remember { mutableStateListOf<ShopPerson>() }
    var loaded by remember { mutableStateOf(false) }
    var term by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(gift.id) {
        runCatching { loadPeople("/api/shop/recipients?giftId=${gift.id}") }
            .onSuccess { list -> people.addAll(list.sortedByDescending { it.username.equals(first, true) }); loaded = true }
            .onFailure { Repo.toast(it.message ?: ""); onClose() }
    }
    ShopDialog(onClose) {
        Text("Подарить ${gift.name}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("${gift.price} Ethic Coin. Выберите, кому подарить.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(term, { term = it }, Modifier.fillMaxWidth(), label = { Text("Поиск") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        OutlinedTextField(note, { note = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Подпись, необязательно") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        val t = term.trim().removePrefix("@")
        val shown = people.filter { t.isEmpty() || it.username.contains(t, true) || it.displayName.contains(t, true) }
        if (loaded && shown.isEmpty()) Text(if (people.isEmpty()) "Пока некому дарить. Друг появится здесь, когда примет заявку." else "Никого не найдено", color = Muted, fontSize = 13.sp)
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(shown, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.asUser, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("@${p.username}", color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    if (p.has) Text("уже есть", color = Muted, fontSize = 12.sp)
                    else Button(
                        {
                            busy = true
                            scope.launch {
                                runCatching { Api.call("/api/shop/buy", "POST", mapOf("giftId" to gift.id, "toUsername" to p.username, "note" to note)) }
                                    .onSuccess { Repo.toast("${gift.name}: готово"); onDone() }
                                    .onFailure { Repo.toast(it.message ?: "") }
                                busy = false
                            }
                        },
                        enabled = !busy, colors = WhiteButton,
                    ) { Text("Подарить") }
                }
            }
        }
        OutlinedButton(onClose, Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}

@Composable
private fun CoinsDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val people = remember { mutableStateListOf<ShopPerson>() }
    var term by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        runCatching { loadPeople("/api/admin/users?q=") }
            .onSuccess { list -> people.addAll(list.filter { it.role != "owner" }) }
            .onFailure { Repo.toast(it.message ?: ""); onClose() }
    }
    ShopDialog(onClose) {
        Text("Выдать Ethic Coin", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("Укажите сумму и выберите, кому выдать. С минусом монеты спишутся.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(amount, { v -> amount = v.filter { it.isDigit() || it == '-' }.take(8) }, Modifier.fillMaxWidth(), label = { Text("Сколько монет") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(term, { term = it }, Modifier.fillMaxWidth(), label = { Text("Поиск") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        val t = term.trim().removePrefix("@")
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(people.filter { t.isEmpty() || it.username.contains(t, true) || it.displayName.contains(t, true) }, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.asUser, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("@${p.username} · ${p.coins} монет", color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Button(
                        {
                            val n = amount.toIntOrNull()
                            if (n == null || n == 0) { Repo.toast("Укажите целое число монет"); return@Button }
                            scope.launch {
                                runCatching { Api.call("/api/admin/coins", "POST", mapOf("username" to p.username, "amount" to n)) }
                                    .onSuccess { r ->
                                        val coins = r["coins"]!!.jsonPrimitive.int
                                        val i = people.indexOfFirst { it.id == p.id }
                                        if (i >= 0) people[i] = p.copy(coins = coins)
                                        Repo.toast("Баланс @${p.username}: $coins Ethic Coin")
                                    }
                                    .onFailure { Repo.toast(it.message ?: "") }
                            }
                        },
                        colors = WhiteButton,
                    ) { Text("Выдать") }
                }
            }
        }
        OutlinedButton(onClose, Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}

@Composable
private fun ReleaseDialog(onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<android.net.Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("500") }
    var limited by remember { mutableStateOf(true) }
    var total by remember { mutableStateOf("20") }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) photo = it }
    ShopDialog(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Выпустить подарок", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(128.dp).clip(RoundedCornerShape(14.dp)).background(GiftGlow).clickable { pick.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                if (photo != null) AsyncImage(photo, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
                else Text("Выбрать фото", fontSize = 13.sp)
            }
            OutlinedTextField(name, { name = it.take(48) }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            OutlinedTextField(price, { v -> price = v.filter(Char::isDigit).take(7) }, Modifier.fillMaxWidth(), label = { Text("Цена в Ethic Coin") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
            Row(Modifier.clickable { limited = !limited }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(limited, { limited = it })
                Text("Лимитка", fontWeight = FontWeight.SemiBold)
            }
            if (limited) OutlinedTextField(total, { v -> total = v.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Сколько всего") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClose, Modifier.weight(1f)) { Text("Отмена") }
                Button(
                    {
                        val uri = photo
                        if (uri == null) { Repo.toast("Выберите фото подарка"); return@Button }
                        busy = true
                        scope.launch {
                            runCatching {
                                val (url, _, _) = Api.upload(ctx, uri, "image", "gift.jpg")
                                Api.call("/api/admin/gifts", "POST", mapOf("name" to name.trim(), "image" to url, "price" to (price.toIntOrNull() ?: -1), "limited" to limited, "total" to (total.toIntOrNull() ?: 0)))
                            }.onSuccess { Repo.toast("Подарок «${name.trim()}» выпущен"); onDone() }.onFailure { Repo.toast(it.message ?: "") }
                            busy = false
                        }
                    },
                    Modifier.weight(1f), enabled = !busy && name.isNotBlank(), colors = WhiteButton,
                ) { Text("Выпустить") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopScreen(to: String = "") {
    val scope = rememberCoroutineScope()
    val me by Repo.me.collectAsState()
    val version by Repo.shopVersion.collectAsState()
    var coins by remember { mutableStateOf(0) }
    val gifts = remember { mutableStateListOf<ShopGift>() }
    var buying by remember { mutableStateOf<ShopGift?>(null) }
    var giftTo by remember { mutableStateOf<ShopGift?>(null) }
    var canBuild by remember { mutableStateOf(false) }
    var releasing by remember { mutableStateOf(false) }
    var granting by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching {
            val state = Repo.loadShopState()
            coins = state.coins
            canBuild = state.canBuild
            gifts.clear(); gifts.addAll(state.gifts)
        }.onFailure { Repo.toast(it.message ?: "") }
    }
    LaunchedEffect(version) { load() }

    Scaffold(containerColor = Bg, topBar = {
        TopAppBar(
            title = { Text("Магазин подарков", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton({ Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ваш баланс", color = Muted, modifier = Modifier.weight(1f))
                CoinMark()
                Spacer(Modifier.width(6.dp))
                Text(coinText(coins), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            // The button appears only after the person said «Нет» to the question.
            if (canBuild) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AsyncImage(Api.abs("/gifts/scooter.png"), null, Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                Text("Все детали собраны", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Семь подарков объединятся в один: самокат Ethic. Детали исчезнут из профиля.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                Button({ Repo.scooterAsk.value = true }, colors = WhiteButton) { Text("Собрать самокат") }
            }
            gifts.forEach { g ->
                val out = g.left == 0
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsyncImage(Api.abs(g.image), null, Modifier.size(104.dp).clip(RoundedCornerShape(14.dp)).background(GiftGlow).padding(6.dp), contentScale = ContentScale.Fit)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(g.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(g.description, color = Muted, fontSize = 13.sp)
                        Text(if (g.total == null) "Без ограничений" else if (out) "Распродано" else "Осталось ${g.left} из ${g.total}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (g.owned) Text("Этот подарок у вас уже был, второй себе не купить.", color = Muted, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CoinMark()
                            Spacer(Modifier.width(6.dp))
                            Text("${g.price}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        }
                        if (!out) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!g.owned) Button({ buying = g }, Modifier.weight(1f), colors = WhiteButton) { Text("Купить") }
                            OutlinedButton({ giftTo = g }, Modifier.weight(1f)) { Text("Подарить", maxLines = 1) }
                        }
                    }
                }
            }
            if (me?.role == "owner") Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Панель владельца", fontWeight = FontWeight.Bold)
                Button({ releasing = true }, Modifier.fillMaxWidth(), colors = WhiteButton) { Text("Выпустить подарок") }
                OutlinedButton({ granting = true }, Modifier.fillMaxWidth()) { Text("Выдать Ethic Coin") }
            }
        }
    }
    buying?.let { g ->
        AlertDialog(
            onDismissRequest = { buying = null },
            title = { Text(g.name) },
            text = { Text("Купить себе за ${g.price} Ethic Coin?") },
            confirmButton = {
                TextButton({
                    buying = null
                    scope.launch {
                        runCatching { Api.call("/api/shop/buy", "POST", mapOf("giftId" to g.id)) }
                            .onSuccess { Repo.toast("${g.name}: готово"); load() }
                            .onFailure { Repo.toast(it.message ?: "") }
                    }
                }) { Text("Купить") }
            },
            dismissButton = { TextButton({ buying = null }) { Text("Отмена", color = Muted) } },
        )
    }
    giftTo?.let { g -> GiftToDialog(g, to.removePrefix("@"), { giftTo = null }) { giftTo = null; scope.launch { load() } } }
    if (releasing) ReleaseDialog({ releasing = false }) { releasing = false; scope.launch { load() } }
    if (granting) CoinsDialog { granting = false }
}

/** The question when the set is complete, the note after «Нет», and the build animation after «Да». Lives above every screen. */
@Composable
fun ScooterPrompt() {
    val scope = rememberCoroutineScope()
    val ask by Repo.scooterAsk.collectAsState()
    var note by remember { mutableStateOf(false) }
    var built by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { runCatching { if (Repo.loadShopState().offer) Repo.scooterAsk.value = true } }
    if (ask) AlertDialog(
        onDismissRequest = {},
        title = { Text("Вы собрали все запчасти") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(Api.abs("/gifts/scooter.png"), null, Modifier.size(150.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                Text("Собрать самокат? Семь деталей объединятся в один подарок и исчезнут из профиля.")
            }
        },
        confirmButton = {
            TextButton({
                Repo.scooterAsk.value = false
                scope.launch {
                    runCatching { Api.call("/api/shop/build", "POST", emptyMap()) }
                        .onSuccess { r -> built = r["serial"]!!.jsonPrimitive.int; Repo.shopVersion.value++ }
                        .onFailure { Repo.toast(it.message ?: "") }
                }
            }) { Text("Да") }
        },
        dismissButton = {
            TextButton({
                Repo.scooterAsk.value = false
                scope.launch {
                    runCatching { Api.call("/api/shop/decline", "POST", emptyMap()) }
                        .onSuccess { note = true; Repo.shopVersion.value++ }
                        .onFailure { Repo.toast(it.message ?: "") }
                }
            }) { Text("Нет", color = Muted) }
        },
    )
    if (note) AlertDialog(
        onDismissRequest = { note = false },
        title = { Text("Хорошо, детали остаются у вас") },
        text = { Text("Собрать самокат можно потом: Настройки → «Магазин подарков», кнопка «Собрать самокат».") },
        confirmButton = { TextButton({ note = false }) { Text("Понятно") } },
    )
    built?.let { serial -> ScooterAnimation(serial) { built = null } }
}

private val SCOOTER_PARTS = listOf("pandemonium", "atlas", "dryade", "merrow", "sylphe", "rubber", "oracle")

/** The parts fly to the middle of the screen, a flash, and the scooter appears. */
@Composable
private fun ScooterAnimation(serial: Int, onDone: () -> Unit) {
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) { time.animateTo(3000f, tween(3000, easing = LinearEasing)) }
    val t = time.value
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val radius = minOf(maxWidth, maxHeight) * 0.38f
            val side = minOf(maxWidth, maxHeight) * 0.24f
            SCOOTER_PARTS.forEachIndexed { i, part ->
                val p = ((t - 500f - i * 70f) / 1100f).coerceIn(0f, 1f)
                val eased = p * p * p
                val a = i / SCOOTER_PARTS.size.toFloat() * 2 * Math.PI.toFloat() - Math.PI.toFloat() / 2
                if (p < 1f) AsyncImage(
                    Api.abs("/gifts/$part.png"), null,
                    Modifier.offset(radius * cos(a) * (1 - eased), radius * sin(a) * (1 - eased)).size(side)
                        .scale(1 - eased * 0.85f).rotate(eased * 300f).alpha(if (p > 0.7f) (1 - p) / 0.3f else 1f),
                    contentScale = ContentScale.Fit,
                )
            }
            val flash = (t - 1750f) / 1100f
            if (flash in 0f..1f) Box(
                Modifier.fillMaxSize().scale(0.2f + (if (flash < 0.3f) flash / 0.3f else 1f) * 0.9f)
                    .alpha(if (flash < 0.3f) flash / 0.3f else 1 - (flash - 0.3f) / 0.7f * 0.8f)
                    .background(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0.6f), Color.Transparent))),
            )
            val show = ((t - 2150f) / 750f).coerceIn(0f, 1f)
            if (show > 0f) Column(
                Modifier.alpha(show).scale(0.6f + 0.4f * show).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AsyncImage(Api.abs("/gifts/scooter.png"), null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)), contentScale = ContentScale.Crop)
                Text("Самокат Ethic", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (serial > 0) "№$serial из 20" else "Вне тиража", color = Color.White, fontWeight = FontWeight.Bold)
                Button(onDone, colors = WhiteButton) { Text("Отлично") }
            }
        }
    }
}

/** Gifts in a profile, like Telegram: a grid with a ribbon on limited gifts; a tap opens a separate window. */
@Composable
fun ProfileGifts(userId: Long) {
    val gifts = remember(userId) { mutableStateListOf<OwnedGift>() }
    val version by Repo.shopVersion.collectAsState()
    var open by remember { mutableStateOf<OwnedGift?>(null) }
    LaunchedEffect(userId, version) {
        runCatching {
            val list = Api.call("/api/users/$userId/gifts")["gifts"]!!
            gifts.clear(); gifts.addAll(Api.json.decodeFromJsonElement(ListSerializer(OwnedGift.serializer()), list))
        }
    }
    if (gifts.isEmpty()) return
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Подарки  ${gifts.size}", fontWeight = FontWeight.Bold)
        gifts.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { g ->
                    val full = g.giftId == "scooter"
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(if (full) SolidColor(Color.Black) else GiftGlow).clickable { open = g }, contentAlignment = Alignment.Center) {
                        AsyncImage(Api.abs(g.image), g.name, Modifier.fillMaxSize().padding(if (full) 0.dp else 14.dp), contentScale = if (full) ContentScale.Crop else ContentScale.Fit)
                        if (g.total != null && g.serial > 0) Text(
                            "${g.serial} из ${g.total}",
                            Modifier.align(Alignment.TopEnd).offset(x = 22.dp, y = 10.dp).rotate(38f).width(88.dp).background(Color(0xFFE6E6EA)),
                            color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    open?.let { g ->
        Dialog({ open = null }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface1).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val full = g.giftId == "scooter"
                AsyncImage(Api.abs(g.image), g.name, Modifier.size(170.dp).clip(RoundedCornerShape(18.dp)).background(if (full) SolidColor(Color.Black) else GiftGlow).padding(if (full) 0.dp else 10.dp), contentScale = if (full) ContentScale.Crop else ContentScale.Fit)
                Text(g.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (g.serial < 0) Text("Вне тиража", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                else if (g.total != null) Text("№${g.serial} из ${g.total}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { open = null; Nav.push(Route.UserR(g.fromId)) }) {
                        Text("От кого", color = Muted, modifier = Modifier.weight(1f))
                        Text(g.fromName, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text("Дата", color = Muted, modifier = Modifier.weight(1f))
                        Text(SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(g.createdAt)))
                    }
                }
                g.note?.let { Text(it, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).padding(12.dp)) }
                Button({ open = null }, Modifier.fillMaxWidth(), colors = WhiteButton) { Text("Закрыть") }
            }
        }
    }
}
