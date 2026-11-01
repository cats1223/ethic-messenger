import SwiftUI

struct ShopGift: Decodable, Identifiable {
    let id: String
    let name: String
    let description: String
    let image: String
    let price: Int
    let total: Int?
    let left: Int?
}

struct OwnedGift: Decodable, Identifiable {
    let id: Int64
    let giftId: String
    let name: String
    let image: String
    let serial: Int
    let total: Int?
    let fromId: Int64
    let fromName: String
    let note: String?
    let createdAt: Double
}

/// The gifts are black: a soft light behind them keeps them visible on the dark app.
private let giftGlow = RadialGradient(colors: [Color(white: 0.55), Color(white: 0.17)], center: .center, startRadius: 0, endRadius: 110)

private struct CoinMark: View {
    var body: some View {
        Text("E").font(.system(size: 12, weight: .heavy)).foregroundStyle(.black)
            .frame(width: 20, height: 20)
            .background(LinearGradient(colors: [.white, Color(white: 0.6), Color(white: 0.9)], startPoint: .topLeading, endPoint: .bottomTrailing), in: Circle())
    }
}

struct ShopView: View {
    var to = ""
    @State private var store = Store.shared
    @State private var coins = 0
    @State private var gifts: [ShopGift] = []
    @State private var recipient = ""
    @State private var note = ""
    @State private var buying: ShopGift?
    @State private var grantName = ""
    @State private var grantAmount = ""

    var body: some View {
        List {
            HStack {
                Text("Ваш баланс").foregroundStyle(muted)
                Spacer()
                CoinMark()
                Text("\(coins)").font(.title2.weight(.heavy))
            }
            Section("Кому") {
                TextField("Username получателя (пусто: себе)", text: $recipient).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Подпись, необязательно", text: $note)
            }
            Section("Подарки") {
                ForEach(gifts) { g in
                    HStack(alignment: .top, spacing: 14) {
                        AsyncImage(url: API.abs(g.image)) { $0.resizable().scaledToFit() } placeholder: { Color.clear }
                            .padding(6).frame(width: 104, height: 104)
                            .background(giftGlow, in: RoundedRectangle(cornerRadius: 14))
                        VStack(alignment: .leading, spacing: 6) {
                            Text(g.name).font(.headline)
                            Text(g.description).font(.footnote).foregroundStyle(muted)
                            Text(g.total == nil ? "Без ограничений" : g.left == 0 ? "Распродано" : "Осталось \(g.left ?? 0) из \(g.total ?? 0)")
                                .font(.footnote.bold())
                            HStack {
                                CoinMark()
                                Text("\(g.price)").font(.headline.weight(.heavy))
                                Spacer()
                                if g.left != 0 {
                                    Button("Купить") { buying = g }.buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                                }
                            }
                        }
                    }
                }
            }
            if store.me?.role == "owner" {
                Section("Выдача Ethic Coin") {
                    TextField("Username", text: $grantName).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Сколько, можно с минусом", text: $grantAmount).keyboardType(.numbersAndPunctuation)
                    Button("Выдать") { grant() }.disabled(grantName.trimmed.isEmpty || Int(grantAmount.trimmed) == nil)
                }
            }
        }
        .navigationTitle("Магазин подарков")
        .task { recipient = to; await load() }
        .alert(buying?.name ?? "", isPresented: Binding(get: { buying != nil }, set: { if !$0 { buying = nil } }), presenting: buying) { g in
            Button("Купить") { buy(g) }
            Button("Отмена", role: .cancel) {}
        } message: { g in
            Text("Купить за \(g.price) Ethic Coin \(recipient.trimmed.isEmpty ? "себе" : "для @\(recipient.trimmed)")?")
        }
    }

    private func load() async {
        do {
            let r = try await API.call("/api/shop")
            coins = (r["coins"] as? NSNumber)?.intValue ?? 0
            gifts = try API.decode([ShopGift].self, r["gifts"])
        } catch { store.show(error.localizedDescription) }
    }

