import AVFoundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Shared player for voice messages.
@MainActor
@Observable
final class VoicePlayer {
    static let shared = VoicePlayer()
    var playing: String?
    var paused = false
    var progress: Double = 0
    private var player: AVPlayer?
    private var observer: Any?

    func toggle(_ url: String) {
        if playing == url, let p = player {
            if paused { p.play() } else { p.pause() }
            paused.toggle(); return
        }
        stop()
        guard let u = url.hasPrefix("file:") ? URL(string: url) : API.playable(url) else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
        let p = AVPlayer(url: u)
        player = p; playing = url; paused = false; progress = 0
        observer = p.addPeriodicTimeObserver(forInterval: CMTime(seconds: 1.0 / 60, preferredTimescale: 600), queue: .main) { [weak self] t in
            MainActor.assumeIsolated {
                guard let self, let d = p.currentItem?.duration.seconds, d.isFinite, d > 0 else { return }
                self.progress = t.seconds / d
                if t.seconds >= d - 0.05 { self.stop() }
            }
        }
        p.play()
    }

    func stop() {
        if let o = observer { player?.removeTimeObserver(o) }
        observer = nil; player?.pause(); player = nil; playing = nil; progress = 0
    }
}

/// Voice messages: AAC in M4A (the server accepts mp4 voice; Android and the website play it).
final class Recorder {
    private var rec: AVAudioRecorder?
    private var started = Date()
    var elapsed: TimeInterval { rec == nil ? 0 : Date().timeIntervalSince(started) }

    func start() -> Bool {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try? s.setActive(true)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 48000, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 128000]
        guard let r = try? AVAudioRecorder(url: url, settings: settings), r.record() else { return false }
        rec = r; started = Date()
        return true
    }

    func stop(keep: Bool) -> (URL, Double)? {
        guard let r = rec else { return nil }
        let dur = Date().timeIntervalSince(started)
        r.stop(); rec = nil
        if !keep || dur < 0.5 { try? FileManager.default.removeItem(at: r.url); return nil }
        return (r.url, dur)
    }
}

struct ChatView: View {
    let chatId: Int64
    @State private var store = Store.shared
    @State private var calls = Calls.shared
    @State private var text = ""
    @State private var reply: Message?
    @State private var editing: Message?
    @State private var photo: PhotosPickerItem?
    @State private var fileImporter = false
    @State private var recorder = Recorder()
    @State private var recording = false
    @State private var elapsed: TimeInterval = 0
    @FocusState private var focused: Bool

