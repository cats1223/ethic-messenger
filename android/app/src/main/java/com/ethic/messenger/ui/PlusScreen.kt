package com.ethic.messenger.ui

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ethic.messenger.R
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Repo
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

private val STYLES = listOf(null to "Без градиента", "red" to "Красно-оранжевый", "blue" to "Сине-голубой", "purple" to "Фиолетово-синий")

fun nameBrush(style: String?): Brush? = when (style) {
    "red" -> Brush.horizontalGradient(listOf(Color(0xFFFF3B30), Color(0xFFFF9500)))
    "blue" -> Brush.horizontalGradient(listOf(Color(0xFF2F6BFF), Color(0xFF38D6FF)))
    "purple" -> Brush.horizontalGradient(listOf(Color(0xFFA855F7), Color(0xFF3B82F6)))
    else -> null
}

fun isVideoBanner(b: String?) = b != null && Regex("^/uploads/[a-f0-9]{32}\\.(mp4|webm)$").matches(b)

/** The Ethic+ pill next to a subscriber's name. */
@Composable
fun PlusBadge() {
    Row(
        Modifier.padding(start = 5.dp).clip(RoundedCornerShape(50)).background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(start = 3.dp, end = 7.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text("Ethic+", style = TextStyle(brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFB9B9C0))), fontSize = 10.sp, fontWeight = FontWeight.Bold))
    }
}

/** A looping muted video, for video banners. */
@Composable
fun VideoBanner(url: String, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f); start() }
                setOnErrorListener { _, _, _ -> true }
                setVideoURI(Uri.parse(Api.abs(url)))
            }
        },
        modifier = modifier,
        onRelease = { it.stopPlayback() },
    )
}

/** «Подписка»: subscribers pick their perks; the owner also grants the subscription here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by Repo.me.collectAsState()
    val u = me ?: return
    val owner = u.role == "owner"
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val users = remember { mutableStateListOf<AdminUser>() }

    suspend fun load() {
        if (!owner) return
        runCatching {
            val list = Api.call("/api/admin/users?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8"))["users"]!!
            users.clear(); users.addAll(Api.json.decodeFromJsonElement(ListSerializer(AdminUser.serializer()), list).filter { !it.username.startsWith("deleted_") })
        }.onFailure { Repo.toast(it.message ?: "") }
    }
    LaunchedEffect(q) { kotlinx.coroutines.delay(250); load() }
    fun save(body: Map<String, Any?>, ok: String) = scope.launch {
        runCatching { Repo.updateMe(body) }.onSuccess { Repo.toast(ok) }.onFailure { Repo.toast(it.message ?: "") }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val f = describe(ctx, uri)
        if (f.size > 25L * 1024 * 1024) { Repo.toast("Видео больше 25 МБ"); return@rememberLauncherForActivityResult }
        busy = true
        scope.launch {
            runCatching {
                val (url, _, ext) = Api.upload(ctx, uri, "file", f.name)
                if (ext != "mp4" && ext != "webm") throw IllegalArgumentException("Нужен файл MP4 или WebM")
                Repo.updateMe(mapOf("banner" to url))
            }.onSuccess { Repo.toast("Видео-баннер установлен") }.onFailure { Repo.toast(it.message ?: "") }
            busy = false
        }
    }

    Scaffold(containerColor = Bg, topBar = {
        TopAppBar(
            title = { Text("Ethic+", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton({ Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Section {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!u.plus) Text("Подписки пока нет. Выдайте её себе в списке ниже.", color = Muted)
                        else {
                            Text("Цвет ника", color = Muted, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) { NameLine(u.displayName, u, fontSize = 22, weight = FontWeight.ExtraBold) }
                            STYLES.forEach { (v, label) ->
                                val sel = u.nameStyle == v
                                Text(
                                    label,
                                    style = nameBrush(v)?.let { TextStyle(brush = it, fontWeight = FontWeight.Bold) } ?: TextStyle(color = Color.White, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2)
                                        .border(2.dp, if (sel) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                                        .clickable { save(mapOf("nameStyle" to v), "Цвет ника изменён") }.padding(12.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("Видео-баннер", color = Muted, fontSize = 13.sp)
                            if (isVideoBanner(u.banner)) VideoBanner(u.banner!!, Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton({ pickVideo.launch("video/*") }, enabled = !busy) { Text(if (busy) "Загрузка…" else "Загрузить видео") }
                                if (isVideoBanner(u.banner)) TextButton({ save(mapOf("banner" to null), "Видео убрано") }) { Text("Убрать", color = Red) }
                            }
                            Text("MP4 или WebM, до 25 МБ", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (owner) {
                item {
                    Text("Выдача подписки", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
                    SearchField(q, { q = it }, "Поиск пользователя")
                }
                items(users, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(a.asUser, 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            NameLine(a.displayName, a.asUser)
                            Text("ID ${a.id} · @${a.username}", color = Muted, fontSize = 12.sp)
                        }
                        Button(
                            {
                                scope.launch {
                                    runCatching { Api.call("/api/admin/users/${a.id}/plus", "POST", mapOf("plus" to !a.plus)) }
                                        .onSuccess { Repo.toast(if (a.plus) "Подписка забрана" else "Подписка выдана"); if (a.id == u.id) runCatching { Repo.fetchMe() }; load() }
                                        .onFailure { Repo.toast(it.message ?: "") }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (a.plus) Surface3 else Color.White, contentColor = if (a.plus) Color.White else Color.Black),
                        ) { Text(if (a.plus) "Забрать" else "Выдать") }
                    }
                }
            }
        }
    }
}
