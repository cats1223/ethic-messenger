package com.ethic.messenger.call

import android.content.Context
import android.content.Intent
import com.ethic.messenger.Notifications
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.AppEvent
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.RingEvent
import com.ethic.messenger.media.Sounds
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

data class Tile(
    val key: String,
    val userId: Long,
    val name: String,
    val local: Boolean,
    val screen: Boolean,
    val speaking: Boolean,
    val micOn: Boolean,
    val video: VideoTrack?,
)

data class CallUi(
    val chatId: Long,
    val title: String,
    val connected: Boolean = false,
    val startedAt: Long = 0,
    val tiles: List<Tile> = emptyList(),
    val micOn: Boolean = true,
    val camOn: Boolean = false,
    val speaker: Boolean = true,
    val frontCamera: Boolean = true,
    val minimized: Boolean = false,
    val status: String = "Соединение…",
)

/** One call at a time; owns the LiveKit room and tells the system a call is running. */
object CallManager {
    private lateinit var app: Context
    val state = MutableStateFlow<CallUi?>(null)
    val incoming = MutableStateFlow<RingEvent?>(null)
    /** Bumped when the UI should ask for the camera permission. */
    val askCamera = MutableStateFlow(0)
    var room: Room? = null
        private set
    private var eventsJob: Job? = null
    private var incomingTimeout: Job? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        Repo.scope.launch {
            Repo.events.collect { ev ->
                when (ev) {
                    is AppEvent.Ring -> onRing(ev.ev)
                    is AppEvent.CallState -> if (ev.call == null && incoming.value?.chatId == ev.chatId) dismissIncoming()
                    is AppEvent.Declined -> if (state.value?.chatId == ev.chatId) { Repo.toast("${Repo.userName(ev.userId)} отклоняет звонок"); Sounds.stop() }
                    is AppEvent.Invited -> if (state.value?.chatId == ev.chatId && ev.by != Repo.me.value?.id) Repo.toast("${Repo.userName(ev.by)} зовёт: ${ev.name}")
                    else -> {}
                }
            }
        }
    }

    fun titleOf(chatId: Long, fallback: String?): String {
        val chat = Repo.chats.value[chatId] ?: return fallback ?: "Звонок"
        return Repo.chatName(chat)
    }

    // ---------- incoming ----------
    /** From a push while the app was closed: same as a ring over the socket. */
    fun ringFromPush(ev: RingEvent) = Repo.scope.launch { onRing(ev) }

    private fun onRing(ev: RingEvent) {
        if (state.value != null || incoming.value != null) return
        Repo.remember(listOf(ev.from))
        incoming.value = ev
        Sounds.ring()
        if (!Repo.foreground) Notifications.incoming(ev)
        incomingTimeout?.cancel()
        incomingTimeout = Repo.scope.launch { delay(45_000); dismissIncoming() }
    }

    fun dismissIncoming() {
        incoming.value ?: return
        incoming.value = null
        incomingTimeout?.cancel()
        Sounds.stop()
        Notifications.cancelIncoming()
    }

    fun decline() {
        val ev = incoming.value ?: return
        Repo.send(mapOf("type" to "call:decline", "chatId" to ev.chatId))
        dismissIncoming()
    }

    fun accept(video: Boolean) {
        val ev = incoming.value ?: return
        dismissIncoming()
        start(ev.chatId, video, ev.title ?: ev.from.displayName)
    }

    // ---------- call ----------
    fun start(chatId: Long, video: Boolean, fallbackTitle: String? = null) {
        state.value?.let { cur ->
            if (cur.chatId == chatId) state.value = cur.copy(minimized = false) else Repo.toast("Сначала завершите текущий звонок")
            return
        }
        state.value = CallUi(chatId, titleOf(chatId, fallbackTitle), camOn = video)
        Repo.scope.launch {
            val creds = try { Api.call("/api/chats/$chatId/call", "POST", mapOf("video" to video)) } catch (e: Exception) {
                Repo.toast(e.message ?: "Ошибка"); state.value = null; return@launch
            }
            val r = LiveKit.create(app, RoomOptions(adaptiveStream = false, dynacast = true,
                audioTrackCaptureDefaults = LocalAudioTrackOptions(noiseSuppression = noiseSuppression, echoCancellation = true, autoGainControl = true)))
            // Loudspeaker first (headsets still win): a call on the earpiece was too quiet.
            r.audioSwitchHandler?.preferredDeviceList = listOf(
                AudioDevice.BluetoothHeadset::class.java, AudioDevice.WiredHeadset::class.java,
                AudioDevice.Speakerphone::class.java, AudioDevice.Earpiece::class.java,
            )
            room = r
            eventsJob = Repo.scope.launch { r.events.collect { onRoomEvent(it) } }
            CallService.start(app, state.value?.title ?: "Звонок", video)
            try {
                r.connect(creds["url"]!!.jsonPrimitive.content, creds["token"]!!.jsonPrimitive.content)
            } catch (e: Exception) {
                Repo.toast("Не удалось подключиться к звонку"); leave(); return@launch
            }
            if (state.value?.chatId != chatId) return@launch
            Repo.send(mapOf("type" to "call:join", "chatId" to chatId))
            state.value = state.value?.copy(connected = true, startedAt = System.currentTimeMillis())
            Sounds.join()
            // The permission prompt may still be on screen: wait for the answer first.
            if (awaitPermission(android.Manifest.permission.RECORD_AUDIO)) runCatching { r.localParticipant.setMicrophoneEnabled(true) }.onFailure { Repo.toast("Нет доступа к микрофону") }
            else Repo.toast("Нет доступа к микрофону — собеседник вас не слышит")
            if (room != r) return@launch
            if (video && awaitPermission(android.Manifest.permission.CAMERA)) runCatching { r.localParticipant.setCameraEnabled(true) }.onFailure { Repo.toast("Нет доступа к камере") }
            CallService.start(app, state.value?.title ?: "Звонок", video)
            if (r.remoteParticipants.isEmpty()) {
                delay(600)
                if (room == r && r.remoteParticipants.isEmpty()) Sounds.ringback()
                Repo.scope.launch { delay(60_000); if (room == r && r.remoteParticipants.isEmpty()) Sounds.stop() }
            }
            refresh()
        }
    }

    private fun onRoomEvent(e: RoomEvent) {
        when (e) {
            is RoomEvent.ParticipantConnected -> { Sounds.stop(); Sounds.join() }
            is RoomEvent.ParticipantDisconnected -> Sounds.leave()
            is RoomEvent.Reconnecting -> state.value = state.value?.copy(status = "Переподключение…")
            is RoomEvent.Disconnected -> if (room === e.room) { leave(fromRoom = true); return }
            else -> {}
        }
        refresh()
    }

    fun refresh() {
        val r = room ?: return
        val cur = state.value ?: return
        val people: List<Participant> = listOf(r.localParticipant) + r.remoteParticipants.values
        val speaking = r.activeSpeakers.map { it.identity?.value }.toSet()
        val tiles = mutableListOf<Tile>()
        for (p in people) {
            val id = p.identity?.value ?: continue
            val uid = id.toLongOrNull() ?: 0
            val local = p === r.localParticipant
            val name = if (local) "Вы" else p.name ?: Repo.userName(uid)
            val scr = p.getTrackPublication(Track.Source.SCREEN_SHARE)
            (scr?.track as? VideoTrack)?.takeIf { !scr.muted }?.let { tiles += Tile("$id:screen", uid, "Экран: $name", local, true, false, true, it) }
            val cam = p.getTrackPublication(Track.Source.CAMERA)
            tiles += Tile("$id:cam", uid, name, local, false, id in speaking, p.isMicrophoneEnabled,
                (cam?.track as? VideoTrack)?.takeIf { !cam.muted })
        }
        val n = r.remoteParticipants.size
        state.value = cur.copy(
            tiles = tiles,
            micOn = r.localParticipant.isMicrophoneEnabled,
            camOn = r.localParticipant.isCameraEnabled,
            speaker = r.audioSwitchHandler?.selectedAudioDevice is AudioDevice.Speakerphone,
            status = if (!cur.connected) "Соединение…" else if (n == 0) "ожидание…" else "${n + 1} участн.",
        )
    }

    private fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(app, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Polls until the permission is granted (the user is answering the system prompt), up to 30 s. */
    private suspend fun awaitPermission(p: String): Boolean {
        repeat(100) { if (granted(p)) return true; delay(300) }
        return granted(p)
    }

    fun toggleMic() = Repo.scope.launch {
        val lp = room?.localParticipant ?: return@launch
        runCatching { lp.setMicrophoneEnabled(!lp.isMicrophoneEnabled) }.onFailure { Repo.toast("Нет доступа к микрофону") }
        refresh()
    }

    fun toggleCam() = Repo.scope.launch {
        val lp = room?.localParticipant ?: return@launch
        val on = !lp.isCameraEnabled
        if (on && !granted(android.Manifest.permission.CAMERA)) { Repo.events.tryEmit(com.ethic.messenger.data.AppEvent.Toast("Разрешите доступ к камере")); askCamera.value++; if (!awaitPermission(android.Manifest.permission.CAMERA)) return@launch }
        runCatching { lp.setCameraEnabled(on) }.onFailure { Repo.toast("Нет доступа к камере") }
        if (on) CallService.start(app, state.value?.title ?: "Звонок", true)
        refresh()
    }

    fun flipCamera() {
        val cur = state.value ?: return
        val track = room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        val front = !cur.frontCamera
        runCatching { track.switchCamera(position = if (front) CameraPosition.FRONT else CameraPosition.BACK) }
        state.value = cur.copy(frontCamera = front)
    }

    fun toggleSpeaker() {
        val h = room?.audioSwitchHandler ?: return
        val toSpeaker = h.selectedAudioDevice !is AudioDevice.Speakerphone
        val target = h.availableAudioDevices.firstOrNull {
            if (toSpeaker) it is AudioDevice.Speakerphone else it !is AudioDevice.Speakerphone
        }
        h.selectDevice(target)
        Repo.scope.launch { delay(300); refresh() }
    }

    fun setMinimized(min: Boolean) { state.value = state.value?.copy(minimized = min) }

    fun invite(userId: Long) = Repo.scope.launch {
        val chatId = state.value?.chatId ?: return@launch
        runCatching { Api.call("/api/chats/$chatId/call/invite", "POST", mapOf("userId" to userId)) }
            .onSuccess { Repo.toast("Звоним: ${Repo.userName(userId)}…") }
            .onFailure { Repo.toast(it.message ?: "Ошибка") }
    }

    fun leave(fromRoom: Boolean = false) {
        val cur = state.value ?: return
        state.value = null
        Sounds.stop()
        Sounds.leave()
        Repo.send(mapOf("type" to "call:leave", "chatId" to cur.chatId))
        eventsJob?.cancel()
        room?.let { r -> if (!fromRoom) r.disconnect(); r.release() }
        room = null
        CallService.stop(app)
    }

    val noiseSuppression get() = Repo.prefs.getBoolean("noise", true)

    /** Notification "Завершить" button. */
    fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            CallService.ACTION_HANGUP -> leave()
            Notifications.ACTION_DECLINE -> decline()
        }
    }
}