    var body: some View {
        let chat = store.chats[chatId]
        let list = store.messages[chatId] ?? []
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 2) {
                    if store.hasMore[chatId] == true {
                        Button("Показать раньше") { Task { try? await store.loadMessages(chatId, older: true) } }
                            .font(.footnote).foregroundStyle(muted).padding(8)
                    }
                    ForEach(Array(list.enumerated()), id: \.element.key) { i, m in
                        let prev = i > 0 ? list[i - 1] : nil
                        let next = i + 1 < list.count ? list[i + 1] : nil
                        let first = prev == nil || prev!.userId != m.userId || m.createdAt - prev!.createdAt > 300_000 || !sameDay(prev!.createdAt, m.createdAt)
                        let last = next == nil || next!.userId != m.userId || next!.createdAt - m.createdAt > 300_000 || !sameDay(next!.createdAt, m.createdAt)
                        if prev == nil || !sameDay(prev!.createdAt, m.createdAt) {
                            Text(dayLabel(m.createdAt)).font(.caption).foregroundStyle(muted)
                                .padding(.horizontal, 12).padding(.vertical, 4).glass().padding(.vertical, 8)
                        }
                        if let chat {
                            Bubble(chat: chat, m: m, mine: m.userId == store.me?.id, first: first, last: last)
                                .id(m.key)
                                .contextMenu { menu(m) }
                                .transition(.asymmetric(insertion: .scale(scale: 0.8, anchor: m.userId == store.me?.id ? .bottomTrailing : .bottomLeading).combined(with: .opacity), removal: .opacity))
                        }
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(.horizontal, 10)
                .animation(.spring(response: 0.35, dampingFraction: 0.7), value: list.count)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: list.last?.key) { _, _ in withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
        }
        .safeAreaInset(edge: .top) { callStrip(chat) }
        .safeAreaInset(edge: .bottom) {
            if let chat, let req = chat.request { requestBar(chat, mine: req.from == store.me?.id) } else { composer }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { if let chat { header(chat) } }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { calls.start(chatId, video: false) } label: { Image(systemName: "phone") }
                Button { calls.start(chatId, video: true) } label: { Image(systemName: "video") }
            }
        }
        .toolbar(.hidden, for: .tabBar)
        .task {
            store.activeChat = chatId
            if store.messages[chatId] == nil { do { try await store.loadMessages(chatId) } catch { store.show(error.localizedDescription) } }
            store.markRead(chatId)
        }
        .onChange(of: list.last?.id) { _, _ in store.markRead(chatId) }
        .onDisappear { if store.activeChat == chatId { store.activeChat = nil }; VoicePlayer.shared.stop() }
        .onChange(of: photo) { _, item in sendPhoto(item) }
        .fileImporter(isPresented: $fileImporter, allowedContentTypes: [.item]) { res in if case .success(let url) = res { sendFile(url) } }
    }

    private func header(_ chat: Chat) -> some View {
        let other = chat.isGroup ? nil : store.other(chat)
        let typers = store.typingNames(chatId)
        return NavigationLink(value: chat.isGroup ? Route.group(chatId) : Route.user(other?.id ?? 0)) {
            HStack(spacing: 8) {
                ChatAvatar(chat: chat, size: 34)
                VStack(alignment: .leading, spacing: 0) {
                    NameLine(name: store.chatName(chat), user: other, font: .subheadline.weight(.semibold))
                    Text(!typers.isEmpty ? "печатает…" : chat.isGroup ? "\(chat.members.count) участн." : lastSeenText(other))
                        .font(.caption).foregroundStyle(!typers.isEmpty || other?.isOnline == true ? blue : muted)
                }
            }
        }
        .foregroundStyle(.white)
    }

    @ViewBuilder
    private func callStrip(_ chat: Chat?) -> some View {
        if let n = chat?.call?.participants.count, n > 0, calls.ui?.chatId != chatId {
            Button { calls.start(chatId, video: false) } label: {
                HStack {
                    Image(systemName: "phone.fill").foregroundStyle(green)
                    Text("Идёт звонок · \(n) участн.")
                    Spacer()
                    Text("Присоединиться").bold().foregroundStyle(green)
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
            }
            .foregroundStyle(.white).glass(RoundedRectangle(cornerRadius: 18), tint: green.opacity(0.2)).padding(.horizontal, 10)
        }
    }

    @ViewBuilder
    private func menu(_ m: Message) -> some View {
        if !m.isPending { Button { reply = m; editing = nil; focused = true } label: { Label("Ответить", systemImage: "arrowshape.turn.up.left") } }
        if !m.text.isEmpty { Button { UIPasteboard.general.string = m.text; store.show("Скопировано") } label: { Label("Копировать", systemImage: "doc.on.doc") } }
        if m.userId == store.me?.id, !m.isPending, m.file?.kind != "voice" {
            Button { editing = m; reply = nil; text = m.text; focused = true } label: { Label("Изменить", systemImage: "pencil") }
        }
        if (m.userId == store.me?.id || store.me?.isStaff == true), !m.isPending {
            Button(role: .destructive) { store.delete(m) } label: { Label("Удалить", systemImage: "trash") }
        }
    }

    private func requestBar(_ chat: Chat, mine: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(mine ? "Заявка отправлена" : "\(store.chatName(chat)) хочет написать вам").font(.headline)
            Text(mine ? "Писать и звонить можно, когда собеседник её примет." : "Примите заявку, чтобы переписываться и созваниваться.")
                .font(.footnote).foregroundStyle(muted)
            if !mine {
                HStack {
                    Button("Отклонить", role: .destructive) { Task { do { try await store.declineRequest(chatId) } catch { store.show(error.localizedDescription) } } }
                    Spacer()
                    Button { Task { do { try await store.acceptRequest(chatId) } catch { store.show(error.localizedDescription) } } } label: {
                        Text("Принять").bold().padding(.horizontal, 22).padding(.vertical, 10).background(.white, in: Capsule()).foregroundStyle(.black)
                    }
                }
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16).glass(RoundedRectangle(cornerRadius: 22)).padding(.horizontal, 10).padding(.bottom, 6)
    }

    private var composer: some View {
        VStack(spacing: 6) {
            if let bar = editing ?? reply {
                HStack {
                    Image(systemName: editing != nil ? "pencil" : "arrowshape.turn.up.left").foregroundStyle(blue)
                    VStack(alignment: .leading) {
                        Text(editing != nil ? "Редактирование" : "Ответ: \(store.userName(bar.userId))").font(.caption.bold()).foregroundStyle(blue)
                        Text(bar.text.isEmpty ? kindText(bar.kind, bar.file?.name) : bar.text).font(.caption).foregroundStyle(muted).lineLimit(1)
                    }
                    Spacer()
                    Button { if editing != nil { text = "" }; editing = nil; reply = nil } label: { Image(systemName: "xmark").foregroundStyle(muted) }
                }
                .padding(.horizontal, 14).padding(.vertical, 8).glass(RoundedRectangle(cornerRadius: 16))
            }
            HStack(alignment: .bottom, spacing: 8) {
                if recording {
                    HStack {
                        Circle().fill(red).frame(width: 10, height: 10)
                        Text(durText(elapsed)).monospacedDigit()
                        Spacer()
                        Button("Отмена") { finishVoice(send: false) }.foregroundStyle(red)
                    }
                    .padding(.horizontal, 16).frame(height: 48).glass()
                    round("arrow.up", bg: blue) { finishVoice(send: true) }
                } else {
                    if editing == nil {
                        Menu {
                            PhotosPicker(selection: $photo, matching: .images) { Label("Фото", systemImage: "photo") }
                            Button { fileImporter = true } label: { Label("Файл", systemImage: "doc") }
                        } label: { Image(systemName: "paperclip").font(.title3).frame(width: 48, height: 48) }
                        .glass(Circle())
                    }
                    TextField("Сообщение", text: $text, axis: .vertical)
                        .lineLimit(1...6)
                        .focused($focused)
                        .padding(.horizontal, 16).padding(.vertical, 13)
                        .glass(RoundedRectangle(cornerRadius: 24))
                        .onChange(of: text) { _, t in if !t.isEmpty { store.sendTyping(chatId) } }
                    if !text.trimmed.isEmpty || editing != nil {
                        round(editing != nil ? "checkmark" : "arrow.up", bg: .white, fg: .black) { send() }
                    } else {
                        round("mic.fill", bg: surface3) { startVoice() }
                    }
                }
            }
        }
        .padding(.horizontal, 10).padding(.bottom, 6)
    }

    private func round(_ icon: String, bg: Color, fg: Color = .white, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.title3.weight(.semibold)).foregroundStyle(fg).frame(width: 48, height: 48).background(bg, in: Circle())
        }
    }

    private func send() {
        let t = text.trimmed
        if let e = editing { if t != e.text { store.edit(e, t) } }
        else if !t.isEmpty { store.sendText(chatId, t, reply: reply) }
        text = ""; reply = nil; editing = nil
    }

    private func startVoice() {
        AVAudioApplication.requestRecordPermission { ok in
            Task { @MainActor in
                guard ok else { store.show("Нет доступа к микрофону"); return }
                guard recorder.start() else { store.show("Не удалось начать запись"); return }
                recording = true
                while recording { elapsed = recorder.elapsed; try? await Task.sleep(nanoseconds: 200_000_000) }
            }
        }
    }

    private func finishVoice(send: Bool) {
        recording = false
        guard let (url, dur) = recorder.stop(keep: send) else { return }
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        let preview = Message(id: 0, chatId: chatId, userId: 0, file: FileRef(url: url.absoluteString, name: "Голосовое сообщение", size: size, kind: "voice", duration: dur))
        store.sendUpload(chatId, file: url, kind: "voice", name: "voice.m4a", reply: reply, preview: preview) { up in
            ["file": ["url": up.url, "name": "Голосовое сообщение", "size": up.size, "kind": "voice", "duration": dur]]
        }
        reply = nil
    }

    private func sendPhoto(_ item: PhotosPickerItem?) {
        guard let item else { return }
        photo = nil
        Task {
            guard let data = try? await item.loadTransferable(type: Data.self), let (file, w, h) = prepareImage(data) else {
                store.show("Не удалось открыть изображение"); return
            }
            var preview = Message(id: 0, chatId: chatId, userId: 0, image: ImageRef(url: file.absoluteString, w: w, h: h))
            preview.localImage = try? Data(contentsOf: file)
            store.sendUpload(chatId, file: file, kind: "image", name: "photo.jpg", reply: reply, preview: preview) { up in
                ["image": ["url": up.url, "w": w, "h": h]]
            }
            reply = nil
        }
    }

    private func sendFile(_ picked: URL) {
        let ok = picked.startAccessingSecurityScopedResource()
        defer { if ok { picked.stopAccessingSecurityScopedResource() } }
        let name = picked.lastPathComponent
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + name)
        guard (try? FileManager.default.copyItem(at: picked, to: tmp)) != nil else { store.show("Не удалось открыть файл"); return }
        let size = (try? FileManager.default.attributesOfItem(atPath: tmp.path)[.size] as? NSNumber)?.int64Value ?? 0
        if size > 50 * 1024 * 1024 { store.show("Файл больше 50 МБ"); return }
        let type = UTType(filenameExtension: picked.pathExtension)
        let preview = Message(id: 0, chatId: chatId, userId: 0, file: FileRef(url: tmp.absoluteString, name: name, size: size, kind: "file"))
        store.sendUpload(chatId, file: tmp, kind: "file", name: name, reply: reply, preview: preview) { up in
            let media = ["mp4", "webm", "ogg", "mp3", "wav"].contains(up.ext ?? "")
            let kind = media && type?.conforms(to: .movie) == true ? "video" : media && type?.conforms(to: .audio) == true ? "audio" : "file"
            return ["file": ["url": up.url, "name": name, "size": up.size, "kind": kind]]
        }
        reply = nil
    }
}

