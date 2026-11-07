import SwiftUI

// MARK: Ethic+ chat themes: the colour of your own messages and the chat background

struct ChatTheme { let bubble: Color; let bg: Color }

let chatThemes: [(String?, String)] = [(nil, "Обычная"), ("ocean", "Океан"), ("sunset", "Закат"), ("forest", "Лес"), ("violet", "Фиалка"), ("rose", "Роза"), ("gold", "Золото")]

private func hex(_ v: UInt32) -> Color { Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255) }

func chatTheme(_ id: String?) -> ChatTheme? {
    switch id {
    case "ocean": ChatTheme(bubble: hex(0x2563EB), bg: hex(0x0B2545))
    case "sunset": ChatTheme(bubble: hex(0xEA580C), bg: hex(0x3B1408))
    case "forest": ChatTheme(bubble: hex(0x16A34A), bg: hex(0x082A17))
    case "violet": ChatTheme(bubble: hex(0x7C3AED), bg: hex(0x21093F))
    case "rose": ChatTheme(bubble: hex(0xDB2777), bg: hex(0x3A0A24))
    case "gold": ChatTheme(bubble: hex(0xCA8A04), bg: hex(0x2E2204))
    default: nil
    }
}

/// The theme in effect for this person: only with Ethic+.
func myTheme(_ me: User?) -> ChatTheme? { me?.plus == true ? chatTheme(me?.theme) : nil }

// MARK: ban and mute lengths

let durations: [(Int?, String)] = [(1, "1 час"), (24, "1 день"), (72, "3 дня"), (168, "7 дней"), (720, "30 дней"), (nil, "Навсегда")]

func fmtUntil(_ ms: Double) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "ru_RU")
    f.dateFormat = "d MMMM, HH:mm"
    return f.string(from: Date(timeIntervalSince1970: ms / 1000))
}

/// Muted: instead of the message field, a line saying until when and why.
struct MuteBar: View {
    let m: User.Mute
    @State private var store = Store.shared

    var body: some View {
        Text("\(m.until.map { "Вам запрещено писать до \(fmtUntil($0))" } ?? "Вам запрещено писать"). \(m.reason.map { "Причина: \($0)" } ?? "Причина не указана")")
            .font(.subheadline.weight(.semibold)).foregroundStyle(amber)
            .frame(maxWidth: .infinity, alignment: .leading).padding(14)
            .background(amber.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 12).padding(.vertical, 8)
            .task(id: m.until) {
                guard let until = m.until else { return }
                let wait = max(0, until - Date().timeIntervalSince1970 * 1000 + 500)
                try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000))
                if !Task.isCancelled { store.me?.mute = nil }
            }
    }
}

/// Staff picking a length and a reason: ban or mute.
struct PunishSheet: View {
    let title: String
    let text: String
    let action: String
    var initialHours: Int? = 24
    let onConfirm: (Int?, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var hours: Int? = 24
    @State private var reason = ""

    var body: some View {
        NavigationStack {
            Form {
                Section { Text(text).foregroundStyle(muted) }
                Section("Срок") {
                    Picker("Срок", selection: $hours) {
                        ForEach(durations, id: \.1) { h, label in Text(label).tag(h) }
                    }
                    .pickerStyle(.inline).labelsHidden()
                }
                Section("Причина") { TextField("Например: спам", text: $reason, axis: .vertical) }
                Section {
                    Button(action, role: .destructive) { onConfirm(hours, String(reason.trimmed.prefix(200))); dismiss() }
                }
            }
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } } }
            .onAppear { hours = initialHours }
        }
    }
}

// MARK: reports

let reportKinds: [(String, String)] = [("spam", "Спам или реклама"), ("insult", "Оскорбления"), ("scam", "Мошенничество"), ("nsfw", "Неприличное"), ("other", "Другое")]
func reportKind(_ k: String?) -> String { reportKinds.first { $0.0 == k }?.1 ?? "Другое" }

struct ReportSheet: View {
    let m: Message
    @Environment(\.dismiss) private var dismiss
    @State private var store = Store.shared
    @State private var kind = "spam"
    @State private var reason = ""

    var body: some View {
        NavigationStack {
            Form {
                Section { Text("Жалобу увидит только администрация. Автор не узнает, кто её отправил.").foregroundStyle(muted) }
                Section("Что не так") {
                    Picker("Что не так", selection: $kind) {
                        ForEach(reportKinds, id: \.0) { v, label in Text(label).tag(v) }
                    }
                    .pickerStyle(.inline).labelsHidden()
                }
                Section("Подробнее, необязательно") { TextField("", text: $reason, axis: .vertical) }
                Section {
                    Button("Отправить", role: .destructive) {
                        let body: [String: Any?] = ["messageId": m.id, "kind": kind, "reason": String(reason.trimmed.prefix(200))]
                        Task {
                            do { try await API.call("/api/reports", method: "POST", body: body); store.show("Жалоба отправлена") }
                            catch { store.show(error.localizedDescription) }
                        }
                        dismiss()
                    }
                }
            }
            .navigationTitle("Пожаловаться").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } } }
        }
    }
}

struct ReportItem: Codable, Identifiable { let id: Int64; var kind: String?; var reason: String?; var reporter: User? }
struct ReportCard: Codable, Identifiable {
    let messageId: Int64
    var chatTitle: String?
    var text: String?
    var deleted: Bool?
    var author: User?
    var reports: [ReportItem]?
    var id: Int64 { messageId }
}
