import LiveKit
import SwiftUI

private func elapsed(_ start: Date?) -> String {
    guard let start else { return "" }
    let s = Int(Date().timeIntervalSince(start))
    return String(format: "%d:%02d", s / 60, s % 60)
}

struct CallPill: View {
    let ui: CallUI
    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            Button { Calls.shared.ui?.minimized = false } label: {
                HStack {
                    Image(systemName: "phone.fill")
                    Text("Вернуться к звонку · \(ui.title)").lineLimit(1)
                    Spacer()
                    Text(elapsed(ui.startedAt)).monospacedDigit()
                }
                .font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(green)
            }
        }
    }
}

private struct TileView: View {
    let tile: Tile
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            RoundedRectangle(cornerRadius: 22).fill(surface2)
            if let v = tile.video {
                SwiftUIVideoView(v, layoutMode: tile.screen ? .fit : .fill, mirrorMode: tile.local && !tile.screen ? .mirror : .off)
                    .clipShape(RoundedRectangle(cornerRadius: 22))
            } else {
                AvatarView(user: Store.shared.user(tile.userId) ?? User(id: tile.userId, displayName: tile.name), size: 92)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            HStack(spacing: 4) {
                if !tile.micOn { Image(systemName: "mic.slash.fill").foregroundStyle(red) }
                Text(tile.name).lineLimit(1)
            }
            .font(.caption).padding(.horizontal, 10).padding(.vertical, 5).glass().padding(10)
        }
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(tile.speaking ? green : .clear, lineWidth: 3))
    }
}

struct CallView: View {
    let ui: CallUI
    private var calls: Calls { Calls.shared }
    @State private var picker = false

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                circle("chevron.down") { calls.ui?.minimized = true }
                Spacer()
                VStack(spacing: 2) {
                    Text(ui.title).font(.headline).lineLimit(1)
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        Text(ui.startedAt == nil ? ui.status : "\(elapsed(ui.startedAt)) · \(ui.status)").font(.caption).foregroundStyle(muted)
                    }
                }
                Spacer()
                circle("person.badge.plus") { Nav.shared.callPicker = true }
            }
            .padding(.horizontal)
            let rows: [[Tile]] = ui.tiles.count <= 2 ? ui.tiles.map { [$0] } : stride(from: 0, to: ui.tiles.count, by: 2).map { Array(ui.tiles[$0..<min($0 + 2, ui.tiles.count)]) }
            VStack(spacing: 8) {
                ForEach(rows.indices, id: \.self) { i in
                    HStack(spacing: 8) { ForEach(rows[i]) { TileView(tile: $0) } }
                }
            }
            .padding(.horizontal, 10)
            // Liquid Glass control bar.
            HStack(spacing: 14) {
                control("speaker.wave.2.fill", "Динамик", on: ui.speaker) { calls.toggleSpeaker() }
                control(ui.camOn ? "video.fill" : "video.slash.fill", "Камера", on: ui.camOn) { calls.toggleCam() }
                if ui.camOn { control("arrow.triangle.2.circlepath.camera", "Повернуть", on: false) { calls.flipCamera() } }
                control(ui.micOn ? "mic.fill" : "mic.slash.fill", "Микрофон", on: !ui.micOn) { calls.toggleMic() }
                Button { calls.leave() } label: {
                    Image(systemName: "phone.down.fill").font(.title2).foregroundStyle(.white).frame(width: 60, height: 60).background(red, in: Circle())
                }
            }
            .padding(14)
            .glass(Capsule())
            .padding(.bottom, 8)
        }
        .background(Color.black.ignoresSafeArea())
    }

    private func circle(_ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).font(.headline).frame(width: 44, height: 44) }.glass(Circle())
    }

    private func control(_ icon: String, _ label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.title3).foregroundStyle(on ? .black : .white)
                .frame(width: 56, height: 56).background(on ? Color.white : surface3, in: Circle())
        }
        .accessibilityLabel(label)
    }
}

struct IncomingView: View {
    let ev: RingEvent
    @State private var pulse = false
    var body: some View {
        let store = Store.shared
        let chat = store.chats[ev.chatId]
        let who = chat?.isGroup == true ? (chat?.title ?? "Группа") : (ev.invite == true ? (ev.title ?? ev.from.displayName) : ev.from.displayName)
        let kind = ev.invite == true ? "\(ev.from.displayName) зовёт вас в звонок" : (ev.video == true ? "Видеозвонок" : "Аудиозвонок") + " Ethic"
        VStack(spacing: 10) {
            Spacer()
            Group {
                if let chat, chat.isGroup { ChatAvatar(chat: chat, size: 130) } else { AvatarView(user: ev.from, size: 130) }
            }
            .scaleEffect(pulse ? 1.1 : 1)
            .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: pulse)
            NameLine(name: who, user: chat?.isGroup == true ? nil : ev.from, font: .largeTitle.weight(.heavy)).padding(.top, 16)
            Text(kind).foregroundStyle(muted)
            Spacer()
            HStack {
                action("phone.down.fill", "Отклонить", red) { Calls.shared.decline() }
                Spacer()
                action("phone.fill", "Ответить", green) { Calls.shared.accept(video: false) }
                if ev.video == true { Spacer(); action("video.fill", "С видео", green) { Calls.shared.accept(video: true) } }
            }
            .padding(.horizontal, 40).padding(.bottom, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.ultraThinMaterial)
        .background(Color.black.opacity(0.7).ignoresSafeArea())
        .onAppear { pulse = true }
    }

    private func action(_ icon: String, _ label: String, _ color: Color, _ act: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Button(action: act) {
                Image(systemName: icon).font(.title).foregroundStyle(.white).frame(width: 72, height: 72).background(color, in: Circle())
            }
            Text(label).font(.footnote).foregroundStyle(muted)
        }
    }
}