struct Bubble: View {
    let chat: Chat
    let m: Message
    let mine: Bool
    let first: Bool
    let last: Bool
    @State private var store = Store.shared
    @Environment(\.openURL) private var openURL

    private var shape: UnevenRoundedRectangle {
        let big: CGFloat = 18, small: CGFloat = 6
        let tl: CGFloat = (mine || first) ? big : small
        let bl: CGFloat = (mine || last) ? big : small
        let br: CGFloat = (!mine || last) ? big : small
        let tr: CGFloat = (!mine || first) ? big : small
        return UnevenRoundedRectangle(topLeadingRadius: tl, bottomLeadingRadius: bl, bottomTrailingRadius: br, topTrailingRadius: tr)
    }

    var body: some View {
        let fg: Color = mine ? .black : .white
        let sub: Color = mine ? Color(white: 0.35) : muted
        let photoOnly = m.image != nil && m.text.isEmpty
        HStack(alignment: .bottom, spacing: 6) {
            if mine { Spacer(minLength: 50) }
            if chat.isGroup && !mine {
                if last { AvatarView(user: store.user(m.userId), size: 30) } else { Color.clear.frame(width: 30, height: 1) }
            }
            VStack(alignment: .leading, spacing: 4) {
                if chat.isGroup && !mine && first {
                    Text(store.userName(m.userId)).font(.caption.bold()).foregroundStyle(colorOf(m.userId))
                }
                if let r = m.replyTo {
                    HStack(spacing: 6) {
                        RoundedRectangle(cornerRadius: 2).fill(blue).frame(width: 3)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(r.deleted == true ? "Сообщение удалено" : store.userName(r.userId ?? 0)).font(.caption.bold()).foregroundStyle(blue)
                            if r.deleted != true {
                                Text((r.text ?? "").isEmpty ? kindText(r.kind, r.fileName) : r.text!).font(.caption).foregroundStyle(sub).lineLimit(1)
                            }
                        }
                    }
                    .padding(6).background(fg.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                }
                if let img = m.image { photo(img) }
                if let f = m.file {
                    if f.kind == "voice" { VoiceRow(m: m, f: f, mine: mine) } else { fileRow(f, fg: fg) }
                }
                if !m.text.isEmpty { Text(m.text).foregroundStyle(fg).padding(.horizontal, photoOnly ? 0 : 0) }
                HStack(spacing: 3) {
                    Spacer(minLength: 0)
                    if m.editedAt != nil { Text("изм.").font(.caption2).foregroundStyle(sub) }
                    Text(timeOf(m.createdAt)).font(.caption2).foregroundStyle(sub)
                    if mine {
                        Image(systemName: m.isPending ? "clock" : m.id <= chat.othersRead ? "checkmark.circle.fill" : "checkmark")
                            .font(.caption2).foregroundStyle(m.id <= chat.othersRead && !m.isPending ? blue : sub)
                    }
                }
                .padding(.trailing, photoOnly ? 6 : 0).padding(.bottom, photoOnly ? 4 : 0)
            }
            .padding(.horizontal, photoOnly ? 3 : 12).padding(.vertical, photoOnly ? 3 : 8)
            .background(mine ? Color.white : Color(white: 0.11), in: shape)
            if !mine { Spacer(minLength: 50) }
        }
        .padding(.top, first ? 6 : 0)
    }

