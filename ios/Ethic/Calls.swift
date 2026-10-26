import AVFoundation
import Foundation
import LiveKit
import Observation

struct Tile: Identifiable {
    let id: String
    let userId: Int64
    let name: String
    let local: Bool
    let screen: Bool
    let speaking: Bool
    let micOn: Bool
    let video: VideoTrack?
}

struct CallUI {
    var chatId: Int64
    var title: String
    var connected = false
    var startedAt: Date?
    var tiles: [Tile] = []
    var micOn = true
    var camOn = false
    var speaker = true
    var minimized = false
    var status = "Соединение…"
}

/// One call at a time: the LiveKit room plus the call UI state.
@MainActor
@Observable
final class Calls {
    static let shared = Calls()
    var ui: CallUI?
    var incoming: RingEvent?
    private(set) var room: Room?
    private var ticker: Task<Void, Never>?
    private var incomingTimeout: Task<Void, Never>?

    private var store: Store { Store.shared }

    func titleOf(_ chatId: Int64, _ fallback: String?) -> String {
        guard let chat = store.chats[chatId] else { return fallback ?? "Звонок" }
        return store.chatName(chat)
    }

    // MARK: incoming
    func onRing(_ ev: RingEvent) {
        guard ui == nil, incoming == nil else { return }
        store.remember([ev.from])
        incoming = ev
        Sounds.shared.ring()
        incomingTimeout?.cancel()
        incomingTimeout = Task { try? await Task.sleep(nanoseconds: 45_000_000_000); if !Task.isCancelled { dismissIncoming() } }
    }

    func dismissIncoming() {
        guard incoming != nil else { return }
        incoming = nil
        incomingTimeout?.cancel()
        Sounds.shared.stop()
    }

    func decline() {
        guard let ev = incoming else { return }
        store.send(["type": "call:decline", "chatId": ev.chatId])
        dismissIncoming()
    }

    func accept(video: Bool) {
        guard let ev = incoming else { return }
        dismissIncoming()
        start(ev.chatId, video: video, fallback: ev.title ?? ev.from.displayName)
    }

    // MARK: call
    func start(_ chatId: Int64, video: Bool, fallback: String? = nil) {
        if let cur = ui {
            if cur.chatId == chatId { ui?.minimized = false } else { store.show("Сначала завершите текущий звонок") }
            return
        }
        ui = CallUI(chatId: chatId, title: titleOf(chatId, fallback), camOn: video)
        Task {
            let creds: [String: Any]
            do { creds = try await API.call("/api/chats/\(chatId)/call", method: "POST", body: ["video": video]) }
            catch { store.show(error.localizedDescription); ui = nil; return }
            guard let url = creds["url"] as? String, let token = creds["token"] as? String else { ui = nil; return }
            Sounds.shared.stop()
            // Loudspeaker by default: the earpiece was too quiet for most people.
            AudioManager.shared.isSpeakerOutputPreferred = true
            let r = Room()
            room = r
            do {
                try await r.connect(url: url, token: token, roomOptions: RoomOptions(adaptiveStream: false, dynacast: true))
            } catch {
                store.show("Не удалось подключиться к звонку"); leave(); return
            }
            guard ui?.chatId == chatId else { return }
            store.send(["type": "call:join", "chatId": chatId])
            ui?.connected = true
            ui?.startedAt = Date()
            Sounds.shared.join()
            do { try await r.localParticipant.setMicrophone(enabled: true) } catch { store.show("Нет доступа к микрофону") }
            if video { do { try await r.localParticipant.setCamera(enabled: true) } catch { store.show("Нет доступа к камере") } }
            if r.remoteParticipants.isEmpty {
                try? await Task.sleep(nanoseconds: 600_000_000)
                if room === r, r.remoteParticipants.isEmpty { Sounds.shared.ringback() }
            }
            startTicker(r)
        }
    }

    /// The UI is rebuilt from the room twice a second (simpler than wiring every delegate event).
    private func startTicker(_ r: Room) {
        ticker?.cancel()
        var lastRemote = r.remoteParticipants.count
        ticker = Task {
            while !Task.isCancelled, room === r {
                let n = r.remoteParticipants.count
                if n > lastRemote { Sounds.shared.stop(); Sounds.shared.join() }
                if n < lastRemote { Sounds.shared.leave() }
                lastRemote = n
                if r.connectionState == .disconnected { leave(fromRoom: true); return }
                refresh()
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }

    func refresh() {
        guard let r = room, var cur = ui else { return }
        var tiles: [Tile] = []
        let people: [Participant] = [r.localParticipant] + Array(r.remoteParticipants.values)
        for p in people {
            guard let id = p.identity?.stringValue else { continue }
            let uid = Int64(id) ?? 0
            let local = p === r.localParticipant
            let name = local ? "Вы" : (p.name ?? store.userName(uid))
            if let scr = p.firstScreenShareVideoTrack, !scr.isMuted {
                tiles.append(Tile(id: "\(id):screen", userId: uid, name: "Экран: \(name)", local: local, screen: true, speaking: false, micOn: true, video: scr))
            }
            let cam = p.firstCameraVideoTrack
            tiles.append(Tile(id: "\(id):cam", userId: uid, name: name, local: local, screen: false, speaking: p.isSpeaking,
                              micOn: p.isMicrophoneEnabled(), video: (cam?.isMuted ?? true) ? nil : cam))
        }
        let n = r.remoteParticipants.count
        cur.tiles = tiles
        cur.micOn = r.localParticipant.isMicrophoneEnabled()
        cur.camOn = r.localParticipant.isCameraEnabled()
        cur.speaker = AudioManager.shared.isSpeakerOutputPreferred
        cur.status = !cur.connected ? "Соединение…" : n == 0 ? "ожидание…" : "\(n + 1) участн."
        ui = cur
    }

    func toggleMic() {
        guard let lp = room?.localParticipant else { return }
        Task { do { try await lp.setMicrophone(enabled: !lp.isMicrophoneEnabled()) } catch { store.show("Нет доступа к микрофону") }; refresh() }
    }

    func toggleCam() {
        guard let lp = room?.localParticipant else { return }
        Task { do { try await lp.setCamera(enabled: !lp.isCameraEnabled()) } catch { store.show("Нет доступа к камере") }; refresh() }
    }

    func flipCamera() {
        guard let track = room?.localParticipant.firstCameraVideoTrack as? LocalVideoTrack,
              let cap = track.capturer as? CameraCapturer else { return }
        Task { _ = try? await cap.switchCameraPosition() }
    }

    func toggleSpeaker() {
        AudioManager.shared.isSpeakerOutputPreferred.toggle()
        refresh()
    }

    func invite(_ userId: Int64) {
        guard let chatId = ui?.chatId else { return }
        Task {
            do {
                try await API.call("/api/chats/\(chatId)/call/invite", method: "POST", body: ["userId": userId])
                store.show("Звоним: \(store.userName(userId))…")
            } catch { store.show(error.localizedDescription) }
        }
    }

    func leave(fromRoom: Bool = false) {
        guard let cur = ui else { return }
        ui = nil
        ticker?.cancel()
        Sounds.shared.stop()
        Sounds.shared.leave()
        store.send(["type": "call:leave", "chatId": cur.chatId])
        if let r = room { room = nil; if !fromRoom { Task { await r.disconnect() } } }
    }
}
