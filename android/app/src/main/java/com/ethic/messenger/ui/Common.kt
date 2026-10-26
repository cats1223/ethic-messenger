package com.ethic.messenger.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Chat
import com.ethic.messenger.data.User
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Blue = Color(0xFF1D9BF0)
val Green = Color(0xFF23A55A)
val Red = Color(0xFFED4245)
val Bg = Color(0xFF000000)
val Surface1 = Color(0xFF0F0F10)
val Surface2 = Color(0xFF1A1A1C)
val Surface3 = Color(0xFF26262A)
val Muted = Color(0xFF9A9AA0)
val Line = Color(0xFF2A2A2E)

private val scheme = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Surface3, onPrimaryContainer = Color.White,
    secondary = Color(0xFFD0D0D4), onSecondary = Color.Black,
    secondaryContainer = Surface3, onSecondaryContainer = Color.White,
    background = Bg, onBackground = Color.White,
    surface = Bg, onSurface = Color.White,
    surfaceVariant = Surface2, onSurfaceVariant = Muted,
    surfaceContainer = Surface1, surfaceContainerLow = Surface1, surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface3,
    surfaceContainerLowest = Bg,
    outline = Line, outlineVariant = Line, error = Red,
)

@Composable
fun EthicTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

private val avatarColors = listOf(0xFF5865F2, 0xFF3BA55C, 0xFFFAA61A, 0xFFED4245, 0xFFEB459E, 0xFF1ABC9C, 0xFF9B59B6, 0xFF607D8B).map { Color(it) }
fun colorOf(id: Long) = avatarColors[(id % avatarColors.size).toInt().let { if (it < 0) -it else it }]
fun initials(name: String) = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }

@Composable
fun Avatar(user: User?, size: Dp, modifier: Modifier = Modifier, online: Boolean = false) {
    Box(modifier.size(size)) {
        val m = Modifier.size(size).clip(CircleShape)
        if (user?.avatar != null) {
            AsyncImage(Api.abs(user.avatar), null, m.background(Surface3), contentScale = ContentScale.Crop)
        } else {
            Box(m.background(colorOf(user?.id ?: 0)), contentAlignment = Alignment.Center) {
                Text(initials(user?.displayName ?: "?"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp)
            }
        }
        if (online) Box(
            Modifier.align(Alignment.BottomEnd).size(size * 0.3f).clip(CircleShape).background(Bg).padding(size * 0.05f)
                .clip(CircleShape).background(Green)
        )
    }
}

@Composable
fun ChatAvatar(chat: Chat, size: Dp, other: User?) {
    if (!chat.isGroup) return Avatar(other, size, online = other?.online == true)
    Box(Modifier.size(size).clip(CircleShape).background(colorOf(chat.id)), contentAlignment = Alignment.Center) {
        val t = chat.title.orEmpty()
        if (t.isNotBlank()) Text(initials(t), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
        else Icon(Icons.Default.Groups, null, tint = Color.White)
    }
}

/** Blue circle with a white tick. */
@Composable
fun Verified(size: Dp = 16.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(size * 0.72f))
    }
}

@Composable
fun NameLine(name: String, user: User?, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.SemiBold, fontSize: Int = 16, color: Color = Color.White) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontWeight = weight, fontSize = fontSize.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (user?.verified == true) { androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp)); Verified((fontSize - 1).dp) }
    }
}

@Composable
fun BadgeLabel(user: User?) {
    val b = user?.badge ?: return
    Text(
        b, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Profile banner: a color or an uploaded picture. */
@Composable
fun Banner(user: User?, modifier: Modifier) {
    val b = user?.banner
    when {
        b != null && b.startsWith("#") -> Box(modifier.background(runCatching { Color(android.graphics.Color.parseColor(b)) }.getOrDefault(Surface3)))
        b != null -> AsyncImage(Api.abs(b), null, modifier.background(Surface3), contentScale = ContentScale.Crop)
        else -> Box(modifier.background(Brush.linearGradient(listOf(colorOf(user?.id ?: 0), Surface2))))
    }
}

fun Modifier.hairline() = this.border(1.dp, Line, RoundedCornerShape(16.dp))

// ---------- formatting ----------
private fun sameDay(a: Long, b: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = a }
    val c2 = Calendar.getInstance().apply { timeInMillis = b }
    return c1[Calendar.YEAR] == c2[Calendar.YEAR] && c1[Calendar.DAY_OF_YEAR] == c2[Calendar.DAY_OF_YEAR]
}
private val RU = Locale.forLanguageTag("ru")
fun timeOf(ms: Long): String = SimpleDateFormat("HH:mm", RU).format(Date(ms))
fun shortWhen(ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ms, now) -> timeOf(ms)
        sameDay(ms, now - 86_400_000) -> "вчера"
        now - ms < 6 * 86_400_000 -> SimpleDateFormat("EE", RU).format(Date(ms))
        else -> SimpleDateFormat("d MMM", RU).format(Date(ms))
    }
}
fun dayLabel(ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ms, now) -> "Сегодня"
        sameDay(ms, now - 86_400_000) -> "Вчера"
        else -> SimpleDateFormat("d MMMM yyyy", RU).format(Date(ms))
    }
}
fun isSameDay(a: Long, b: Long) = sameDay(a, b)
fun lastSeenText(u: User?): String = when {
    u == null -> ""
    u.online -> "в сети"
    u.lastSeen == null -> "не в сети"
    else -> {
        val ago = (System.currentTimeMillis() - u.lastSeen) / 60_000
        when {
            ago < 1 -> "был(а) только что"
            ago < 60 -> "был(а) $ago мин назад"
            sameDay(u.lastSeen, System.currentTimeMillis()) -> "был(а) в ${timeOf(u.lastSeen)}"
            else -> "был(а) ${shortWhen(u.lastSeen)} в ${timeOf(u.lastSeen)}"
        }
    }
}
fun sizeText(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "${b / 1024} КБ"
    else -> String.format(RU, "%.1f МБ", b / 1024.0 / 1024.0)
}
fun durText(s: Double?): String { val t = (s ?: 0.0).toInt(); return "%d:%02d".format(t / 60, t % 60) }

// ---------- files & images ----------
data class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String)

fun describe(ctx: Context, uri: Uri): PickedFile {
    var name = "файл"; var size = 0L
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    return PickedFile(uri, name, size, ctx.contentResolver.getType(uri) ?: "application/octet-stream")
}

/** Decodes, fixes rotation, scales down and saves as JPEG. Returns (file, w, h). */
fun prepareImage(ctx: Context, uri: Uri, maxSide: Int = 2560, aspect: Float? = null): Triple<File, Int, Int> {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) throw IllegalArgumentException("Не удалось открыть изображение")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: throw IllegalArgumentException("Не удалось открыть изображение")
    val rot = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
    if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
    if (aspect != null) {
        var w = bmp.width; var h = (w / aspect).toInt()
        if (h > bmp.height) { h = bmp.height; w = (h * aspect).toInt() }
        bmp = Bitmap.createBitmap(bmp, (bmp.width - w) / 2, (bmp.height - h) / 2, w, h)
    }
    val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
    if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    val out = File(ctx.cacheDir, "img-${System.currentTimeMillis()}.jpg")
    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    return Triple(out, bmp.width, bmp.height)
}