    @ViewBuilder
    private func photo(_ img: ImageRef) -> some View {
        let ratio = (img.w ?? 0) > 0 && (img.h ?? 0) > 0 ? min(2, max(0.5, CGFloat(img.w!) / CGFloat(img.h!))) : 1
        Group {
            if let data = m.localImage, let ui = UIImage(data: data) {
                Image(uiImage: ui).resizable().scaledToFill()
            } else {
                AsyncImage(url: API.abs(img.url)) { $0.resizable().scaledToFill() } placeholder: { surface3 }
            }
        }
        .frame(width: 250, height: 250 / ratio)
        .clipShape(RoundedRectangle(cornerRadius: 15))
        .onTapGesture { if !m.isPending { Nav.shared.image = API.abs(img.url) } }
    }

    private func fileRow(_ f: FileRef, fg: Color) -> some View {
        Button { if !m.isPending, let u = API.abs(f.url) { openURL(u) } } label: {
            HStack(spacing: 10) {
                Image(systemName: f.kind == "video" ? "video.fill" : "doc.fill").foregroundStyle(mine ? .white : .black)
                    .frame(width: 42, height: 42).background(mine ? Color.black : Color.white, in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading) {
                    Text(f.name ?? "Файл").font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(sizeText(f.size ?? 0)).font(.caption).opacity(0.6)
                }
                .foregroundStyle(fg)
                Spacer(minLength: 0)
            }
            .frame(width: 230)
        }
    }
}

