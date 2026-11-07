import SwiftUI

struct AdminUser: Codable, Identifiable {
    let id: Int64
    var username: String = ""
    var displayName: String = ""
    var avatar: String?
    var role: String?
    var verified: Bool?
    var badge: String?
    var online: Bool?
    var banned: Bool?
    var banReason: String?
    var grantedCheck: Bool?
    var customBadge: String?
    var messages: Int?
    var plus: Bool?
    var nameStyle: String?
    var warnings: Int?
    var warnLimit: Int?
    var banUntil: Double?
    var mute: User.Mute?

    var asUser: User { User(id: id, username: username, displayName: displayName, avatar: avatar, role: role, verified: verified, badge: badge, online: online, plus: plus, nameStyle: nameStyle) }
}

private let roles: [(String, String)] = [("user", "Без роли"), ("tester", "Тестер"), ("support", "Поддержка"), ("dev", "Разработчик")]
private let statNames: [(String, String)] = [("users", "Пользователей"), ("online", "В сети"), ("chats", "Чатов"), ("messages", "Сообщений"),
                                             ("messagesToday", "За сутки"), ("photos", "Фото"), ("files", "Файлов"), ("activeCalls", "Звонков"), ("banned", "Заблокировано")]

/// The admin panel, native (same server endpoints as the website).
struct AdminView: View {
    @State private var store = Store.shared
    @State private var stats: [String: Any] = [:]
    @State private var users: [AdminUser] = []
    @State private var q = ""
    @State private var badgeFor: AdminUser?
    @State private var badgeText = ""
    @State private var banFor: AdminUser?
    @State private var deleteFor: AdminUser?
    @State private var warnFor: AdminUser?
    @State private var muteFor: AdminUser?
    @State private var reports: [ReportCard] = []
    @State private var reason = ""

    private var owner: Bool { store.me?.role == "owner" }

