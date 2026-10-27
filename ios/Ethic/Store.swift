import Foundation
import Observation

/// App state: session, chats, messages, realtime socket.
@MainActor
@Observable
final class Store {
    static let shared = Store()

    var me: User?
    var chats: [Int64: Chat] = [:]
    var users: [Int64: User] = [:]
    var messages: [Int64: [Message]] = [:]
    var hasMore: [Int64: Bool] = [:]
    var typing: [Int64: [Int64: Date]] = [:]
    var connected = false
    var loggedIn = false
    var booting = true
    var toast: String?
    var emailEnabled = false
    var inviteRequired = false

    var activeChat: Int64?
    var foreground = true

    // MARK: names
    func user(_ id: Int64) -> User? { id == me?.id ? me : users[id] }
    func userName(_ id: Int64) -> String { user(id)?.displayName ?? "Пользователь" }
    func other(_ chat: Chat) -> User? {
        let m = chat.members.first { $0.id != me?.id } ?? chat.members.first
        return m.map { users[$0.id] ?? $0 }
    }
    func chatName(_ chat: Chat) -> String { chat.isGroup ? (chat.title ?? "Группа") : (other(chat)?.displayName ?? "Чат") }
    var sortedChats: [Chat] { chats.values.sorted { $0.sortKey > $1.sortKey } }
    var totalUnread: Int { chats.values.reduce(0) { $0 + ($1.id == activeChat ? 0 : $1.unread) } }

    func remember(_ list: [User]) {
        for u in list {
            var n = u
            if n.email == nil { n.email = users[u.id]?.email }
            users[u.id] = n
            if u.id == me?.id { let email = me?.email; me = n; if n.email == nil { me?.email = email } }
        }
    }

    func show(_ text: String) {
        toast = text
        Task { try? await Task.sleep(nanoseconds: 2_500_000_000); if toast == text { toast = nil } }
    }

    // MARK: session
    func boot() async {
        // Simulator screenshots in CI: `-demo chats|chat|call` fills the app with sample data, no server.
        if let i = CommandLine.arguments.firstIndex(of: "-demo") {
            loadDemo(i + 1 < CommandLine.arguments.count ? CommandLine.arguments[i + 1] : "chats")
            booting = false
            return
        }
        await loadConfig()
        if API.token != nil {
            do {
                let res = try await API.call("/api/me")
                await start(try API.decode(User.self, res["user"]))
            } catch let e as APIError where e.status != 401 {
                loggedIn = true; connectWs()
            } catch { clearSession() }
        }
        booting = false
    }

    private func loadDemo(_ screen: String) {
        let now = Date().timeIntervalSince1970 * 1000
        let meU = User(id: 1, username: "Cats_yt", displayName: "Cats", banner: "#5865f2", role: "owner", verified: true, badge: "разработчик", online: true)
        let bob = User(id: 2, username: "bob", displayName: "Боб", online: true)
        let kate = User(id: 3, username: "kate", displayName: "Катя", verified: true, online: false, lastSeen: now - 600_000)
        me = meU; remember([meU, bob, kate]); loggedIn = true; connected = true
        func msg(_ id: Int64, _ chat: Int64, _ user: Int64, _ text: String, _ ago: Double) -> Message {
            Message(id: id, chatId: chat, userId: user, text: text, createdAt: now - ago * 60_000)
        }
        var voice = msg(5, 1, 2, "", 3); voice.file = FileRef(url: "/x.m4a", name: "Голосовое сообщение", size: 40000, kind: "voice", duration: 14)
        var replied = msg(6, 1, 1, "Да, уже ставлю новую версию", 1); replied.replyTo = ReplyRef(id: 4, userId: 2, text: "Видел, вышло нативное приложение?")
        let list = [msg(1, 1, 2, "Привет! Как дела?", 30), msg(2, 1, 1, "Привет, всё отлично 🙂", 29), msg(3, 1, 1, "Смотри, что я сделал", 28),
                    msg(4, 1, 2, "Видел, вышло нативное приложение?", 5), voice, replied]
        messages[1] = list
        chats = [
            1: Chat(id: 1, type: "direct", createdAt: now, members: [meU, bob], lastMessage: list.last, unread: 0, othersRead: 6),
            2: Chat(id: 2, type: "direct", createdAt: now, members: [meU, kate], lastMessage: msg(7, 2, 3, "Созвонимся вечером?", 50), unread: 2),
            3: Chat(id: 3, type: "group", title: "Ethic команда", createdAt: now, members: [meU, bob, kate], lastMessage: msg(8, 3, 2, "Сервер обновлён", 120), unread: 0,
                    call: CallInfo(participants: [2, 3])),
        ]
        if screen == "chat" { Nav.shared.openChat(1) }
        if screen == "call" {
            Calls.shared.ui = CallUI(chatId: 1, title: "Боб", connected: true, startedAt: Date().addingTimeInterval(-83), tiles: [
                Tile(id: "2", userId: 2, name: "Боб", local: false, screen: false, speaking: true, micOn: true, video: nil),
                Tile(id: "1", userId: 1, name: "Вы", local: true, screen: false, speaking: false, micOn: false, video: nil),
            ], micOn: false, status: "2 участн.")
        }
    }

