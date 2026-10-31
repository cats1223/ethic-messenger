import AVFoundation
import PhotosUI
import SwiftUI

private let styles: [(String?, String)] = [(nil, "Без градиента"), ("red", "Красно-оранжевый"), ("blue", "Сине-голубой"), ("purple", "Фиолетово-синий")]

func nameGradient(_ style: String?) -> LinearGradient? {
    let c: [Color]
    switch style {
    case "red": c = [Color(red: 1, green: 0.23, blue: 0.19), Color(red: 1, green: 0.58, blue: 0)]
    case "blue": c = [Color(red: 0.18, green: 0.42, blue: 1), Color(red: 0.22, green: 0.84, blue: 1)]
    case "purple": c = [Color(red: 0.66, green: 0.33, blue: 0.97), Color(red: 0.23, green: 0.51, blue: 0.96)]
    default: return nil
    }
    return LinearGradient(colors: c, startPoint: .leading, endPoint: .trailing)
}

func isVideoBanner(_ b: String?) -> Bool {
    guard let b else { return false }
    return b.hasPrefix("/uploads/") && (b.hasSuffix(".mp4") || b.hasSuffix(".webm"))
}

/// The Ethic+ pill next to a subscriber's name.
struct PlusBadge: View {
    var body: some View {
        HStack(spacing: 3) {
            Image("Logo").resizable().scaledToFit().frame(width: 13, height: 13)
            Text("Ethic+").font(.system(size: 10, weight: .bold)).foregroundStyle(LinearGradient(colors: [.white, Color(white: 0.72)], startPoint: .top, endPoint: .bottom))
        }
        .padding(.leading, 3).padding(.trailing, 7).padding(.vertical, 2)
        .background(.black, in: Capsule())
        .overlay(Capsule().stroke(.white.opacity(0.35), lineWidth: 1))
    }
}

/// A looping muted video, for video banners.
struct VideoBanner: UIViewRepresentable {
    let url: URL
    final class PlayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var looper: AVPlayerLooper?
    }
    func makeUIView(context: Context) -> PlayerView {
        let v = PlayerView()
        let player = AVQueuePlayer()
        player.isMuted = true
        v.looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(url: url))
        (v.layer as? AVPlayerLayer)?.player = player
        (v.layer as? AVPlayerLayer)?.videoGravity = .resizeAspectFill
        player.play()
        return v
    }
    func updateUIView(_ uiView: PlayerView, context: Context) {}
}

/// «Подписка»: subscribers pick their perks; the owner also grants the subscription here.
struct PlusView: View {
    @State private var store = Store.shared
    @State private var q = ""
    @State private var users: [AdminUser] = []
    @State private var video: PhotosPickerItem?
    @State private var busy = false

    private var owner: Bool { store.me?.role == "owner" }

    var body: some View {
        List {
            if let u = store.me {
                if u.plus == true {
                    Section("Цвет ника") {
                        NameLine(name: u.displayName, user: u, font: .title2.weight(.heavy))
                        ForEach(styles, id: \.1) { v, label in
                            Button { save(["nameStyle": v], "Цвет ника изменён") } label: {
                                HStack {
                                    if let g = nameGradient(v) { Text(label).bold().foregroundStyle(g) } else { Text(label).bold().foregroundStyle(.white) }
                                    Spacer()
                                    if u.nameStyle == v { Image(systemName: "checkmark").foregroundStyle(.white) }
                                }
                            }
                        }
                    }
                    Section("Видео-баннер") {
                        if isVideoBanner(u.banner), let url = API.abs(u.banner) {
                            VideoBanner(url: url).frame(height: 110).clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                        PhotosPicker(selection: $video, matching: .videos) { Text(busy ? "Загрузка…" : "Загрузить видео") }.disabled(busy)
                        if isVideoBanner(u.banner) { Button("Убрать видео", role: .destructive) { save(["banner": nil], "Видео убрано") } }
                        Text("MP4, до 25 МБ").font(.caption).foregroundStyle(muted)
                    }
                } else {
                    Text("Подписки пока нет. Выдайте её себе в списке ниже.").foregroundStyle(muted)
                }
            }
            if owner {
                Section("Выдача подписки") {
                    TextField("Поиск пользователя", text: $q).textInputAutocapitalization(.never).autocorrectionDisabled()
                    ForEach(users) { a in
                        HStack(spacing: 12) {
                            AvatarView(user: a.asUser, size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                NameLine(name: a.displayName, user: a.asUser)
                                Text("ID \(a.id) · @\(a.username)").font(.caption).foregroundStyle(muted)
                            }
                            Spacer()
                            Button(a.plus == true ? "Забрать" : "Выдать") { grant(a) }
                                .buttonStyle(.borderless).bold()
                                .foregroundStyle(a.plus == true ? muted : .white)
                        }
                    }
                }
            }
        }
        .navigationTitle("Ethic+")
        .task(id: q) { try? await Task.sleep(nanoseconds: 250_000_000); await load() }
        .onChange(of: video) { _, item in upload(item) }
    }

    private func load() async {
        guard owner else { return }
        let enc = q.trimmed.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        do {
            let list = try API.decode([AdminUser].self, try await API.call("/api/admin/users?q=\(enc)")["users"])
            users = list.filter { !$0.username.hasPrefix("deleted_") }
        } catch { store.show(error.localizedDescription) }
    }

    private func save(_ body: [String: Any?], _ ok: String) {
        Task { do { try await store.updateMe(body); store.show(ok) } catch { store.show(error.localizedDescription) } }
    }

    private func grant(_ a: AdminUser) {
        Task {
            do {
                let on = a.plus != true
                try await API.call("/api/admin/users/\(a.id)/plus", method: "POST", body: ["plus": on])
                store.show(on ? "Подписка выдана" : "Подписка забрана")
                if a.id == store.me?.id { store.me = try API.decode(User.self, try await API.call("/api/me")["user"]) }
                await load()
            } catch { store.show(error.localizedDescription) }
        }
    }

    private func upload(_ item: PhotosPickerItem?) {
        guard let item else { return }
        video = nil
        busy = true
        Task {
            defer { busy = false }
            guard let data = try? await item.loadTransferable(type: Data.self) else { store.show("Не удалось открыть видео"); return }
            if data.count > 25 * 1024 * 1024 { store.show("Видео больше 25 МБ"); return }
            let file = FileManager.default.temporaryDirectory.appendingPathComponent("banner-\(UUID().uuidString).mp4")
            do {
                try data.write(to: file)
                let up = try await API.upload(file: file, kind: "file", name: "banner.mp4")
                guard up.ext == "mp4" || up.ext == "webm" else { store.show("Нужен файл MP4"); return }
                try await store.updateMe(["banner": up.url])
                store.show("Видео-баннер установлен")
            } catch { store.show(error.localizedDescription) }
        }
    }
}
