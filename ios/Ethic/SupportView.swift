import SwiftUI

struct Ticket: Codable, Identifiable, Hashable {
    let id: Int64
    var title: String = ""
    var category: String?
    var status: String?
    var createdAt: Double?
    var updatedAt: Double?
    var user: User?
    var awaitingStaff: Bool?
}

struct TicketMsg: Codable, Identifiable {
    let id: Int64
    var userId: Int64?
    var user: User?
    var text: String = ""
    var createdAt: Double?
}

private let cats: [(String, String)] = [("bug", "Ошибка"), ("question", "Вопрос"), ("idea", "Предложение"), ("complaint", "Жалоба"), ("other", "Другое")]
private func catName(_ c: String?) -> String { cats.first { $0.0 == c }?.1 ?? "Другое" }

private struct StatusPill: View {
    let t: Ticket
    var body: some View {
        let (text, color): (String, Color) = t.status == "closed" ? ("Закрыто", muted) : t.awaitingStaff == true ? ("Ждёт ответа", blue) : ("Есть ответ", green)
        Text(text).font(.caption.bold()).foregroundStyle(color).padding(.horizontal, 8).padding(.vertical, 2).background(color.opacity(0.15), in: Capsule())
    }
}

/// Support tickets: own tickets for everyone; all tickets for support / staff.
struct SupportView: View {
    let staff: Bool
    @State private var store = Store.shared
    @State private var list: [Ticket] = []
    @State private var filter: String
    @State private var creating = false

    init(staff: Bool) { self.staff = staff; _filter = State(initialValue: staff ? "open" : "") }

    var body: some View {
        List {
            if staff {
                Picker("Фильтр", selection: $filter) { Text("Открытые").tag("open"); Text("Закрытые").tag("closed"); Text("Все").tag("") }
                    .pickerStyle(.segmented).listRowBackground(Color.clear)
            }
            if list.isEmpty {
                Text(staff ? "Обращений нет" : "У вас пока нет обращений. Опишите проблему — мы ответим здесь же.").foregroundStyle(muted)
            }
            ForEach(list) { t in
                NavigationLink { TicketThreadView(id: t.id) } label: {
                    HStack(spacing: 10) {
                        if staff { AvatarView(user: t.user, size: 38) }
                        VStack(alignment: .leading, spacing: 2) {
                            Text("#\(t.id) · \(t.title)").font(.subheadline.weight(.semibold)).lineLimit(1)
                            Text(catName(t.category) + (staff ? " · \(t.user?.displayName ?? "")" : "") + " · \(shortWhen(t.updatedAt ?? 0))").font(.caption).foregroundStyle(muted)
                        }
                        Spacer()
                        StatusPill(t: t)
                    }
                }
            }
        }
        .navigationTitle(staff ? "Обращения" : "Поддержка")
        .toolbar { if !staff { Button { creating = true } label: { Image(systemName: "plus") } } }
        .sheet(isPresented: $creating) { NewTicketView { creating = false } }
        .task(id: "\(filter)-\(store.ticketsVersion)-\(creating)") { await load() }
    }

    private func load() async {
        var q = staff ? "all=1&" : ""
        if !filter.isEmpty { q += "status=\(filter)" }
        do { list = try API.decode([Ticket].self, try await API.call("/api/tickets?\(q)")["tickets"]) } catch { store.show(error.localizedDescription) }
    }
}

private struct NewTicketView: View {
    let done: () -> Void
    @State private var title = ""
    @State private var cat = "bug"
    @State private var text = ""
    @State private var busy = false
    var body: some View {
        NavigationStack {
            Form {
                TextField("Тема", text: $title)
                Picker("Категория", selection: $cat) { ForEach(cats, id: \.0) { Text($0.1).tag($0.0) } }
                Section("Описание") { TextEditor(text: $text).frame(minHeight: 160) }
            }
            .navigationTitle("Новое обращение").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Отмена", action: done) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Отправить") {
                        busy = true
                        Task {
                            do {
                                try await API.call("/api/tickets", method: "POST", body: ["title": title.trimmed, "category": cat, "text": text.trimmed])
                                Store.shared.show("Обращение отправлено"); done()
                            } catch { Store.shared.show(error.localizedDescription) }
                            busy = false
                        }
                    }
                    .disabled(busy)
                }
            }
        }
    }
}

private struct TicketThreadView: View {
    let id: Int64
    @State private var store = Store.shared
    @State private var ticket: Ticket?
    @State private var msgs: [TicketMsg] = []
    @State private var reply = ""

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(msgs) { m in
                        let fromStaff = m.userId != ticket?.user?.id
                        HStack {
                            if fromStaff { Spacer(minLength: 40) }
                            VStack(alignment: .leading, spacing: 3) {
                                Text((m.user?.displayName ?? "") + (fromStaff ? " · поддержка" : "")).font(.caption.bold()).foregroundStyle(fromStaff ? Color.gray : muted)
                                Text(m.text).foregroundStyle(fromStaff ? .black : .white)
                                Text(timeOf(m.createdAt ?? 0)).font(.caption2).foregroundStyle(.gray).frame(maxWidth: .infinity, alignment: .trailing)
                            }
                            .padding(10).background(fromStaff ? Color.white : surface2, in: RoundedRectangle(cornerRadius: 16))
                            if !fromStaff { Spacer(minLength: 40) }
                        }
                        .id(m.id)
                    }
                }
                .padding(12)
            }
            .onChange(of: msgs.count) { _, _ in if let l = msgs.last { proxy.scrollTo(l.id, anchor: .bottom) } }
        }
        .safeAreaInset(edge: .bottom) {
            HStack(alignment: .bottom) {
                TextField(ticket?.status == "closed" ? "Новое сообщение откроет обращение" : "Ответ…", text: $reply, axis: .vertical)
                    .lineLimit(1...5).padding(.horizontal, 14).padding(.vertical, 11).glass(RoundedRectangle(cornerRadius: 22))
                Button { send() } label: { Image(systemName: "arrow.up").font(.headline).foregroundStyle(.black).frame(width: 44, height: 44).background(.white, in: Circle()) }
            }
            .padding(10)
        }
        .navigationTitle(ticket?.title ?? "Обращение #\(id)").navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let t = ticket {
                Button(t.status == "closed" ? "Открыть" : "Закрыть") {
                    Task {
                        do { try await API.call("/api/tickets/\(id)/status", method: "POST", body: ["status": t.status == "closed" ? "open" : "closed"]); await load() }
                        catch { store.show(error.localizedDescription) }
                    }
                }
            }
        }
        .task(id: store.ticketsVersion) { await load() }
    }

    private func load() async {
        do {
            let r = try await API.call("/api/tickets/\(id)")
            ticket = try API.decode(Ticket.self, r["ticket"])
            msgs = try API.decode([TicketMsg].self, r["messages"])
        } catch { store.show(error.localizedDescription) }
    }

    private func send() {
        let text = reply.trimmed
        guard !text.isEmpty else { return }
        reply = ""
        Task {
            do { try await API.call("/api/tickets/\(id)/messages", method: "POST", body: ["text": text]); await load() }
            catch { store.show(error.localizedDescription); reply = text }
        }
    }
}
