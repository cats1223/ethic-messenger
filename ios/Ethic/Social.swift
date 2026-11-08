import SwiftUI
import AVFoundation
import AVKit

// MARK: models for the social features

struct ForwardRef: Codable, Hashable { var id: Int64 = 0; var name: String = "" }
struct Reaction: Codable, Hashable { var emoji: String; var users: [Int64] = [] }
struct Folder: Codable, Hashable, Identifiable { let id: Int64; var name: String; var chatIds: [Int64] = [] }
struct JoinInfo: Codable { let id: Int64; var type: String?; var title: String?; var members: Int?; var joined: Bool? }
struct Device: Codable, Identifiable { let id: String; var name: String?; var platform: String?; var app: String?; var createdAt: Double?; var lastSeen: Double?; var current: Bool? }
struct LinkPreview: Codable { let url: String; var site: String?; var title: String?; var description: String?; var image: String? }

/// The address people see and share: without a port. The app itself still talks to the server on 8443.
let publicURL = "https://ethicmessenger.duckdns.org:8443"
let reactionEmojis = ["👍", "❤️", "😂", "😮", "😢", "🔥", "👎", "🎉"]
let chatTimers: [(Int, String)] = [(0, "Выключены"), (3600, "Через 1 час"), (86400, "Через 1 день"), (604800, "Через 7 дней")]
let messageSoundKinds: [(String, String)] = [("drop", "Капля"), ("bubble", "Пузырь"), ("bell", "Колокольчик"), ("none", "Без звука")]

// MARK: links and @mentions in message text

/// «…/join/CODE» of our own server: joining a group, not a web page.
func joinCode(_ url: URL) -> String? {
    guard url.absoluteString.hasPrefix(API.base) || url.host?.contains("ethicmessenger") == true else { return nil }
    let parts = url.pathComponents
    guard parts.count == 3, parts[1] == "join" else { return nil }
    return parts[2]
}

func firstURL(_ text: String) -> URL? {
    let det = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
    return det?.firstMatch(in: text, range: NSRange(text.startIndex..., in: text))?.url
}

func linkified(_ text: String, link: Color) -> AttributedString {
    var s = AttributedString(text)
    let ns = text as NSString
    if let det = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) {
        for m in det.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            guard let url = m.url, let r = Range(m.range, in: text), let ar = Range(r, in: s) else { continue }
            s[ar].link = url
            s[ar].foregroundColor = link
            s[ar].underlineStyle = .single
        }
    }
    if let re = try? NSRegularExpression(pattern: "(^|[\\s(])(@[A-Za-z0-9_.]{2,32})") {
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            guard let r = Range(m.range(at: 2), in: text), let ar = Range(r, in: s), s[ar].link == nil else { continue }
            s[ar].foregroundColor = link
            s[ar].font = .body.weight(.semibold)
        }
    }
    return s
}

// MARK: reactions

struct ReactionsRow: View {
    let m: Message
    let dark: Bool
    @State private var store = Store.shared
    var body: some View {
        if let list = m.reactions, !list.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(list, id: \.emoji) { r in
                        let on = r.users.contains(store.me?.id ?? -1)
                        Button { store.react(m, r.emoji) } label: {
                            Text("\(r.emoji) \(r.users.count)").font(.footnote).foregroundStyle(dark && !on ? .black : .white)
                                .padding(.horizontal, 8).padding(.vertical, 2)
                                .background(on ? blue.opacity(0.45) : (dark ? Color.black.opacity(0.12) : Color.white.opacity(0.12)), in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.top, 2)
        }
    }
}

// MARK: forwarding and search

struct ForwardSheet: View {
    let m: Message
    @State private var store = Store.shared
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List(store.sortedChats.filter { $0.request == nil && (!$0.isChannel || $0.isAdmin(store.me?.id)) }) { c in
                Button { store.forward(m, to: c.id); store.show("Переслано"); dismiss() } label: {
                    HStack(spacing: 10) { ChatAvatar(chat: c, size: 36); Text(store.chatName(c)).foregroundStyle(.white) }
                }
            }
            .navigationTitle("Переслать").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } } }
        }
    }
}

