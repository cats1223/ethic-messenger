import SwiftUI

let amber = Color(red: 0.94, green: 0.70, blue: 0.20)

/// Banned, deleted or warned: a window over everything with the reason.
struct NoticeOverlay: View {
    @State private var store = Store.shared

    var body: some View {
        if let e = store.ended {
            NoticeCard(icon: "nosign", tint: red, title: e.deleted ? "Аккаунт удалён" : "Аккаунт заблокирован", reason: e.reason,
                       note: e.until.map { "Блокировка до \(fmtUntil($0))" },
                       button: "Выйти из аккаунта", buttonColor: red, buttonText: .white) { store.leaveEnded() }
        } else if let w = store.warnings.first {
            NoticeCard(icon: "exclamationmark.triangle.fill", tint: amber, title: "Вам выдано предупреждение", reason: w.reason, note: leftText(w.left ?? 0),
                       button: "Понятно", buttonColor: .white, buttonText: .black) { store.warningSeen() }
        }
    }

    private func leftText(_ n: Int) -> String? {
        n <= 0 ? nil : "Ещё \(n) \(n == 1 ? "предупреждение" : "предупреждения") — и аккаунт будет заблокирован"
    }
}

private struct NoticeCard: View {
    let icon: String
    let tint: Color
    let title: String
    let reason: String?
    let note: String?
    let button: String
    let buttonColor: Color
    let buttonText: Color
    let action: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.92).ignoresSafeArea()
            VStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 28, weight: .semibold)).foregroundStyle(tint)
                    .frame(width: 56, height: 56).background(tint.opacity(0.15), in: Circle())
                Text(title).font(.title3.bold()).foregroundStyle(.white).multilineTextAlignment(.center)
                Text(reason.map { "Причина: \($0)" } ?? "Причина не указана").foregroundStyle(muted).multilineTextAlignment(.center)
                if let note { Text(note).font(.subheadline.weight(.semibold)).foregroundStyle(amber).multilineTextAlignment(.center) }
                Button(action: action) {
                    Text(button).bold().foregroundStyle(buttonText).frame(maxWidth: .infinity, minHeight: 50)
                        .background(buttonColor, in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain).padding(.top, 4)
            }
            .padding(24).background(surface2, in: RoundedRectangle(cornerRadius: 20)).padding(24)
        }
    }
}
