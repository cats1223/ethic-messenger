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
    var grantedCheck: Bool?
    var customBadge: String?
    var messages: Int?
    var plus: Bool?
    var nameStyle: String?

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
                                    BadgeLabel(user: u.asUser)
                                    if u.banned == true { Text("заблокирован").font(.caption).foregroundStyle(red) }
                                }
                                Text("ID \(u.id) · @\(u.username) · \(u.messages ?? 0) сообщ.").font(.caption).foregroundStyle(muted)
                            }
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
        .confirmationDialog("Удалить аккаунт \(deleteFor?.displayName ?? "") навсегда?", isPresented: Binding(get: { deleteFor != nil }, set: { if !$0 { deleteFor = nil } }), titleVisibility: .visible) {
            Button("Удалить навсегда", role: .destructive) { if let u = deleteFor { act("/api/admin/users/\(u.id)/delete", [:], "Аккаунт удалён") } }
        } message: { Text("Логин, фото, почта и вход будут удалены, отменить нельзя.") }
        .confirmationDialog("Заблокировать \(banFor?.displayName ?? "")?", isPresented: Binding(get: { banFor != nil }, set: { if !$0 { banFor = nil } }), titleVisibility: .visible) {
            Button("Заблокировать", role: .destructive) { if let u = banFor { act("/api/admin/users/\(u.id)/ban", ["banned": true], "Заблокирован") } }
        }
    }

    private func reload() async {
        do {
            stats = try await API.call("/api/admin/stats")
            let enc = q.trimmed.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            users = try API.decode([AdminUser].self, try await API.call("/api/admin/users?q=\(enc)")["users"])
        } catch { store.show(error.localizedDescription) }
    }

    private func act(_ path: String, _ body: [String: Any?], _ ok: String? = nil) {
        Task {
            do { try await API.call(path, method: "POST", body: body); if let ok { store.show(ok) }; await reload() }
            catch { store.show(error.localizedDescription) }
        }
    }
}