struct SearchSheet: View {
    let chatId: Int64?
    @State private var store = Store.shared
    @State private var q = ""
    @State private var here = true
    @State private var found: [Message] = []
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if chatId != nil {
                    Picker("Где искать", selection: $here) { Text("В этом чате").tag(true); Text("Во всех чатах").tag(false) }.pickerStyle(.segmented)
                }
                ForEach(found) { m in
                    Button { dismiss(); Nav.shared.openChat(m.chatId) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(store.chats[m.chatId].map { store.chatName($0) } ?? "Чат") · \(store.userName(m.userId)) · \(timeOf(m.createdAt))").font(.caption).foregroundStyle(muted)
                            Text(m.text).foregroundStyle(.white).lineLimit(3)
                        }
                    }
                }
                if q.trimmed.count >= 2 && found.isEmpty { Text("Ничего не найдено").foregroundStyle(muted) }
            }
            .searchable(text: $q, placement: .navigationBarDrawer(displayMode: .always), prompt: "Что найти")
            .task(id: "\(q)|\(here)") {
                try? await Task.sleep(nanoseconds: 300_000_000)
                guard q.trimmed.count >= 2 else { found = []; return }
                found = (try? await store.search(q.trimmed, chatId: here ? chatId : nil)) ?? []
            }
            .navigationTitle("Поиск по сообщениям").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } } }
        }
    }
}

// MARK: group settings, timer, sounds

struct GroupSettingsSheet: View {
    let chatId: Int64
    @State private var store = Store.shared
    @State private var title = ""
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                if let c = store.chats[chatId] {
                    Section("Название") {
                        TextField("Название", text: $title)
                        Button("Сохранить название") { act { _ = try await store.patchChat(chatId, ["title": title.trimmed]); store.show("Сохранено") } }
                    }
                    Section("Ссылка-приглашение") {
                        if let code = c.inviteCode {
                            let link = "\(publicURL)/join/\(code)"
                            Text(link).font(.footnote.monospaced())
                            Button("Скопировать") { UIPasteboard.general.string = link; store.show("Скопировано") }
                            Button("Новая ссылка") { act { _ = try await store.invite(chatId) } }
                            Button("Выключить ссылку", role: .destructive) { act { _ = try await store.invite(chatId, off: true) } }
                        } else {
                            Text("По ссылке человек сам вступит, без вашего приглашения.").foregroundStyle(muted)
                            Button("Создать ссылку") { act { _ = try await store.invite(chatId) } }
                        }
                    }
                    Section("Исчезающие сообщения") {
                        ForEach(chatTimers, id: \.0) { s, label in
                            Button { act { _ = try await store.patchChat(chatId, ["disappear": s]) } } label: {
                                HStack { Text(label).foregroundStyle(.white); Spacer(); if (c.disappear ?? 0) == s { Image(systemName: "checkmark") } }
                            }
                        }
                    }
                    Section { Text("Администраторов назначают и исключают участников в списке участников: кнопка ⋯ у человека.").font(.footnote).foregroundStyle(muted) }
                }
            }
            .onAppear { title = store.chats[chatId]?.title ?? "" }
            .navigationTitle(store.chats[chatId]?.isChannel == true ? "Настройки канала" : "Настройки группы").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
        }
    }
    private func act(_ f: @escaping () async throws -> Void) { Task { do { try await f() } catch { store.show(error.localizedDescription) } } }
}

