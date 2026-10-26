package com.ethic.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ethic.messenger.call.CallManager
import com.ethic.messenger.call.CallUi
import com.ethic.messenger.call.Tile
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.RingEvent
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay

private fun elapsed(start: Long): String {
    val s = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun CallPill(ui: CallUi) {
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Row(
        Modifier.fillMaxWidth().background(Green).statusBarsPadding().clickable { CallManager.setMinimized(false) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PhoneInTalk, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Вернуться к звонку · ${ui.title}", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
        @Suppress("UNUSED_EXPRESSION") now
        if (ui.startedAt > 0) Text(elapsed(ui.startedAt), color = Color.White)
    }
}

@Composable
private fun VideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier) {
    val room = CallManager.room ?: return
    var view by remember { androidx.compose.runtime.mutableStateOf<TextureViewRenderer?>(null) }
    AndroidView(
        factory = { ctx -> TextureViewRenderer(ctx).also { room.initVideoRenderer(it); view = it } },
        modifier = modifier,
        update = { it.setMirror(mirror) },
        onRelease = { it.release() },
    )
    DisposableEffect(track, view) {
        val v = view
        if (v != null) track.addRenderer(v)
        onDispose { if (v != null) track.removeRenderer(v) }
    }
}

@Composable
private fun TileView(t: Tile, ui: CallUi, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(Surface2)
            .border(if (t.speaking) 3.dp else 0.dp, if (t.speaking) Green else Color.Transparent, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (t.video != null) VideoView(t.video, t.local && !t.screen && ui.frontCamera, Modifier.fillMaxSize())
        else Avatar(Repo.user(t.userId) ?: com.ethic.messenger.data.User(t.userId, displayName = t.name), 88.dp)
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!t.micOn) { Icon(Icons.Default.MicOff, null, tint = Red, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            Text(t.name, color = Color.White, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
fun CallScreen(ui: CallUi) {
    BackHandler { CallManager.setMinimized(true) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallRound(Icons.Default.KeyboardArrowDown) { CallManager.setMinimized(true) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
                @Suppress("UNUSED_EXPRESSION") now
                Text(if (ui.startedAt > 0) "${elapsed(ui.startedAt)} · ${ui.status}" else ui.status, color = Muted, fontSize = 13.sp)
            }
            SmallRound(Icons.Default.PersonAdd) { Nav.push(Route.Picker(PickMode.ADD_TO_CALL, ui.chatId)); CallManager.setMinimized(true) }
        }
        // Screen share gets the big tile; the rest share the space in a 1–2 column grid.
        val tiles = ui.tiles
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rows = if (tiles.size <= 2) tiles.map { listOf(it) } else tiles.chunked(2)
            rows.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t -> TileView(t, ui, Modifier.weight(1f).fillMaxSize()) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            BigRound(Icons.AutoMirrored.Filled.VolumeUp, "Динамик", on = ui.speaker) { CallManager.toggleSpeaker() }
            BigRound(if (ui.camOn) Icons.Default.Videocam else Icons.Default.VideocamOff, "Камера", on = ui.camOn) { CallManager.toggleCam() }
            if (ui.camOn) BigRound(Icons.Default.Cameraswitch, "Повернуть", on = false) { CallManager.flipCamera() }
            BigRound(if (ui.micOn) Icons.Default.Mic else Icons.Default.MicOff, "Микрофон", on = !ui.micOn, invert = true) { CallManager.toggleMic() }
            BigRound(Icons.Default.CallEnd, "Завершить", bg = Red) { CallManager.leave() }
        }
    }
}

@Composable
private fun SmallRound(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(Surface2).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, null) }
}

/** on = highlighted (white). For the mic, highlighted means muted. */
@Composable
private fun BigRound(icon: ImageVector, label: String, on: Boolean = false, invert: Boolean = false, bg: Color? = null, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val back = bg ?: if (on) Color.White else Surface3
        Box(Modifier.size(60.dp).clip(CircleShape).background(back).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (bg == null && on) Color.Black else Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = Muted)
    }
    @Suppress("UNUSED_EXPRESSION") invert
}

@Composable
fun IncomingScreen(ev: RingEvent, onAnswer: (Boolean) -> Unit) {
    BackHandler { }
    val chat = Repo.chats.value[ev.chatId]
    val who = if (chat?.isGroup == true) chat.title ?: "Группа" else if (ev.invite && ev.title != null) ev.title else ev.from.displayName
    val kind = if (ev.invite) "${ev.from.displayName} зовёт вас в звонок" else (if (ev.video) "Видеозвонок" else "Аудиозвонок") + " Ethic"
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.scale(pulse.value)) {
            if (chat?.isGroup == true) ChatAvatar(chat, 130.dp, null) else Avatar(ev.from, 130.dp)
        }
        Spacer(Modifier.height(24.dp))
        NameLine(who, if (chat?.isGroup == true) null else ev.from, fontSize = 28, weight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(kind, color = Muted)
        Spacer(Modifier.weight(1.4f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BigRound(Icons.Default.CallEnd, "Отклонить", bg = Red) { CallManager.decline() }
            BigRound(Icons.Default.Call, "Ответить", bg = Green) { onAnswer(false) }
            if (ev.video) BigRound(Icons.Default.Videocam, "С видео", bg = Green) { onAnswer(true) }
        }
        Spacer(Modifier.height(32.dp))
    }
}