    func loadConfig() async {
        if let c = try? await API.call("/api/config") {
            emailEnabled = c["emailEnabled"] as? Bool ?? false
            inviteRequired = c["inviteRequired"] as? Bool ?? false
        }
    }

    func auth(_ path: String, _ body: [String: Any?]) async throws {
        let res = try await API.call(path, method: "POST", body: body)
        guard let token = res["token"] as? String else { throw APIError(message: "Ошибка входа") }
        API.token = token
        UserDefaults.standard.set(token, forKey: "token")
        await start(try API.decode(User.self, res["user"]))
    }

    private func start(_ user: User) async {
        me = user
        remember([user])
        loggedIn = true
        connectWs()
        Task { do { try await loadChats() } catch { show(error.localizedDescription) } }
    }

    func logout() {
        Task { try? await API.call("/api/logout", method: "POST"); clearSession() }
    }

    func sessionExpired() { clearSession() }

    private func clearSession() {
        API.token = nil
        UserDefaults.standard.removeObject(forKey: "token")
        socket?.cancel(with: .goingAway, reason: nil); socket = nil
        me = nil; chats = [:]; messages = [:]; users = [:]
        loggedIn = false
    }

    // MARK: chats
    func loadChats() async throws {
        let res = try await API.call("/api/chats")
        let list = try API.decode([Chat].self, res["chats"])
        for c in list { remember(c.members) }
        chats = Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
    }

    func upsert(_ chat: Chat) { remember(chat.members); chats[chat.id] = chat }

    func openDirect(_ userId: Int64) async throws -> Chat {
        let res = try await API.call("/api/chats/direct", method: "POST", body: ["userId": userId])
        let c = try API.decode(Chat.self, res["chat"]); upsert(c); return c
    }

    func createGroup(_ title: String, _ ids: [Int64]) async throws -> Chat {
        let res = try await API.call("/api/chats/group", method: "POST", body: ["title": title, "memberIds": ids])
        let c = try API.decode(Chat.self, res["chat"]); upsert(c); return c
    }

    func addMember(_ chatId: Int64, _ userId: Int64) async throws {
        let res = try await API.call("/api/chats/\(chatId)/members", method: "POST", body: ["userId": userId])
        upsert(try API.decode(Chat.self, res["chat"]))
    }

    func acceptRequest(_ chatId: Int64) async throws {
        upsert(try API.decode(Chat.self, try await API.call("/api/chats/\(chatId)/accept", method: "POST")["chat"]))
    }
    func declineRequest(_ chatId: Int64) async throws {
        try await API.call("/api/chats/\(chatId)/decline", method: "POST")
        chats[chatId] = nil; messages[chatId] = nil
    }

    func leaveChat(_ chatId: Int64) async throws {
        try await API.call("/api/chats/\(chatId)/members/me", method: "DELETE")
        chats[chatId] = nil; messages[chatId] = nil
    }

    func searchUsers(_ q: String) async throws -> [User] {
        let enc = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let list = try API.decode([User].self, try await API.call("/api/users?q=\(enc)")["users"])
        remember(list); return list
    }

    func fetchUser(_ id: Int64) async { if let u = try? API.decode(User.self, try await API.call("/api/users/\(id)")["user"]) { remember([u]) } }

    func updateMe(_ body: [String: Any?]) async throws {
        let u = try API.decode(User.self, try await API.call("/api/me", method: "PATCH", body: body)["user"])
        me = u; remember([u])
    }

    // MARK: messages
    func loadMessages(_ chatId: Int64, older: Bool = false) async throws {
        let cur = messages[chatId] ?? []
        let before = older ? cur.first(where: { !$0.isPending })?.id : nil
        let res = try await API.call("/api/chats/\(chatId)/messages" + (before.map { "?before=\($0)" } ?? ""))
        let list = try API.decode([Message].self, res["messages"])
        hasMore[chatId] = res["hasMore"] as? Bool ?? false
        if older { messages[chatId] = list + cur } else { messages[chatId] = list + cur.filter { $0.isPending } }
    }