struct VoiceRow: View {
    let m: Message
    let f: FileRef
    let mine: Bool
    @State private var player = VoicePlayer.shared

    /// Waveform-like bar heights derived from the message id.
    private func bar(_ i: Int) -> CGFloat {
        let v: Int64 = (m.id &* 31 &+ Int64(i) &* 17) % 18
        return CGFloat(6 + Int(abs(v)))
    }
    var body: some View {
        let url = f.url
        let active = player.playing == url
        let fg: Color = mine ? .black : .white
        HStack(spacing: 10) {
            Button { player.toggle(url) } label: {
                Image(systemName: active && !player.paused ? "pause.fill" : "play.fill")
                    .foregroundStyle(mine ? .white : .black).frame(width: 40, height: 40).background(fg, in: Circle())
            }
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 2) {
                    ForEach(0..<26, id: \.self) { i in
                        let h = bar(i)
                        // Each bar fills partially, so progress moves smoothly instead of bar by bar.
                        let f = active ? min(1, max(0, player.progress * 26 - Double(i))) : 0
                        ZStack(alignment: .leading) {
                            Rectangle().fill(fg.opacity(0.35))
                            Rectangle().fill(blue).frame(width: 3 * f)
                        }
                        .frame(width: 3, height: h)
                        .clipShape(RoundedRectangle(cornerRadius: 1.5))
                    }
                }
                .frame(height: 26)
                Text(durText(f.duration)).font(.caption).foregroundStyle(fg.opacity(0.6))
            }
        }
    }
}
