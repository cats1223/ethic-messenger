import SwiftUI

/// Where the subscription is sold.
let plusBuyURL = URL(string: "https://playerok.com/products/c28a9d3b0559-podpiska-ethic-v-ethic-messenger")!

/// What Ethic+ gives, shown to everyone in Settings.
let plusPerks: [(String, String)] = [
    ("Градиентный ник", "Имя переливается цветом в чатах, в списке участников и в профиле."),
    ("Видео-баннер", "Вместо картинки в шапке профиля играет ваше видео."),
    ("Темы чатов", "Оформление чатов, доступное только подписчикам."),
    ("Поддержка проекта", "Ethic развивается без рекламы и без продажи ваших данных."),
]

/// The section people without Ethic+ see: what it gives, where to buy it, and the hint about the hoodie.
struct PlusOffer: View {
    let owner: Bool
    var body: some View {
        Section("Что даёт Ethic+") {
            ForEach(plusPerks, id: \.0) { title, about in
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.bold())
                    Text(about).font(.footnote).foregroundStyle(muted)
                }
                .padding(.vertical, 2)
            }
        }
        Section {
            if owner {
                Text("Вы владелец: выдайте подписку себе в списке ниже.").foregroundStyle(muted)
            } else {
                Link(destination: plusBuyURL) {
                    Text("Купить Ethic+").font(.headline).foregroundStyle(.black)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(.white, in: RoundedRectangle(cornerRadius: 14))
                }
                .listRowBackground(Color.clear).listRowInsets(EdgeInsets())
            }
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                if !owner { Text("Покупка проходит на Playerok. После оплаты подписку включает администрация Ethic.") }
                Text("Если найдёте человека с толстовкой Ethic, отсканируйте QR-код на ней и получите подарок.").foregroundStyle(.white)
            }
        }
    }
}

/// The QR code on the Ethic hoodie opens …/hoodie: a greeting page with one button.
/// Not signed in: the button leads to sign-in and the gift is given right after it. Signed in: the button gives it at once.
struct PromoOverlay: View {
    @State private var store = Store.shared
    @State private var busy = false

    var body: some View {
        Group {
            if store.promo != nil, !store.promoAccepted {
                ZStack {
                    Color.black.ignoresSafeArea()
                    VStack(spacing: 16) {
                        Image("Logo").resizable().scaledToFit().frame(width: 120, height: 120)
                        Text("Привет! Это Ethic Messenger").font(.system(size: 28, weight: .heavy)).multilineTextAlignment(.center)
                        Text("Ты отсканировал QR-код и получишь подарок: 7 дней Ethic+ бесплатно.")
                            .font(.system(size: 17)).foregroundStyle(Color(white: 0.8)).multilineTextAlignment(.center)
                        Button {
                            if store.me != nil { claim() } else { store.promoAccepted = true }
                        } label: {
                            Text(store.me != nil ? "Получить подарок" : "Войти в аккаунт и получить").font(.headline).foregroundStyle(.black)
                                .frame(maxWidth: .infinity).padding(.vertical, 15)
                                .background(.white, in: RoundedRectangle(cornerRadius: 14))
                        }
                        .disabled(busy)
                    }
                    .padding(.horizontal, 24).frame(maxWidth: 560)
                }
                .foregroundStyle(.white)
            }
        }
        // Pressed the button before signing in: give the gift as soon as the profile is there, without asking twice.
        .onChange(of: store.me?.id) { _, id in if id != nil, store.promo != nil, store.promoAccepted { claim() } }
    }

    private func claim() {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let until = try await store.claimPromo()
                if until > 0 { store.show("Подарок получен: Ethic+ активен до \(fmtUntil(until))") }
            } catch { store.show(error.localizedDescription) }
        }
    }
}