    private func buy(_ g: ShopGift) {
        Task {
            do {
                let r = try await API.call("/api/shop/buy", method: "POST", body: ["giftId": g.id, "toUsername": recipient.trimmed, "note": note.trimmed])
                store.show("\(g.name) №\((r["serial"] as? NSNumber)?.intValue ?? 0): готово")
                recipient = ""; note = ""
                await load()
            } catch { store.show(error.localizedDescription) }
        }
    }

    private func grant() {
        Task {
            do {
                let r = try await API.call("/api/admin/coins", method: "POST", body: ["username": grantName.trimmed, "amount": Int(grantAmount.trimmed) ?? 0])
                store.show("Баланс: \((r["coins"] as? NSNumber)?.intValue ?? 0) Ethic Coin")
                grantName = ""; grantAmount = ""
                await load()
            } catch { store.show(error.localizedDescription) }
        }
    }
}

/// Gifts in a profile, like Telegram: a grid with a ribbon on limited gifts; a tap opens a separate window.
struct ProfileGifts: View {
    let userId: Int64
    @State private var gifts: [OwnedGift] = []
    @State private var open: OwnedGift?

    var body: some View {
        Group {
            if !gifts.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Подарки  \(gifts.count)").font(.headline)
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                        ForEach(gifts) { g in
                            Button { open = g } label: { cell(g) }.buttonStyle(.plain)
                        }
                    }
                }
                .padding()
            }
        }
        .task(id: userId) {
            if let r = try? await API.call("/api/users/\(userId)/gifts"), let list = try? API.decode([OwnedGift].self, r["gifts"]) { gifts = list }
        }
        .sheet(item: $open) { g in
            GiftDetail(gift: g) { open = nil }.presentationDetents([.medium, .large])
        }
    }

    private func cell(_ g: OwnedGift) -> some View {
        ZStack(alignment: .topTrailing) {
            giftGlow
            AsyncImage(url: API.abs(g.image)) { $0.resizable().scaledToFit() } placeholder: { Color.clear }.padding(14)
            if let total = g.total {
                Text("\(g.serial) из \(total)").font(.system(size: 10, weight: .heavy)).foregroundStyle(.black)
                    .frame(width: 88).background(Color(white: 0.9))
                    .rotationEffect(.degrees(38)).offset(x: 22, y: 14)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

private struct GiftDetail: View {
    let gift: OwnedGift
    let close: () -> Void
    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                AsyncImage(url: API.abs(gift.image)) { $0.resizable().scaledToFit() } placeholder: { Color.clear }
                    .padding(10).frame(width: 170, height: 170)
                    .background(giftGlow, in: RoundedRectangle(cornerRadius: 18))
                Text(gift.name).font(.title3.bold())
                if let total = gift.total { Text("№\(gift.serial) из \(total)").font(.footnote.bold()) }
                VStack(spacing: 8) {
                    Button {
                        close()
                        Nav.shared.tab = 0
                        Nav.shared.chatsPath.append(Route.user(gift.fromId))
                    } label: {
                        HStack { Text("От кого").foregroundStyle(muted); Spacer(); Text(gift.fromName).bold().foregroundStyle(.white) }
                    }
                    HStack { Text("Дата").foregroundStyle(muted); Spacer(); Text(dayLabel(gift.createdAt)) }
                }
                .padding(12).background(surface2, in: RoundedRectangle(cornerRadius: 12))
                if let note = gift.note {
                    Text(note).frame(maxWidth: .infinity, alignment: .leading).padding(12).background(surface2, in: RoundedRectangle(cornerRadius: 12))
                }
                Button(action: close) {
                    Text("Закрыть").font(.headline).frame(maxWidth: .infinity).frame(height: 50)
                        .background(.white, in: RoundedRectangle(cornerRadius: 14)).foregroundStyle(.black)
                }
            }
            .padding(20)
        }
    }
}