    func markRead(_ chatId: Int64) {
        guard foreground, activeChat == chatId, let last = messages[chatId]?.last(where: { !$0.isPending }) else { return }
        if (chats[chatId]?.unread ?? 0) > 0 { chats[chatId]?.unread = 0 }
        send(["type": "read", "chatId": chatId, "messageId": last.id])
    }

    private var pendingSeq: Int64 = -1
    private func pushPending(_ chatId: Int64, reply: Message?, _ base: Message) -> Message {
        var p = base
        p.id = pendingSeq; pendingSeq -= 1
        p.chatId = chatId; p.userId = me?.id ?? 0
        p.clientId = UUID().uuidString; p.pending = true
        p.createdAt = Date().timeIntervalSince1970 * 1000
        if let r = reply { p.replyTo = ReplyRef(id: r.id, userId: r.userId, text: String(r.text.prefix(140)), kind: r.kind, fileName: r.file?.name) }
        messages[chatId, default: []].append(p)
        return p
    }

    func sendText(_ chatId: Int64, _ text: String, reply: Message?) {
        let p = pushPending(chatId, reply: reply, Message(id: 0, chatId: chatId, userId: 0, text: text))
        Sounds.shared.sent()
        send(["type": "message", "chatId": chatId, "text": text, "clientId": p.clientId, "replyTo": reply?.id])
    }

    func sendUpload(_ chatId: Int64, file: URL, kind: String, name: String, reply: Message?, preview: Message,
                    build: @escaping (API.Uploaded) -> [String: Any]) {
        let p = pushPending(chatId, reply: reply, preview)
        Sounds.shared.sent()
        Task {
            do {
                let up = try await API.upload(file: file, kind: kind, name: name)
                var msg: [String: Any?] = ["type": "message", "chatId": chatId, "clientId": p.clientId, "text": "", "replyTo": reply?.id]
                for (k, v) in build(up) { msg[k] = v }
                send(msg)
            } catch {
                show("Не отправлено: \(error.localizedDescription)")
                messages[chatId]?.removeAll { $0.clientId == p.clientId }
            }
        }
    }

    func edit(_ m: Message, _ text: String) { send(["type": "edit", "chatId": m.chatId, "messageId": m.id, "text": text]) }
    func delete(_ m: Message) { send(["type": "delete", "chatId": m.chatId, "messageId": m.id]) }

    private var lastTyping = Date.distantPast
    func sendTyping(_ chatId: Int64) {
        guard Date().timeIntervalSince(lastTyping) > 2.5 else { return }
        lastTyping = Date()
        send(["type": "typing", "chatId": chatId])
    }

    func typingNames(_ chatId: Int64) -> [String] {
        (typing[chatId] ?? [:]).filter { $0.value > Date() }.keys.map { userName($0) }
    }

    // MARK: websocket
    private var socket: URLSessionWebSocketTask?
    private var retry = 0

    func connectWs() {
        guard let token = API.token, let url = URL(string: "\(API.ws)?token=\(token)") else { return }
        socket?.cancel(with: .goingAway, reason: nil)
        let s = URLSession.shared.webSocketTask(with: url)
        socket = s
        s.resume()
        receive(s)
        s.sendPing { [weak self] err in
            Task { @MainActor in
                guard let self, self.socket === s else { return }
                if err == nil {
                    self.connected = true
                    if self.retry > 0 { try? await self.loadChats(); for id in self.messages.keys { try? await self.loadMessages(id) } }
                    self.retry = 0
                }
            }
        }
        Task { [weak self] in
            // Keepalive; also detects a dead connection.
            while let self, self.socket === s {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                s.sendPing { _ in }
            }
        }
    }

