package com.ethic.messenger.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ethic.messenger.data.Repo
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** A synthesized note, the same shapes as the website's sounds. */
data class Note(
    val at: Double, val freq: Double, val dur: Double,
    val wave: String = "sine", val vol: Double = 0.15, val to: Double? = null, val attack: Double = 0.015,
)

private const val RATE = 44100

private fun render(notes: List<Note>, length: Double, gain: Double = 2.2): ShortArray {
    val n = (length * RATE).toInt()
    val buf = DoubleArray(n)
    for (note in notes) {
        val start = (note.at * RATE).toInt()
        val len = ((note.dur + 0.05) * RATE).toInt()
        var phase = 0.0
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= n) break
            val t = i.toDouble() / RATE
            val f = if (note.to != null) note.freq * exp(ln(note.to / note.freq) * minOf(1.0, t / note.dur)) else note.freq
            phase += 2 * PI * f / RATE
            val s = when (note.wave) {
                "square" -> if (sin(phase) >= 0) 1.0 else -1.0
                "triangle" -> 2 / PI * kotlin.math.asin(sin(phase))
                else -> sin(phase)
            }
            // Exponential attack/decay envelope like the WebAudio version.
            val env = when {
                t < note.attack -> 0.0001 * Math.pow(note.vol / 0.0001, t / note.attack)
                t < note.dur -> note.vol * Math.pow(0.0001 / note.vol, (t - note.attack) / (note.dur - note.attack))
                else -> 0.0
            }
            buf[idx] += s * env
        }
    }
    return ShortArray(n) { i -> (buf[i] * gain).coerceIn(-1.0, 1.0).times(32767).toInt().toShort() }
}

object Sounds {
    private lateinit var app: Context

    data class Ringtone(val id: String, val name: String, val period: Double, val notes: List<Note>?)

    val ringtones: List<Ringtone> = listOf(
        Ringtone("classic", "Классика", 2.0, listOf(Note(0.0, 880.0, 0.22), Note(0.25, 660.0, 0.22))),
        Ringtone("marimba", "Маримба", 2.4, listOf(659, 784, 988, 1319, 988, 1319).mapIndexed { i, f ->
            Note(i * 0.13, f.toDouble(), 0.35, "triangle", 0.2, attack = 0.005) }),
        Ringtone("soft", "Нежный", 2.6, listOf(523, 659, 784).map { Note(0.0, it.toDouble(), 1.1, vol = 0.07, attack = 0.25) }),
        Ringtone("digital", "Цифровой", 1.6, (0 until 4).map { Note(it * 0.12, 1250.0, 0.07, "square", 0.05, attack = 0.003) }),
        Ringtone("retro", "Старый телефон", 3.0, (0 until 20).map { Note(it * 0.05, if (it % 2 == 1) 480.0 else 440.0, 0.05, "square", 0.04, attack = 0.002) }),
        Ringtone("system", "Системный рингтон", 2.0, null),
        Ringtone("none", "Без звука", 2.0, emptyList()),
    )

    fun init(ctx: Context) { app = ctx.applicationContext }

    val ringtoneId get() = Repo.prefs.getString("ringtone", "classic") ?: "classic"
    val messageSounds get() = Repo.prefs.getBoolean("msgSound", true)

    private fun play(notes: List<Note>, length: Double, usage: Int) {
        Thread {
            val pcm = render(notes, length)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            runCatching {
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((length * 1000).toLong() + 100)
            }
            track.release()
        }.start()
    }

    fun sent() { if (messageSounds) play(listOf(Note(0.0, 320.0, 0.14, vol = 0.07, to = 1400.0, attack = 0.01)), 0.25, AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) }
    fun received() {
        if (messageSounds) play(listOf(Note(0.0, 988.0, 0.12, "triangle", 0.12, attack = 0.004), Note(0.09, 1319.0, 0.18, "triangle", 0.1, attack = 0.004)), 0.35, AudioAttributes.USAGE_NOTIFICATION)
    }
    // Call sounds go on the voice stream so they follow the call volume.
    fun join() = play(listOf(Note(0.0, 587.0, 0.16, "triangle", 0.14, attack = 0.005), Note(0.11, 880.0, 0.24, "triangle", 0.14, attack = 0.005)), 0.4, AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
    fun leave() = play(listOf(Note(0.0, 880.0, 0.16, "triangle", 0.12, attack = 0.005), Note(0.11, 523.0, 0.26, "triangle", 0.12, attack = 0.005)), 0.42, AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)

    // ---------- looping: ringtone (incoming) and ringback (outgoing) ----------
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private fun wavOf(name: String, notes: List<Note>, period: Double): File {
        val f = File(app.cacheDir, "$name.wav")
        if (f.exists()) return f
        val pcm = render(notes, period)
        val bytes = java.io.ByteArrayOutputStream()
        val data = pcm.size * 2
        fun i32(v: Int) = bytes.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun i16(v: Int) = bytes.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        bytes.write("RIFF".toByteArray()); i32(36 + data); bytes.write("WAVEfmt ".toByteArray())
        i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        bytes.write("data".toByteArray()); i32(data)
        for (s in pcm) i16(s.toInt())
        f.writeBytes(bytes.toByteArray())
        return f
    }

    private fun loop(uri: Uri, usage: Int) {
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(app, uri)
            isLooping = true
            prepare()
            start()
        }
    }

    /** Incoming call: ring volume, respects silent / vibrate mode like a phone call. */
    fun ring(id: String = ringtoneId, preview: Boolean = false) {
        stop()
        val am = app.getSystemService(AudioManager::class.java)
        val mode = am.ringerMode
        if (!preview && mode == AudioManager.RINGER_MODE_SILENT) return
        if (!preview) vibrate()
        if (mode != AudioManager.RINGER_MODE_NORMAL && !preview) return
        val r = ringtones.firstOrNull { it.id == id } ?: ringtones[0]
        if (r.notes?.isEmpty() == true) return
        runCatching {
            val uri = if (r.notes == null) RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            else Uri.fromFile(wavOf("ring-${r.id}", r.notes, r.period))
            loop(uri, AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        }
    }

    /** Outgoing call beeps while nobody has joined yet. */
    fun ringback() {
        stop()
        runCatching { loop(Uri.fromFile(wavOf("ringback", listOf(Note(0.0, 425.0, 1.0, vol = 0.06, attack = 0.02)), 4.0)), AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING) }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = if (android.os.Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
        else app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!v.hasVibrator()) return
        vibrator = v
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 1),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        vibrator?.cancel(); vibrator = null
    }
}
