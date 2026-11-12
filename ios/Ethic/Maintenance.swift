import SwiftUI

/// The messenger is closed for maintenance: a whole page instead of the app, with the owner's reason.
/// «Вход для администрации» asks for the password the owner set; the owner's own account passes without it.
struct MaintenanceOverlay: View {
    @State private var store = Store.shared
    @State private var asking = false
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    private var shown: Maintenance? {
        guard let m = store.maintenance, store.maintUnlocked != m.id, store.me?.role != "owner" else { return nil }
        // Signed in, profile still loading and nobody refused yet: wait instead of flashing the page at the owner.
        if API.token != nil && store.me == nil && !store.maintDenied { return nil }
        return m
    }

    var body: some View {
        Group {
            if let m = shown {
                ZStack {
                    Color.black.ignoresSafeArea()
                    ScrollView {
                        VStack(spacing: 16) {
                            Image("Logo").resizable().scaledToFit().frame(width: 120, height: 120)
                            Text("Здравствуйте, дорогой пользователь!").font(.system(size: 28, weight: .heavy)).multilineTextAlignment(.center)
                            Text("Ethic Messenger в данный момент не работает.").font(.system(size: 17)).foregroundStyle(Color(white: 0.8)).multilineTextAlignment(.center)
                            VStack(spacing: 8) {
                                Text("ПРИЧИНА").font(.caption).kerning(1).foregroundStyle(muted)
                                Text(m.reason.isEmpty ? "не указана" : m.reason).font(.system(size: 19, weight: .semibold)).multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity).padding(20)
                            .background(Color(white: 0.06), in: RoundedRectangle(cornerRadius: 18))
                            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.white.opacity(0.14)))
                            if !m.back.isEmpty { Text("Откроемся: \(m.back)").font(.system(size: 16, weight: .semibold)).foregroundStyle(amber) }
                        }
                        .padding(.horizontal, 24).padding(.vertical, 40).frame(maxWidth: 560)
                    }
                    .frame(maxHeight: .infinity)
                    VStack {
                        Spacer()
                        Button("Вход для администрации") { password = ""; error = nil; asking = true }
                            .font(.footnote).foregroundStyle(Color(white: 0.42)).padding(.bottom, 12)
                    }
                }
                .foregroundStyle(.white)
                .alert("Вход для администрации", isPresented: $asking) {
                    SecureField("Пароль", text: $password)
                    Button("Войти") { unlock() }
                    Button("Отмена", role: .cancel) {}
                } message: { Text(error ?? "Введите пароль, который задан при закрытии мессенджера.") }
            }
        }
        // Ask at the start and, while the page is up, again now and then, so the app comes back by itself.
        .task(id: store.maintenance != nil) {
            while !Task.isCancelled {
                await store.loadConfig()
                if store.maintenance == nil { break }
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    private func unlock() {
        guard !busy, !password.isEmpty else { return }
        busy = true
        Task {
            defer { busy = false }
            do { try await store.unlockMaintenance(password) } catch { self.error = error.localizedDescription; asking = true }
        }
    }
}
