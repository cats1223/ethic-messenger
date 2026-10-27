package com.ethic.messenger.media

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Voice messages: AAC in MP4, 128 kbps / 48 kHz (the server accepts mp4 voice). */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L

    fun start() {
        val f = File(ctx.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(128_000)
        r.setAudioSamplingRate(48_000)
        r.setAudioChannels(1)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        rec = r; file = f; started = System.currentTimeMillis()
    }

    val elapsed get() = if (rec == null) 0L else System.currentTimeMillis() - started

    /** Returns the recording and its length in seconds, or null if too short / cancelled. */
    fun stop(keep: Boolean): Pair<File, Double>? {
        val r = rec ?: return null
        val dur = (System.currentTimeMillis() - started) / 1000.0
        rec = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = file ?: return null
        if (!keep || !ok || dur < 0.5) { f.delete(); return null }
        return f to dur
    }
}

/** One shared player for voice messages: starting one stops the other. */
object VoicePlayer {
    private var player: ExoPlayer? = null
    val playing = MutableStateFlow<String?>(null)
    val position = MutableStateFlow(0f)
    private var ticker: Job? = null

    fun toggle(ctx: Context, url: String) {
        val p = player ?: ExoPlayer.Builder(ctx.applicationContext).build().also { player = it }
        if (playing.value == url) {
            if (p.isPlaying) p.pause() else p.play()
            return
        }
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.prepare()
        p.play()
        playing.value = url
        position.value = 0f
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && playing.value == url) { playing.value = null; position.value = 0f; p.removeListener(this) }
            }
        })
        ticker?.cancel()
        ticker = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val d = p.duration
                if (d > 0) position.value = (p.currentPosition.toFloat() / d).coerceIn(0f, 1f)
                delay(16) // every frame: smooth progress
            }
        }
    }

    val isPaused get() = player?.isPlaying == false

    fun stop() { player?.stop(); playing.value = null; ticker?.cancel() }
}
