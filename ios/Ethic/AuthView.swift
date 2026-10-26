import SwiftUI

private enum AuthMode { case login, register, reset }

struct Field: View {
    let title: String
    @Binding var text: String
    var secure = false
    var keyboard: UIKeyboardType = .default
    var body: some View {
        Group {
            if secure { SecureField(title, text: $text) } else { TextField(title, text: $text).keyboardType(keyboard) }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .padding(.horizontal, 16).frame(height: 52)
        .background(surface2, in: RoundedRectangle(cornerRadius: 14))
    }
}

/// "Получить код" with a one-minute cooldown.
struct CodeButton: View {
    var enabled = true
    let request: () async -> Bool
    @State private var left = 0
    @State private var busy = false
    var body: some View {
        Button {
            Task {
                busy = true
                if await request() {
                    left = 60
                    while left > 0 { try? await Task.sleep(nanoseconds: 1_000_000_000); left -= 1 }
                }
                busy = false
            }
        } label: {
            Text(left > 0 ? "Ещё раз (\(left))" : "Получить код").font(.subheadline.weight(.semibold))
                .padding(.horizontal, 14).frame(height: 52)
        }
        .glass(RoundedRectangle(cornerRadius: 14))
        .disabled(!enabled || left > 0 || busy)
    }
}

struct AuthView: View {
    @State private var mode = AuthMode.login
    @State private var login = ""
    @State private var name = ""
    @State private var email = ""
    @State private var code = ""
    @State private var password = ""
    @State private var invite = ""
    @State private var error = ""
    @State private var hint = ""
    @State private var busy = false
    private var store: Store { Store.shared }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                Image("Logo").resizable().scaledToFit().frame(width: 110, height: 110).padding(.top, 30)
                Text(mode == .login ? "С возвращением!" : mode == .register ? "Создать аккаунт" : "Сброс пароля")
                    .font(.largeTitle.weight(.heavy))
                Text(mode == .login ? "Рады видеть вас снова в Ethic" : mode == .register ? "Добро пожаловать в Ethic" : "Пришлём код на почту, привязанную к аккаунту")
                    .foregroundStyle(muted).multilineTextAlignment(.center)
                    .padding(.bottom, 8)
                switch mode {
                case .login:
                    Field(title: store.emailEnabled ? "Логин или почта" : "Логин", text: $login)
                    Field(title: "Пароль", text: $password, secure: true)
                    if store.emailEnabled {
                        Button("Забыли пароль?") { mode = .reset; error = ""; hint = "" }
                            .font(.subheadline).foregroundStyle(muted).frame(maxWidth: .infinity, alignment: .trailing)
                    }
                case .register:
                    Field(title: "Логин", text: $login)
                    Field(title: "Отображаемое имя", text: $name)
                    if store.emailEnabled {
                        HStack {
                            Field(title: "Почта", text: $email, keyboard: .emailAddress)
                            CodeButton(enabled: !email.isEmpty) {
                                do { try await API.call("/api/register/code", method: "POST", body: ["email": email.trimmed]); hint = "Код отправлен на \(email.trimmed)"; return true }
                                catch { self.error = error.localizedDescription; return false }
                            }
                        }
                        Field(title: "Код из письма", text: $code, keyboard: .numberPad)
                    }
                    Field(title: "Пароль (от 6 символов)", text: $password, secure: true)
                    if store.inviteRequired { Field(title: "Код приглашения", text: $invite) }
                case .reset:
                    HStack {
                        Field(title: "Логин или почта", text: $login)
                        CodeButton(enabled: !login.isEmpty) {
                            do {
                                let r = try await API.call("/api/password/forgot", method: "POST", body: ["login": login.trimmed])
                                hint = "Код отправлен на \(r["email"] as? String ?? "почту")"; return true
                            } catch { self.error = error.localizedDescription; return false }
                        }
                    }
                    Field(title: "Код из письма", text: $code, keyboard: .numberPad)
                    Field(title: "Новый пароль", text: $password, secure: true)
                }
                if !hint.isEmpty { Text(hint).font(.footnote).foregroundStyle(muted) }
                if !error.isEmpty { Text(error).font(.subheadline).foregroundStyle(red).multilineTextAlignment(.center) }
                Button(action: submit) {
                    Group {
                        if busy { ProgressView().tint(.black) } else {
                            Text(mode == .login ? "Войти" : mode == .register ? "Создать аккаунт" : "Сменить пароль и войти").font(.headline)
                        }
                    }
                    .frame(maxWidth: .infinity).frame(height: 54)
                    .background(.white, in: RoundedRectangle(cornerRadius: 16)).foregroundStyle(.black)
                }
                .disabled(busy)
                Button(mode == .login ? "Нет аккаунта? Зарегистрироваться" : mode == .register ? "Уже есть аккаунт? Войти" : "Назад ко входу") {
                    mode = mode == .login ? .register : .login
                    error = ""; hint = ""; code = ""
                }
                .foregroundStyle(.white).padding(.top, 4)
            }
            .padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
        .task { await store.loadConfig() }
    }

    private func submit() {
        error = ""; busy = true
        Task {
            do {
                switch mode {
                case .login: try await store.auth("/api/login", ["username": login.trimmed, "password": password])
                case .register: try await store.auth("/api/register", ["username": login.trimmed, "displayName": name.trimmed, "password": password,
                                                                       "invite": invite.trimmed, "email": email.trimmed, "emailCode": code])
                case .reset: try await store.auth("/api/password/reset", ["login": login.trimmed, "code": code, "password": password])
                }
            } catch { self.error = error.localizedDescription }
            busy = false
        }
    }
}

extension String { var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) } }