    var body: some View {
        List {
            Section {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 8) {
                    ForEach(statNames, id: \.0) { key, label in
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\((stats[key] as? NSNumber)?.intValue ?? 0)").font(.title3.weight(.heavy))
                            Text(label).font(.caption).foregroundStyle(muted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading).padding(10)
                        .background(surface2, in: RoundedRectangle(cornerRadius: 12))
                    }
                }
            }
            .listRowBackground(Color.clear)
            Section("Код приглашения") {
                let code = stats["inviteCode"] as? String ?? ""
                Text(code.isEmpty ? "не требуется" : code).font(.headline)
                if !code.isEmpty { Button("Скопировать") { UIPasteboard.general.string = code; store.show("Скопировано") } }
                Button("Новый код") { act("/api/admin/invite", [:], "Новый код") }
                Button("Без кода") { act("/api/admin/invite", ["code": ""], "Код отключён") }
            }
            if !reports.isEmpty {
                Section("Жалобы на сообщения: \(reports.count)") {
                    ForEach(reports) { c in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 10) {
                                if let a = c.author { AvatarView(user: a, size: 34) }
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(c.author?.displayName ?? "").bold()
                                    Text("@\(c.author?.username ?? "") · " + (c.chatTitle.map { "в группе «\($0)»" } ?? "в личных")).font(.caption).foregroundStyle(muted)
                                }
                                Spacer()
                                if let a = c.author {
                                    let au = users.first { $0.id == a.id } ?? AdminUser(id: a.id, username: a.username, displayName: a.displayName, avatar: a.avatar, role: a.role)
                                    Menu {
                                        Button("Предупредить…") { warnFor = au }
                                        Button("Замутить…") { muteFor = au }
                                        Button("Заблокировать…", role: .destructive) { banFor = au }
                                    } label: { Image(systemName: "ellipsis").foregroundStyle(muted).frame(width: 32, height: 32) }
                                }
                            }
                            Text(c.deleted == true ? "Сообщение уже удалено" : c.text ?? "").foregroundStyle(c.deleted == true ? muted : .white)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(10).background(Color.black, in: RoundedRectangle(cornerRadius: 10))
                            Text((c.reports ?? []).map { "\($0.reporter?.displayName ?? "?"): \(reportKind($0.kind))" + ($0.reason.map { " — \($0)" } ?? "") }.joined(separator: "\n"))
                                .font(.caption).foregroundStyle(muted)
                            HStack {
                                if c.deleted != true {
                                    Button("Удалить сообщение", role: .destructive) { act("/api/admin/reports/\(c.messageId)/close", ["deleteMessage": true], "Сообщение удалено") }
                                        .buttonStyle(.bordered)
                                }
                                Button(c.deleted == true ? "Закрыть" : "Отклонить") { act("/api/admin/reports/\(c.messageId)/close", ["deleteMessage": false], "Жалоба отклонена") }
                                    .buttonStyle(.bordered)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            Section("Пользователи") {
                ForEach(users) { u in
                    Menu {
                        if owner && u.role != "owner" {
                            Menu("Роль") {
                                ForEach(roles, id: \.0) { r, label in
                                    Button { act("/api/admin/users/\(u.id)/role", ["role": r], "Роль изменена") } label: {
                                        if u.role == r { Label(label, systemImage: "checkmark") } else { Text(label) }
                                    }
                                }
                            }
                        }
                        if owner && u.role != "owner" && u.role != "dev" {
                            Button(u.grantedCheck == true ? "Снять галочку" : "Выдать галочку") {
                                act("/api/admin/users/\(u.id)/verify", ["verified": !(u.grantedCheck ?? false)])
                            }
                        }
                        if owner { Button("Надпись у имени…") { badgeText = u.customBadge ?? ""; badgeFor = u } }
                        if u.id != store.me?.id && u.role != "owner" && (u.role != "dev" || owner) {
                            if u.banned != true { Button("Предупредить…") { warnFor = u } }
                            if u.banned != true {
                                if u.mute != nil { Button("Снять мут") { act("/api/admin/users/\(u.id)/mute", ["muted": false], "Мут снят") } }
                                else { Button("Замутить…") { muteFor = u } }
                            }
                            if u.banned == true { Button("Разблокировать") { act("/api/admin/users/\(u.id)/ban", ["banned": false]) } }
                            else { Button("Заблокировать", role: .destructive) { banFor = u } }
                        }
                        if owner && u.role != "owner" && !u.username.hasPrefix("deleted_") {
                            Button("Удалить аккаунт", role: .destructive) { deleteFor = u }
                        }
                    } label: {
                        HStack(spacing: 12) {
                            AvatarView(user: u.asUser, size: 42, showOnline: true)
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    NameLine(name: u.displayName, user: u.asUser)
                                    if u.banned == true { Text("заблокирован" + (u.banUntil.map { " до \(fmtUntil($0))" } ?? "")).font(.caption).foregroundStyle(red) }
                                    if let m = u.mute { Text("мут" + (m.until.map { " до \(fmtUntil($0))" } ?? "")).font(.caption).foregroundStyle(amber) }
                                }
                                if u.banned == true, let why = u.banReason { Text("Причина: \(why)").font(.caption).foregroundStyle(muted) }
                                HStack(spacing: 6) {
                                    Text("ID \(u.id) · @\(u.username)").font(.caption).foregroundStyle(muted)
                                    BadgeLabel(user: u.asUser)
                                    Text("\(u.messages ?? 0) сообщ.").font(.caption).foregroundStyle(muted)
                                }
                                if let n = u.warnings, n > 0 { Text("Предупреждений: \(n) из \(u.warnLimit ?? 3)").font(.caption).foregroundStyle(amber) }
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "ellipsis").foregroundStyle(muted).frame(width: 32, height: 32)
                        }
                        .foregroundStyle(.white)
                    }
                }
            }
        }
        .searchable(text: $q, prompt: "Поиск пользователей")
        .navigationTitle("Админ-панель")
        .task(id: q) { try? await Task.sleep(nanoseconds: 250_000_000); await reload() }
        .alert("Надпись у имени", isPresented: Binding(get: { badgeFor != nil }, set: { if !$0 { badgeFor = nil } })) {
            TextField("До 24 символов", text: $badgeText)
            Button("Сохранить") { if let u = badgeFor { act("/api/admin/users/\(u.id)/badge", ["badge": String(badgeText.trimmed.prefix(24))], "Сохранено") } }
            Button("Отмена", role: .cancel) {}
        } message: { Text("Пусто — надпись роли.") }
        .alert("Удалить аккаунт \(deleteFor?.displayName ?? "") навсегда?", isPresented: Binding(get: { deleteFor != nil }, set: { if !$0 { deleteFor = nil } })) {
            TextField("Причина, её увидит пользователь", text: $reason)
            Button("Удалить навсегда", role: .destructive) {
                if let u = deleteFor { act("/api/admin/users/\(u.id)/delete", ["reason": String(reason.trimmed.prefix(200))], "Аккаунт удалён") }
                reason = ""
            }
            Button("Отмена", role: .cancel) { reason = "" }
        } message: { Text("Логин, фото, почта и вход будут удалены, отменить нельзя.") }
        .alert("Предупредить \(warnFor?.displayName ?? "")?", isPresented: Binding(get: { warnFor != nil }, set: { if !$0 { warnFor = nil } })) {
            TextField("Причина, её увидит пользователь", text: $reason)
            Button(warnIsLast ? "Предупредить и заблокировать" : "Предупредить", role: warnIsLast ? .destructive : nil) {
                if let u = warnFor {
                    act("/api/admin/users/\(u.id)/warn", ["reason": String(reason.trimmed.prefix(200))],
                        warnIsLast ? "Предупреждение выдано, аккаунт заблокирован" : "Предупреждение выдано: \(warnNumber) из \(u.warnLimit ?? 3)")
                }
                reason = ""
            }
            Button("Отмена", role: .cancel) { reason = "" }
        } message: {
            Text(warnIsLast ? "Это \(warnNumber)-е предупреждение: аккаунт сразу заблокируется с этой причиной."
                 : "Это \(warnNumber)-е предупреждение из \(warnFor?.warnLimit ?? 3). Он увидит окно с причиной, на \(warnFor?.warnLimit ?? 3)-м аккаунт заблокируется сам.")
        }
        .sheet(item: $banFor) { u in
            PunishSheet(title: "Заблокировать \(u.displayName)?", text: "Он сразу выйдет из аккаунта и увидит окно с причиной и сроком. Блокировка на время снимется сама.",
                        action: "Заблокировать", initialHours: 24) { hours, why in
                act("/api/admin/users/\(u.id)/ban", ["banned": true, "hours": hours, "reason": why], "Заблокирован")
            }
        }
        .sheet(item: $muteFor) { u in
            PunishSheet(title: "Замутить \(u.displayName)?", text: "Он сможет читать чаты, но не сможет писать. Увидит срок и причину.",
                        action: "Замутить", initialHours: 1) { hours, why in
                act("/api/admin/users/\(u.id)/mute", ["muted": true, "hours": hours, "reason": why], "Замучен")
            }
        }
    }

    private var warnNumber: Int { (warnFor?.warnings ?? 0) + 1 }
    private var warnIsLast: Bool { warnNumber >= (warnFor?.warnLimit ?? 3) }

    private func reload() async {
        do {
            stats = try await API.call("/api/admin/stats")
            let enc = q.trimmed.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            users = try API.decode([AdminUser].self, try await API.call("/api/admin/users?q=\(enc)")["users"])
            reports = (try? API.decode([ReportCard].self, try await API.call("/api/admin/reports")["reports"])) ?? []
            store.reports = reports.count
        } catch { store.show(error.localizedDescription) }
    }

    private func act(_ path: String, _ body: [String: Any?], _ ok: String? = nil) {
        Task {
            do { try await API.call(path, method: "POST", body: body); if let ok { store.show(ok) }; await reload() }
            catch { store.show(error.localizedDescription) }
        }
    }
}
