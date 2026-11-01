package com.ethic.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Repo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ShopGift(
    val id: String, val name: String = "", val description: String = "", val image: String = "",
    val price: Int = 0, val total: Int? = null, val left: Int? = null,
)

@Serializable
data class OwnedGift(
    val id: Long, val giftId: String = "", val name: String = "", val image: String = "", val serial: Int = 0, val total: Int? = null,
    val fromId: Long = 0, val fromName: String = "", val note: String? = null, val createdAt: Long = 0,
)

/** The gifts are black: a soft light behind them keeps them visible on the dark app. */
private val GiftGlow = Brush.radialGradient(listOf(Color(0xFF8A8A92), Color(0xFF2A2A2F)))

@Composable
private fun CoinMark() {
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(Brush.linearGradient(listOf(Color.White, Color(0xFF9A9AA2), Color(0xFFE6E6EA)))),
        contentAlignment = Alignment.Center,
    ) { Text("E", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
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
    var recipient by remember { mutableStateOf(to) }
    var note by remember { mutableStateOf("") }
    var grantName by remember { mutableStateOf("") }
    var grantAmount by remember { mutableStateOf("") }

    suspend fun load() {
        runCatching {
            val r = Api.call("/api/shop")
            coins = r["coins"]!!.jsonPrimitive.int
            gifts.clear(); gifts.addAll(Api.json.decodeFromJsonElement(ListSerializer(ShopGift.serializer()), r["gifts"]!!))
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
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ваш баланс", color = Muted, modifier = Modifier.weight(1f))
                CoinMark()
                Spacer(Modifier.width(6.dp))
                Text("$coins", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            OutlinedTextField(recipient, { recipient = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Username получателя (пусто: себе)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            OutlinedTextField(note, { note = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Подпись, необязательно") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            gifts.forEach { g ->
                val out = g.left == 0
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsyncImage(Api.abs(g.image), null, Modifier.size(104.dp).clip(RoundedCornerShape(14.dp)).background(GiftGlow).padding(6.dp), contentScale = ContentScale.Fit)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(g.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(g.description, color = Muted, fontSize = 13.sp)
                        Text(if (g.total == null) "Без ограничений" else if (out) "Распродано" else "Осталось ${g.left} из ${g.total}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CoinMark()
                            Spacer(Modifier.width(6.dp))
                            Text("${g.price}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            if (!out) Button({ buying = g }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Купить") }
                        }
                    }
                }
            }
            if (me?.role == "owner") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Выдача Ethic Coin", fontWeight = FontWeight.Bold)
                    OutlinedTextField(grantName, { grantName = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(grantAmount, { grantAmount = it.filter { c -> c.isDigit() || c == '-' }.take(8) }, Modifier.fillMaxWidth(), label = { Text("Сколько, можно с минусом") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), shape = RoundedCornerShape(14.dp))
                    Button(
                        {
                            scope.launch {
                                runCatching { Api.call("/api/admin/coins", "POST", mapOf("username" to grantName, "amount" to (grantAmount.toIntOrNull() ?: 0))) }
                                    .onSuccess { r -> Repo.toast("Баланс: ${r["coins"]!!.jsonPrimitive.int} Ethic Coin"); grantName = ""; grantAmount = ""; load() }
                                    .onFailure { Repo.toast(it.message ?: "") }
                            }
                        },
                        enabled = grantName.isNotEmpty() && grantAmount.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) { Text("Выдать") }
                }
            }
        }
    }
    buying?.let { g ->
        AlertDialog(
            onDismissRequest = { buying = null },
            title = { Text(g.name) },
            text = { Text("Купить за ${g.price} Ethic Coin " + if (recipient.isEmpty()) "себе?" else "для @${recipient.removePrefix("@")}?") },
            confirmButton = {
                TextButton({
                    buying = null
                    scope.launch {
                        runCatching { Api.call("/api/shop/buy", "POST", mapOf("giftId" to g.id, "toUsername" to recipient, "note" to note)) }
                            .onSuccess { r -> Repo.toast("${g.name} №${r["serial"]!!.jsonPrimitive.int}: готово"); recipient = ""; note = ""; load() }
                            .onFailure { Repo.toast(it.message ?: "") }
                    }
                }) { Text("Купить") }
            },
            dismissButton = { TextButton({ buying = null }) { Text("Отмена", color = Muted) } },
        )
    }
}

/** Gifts in a profile, like Telegram: a grid with a ribbon on limited gifts; a tap opens a separate window. */
@Composable
fun ProfileGifts(userId: Long) {
    val gifts = remember(userId) { mutableStateListOf<OwnedGift>() }
    var open by remember { mutableStateOf<OwnedGift?>(null) }
    LaunchedEffect(userId) {
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
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(GiftGlow).clickable { open = g }, contentAlignment = Alignment.Center) {
                        AsyncImage(Api.abs(g.image), g.name, Modifier.fillMaxSize().padding(14.dp), contentScale = ContentScale.Fit)
                        if (g.total != null) Text(
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
                AsyncImage(Api.abs(g.image), g.name, Modifier.size(170.dp).clip(RoundedCornerShape(18.dp)).background(GiftGlow).padding(10.dp), contentScale = ContentScale.Fit)
                Text(g.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (g.total != null) Text("№${g.serial} из ${g.total}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                Button({ open = null }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Закрыть") }
            }
        }
    }
}