struct SoundsSheet: View {
    let chatId: Int64
    @State private var store = Store.shared
    @State private var msg: String?
    @State private var ring: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section("Новое сообщение") {
                    choice(nil, "Как обычно", $msg)
                    ForEach(messageSoundKinds, id: \.0) { k, label in choice(k, label, $msg) { Sounds.shared.received(k) } }
                }
                Section("Звонок") {
                    choice(nil, "Как обычно", $ring)
                    ForEach(Sounds.shared.ringtones) { r in choice(r.id, r.name, $ring) { Sounds.shared.ring(r.id, preview: true) } }
                }
            }
            .onAppear { msg = store.chatMsgSound(chatId); ring = store.chatRing(chatId) }
            .navigationTitle("Звуки чата").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Отмена") { Sounds.shared.stop(); dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Сохранить") { Sounds.shared.stop(); store.setChatSounds(chatId, msg: msg, ring: ring); store.show("Звуки сохранены"); dismiss() } }
            }
        }
    }
    private func choice(_ v: String?, _ label: String, _ sel: Binding<String?>, play: @escaping () -> Void = {}) -> some View {
        Button { sel.wrappedValue = v; play() } label: { HStack { Text(label).foregroundStyle(.white); Spacer(); if sel.wrappedValue == v { Image(systemName: "checkmark") } } }
    }
}

// MARK: links from outside or from a chat: joining a group, signing in a computer

struct LinkDialogs: ViewModifier {
    @State private var store = Store.shared
    @State private var join: (String, JoinInfo)?
    func body(content: Content) -> some View {
        content
            .onOpenURL { store.pendingLink = $0.absoluteString }
            .onChange(of: store.pendingLink) { _, link in Task { await resolve(link) } }
            .alert("Войти на компьютере?", isPresented: Binding(get: { qrToken != nil }, set: { if !$0 { store.pendingLink = nil } })) {
                Button("Войти") {
                    let t = qrToken
                    store.pendingLink = nil
                    if let t { Task { do { let d = try await store.approveQr(t); store.show("Вход выполнен: \(d ?? "компьютер")") } catch { store.show(error.localizedDescription) } } }
                }
                Button("Отмена", role: .cancel) { store.pendingLink = nil }
            } message: { Text("Компьютер, на экране которого вы отсканировали код, войдёт в ваш аккаунт. Подтверждайте, только если это ваш компьютер.") }
            .alert("Вступить в «\(join?.1.title ?? "")»?", isPresented: Binding(get: { join != nil }, set: { if !$0 { join = nil } })) {
                Button("Вступить") {
                    if let code = join?.0 { Task { do { let c = try await store.join(code); Nav.shared.openChat(c.id) } catch { store.show(error.localizedDescription) } } }
                    join = nil
                }
                Button("Отмена", role: .cancel) { join = nil }
            } message: { Text("\(join?.1.type == "channel" ? "Канал" : "Группа") · \(join?.1.members ?? 0) участн.") }
    }
    private var qrToken: String? {
        guard let l = store.pendingLink, l.hasPrefix("ethic://qr/"), store.loggedIn else { return nil }
        return String(l.dropFirst("ethic://qr/".count))
    }
    private func resolve(_ link: String?) async {
        guard let link, store.loggedIn, let url = URL(string: link), let code = joinCode(url) else { return }
        store.pendingLink = nil
        do {
            let info = try await store.joinInfo(code)
            if info.joined == true { Nav.shared.openChat(info.id) } else { join = (code, info) }
        } catch { store.show(error.localizedDescription) }
    }
}

// MARK: folders

struct FolderTabs: View {
    @Binding var selected: String
    @State private var store = Store.shared
    @State private var edit = false
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach([("all", "Все"), ("direct", "Личные"), ("groups", "Группы")] + store.folders.map { ("\($0.id)", $0.name) }, id: \.0) { id, name in
                    Button(name) { selected = id; UserDefaults.standard.set(id, forKey: "folder") }
                        .font(.footnote.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 6)
                        .background(selected == id ? Color.white : surface2, in: Capsule()).foregroundStyle(selected == id ? .black : muted)
                }
                Button { edit = true } label: { Image(systemName: "ellipsis") }
                    .padding(.horizontal, 12).padding(.vertical, 6).background(surface2, in: Capsule()).foregroundStyle(muted)
            }
        }
        .sheet(isPresented: $edit) { FoldersSheet() }
    }
}

