package com.ethic.clicker

import android.accounts.AccountManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val BASE = "https://ethicmessenger.duckdns.org:8443"
private const val ID_PACKAGE = "com.ethic.id"
private const val ID_CERT = "13ed7d11193493d1613c6a5afbcb3140b71856997faf842bf74fd4dfe3d32354"
private const val TOKEN_TYPE = "clicker"
private const val ENDLESS_COINS = 999_999_999

private val Bg = Color.Black
private val Card = Color(0xFF111114)
private val Muted = Color(0xFF9A9AA2)
private val Red = Color(0xFFED4245)

/** The clicker runs only on Ethic OS: it is a system app itself, next to the real system Ethic ID. */
private fun onEthicOs(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 28) return false
    if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) return false
    return runCatching {
        val info = ctx.packageManager.getPackageInfo(ID_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        info.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0 && signers.size == 1 &&
            MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).joinToString("") { "%02x".format(it) } == ID_CERT
    }.getOrDefault(false)
}

private class ApiError(message: String, val status: Int) : Exception(message)

private class Api(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("clicker", Context.MODE_PRIVATE)
    private var token: String? = prefs.getString("token", null)

    private fun account() = AccountManager.get(ctx).getAccountsByType(ID_PACKAGE).firstOrNull()

    /** The session comes from Ethic ID once and is kept here. */
    private fun ensureToken(): String {
        token?.let { return it }
        val acc = account() ?: throw ApiError("Войдите в Ethic ID в настройках системы", 0)
        val t = runCatching { AccountManager.get(ctx).getAuthToken(acc, TOKEN_TYPE, null, false, null, null).result.getString(AccountManager.KEY_AUTHTOKEN) }.getOrNull()
            ?: throw ApiError("Ethic ID не выдал вход. Проверьте сеть и попробуйте ещё раз", 0)
        token = t
        prefs.edit().putString("token", t).apply()
        return t
    }

    private fun forget() {
        token?.let { AccountManager.get(ctx).invalidateAuthToken(ID_PACKAGE, it) }
        token = null
        prefs.edit().remove("token").apply()
    }

    private fun send(path: String, body: JSONObject?, bearer: String): Pair<Int, JSONObject> {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer $bearer")
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = try { c.responseCode } catch (e: java.io.IOException) { throw ApiError("Нет соединения с сервером", 0) }
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    suspend fun call(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        var (code, json) = send(path, body, ensureToken())
        if (code == 401) {
            // The session ended (signed out from the device list): ask Ethic ID once more.
            forget()
            val retry = send(path, body, ensureToken())
            code = retry.first; json = retry.second
        }
        if (code !in 200..299) throw ApiError(json.optString("error", "Ошибка $code"), code)
        json
    }
}

private data class Quest(val id: String, val title: String, val reward: Int, val goal: Int, val progress: Int, val premium: Boolean, val locked: Boolean, val claimed: Boolean)
private data class State(val clicks: Int, val earned: Int, val cap: Int, val coins: Int, val quests: List<Quest>)

private fun parse(j: JSONObject): State {
    val a = j.getJSONArray("quests")
    val quests = (0 until a.length()).map {
        val q = a.getJSONObject(it)
        Quest(q.getString("id"), q.getString("title"), q.getInt("reward"), q.getInt("goal"), q.getInt("progress"), q.optBoolean("premium"), q.optBoolean("locked"), q.optBoolean("claimed"))
    }
    return State(j.getInt("clicks"), j.getInt("earned"), j.getInt("cap"), j.getInt("coins"), quests)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, background = Bg, surface = Bg)) {
                Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
                    if (onEthicOs(this@MainActivity)) Clicker() else Blocked { finish() }
                }
            }
        }
    }
}

@Composable
private fun Blocked(close: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Доступно только на Ethic OS", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.size(16.dp))
        Button(close, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Закрыть") }
    }
}

@Composable
private fun Clicker() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { Api(ctx) }
    var state by remember { mutableStateOf<State?>(null) }
    var error by remember { mutableStateOf("") }
    var pending by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableIntStateOf(0) }
    val pulse by animateFloatAsState(if (pressed % 2 == 0) 1f else 0.94f, label = "tap")

    suspend fun load() {
        runCatching { api.call("/api/clicker") }.onSuccess { state = parse(it); error = "" }.onFailure { error = it.message ?: "Ошибка" }
    }
    LaunchedEffect(Unit) { load() }
    // Clicks go to the server in small batches; it decides how many count.
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            val n = pending
            if (n > 0) {
                pending -= n
                runCatching { api.call("/api/clicker/clicks", JSONObject().put("count", n)) }.onSuccess { state = parse(it) }
            }
        }
    }

    val s = state
    if (s == null) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (error.isEmpty()) CircularProgressIndicator(color = Color.White)
            else {
                Text(error, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.size(16.dp))
                Button({ error = ""; scope.launch { load() } }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Повторить") }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ethic Coin", color = Muted, fontSize = 13.sp)
                Text(if (s.coins >= ENDLESS_COINS) "∞" else "${s.coins}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Сегодня", color = Muted, fontSize = 13.sp)
                Text("${s.earned} из ${s.cap}", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(220.dp).scale(pulse).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF8A8A92), Color(0xFF1C1C20))))
                    .clickable(remember { MutableInteractionSource() }, null) { pending++; pressed++ },
                contentAlignment = Alignment.Center,
            ) { Image(painterResource(R.drawable.logo), null, Modifier.size(170.dp)) }
        }
        Text("${s.clicks + pending} кликов сегодня", color = Muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        if (error.isNotEmpty()) Text(error, color = Red, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text("Задания", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        s.quests.forEach { q ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.title, color = if (q.locked) Muted else Color.White, fontWeight = FontWeight.SemiBold)
                            if (q.premium) {
                                Spacer(Modifier.width(6.dp))
                                Text("Ethic+", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                        }
                        Text(if (q.locked) "Нужна подписка Ethic+" else "+${q.reward} монет", color = Muted, fontSize = 12.sp)
                    }
                    when {
                        q.claimed -> Text("Получено", color = Muted, fontSize = 13.sp)
                        q.locked -> Unit
                        else -> Button(
                            {
                                scope.launch {
                                    runCatching { api.call("/api/clicker/claim", JSONObject().put("id", q.id)) }
                                        .onSuccess { state = parse(it); error = "" }
                                        .onFailure { error = it.message ?: "Ошибка" }
                                }
                            },
                            enabled = q.progress >= q.goal,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        ) { Text("Забрать") }
                    }
                }
                if (q.goal > 1 && !q.locked) LinearProgressIndicator({ q.progress / q.goal.toFloat() }, Modifier.fillMaxWidth(), color = Color.White, trackColor = Color(0xFF2A2A2F))
            }
        }
    }
}