    private func receive(_ s: URLSessionWebSocketTask) {
        s.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.socket === s else { return }
                switch result {
                case .success(let msg):
                    if case .string(let text) = msg, let data = text.data(using: .utf8),
                       let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { self.handle(obj) }
                    self.receive(s)
                case .failure:
                    self.closed(s)
                }
            }
        }
    }

    private func closed(_ s: URLSessionWebSocketTask) {
        connected = false
        if s.closeCode.rawValue == 4001 { show("Сессия завершена"); clearSession(); return }
        guard API.token != nil else { return }
        retry += 1
        let delay = min(pow(2.0, Double(min(retry, 4))), 15)
        Task { try? await Task.sleep(nanoseconds: UInt64(delay * 1e9)); if self.socket === s { self.connectWs() } }
    }

    func onForeground() {
        foreground = true
        if loggedIn, !connected { retry = 1; connectWs() }
        if let a = activeChat { markRead(a) }
    }

    @discardableResult
    func send(_ obj: [String: Any?]) -> Bool {
        guard let socket, let data = try? JSONSerialization.data(withJSONObject: obj.compactMapValues { $0 }),
              let text = String(data: data, encoding: .utf8) else { return false }
        socket.send(.string(text)) { _ in }
        return true
    }

    private func int(_ v: Any?) -> Int64 { (v as? NSNumber)?.int64Value ?? 0 }

    private func handle(_ ev: [String: Any]) {
        switch ev["type"] as? String {
        case "message":
            if let m = try? API.decode(Message.self, ev["message"]) { onMessage(m, clientId: ev["clientId"] as? String) }
        case "message:update":
            if let m = try? API.decode(Message.self, ev["message"]) {
                if let i = messages[m.chatId]?.firstIndex(where: { $0.id == m.id }) { messages[m.chatId]?[i] = m }
                if chats[m.chatId]?.lastMessage?.id == m.id { chats[m.chatId]?.lastMessage = m }
            }
        case "message:delete":
            let cid = int(ev["chatId"]), mid = int(ev["messageId"])
            messages[cid]?.removeAll { $0.id == mid }
            if var list = messages[cid] {
                for i in list.indices where list[i].replyTo?.id == mid { list[i].replyTo = ReplyRef(id: mid, deleted: true) }
                messages[cid] = list
            }
            chats[cid]?.lastMessage = try? API.decode(Message.self, ev["lastMessage"])
        case "read":
            let cid = int(ev["chatId"])
            if int(ev["userId"]) != me?.id { chats[cid]?.othersRead = max(chats[cid]?.othersRead ?? 0, int(ev["messageId"])) }
        case "chat":
            if let c = try? API.decode(Chat.self, ev["chat"]) { upsert(c) }
        case "notice":
            if let t = ev["text"] as? String { show(t) }
        case "chat:removed":
            let cid = int(ev["chatId"])
            chats[cid] = nil; messages[cid] = nil
            if let t = ev["text"] as? String { show(t) }
            if Nav.shared.chatsPath.contains(.chat(cid)) { Nav.shared.chatsPath = [] }
        case "user":
            if let u = try? API.decode(User.self, ev["user"]) {
                remember([u])
                for (id, var c) in chats { c.members = c.members.map { $0.id == u.id ? u : $0 }; chats[id] = c }
            }
        case "presence":
            let id = int(ev["userId"])
            users[id]?.online = ev["online"] as? Bool ?? false
            users[id]?.lastSeen = (ev["lastSeen"] as? NSNumber)?.doubleValue
        case "typing":
            setTyping(int(ev["chatId"]), int(ev["userId"]), true)
        case "call:ring":
            if let r = try? API.decode(RingEvent.self, ev) { Calls.shared.onRing(r) }
        case "call:state":
            let cid = int(ev["chatId"])
            let call = try? API.decode(CallInfo.self, ev["call"])
            chats[cid]?.call = call
            if call == nil, Calls.shared.incoming?.chatId == cid { Calls.shared.dismissIncoming() }
        case "call:declined":
            let cid = int(ev["chatId"])
            if Calls.shared.ui?.chatId == cid { show("\(userName(int(ev["userId"]))) отклоняет звонок"); Sounds.shared.stop() }
        case "call:invited":
            if let u = try? API.decode(User.self, ev["user"]) { remember([u]) }
            if Calls.shared.ui?.chatId == int(ev["chatId"]), int(ev["by"]) != me?.id {
                show("\(userName(int(ev["by"]))) зовёт: \(userName(int(ev["userId"])))")
            }
        default: break
        }
    }

    private func setTyping(_ chatId: Int64, _ userId: Int64, _ on: Bool) {
        var m = typing[chatId] ?? [:]
        m[userId] = on ? Date().addingTimeInterval(4) : nil
        typing[chatId] = m
        if on { Task { try? await Task.sleep(nanoseconds: 4_100_000_000); self.typing[chatId] = self.typing[chatId] } }
    }

    private func onMessage(_ m: Message, clientId: String?) {
        guard chats[m.chatId] != nil else { Task { try? await loadChats() }; return }
        let mine = m.userId == me?.id
        if messages[m.chatId] != nil {
            if let cid = clientId, let i = messages[m.chatId]?.firstIndex(where: { $0.clientId == cid }) {
                var n = m; n.localImage = messages[m.chatId]?[i].localImage; n.clientId = cid
                messages[m.chatId]?[i] = n
            } else { messages[m.chatId]?.append(m) }
        }
        setTyping(m.chatId, m.userId, false)
        let visible = m.chatId == activeChat && foreground
        chats[m.chatId]?.lastMessage = m
        if !mine {
            Sounds.shared.received()
            if !visible { chats[m.chatId]?.unread += 1 }
        }
        if visible { markRead(m.chatId) }
    }
}