func chatInFolder(_ c: Chat, _ folder: String, _ folders: [Folder]) -> Bool {
    switch folder {
    case "all": true
    case "direct": c.type == "direct"
    case "groups": c.isGroup
    default: folders.first { "\($0.id)" == folder }?.chatIds.contains(c.id) ?? true
    }
}

struct FoldersSheet: View {
    @State private var store = Store.shared
    @State private var editing: Folder?
    @State private var creating = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if store.folders.isEmpty { Text("Своих папок пока нет. «Личные» и «Группы» есть всегда.").foregroundStyle(muted) }
                ForEach(store.folders) { f in
                    Button { editing = f } label: { HStack { Text(f.name).foregroundStyle(.white); Spacer(); Text("\(f.chatIds.count)").foregroundStyle(muted) } }
                        .swipeActions { Button("Удалить", role: .destructive) { Task { try? await store.deleteFolder(f.id) } } }
                }
                Button("Новая папка") { creating = true }
            }
            .navigationTitle("Папки").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
            .sheet(item: $editing) { FolderEditSheet(folder: $0) }
            .sheet(isPresented: $creating) { FolderEditSheet(folder: nil) }
        }
    }
}

struct FolderEditSheet: View {
    let folder: Folder?
    @State private var store = Store.shared
    @State private var name = ""
    @State private var picked: Set<Int64> = []
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                Section { TextField("Название, например «Работа»", text: $name) }
                Section("Чаты в папке") {
                    ForEach(store.sortedChats) { c in
                        Button { if picked.contains(c.id) { picked.remove(c.id) } else { picked.insert(c.id) } } label: {
                            HStack { Text(store.chatName(c)).foregroundStyle(.white); Spacer(); if picked.contains(c.id) { Image(systemName: "checkmark") } }
                        }
                    }
                }
            }
            .onAppear { name = folder?.name ?? ""; picked = Set(folder?.chatIds ?? []) }
            .navigationTitle(folder == nil ? "Новая папка" : "Изменить папку").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Сохранить") { Task { do { try await store.saveFolder(folder?.id, name: name.trimmed, chatIds: Array(picked)); dismiss() } catch { store.show(error.localizedDescription) } } }
                        .disabled(name.trimmed.isEmpty)
                }
            }
        }
    }
}

// MARK: link previews

@MainActor private var previewCache: [String: LinkPreview?] = [:]

struct LinkPreviewCard: View {
    let text: String
    let dark: Bool
    @State private var p: LinkPreview?
    @Environment(\.openURL) private var openURL
    var body: some View {
        Group {
            if let p, let url = URL(string: p.url) {
                Button { openURL(url) } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        if let img = p.image, let u = URL(string: img) {
                            AsyncImage(url: u) { $0.resizable().scaledToFill() } placeholder: { Color.clear }
                                .frame(maxWidth: 260, maxHeight: 130).clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                        Text(p.site ?? "").font(.caption).foregroundStyle(blue)
                        Text(p.title ?? "").font(.subheadline.bold()).foregroundStyle(dark ? .black : .white).lineLimit(2)
                        if let d = p.description { Text(d).font(.caption).foregroundStyle(dark ? Color(white: 0.35) : muted).lineLimit(3) }
                    }
                    .frame(maxWidth: 260, alignment: .leading).padding(8)
                    .background((dark ? Color.black : Color.white).opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
            }
        }
        .task(id: text) {
            guard let url = firstURL(text), joinCode(url) == nil else { return }
            let key = url.absoluteString
            if let cached = previewCache[key] { p = cached; return }
            let got = try? await Store.shared.preview(key)
            previewCache[key] = got
            p = got
        }
    }
}

// MARK: round video messages

struct CircleVideo: View {
    let url: URL
    @State private var player: AVPlayer?
    var body: some View {
        ZStack {
            Color(white: 0.07)
            if let player { VideoLayer(player: player) } else { Image(systemName: "play.fill").font(.largeTitle).foregroundStyle(.white) }
        }
        .frame(width: 220, height: 220).clipShape(Circle())
        .onTapGesture {
            if let p = player { if p.timeControlStatus == .playing { p.pause() } else { p.play() } }
            else {
                let p = AVPlayer(url: url)
                NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: p.currentItem, queue: .main) { _ in p.seek(to: .zero); p.play() }
                player = p
                p.play()
            }
        }
        .onDisappear { player?.pause() }
    }
}

