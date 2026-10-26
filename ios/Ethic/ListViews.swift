import PhotosUI
import SwiftUI

struct ChatsView: View {
    @State private var store = Store.shared
    @State private var q = ""
    @State private var leaving: Chat?

    var body: some View {
        let list = store.sortedChats.filter { q.isEmpty || store.chatName($0).localizedCaseInsensitiveContains(q) }
        List {
            if list.isEmpty {
                Text("Пока нет чатов. Найдите друга во вкладке «Люди».").foregroundStyle(muted).listRowBackground(Color.clear)
            }
            ForEach(list) { chat in
                NavigationLink(value: Route.chat(chat.id)) { ChatRow(chat: chat) }
                    .listRowBackground(Color.black)
                    .swipeActions {
                        Button(chat.isGroup ? "Покинуть" : "Удалить", role: .destructive) { leaving = chat }
                    }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .searchable(text: $q, prompt: "Поиск чатов")
        .refreshable { try? await store.loadChats() }
        .navigationTitle(store.connected ? "Чаты" : "Соединение…")
        .toolbar {
            NavigationLink(value: Route.picker(.group, 0)) { Image(systemName: "square.and.pencil") }
        }
        .confirmationDialog(leaving.map { $0.isGroup ? "Покинуть группу?" : "Удалить чат?" } ?? "", isPresented: Binding(get: { leaving != nil }, set: { if !$0 { leaving = nil } }), titleVisibility: .visible) {
            Button(leaving?.isGroup == true ? "Покинуть" : "Удалить", role: .destructive) {
                if let c = leaving { Task { do { try await store.leaveChat(c.id) } catch { store.show(error.localizedDescription) } } }
            }
        }
    }
}

struct ChatRow: View {
    let chat: Chat
    @State private var store = Store.shared
    var body: some View {
        let other = chat.isGroup ? nil : store.other(chat)
        let typers = store.typingNames(chat.id)
        HStack(spacing: 12) {
            ChatAvatar(chat: chat, size: 54)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    NameLine(name: store.chatName(chat), user: other)
                    Spacer()
                    if chat.call?.participants.isEmpty == false { Image(systemName: "phone.fill").font(.caption).foregroundStyle(green) }
                    if let m = chat.lastMessage { Text(shortWhen(m.createdAt)).font(.caption).foregroundStyle(muted) }
                }
                HStack {
                    let last = chat.lastMessage
                    let prefix = last == nil ? "" : last!.userId == store.me?.id ? "Вы: " : chat.isGroup ? "\(store.userName(last!.userId)): " : ""
                    Text(typers.isEmpty ? prefix + previewText(last) : "печатает…")
                        .font(.subheadline).foregroundStyle(typers.isEmpty ? muted : blue).lineLimit(1)
                    Spacer()
                    if chat.unread > 0 {
                        Text("\(chat.unread)").font(.caption.weight(.bold)).padding(.horizontal, 7).frame(minWidth: 20, minHeight: 20)
                            .background(blue, in: Capsule())
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

struct UserRow: View {
    let user: User
    var body: some View {
        HStack(spacing: 12) {
            AvatarView(user: user, size: 46, showOnline: true)
            VStack(alignment: .leading, spacing: 2) {
                NameLine(name: user.displayName, user: user)
                Text("@\(user.username) · \(lastSeenText(user))").font(.footnote).foregroundStyle(muted).lineLimit(1)
            }
        }
    }
}

struct PeopleView: View {
    @State private var store = Store.shared
    @State private var q = ""
    @State private var found: [User] = []
    var body: some View {
        List(found) { u in
            NavigationLink(value: Route.user(u.id)) { UserRow(user: store.users[u.id] ?? u) }.listRowBackground(Color.black)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .searchable(text: $q, prompt: "Имя или логин")
        .navigationTitle("Люди")
        .task(id: q) {
            try? await Task.sleep(nanoseconds: 250_000_000)
            if let list = try? await store.searchUsers(q.trimmed) { found = list }
        }
    }
}

struct SettingsView: View {
    @State private var store = Store.shared
    @State private var emailSheet = false
    @State private var confirmLogout = false
    @State private var msgSound = Sounds.shared.messageSounds
    @State private var ringtone = Sounds.shared.ringtoneId
    @Environment(\.openURL) private var openURL

    var body: some View {
        List {
            if let u = store.me {
                Section {
                    NavigationLink(value: Route.editProfile) {
                        HStack(spacing: 14) {
                            AvatarView(user: u, size: 64)
                            VStack(alignment: .leading, spacing: 3) {
                                NameLine(name: u.displayName, user: u, font: .title3.weight(.bold))
                                Text("@\(u.username)").foregroundStyle(muted)
                                BadgeLabel(user: u)
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }
                Section {
                    if store.emailEnabled || u.email != nil {
                        Button { emailSheet = true } label: {
                            LabeledContent { Text(u.email ?? "Не привязана").foregroundStyle(muted) } label: { Label("Почта", systemImage: "envelope") }
                        }
                    }
                    Picker(selection: $ringtone) {
                        ForEach(Sounds.shared.ringtones) { Text($0.name).tag($0.id) }
                    } label: { Label("Рингтон", systemImage: "music.note") }
                    .onChange(of: ringtone) { _, v in
                        Sounds.shared.ringtoneId = v
                        Sounds.shared.ring(v, preview: true)
                        Task { try? await Task.sleep(nanoseconds: 3_000_000_000); Sounds.shared.stop() }
                    }
                    Toggle(isOn: $msgSound) { Label("Звуки сообщений", systemImage: "bell.badge") }
                        .onChange(of: msgSound) { _, v in Sounds.shared.messageSounds = v }
                        .tint(blue)
                }
                Section {
                    if u.isStaff {
                        Button { openURL(URL(string: API.base + "/#admin")!) } label: { Label("Админ-панель", systemImage: "shield") }
                    }
                    Button { openURL(URL(string: API.base)!) } label: { Label("Открыть сайт", systemImage: "safari") }
                    Button(role: .destructive) { confirmLogout = true } label: { Label("Выйти", systemImage: "rectangle.portrait.and.arrow.right") }
                }
                Section { Text("Ethic Messenger \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")").font(.footnote).foregroundStyle(muted) }
                    .listRowBackground(Color.clear)
            }
        }
        .navigationTitle("Настройки")
        .sheet(isPresented: $emailSheet) { EmailSheet() }
        .confirmationDialog("Выйти из аккаунта?", isPresented: $confirmLogout, titleVisibility: .visible) {
            Button("Выйти", role: .destructive) { Calls.shared.leave(); store.logout() }
        }
    }
}

struct EmailSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var code = ""
    @State private var sent = false
    @State private var error = ""
    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                if let cur = Store.shared.me?.email { Text("Сейчас: \(cur)").foregroundStyle(muted) }
                HStack {
                    Field(title: "Новая почта", text: $email, keyboard: .emailAddress)
                    CodeButton(enabled: !email.isEmpty) {
                        do { try await API.call("/api/me/email/code", method: "POST", body: ["email": email.trimmed]); sent = true; error = ""; return true }
                        catch { self.error = error.localizedDescription; return false }
                    }
                }
                if sent { Field(title: "Код из письма", text: $code, keyboard: .numberPad) }
                if !error.isEmpty { Text(error).foregroundStyle(red).font(.footnote) }
                Spacer()
            }
            .padding()
            .navigationTitle(Store.shared.me?.email == nil ? "Привязать почту" : "Сменить почту")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Готово") {
                        Task {
                            do {
                                let r = try await API.call("/api/me/email", method: "POST", body: ["email": email.trimmed, "code": code])
                                Store.shared.me = try API.decode(User.self, r["user"])
                                Store.shared.show("Почта привязана"); dismiss()
                            } catch { self.error = error.localizedDescription }
                        }
                    }
                    .disabled(!sent || code.count < 6)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

private struct ProfileHeader: View {
    let user: User
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                BannerView(user: user).frame(height: 140).frame(maxWidth: .infinity).clipped()
                AvatarView(user: user, size: 104)
                    .overlay(Circle().stroke(.black, lineWidth: 5))
                    .offset(x: 20, y: 52)
            }
            .padding(.bottom, 56)
            VStack(alignment: .leading, spacing: 4) {
                NameLine(name: user.displayName, user: user, font: .title.weight(.heavy))
                Text("@\(user.username)").foregroundStyle(muted)
                HStack {
                    BadgeLabel(user: user)
                    Text(lastSeenText(user)).font(.subheadline).foregroundStyle(user.isOnline ? green : muted)
                }
            }
            .padding(.horizontal, 20)
        }
    }
}

struct UserView: View {
    let userId: Int64
    @State private var store = Store.shared
    var body: some View {
        ScrollView {
            if let u = store.user(userId) {
                ProfileHeader(user: u)
                if u.id != store.me?.id {
                    HStack(spacing: 10) {
                        action("bubble.left.fill", "Написать") { Nav.shared.openChat($0) }
                        action("phone.fill", "Звонок") { Calls.shared.start($0, video: false) }
                        action("video.fill", "Видео") { Calls.shared.start($0, video: true) }
                    }
                    .padding()
                }
            }
        }
        .ignoresSafeArea(edges: .top)
        .task { await store.fetchUser(userId) }
    }

    private func action(_ icon: String, _ label: String, then: @escaping (Int64) -> Void) -> some View {
        Button {
            Task { do { then(try await store.openDirect(userId).id) } catch { store.show(error.localizedDescription) } }
        } label: {
            VStack(spacing: 6) { Image(systemName: icon).font(.title3); Text(label).font(.footnote) }
                .frame(maxWidth: .infinity).padding(.vertical, 14)
        }
        .glass(RoundedRectangle(cornerRadius: 18))
    }
}

struct GroupView: View {
    let chatId: Int64
    @State private var store = Store.shared
    @State private var leave = false
    var body: some View {
        List {
            if let chat = store.chats[chatId] {
                Section {
                    VStack(spacing: 8) {
                        ChatAvatar(chat: chat, size: 96)
                        Text(chat.title ?? "Группа").font(.title.weight(.heavy))
                        Text("\(chat.members.count) участн.").foregroundStyle(muted)
                    }
                    .frame(maxWidth: .infinity).listRowBackground(Color.clear)
                }
                Section {
                    NavigationLink(value: Route.picker(.addMember, chatId)) { Label("Добавить участника", systemImage: "person.badge.plus") }
                    Button(role: .destructive) { leave = true } label: { Label("Покинуть группу", systemImage: "rectangle.portrait.and.arrow.right") }
                }
                Section("Участники") {
                    ForEach(chat.members) { m in NavigationLink(value: Route.user(m.id)) { UserRow(user: store.users[m.id] ?? m) } }
                }
            }
        }
        .navigationTitle("Группа").navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Покинуть группу?", isPresented: $leave, titleVisibility: .visible) {
            Button("Покинуть", role: .destructive) {
                Task { do { try await store.leaveChat(chatId); Nav.shared.chatsPath = [] } catch { store.show(error.localizedDescription) } }
            }
        }
    }
}

private let swatches = ["#5865f2", "#3ba55c", "#faa61a", "#ed4245", "#eb459e", "#1abc9c", "#9b59b6", "#202225", "#ffffff"]

struct EditProfileView: View {
    @State private var store = Store.shared
    @State private var name = Store.shared.me?.displayName ?? ""
    @State private var avatarItem: PhotosPickerItem?
    @State private var bannerItem: PhotosPickerItem?
    @State private var busy = false

    var body: some View {
        ScrollView {
            if let u = store.me {
                ProfileHeader(user: u)
                if busy { ProgressView().padding() }
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        PhotosPicker(selection: $avatarItem, matching: .images) { Label("Сменить аватар", systemImage: "person.crop.circle") }
                        Spacer()
                        if u.avatar != nil { Button("Удалить", role: .destructive) { save(["avatar": nil]) } }
                    }
                    Text("Баннер").foregroundStyle(muted)
                    HStack(spacing: 8) {
                        ForEach(swatches, id: \.self) { c in
                            Circle().fill(hexColor(c) ?? .gray).frame(width: 30, height: 30)
                                .overlay(Circle().stroke(u.banner == c ? blue : surface3, lineWidth: 2))
                                .onTapGesture { save(["banner": c]) }
                        }
                    }
                    HStack {
                        PhotosPicker(selection: $bannerItem, matching: .images) { Text("Загрузить картинку") }
                        Spacer()
                        Button("Сбросить") { save(["banner": nil]) }.foregroundStyle(muted)
                    }
                    Field(title: "Отображаемое имя", text: $name)
                    Button { save(["displayName": name.trimmed], toast: "Сохранено") } label: {
                        Text("Сохранить").font(.headline).frame(maxWidth: .infinity).frame(height: 50)
                            .background(.white, in: RoundedRectangle(cornerRadius: 14)).foregroundStyle(.black)
                    }
                }
                .padding(20)
            }
        }
        .ignoresSafeArea(edges: .top)
        .onChange(of: avatarItem) { _, item in upload(item, aspect: 1, maxSide: 512, field: "avatar") }
        .onChange(of: bannerItem) { _, item in upload(item, aspect: 3, maxSide: 1500, field: "banner") }
    }

    private func save(_ body: [String: Any?], toast: String? = nil) {
        Task {
            do { try await store.updateMe(body); if let toast { store.show(toast) } } catch { store.show(error.localizedDescription) }
        }
    }

    private func upload(_ item: PhotosPickerItem?, aspect: CGFloat, maxSide: CGFloat, field: String) {
        guard let item else { return }
        busy = true
        Task {
            defer { busy = false }
            guard let data = try? await item.loadTransferable(type: Data.self), let (file, _, _) = prepareImage(data, maxSide: maxSide, aspect: aspect) else {
                store.show("Не удалось открыть изображение"); return
            }
            do {
                let up = try await API.upload(file: file, kind: "image", name: "image.jpg")
                try await store.updateMe([field: up.url])
            } catch { store.show(error.localizedDescription) }
        }
    }
}

/// Pick people: new group, add a member, or ring someone into the call.
struct PickerView: View {
    let mode: PickMode
    let chatId: Int64
    @State private var store = Store.shared
    @State private var q = ""
    @State private var title = ""
    @State private var found: [User] = []
    @State private var picked: Set<Int64> = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let exclude: [Int64] = mode == .addMember ? (store.chats[chatId]?.members.map(\.id) ?? []) : mode == .addToCall ? (Calls.shared.ui?.tiles.map(\.userId) ?? []) : []
        List {
            if mode == .group { Section { TextField("Название группы", text: $title) } }
            ForEach(found.filter { !exclude.contains($0.id) }) { u in
                Button { tap(u) } label: {
                    HStack {
                        UserRow(user: u)
                        Spacer()
                        if mode == .group { Image(systemName: picked.contains(u.id) ? "checkmark.circle.fill" : "circle").foregroundStyle(picked.contains(u.id) ? blue : muted) }
                    }
                }
                .foregroundStyle(.white)
            }
        }
        .searchable(text: $q, prompt: "Имя или логин")
        .navigationTitle(mode == .group ? "Новая группа" : mode == .addMember ? "Добавить участника" : "Добавить в звонок")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if mode == .group {
                Button("Создать") {
                    Task {
                        do { let c = try await store.createGroup(title.trimmed, Array(picked)); Nav.shared.chatsPath = [.chat(c.id)] }
                        catch { store.show(error.localizedDescription) }
                    }
                }
                .disabled(title.trimmed.isEmpty)
            }
        }
        .task(id: q) {
            try? await Task.sleep(nanoseconds: 250_000_000)
            if let list = try? await store.searchUsers(q.trimmed) { found = list }
        }
    }

    private func tap(_ u: User) {
        switch mode {
        case .group: if picked.contains(u.id) { picked.remove(u.id) } else { picked.insert(u.id) }
        case .addMember:
            Task {
                do { try await store.addMember(chatId, u.id); store.show("\(u.displayName) добавлен(а)"); dismiss() }
                catch { store.show(error.localizedDescription) }
            }
        case .addToCall: Calls.shared.invite(u.id); dismiss()
        }
    }
}

struct ImageViewer: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1
    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            AsyncImage(url: url) { $0.resizable().scaledToFit() } placeholder: { ProgressView() }
                .scaleEffect(scale)
                .gesture(MagnificationGesture().onChanged { scale = max(1, min(5, $0)) }.onEnded { _ in withAnimation { if scale < 1.2 { scale = 1 } } })
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            Button { dismiss() } label: { Image(systemName: "xmark").font(.title3).padding(12) }
                .glass(Circle()).padding()
        }
    }
}