private struct VideoLayer: UIViewRepresentable {
    let player: AVPlayer
    func makeUIView(context: Context) -> PlayerView { let v = PlayerView(); v.playerLayer.player = player; v.playerLayer.videoGravity = .resizeAspectFill; return v }
    func updateUIView(_ v: PlayerView, context: Context) { v.playerLayer.player = player }
    final class PlayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}

/// Records a round video with the front camera, up to a minute.
struct CircleRecorder: UIViewControllerRepresentable {
    let onDone: (URL?) -> Void
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let p = UIImagePickerController()
        p.sourceType = .camera
        p.mediaTypes = ["public.movie"]
        p.cameraDevice = .front
        p.videoMaximumDuration = 60
        p.videoQuality = .typeMedium
        p.delegate = context.coordinator
        return p
    }
    func updateUIViewController(_ c: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coord { Coord(onDone) }
    final class Coord: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onDone: (URL?) -> Void
        init(_ f: @escaping (URL?) -> Void) { onDone = f }
        func imagePickerController(_ p: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            p.dismiss(animated: true); onDone(info[.mediaURL] as? URL)
        }
        func imagePickerControllerDidCancel(_ p: UIImagePickerController) { p.dismiss(animated: true); onDone(nil) }
    }
}

// MARK: privacy and the block list

struct PrivacyView: View {
    @State private var store = Store.shared
    @State private var blocked: [User] = []
    var body: some View {
        List {
            Section("Кто видит, что я в сети и когда был(а)") {
                ForEach([("all", "Все"), ("contacts", "Только те, с кем я переписываюсь"), ("nobody", "Никто")], id: \.0) { v, label in
                    Button { Task { do { try await store.updateMe(["lastSeenPrivacy": v]) } catch { store.show(error.localizedDescription) } } } label: {
                        HStack { Text(label).foregroundStyle(.white); Spacer(); if (store.me?.lastSeenPrivacy ?? "all") == v { Image(systemName: "checkmark") } }
                    }
                }
            }
            Section {
                if blocked.isEmpty { Text("Никого").foregroundStyle(muted) }
                ForEach(blocked) { u in
                    HStack {
                        UserRow(user: u)
                        Spacer()
                        Button("Разблокировать") { Task { try? await store.setBlocked(u.id, false); blocked.removeAll { $0.id == u.id } } }.font(.footnote)
                    }
                }
            } header: { Text("Чёрный список") } footer: { Text("Эти люди не могут писать вам и звонить. Добавить человека — в его профиле, кнопка ⋯.") }
        }
        .task { blocked = (try? await store.blocks()) ?? [] }
        .navigationTitle("Конфиденциальность").navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: devices and the QR scanner

struct DevicesView: View {
    @State private var store = Store.shared
    @State private var list: [Device] = []
    @State private var scanning = false
    @State private var endAll = false
    var body: some View {
        List {
            Section {
                Button { scanning = true } label: { Label("Привязать устройство", systemImage: "qrcode.viewfinder").bold() }
            } footer: { Text("На компьютере откройте сайт или приложение Ethic и нажмите «Войти по QR-коду».") }
            Section("Где выполнен вход") {
                ForEach(list) { d in
                    HStack(spacing: 12) {
                        Text((d.name ?? "").contains("Android") || (d.name ?? "").contains("iPhone") ? "📱" : "💻").font(.title2)
                        VStack(alignment: .leading, spacing: 2) {
                            Text((d.name ?? "Устройство") + (d.app == "ethic-id" ? " · Ethic ID" : d.app == "clicker" ? " · Ethic Clicker" : ""))
                            Text(d.current == true ? "это устройство" : d.lastSeen.map { "активно \(timeOf($0))" } ?? "вход \(timeOf(d.createdAt ?? 0))")
                                .font(.caption).foregroundStyle(d.current == true ? .green : muted)
                        }
                        Spacer()
                        if d.current != true { Button("Завершить", role: .destructive) { Task { try? await store.endDevice(d.id); await reload() } }.font(.footnote) }
                    }
                }
            }
            Section { Button("Выйти на всех других устройствах", role: .destructive) { endAll = true } }
        }
        .task { await reload() }
        .navigationTitle("Устройства").navigationBarTitleDisplayMode(.inline)
        .fullScreenCover(isPresented: $scanning) {
            QRScanner { code in
                scanning = false
                guard let code else { return }
                if code.hasPrefix("ethic://qr/") { store.pendingLink = code } else { store.show("Это не код входа Ethic") }
            }
        }
        .confirmationDialog("Выйти на всех других?", isPresented: $endAll, titleVisibility: .visible) {
            Button("Выйти", role: .destructive) { Task { try? await store.endOtherDevices(); await reload() } }
        }
    }
    private func reload() async { list = (try? await store.devices()) ?? [] }
}

struct QRScanner: UIViewControllerRepresentable {
    let onDone: (String?) -> Void
    func makeUIViewController(context: Context) -> ScannerVC { let v = ScannerVC(); v.onDone = onDone; return v }
    func updateUIViewController(_ v: ScannerVC, context: Context) {}

    final class ScannerVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onDone: ((String?) -> Void)?
        private let session = AVCaptureSession()
        private var finished = false
        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            if let dev = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: dev), session.canAddInput(input) {
                session.addInput(input)
                let out = AVCaptureMetadataOutput()
                if session.canAddOutput(out) { session.addOutput(out); out.setMetadataObjectsDelegate(self, queue: .main); out.metadataObjectTypes = [.qr] }
                let preview = AVCaptureVideoPreviewLayer(session: session)
                preview.frame = view.bounds
                preview.videoGravity = .resizeAspectFill
                view.layer.addSublayer(preview)
                DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
            }
            let close = UIButton(type: .system)
            close.setTitle("Закрыть", for: .normal)
            close.setTitleColor(.white, for: .normal)
            close.titleLabel?.font = .boldSystemFont(ofSize: 18)
            close.frame = CGRect(x: 20, y: 60, width: 120, height: 44)
            close.addTarget(self, action: #selector(cancel), for: .touchUpInside)
            view.addSubview(close)
            let hint = UILabel(frame: CGRect(x: 20, y: view.bounds.height - 140, width: view.bounds.width - 40, height: 60))
            hint.text = "Наведите камеру на QR-код на экране компьютера"
            hint.textColor = .white
            hint.numberOfLines = 2
            hint.textAlignment = .center
            view.addSubview(hint)
        }
        @objc private func cancel() { finish(nil) }
        func metadataOutput(_ o: AVCaptureMetadataOutput, didOutput objs: [AVMetadataObject], from c: AVCaptureConnection) {
            if let s = (objs.first as? AVMetadataMachineReadableCodeObject)?.stringValue { finish(s) }
        }
        private func finish(_ s: String?) {
            guard !finished else { return }
            finished = true
            session.stopRunning()
            onDone?(s)
        }
    }
}
