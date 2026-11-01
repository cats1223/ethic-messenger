import PhotosUI
import SwiftUI

struct ShopGift: Decodable, Identifiable {
    let id: String
    let name: String
    let description: String
    let image: String
    let price: Int
    let total: Int?
    let left: Int?
    var owned: Bool? = nil
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

/// Someone in the gift or coin windows. `has` means this limited gift was already theirs.
struct ShopPerson: Decodable, Identifiable {
    let id: Int64
    let username: String
    let displayName: String
    var avatar: String? = nil
    var role: String? = nil
    var has: Bool? = nil
    var coins: Int? = nil
}

/// The gifts are black: a soft light behind them keeps them visible on the dark app.
private let giftGlow = RadialGradient(colors: [Color(white: 0.55), Color(white: 0.17)], center: .center, startRadius: 0, endRadius: 110)
private let endlessCoins = 999_999_999

private struct CoinMark: View {
    var body: some View {
        Text("E").font(.system(size: 12, weight: .heavy)).foregroundStyle(.black)
            .frame(width: 20, height: 20)
            .background(LinearGradient(colors: [.white, Color(white: 0.6), Color(white: 0.9)], startPoint: .topLeading, endPoint: .bottomTrailing), in: Circle())
    }
}

private struct PersonRow<Trailing: View>: View {
    let person: ShopPerson
    var detail: String? = nil
    @ViewBuilder let trailing: () -> Trailing
    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                colorOf(person.id)
                if let url = API.abs(person.avatar) {
                    AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { surface3 }
                } else {
                    Text(initials(person.displayName)).font(.caption.bold()).foregroundStyle(.white)
                }
            }
            .frame(width: 40, height: 40).clipShape(Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(person.displayName).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(detail ?? "@\(person.username)").font(.caption).foregroundStyle(muted).lineLimit(1)
            }
            Spacer()
            trailing()
        }
    }
}

struct ShopView: View {
    var to = ""
    @State private var store = Store.shared
    @State private var coins = 0
    @State private var gifts: [ShopGift] = []
    @State private var buying: ShopGift?
    @State private var giftTo: ShopGift?
    @State private var canBuild = false
    @State private var releasing = false
    @State private var granting = false

    var body: some View {
        List {
            HStack {
                Text("Ваш баланс").foregroundStyle(muted)
                Spacer()
                CoinMark()
                Text(coins >= endlessCoins ? "∞" : "\(coins)").font(.title2.weight(.heavy))
            }
            // The button appears only after the person said «Нет» to the question.
            if canBuild {
                VStack(spacing: 8) {
                    AsyncImage(url: API.abs("/gifts/scooter.png")) { $0.resizable().scaledToFill() } placeholder: { Color.black }
                        .frame(width: 200, height: 200).clipShape(RoundedRectangle(cornerRadius: 16))
                    Text("Все детали собраны").font(.headline)
                    Text("Семь подарков объединятся в один: самокат Ethic. Детали исчезнут из профиля.").font(.footnote).foregroundStyle(muted).multilineTextAlignment(.center)
                    Button("Собрать самокат") { store.scooterAsk = true }.buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                }
                .frame(maxWidth: .infinity)
            }
            Section("Подарки") {
                ForEach(gifts) { g in giftRow(g) }
            }
            if store.me?.role == "owner" {
                Section("Панель владельца") {
                    Button("Выпустить подарок") { releasing = true }
                    Button("Выдать Ethic Coin") { granting = true }
                }
            }
        }
        .navigationTitle("Магазин подарков")
        .task(id: store.shopVersion) { await load() }
        .alert(buying?.name ?? "", isPresented: Binding(get: { buying != nil }, set: { if !$0 { buying = nil } }), presenting: buying) { g in
            Button("Купить") { buy(g) }
            Button("Отмена", role: .cancel) {}
        } message: { g in Text("Купить себе за \(g.price) Ethic Coin?") }
        .sheet(item: $giftTo) { g in GiftToSheet(gift: g, first: to) { Task { await load() } } }
        .sheet(isPresented: $releasing) { ReleaseSheet { Task { await load() } } }
        .sheet(isPresented: $granting) { CoinsSheet() }
    }

    private func giftRow(_ g: ShopGift) -> some View {
        HStack(alignment: .top, spacing: 14) {
            AsyncImage(url: API.abs(g.image)) { $0.resizable().scaledToFit() } placeholder: { Color.clear }
                .padding(6).frame(width: 104, height: 104)
                .background(giftGlow, in: RoundedRectangle(cornerRadius: 14))
            VStack(alignment: .leading, spacing: 6) {
                Text(g.name).font(.headline)
                Text(g.description).font(.footnote).foregroundStyle(muted)
                Text(g.total == nil ? "Без ограничений" : g.left == 0 ? "Распродано" : "Осталось \(g.left ?? 0) из \(g.total ?? 0)").font(.footnote.bold())
                if g.owned == true { Text("Этот подарок у вас уже был, второй себе не купить.").font(.caption).foregroundStyle(muted) }
                HStack { CoinMark(); Text("\(g.price)").font(.headline.weight(.heavy)) }
                if g.left != 0 {
                    HStack {
                        if g.owned != true {
                            Button("Купить") { buying = g }.buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                        }
                        Button("Подарить") { giftTo = g }.buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    private func load() async {
        do {
            let r = try await API.call("/api/shop")
            coins = (r["coins"] as? NSNumber)?.intValue ?? 0
            canBuild = (r["canBuild"] as? NSNumber)?.boolValue ?? false
            gifts = try API.decode([ShopGift].self, r["gifts"])
        } catch { store.show(error.localizedDescription) }
    }

    private func buy(_ g: ShopGift) {
        Task {
            do {
                try await API.call("/api/shop/buy", method: "POST", body: ["giftId": g.id])
                store.show("\(g.name): готово")
                await load()
            } catch { store.show(error.localizedDescription) }
        }
    }
}

/// «Подарить»: friends who accepted the request; the owner sees everyone. Who already had this gift cannot get it.
private struct GiftToSheet: View {
    let gift: ShopGift
    let first: String
    let done: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var store = Store.shared
    @State private var people: [ShopPerson] = []
    @State private var loaded = false
    @State private var term = ""
    @State private var note = ""
    @State private var busy = false

    private var shown: [ShopPerson] {
        let t = term.trimmed.replacingOccurrences(of: "@", with: "")
        return t.isEmpty ? people : people.filter { $0.username.localizedCaseInsensitiveContains(t) || $0.displayName.localizedCaseInsensitiveContains(t) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section { TextField("Подпись, необязательно", text: $note) } footer: { Text("\(gift.price) Ethic Coin. Выберите, кому подарить.") }
                if loaded && shown.isEmpty {
                    Text(people.isEmpty ? "Пока некому дарить. Друг появится здесь, когда примет заявку." : "Никого не найдено").foregroundStyle(muted)
                }
                ForEach(shown) { p in
                    PersonRow(person: p) {
                        if p.has == true { Text("уже есть").font(.caption).foregroundStyle(muted) }
                        else { Button("Подарить") { send(p) }.buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black).disabled(busy) }
                    }
                }
            }
            .searchable(text: $term, prompt: "Поиск")
            .navigationTitle("Подарить \(gift.name)").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } } }
            .task { await load() }
        }
    }

    private func load() async {
        do {
            let r = try await API.call("/api/shop/recipients?giftId=\(gift.id)")
            let list = try API.decode([ShopPerson].self, r["users"])
            people = list.sorted { $0.username.lowercased() == first.lowercased() && $1.username.lowercased() != first.lowercased() }
            loaded = true
        } catch { store.show(error.localizedDescription); dismiss() }
    }

    private func send(_ p: ShopPerson) {
        busy = true
        Task {
            defer { busy = false }
            do {
                try await API.call("/api/shop/buy", method: "POST", body: ["giftId": gift.id, "toUsername": p.username, "note": note.trimmed])
                store.show("\(gift.name): готово")
                done(); dismiss()
            } catch { store.show(error.localizedDescription) }
        }
    }
}

private struct CoinsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var store = Store.shared
    @State private var people: [ShopPerson] = []
    @State private var term = ""
    @State private var amount = ""

    private var shown: [ShopPerson] {
        let t = term.trimmed.replacingOccurrences(of: "@", with: "")
        return t.isEmpty ? people : people.filter { $0.username.localizedCaseInsensitiveContains(t) || $0.displayName.localizedCaseInsensitiveContains(t) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("Сколько монет", text: $amount).keyboardType(.numbersAndPunctuation)
                } footer: { Text("Укажите сумму и выберите, кому выдать. С минусом монеты спишутся.") }
                ForEach(shown) { p in
                    PersonRow(person: p, detail: "@\(p.username) · \(p.coins ?? 0) монет") {
                        Button("Выдать") { grant(p) }.buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                    }
                }
            }
            .searchable(text: $term, prompt: "Поиск")
            .navigationTitle("Выдать Ethic Coin").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } } }
            .task {
                do {
                    let r = try await API.call("/api/admin/users?q=")
                    people = try API.decode([ShopPerson].self, r["users"]).filter { $0.role != "owner" }
                } catch { store.show(error.localizedDescription); dismiss() }
            }
        }
    }

    private func grant(_ p: ShopPerson) {
        guard let n = Int(amount.trimmed), n != 0 else { store.show("Укажите целое число монет"); return }
        Task {
            do {
                let r = try await API.call("/api/admin/coins", method: "POST", body: ["username": p.username, "amount": n])
                let coins = (r["coins"] as? NSNumber)?.intValue ?? 0
                if let i = people.firstIndex(where: { $0.id == p.id }) { people[i].coins = coins }
                store.show("Баланс @\(p.username): \(coins) Ethic Coin")
            } catch { store.show(error.localizedDescription) }
        }
    }
}

private struct ReleaseSheet: View {
    let done: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var store = Store.shared
    @State private var item: PhotosPickerItem?
    @State private var photo: Data?
    @State private var name = ""
    @State private var price = "500"
    @State private var limited = true
    @State private var total = "20"
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PhotosPicker(selection: $item, matching: .images) {
                        ZStack {
                            giftGlow
                            if let photo, let img = UIImage(data: photo) { Image(uiImage: img).resizable().scaledToFit().padding(6) }
                            else { Text("Выбрать фото").font(.footnote).foregroundStyle(.white) }
                        }
                        .frame(width: 128, height: 128).clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                    .frame(maxWidth: .infinity)
                }
                TextField("Название", text: $name)
                TextField("Цена в Ethic Coin", text: $price).keyboardType(.numberPad)
                Toggle("Лимитка", isOn: $limited)
                if limited { TextField("Сколько всего", text: $total).keyboardType(.numberPad) }
            }
            .navigationTitle("Выпустить подарок").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Выпустить") { release() }.disabled(busy || name.trimmed.isEmpty) }
            }
            .onChange(of: item) { _, new in
                Task { if let d = try? await new?.loadTransferable(type: Data.self) { photo = d } }
            }
        }
    }

    private func release() {
        guard let photo, let (file, _, _) = prepareImage(photo, maxSide: 1024) else { store.show("Выберите фото подарка"); return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let up = try await API.upload(file: file, kind: "image", name: "gift.jpg")
                try await API.call("/api/admin/gifts", method: "POST", body: ["name": name.trimmed, "image": up.url, "price": Int(price) ?? -1, "limited": limited, "total": Int(total) ?? 0])
                store.show("Подарок «\(name.trimmed)» выпущен")
                done(); dismiss()
            } catch { store.show(error.localizedDescription) }
        }
    }
}

/// The question when the set is complete, the note after «Нет», and the build animation after «Да». Lives above every screen.
struct ScooterPrompt: ViewModifier {
    @State private var store = Store.shared
    @State private var note = false
    @State private var built: BuiltScooter?

    func body(content: Content) -> some View {
        content
            .alert("Вы собрали все запчасти", isPresented: $store.scooterAsk) {
                Button("Нет", role: .cancel) { decline() }
                Button("Да") { build() }
            } message: { Text("Собрать самокат? Семь деталей объединятся в один подарок и исчезнут из профиля.") }
            .alert("Хорошо, детали остаются у вас", isPresented: $note) {
                Button("Понятно", role: .cancel) {}
            } message: { Text("Собрать самокат можно потом: Настройки → «Магазин подарков», кнопка «Собрать самокат».") }
            .fullScreenCover(item: $built) { b in ScooterAnimation(serial: b.serial) { built = nil } }
            .task(id: store.loggedIn) {
                guard store.loggedIn, let r = try? await API.call("/api/shop") else { return }
                if (r["offer"] as? NSNumber)?.boolValue == true { store.scooterAsk = true }
            }
    }

    private func decline() {
        Task {
            do { try await API.call("/api/shop/decline", method: "POST", body: [:]); note = true; store.shopVersion += 1 }
            catch { store.show(error.localizedDescription) }
        }
    }

    private func build() {
        Task {
            do {
                let r = try await API.call("/api/shop/build", method: "POST", body: [:])
                built = BuiltScooter(serial: (r["serial"] as? NSNumber)?.intValue ?? 0)
                store.shopVersion += 1
            } catch { store.show(error.localizedDescription) }
        }
    }
}

private struct BuiltScooter: Identifiable {
    let serial: Int
    var id: Int { serial }
}

/// The parts fly to the middle of the screen, a flash, and the scooter appears.
private struct ScooterAnimation: View {
    let serial: Int
    let done: () -> Void
    private let parts = ["pandemonium", "atlas", "dryade", "merrow", "sylphe", "rubber", "oracle"]
    @State private var gathered = false
    @State private var flash = false
    @State private var flashOut = false
    @State private var show = false

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            ZStack {
                Color.black.ignoresSafeArea()
                ForEach(parts.indices, id: \.self) { i in
                    let a = Double(i) / Double(parts.count) * 2 * .pi - .pi / 2
                    AsyncImage(url: API.abs("/gifts/\(parts[i]).png")) { $0.resizable().scaledToFit() } placeholder: { Color.clear }
                        .frame(width: side * 0.24, height: side * 0.24)
                        .scaleEffect(gathered ? 0.15 : 1)
                        .rotationEffect(.degrees(gathered ? 300 : 0))
                        .opacity(gathered ? 0 : 1)
                        .offset(x: gathered ? 0 : side * 0.38 * cos(a), y: gathered ? 0 : side * 0.38 * sin(a))
                        .animation(.easeIn(duration: 1.1).delay(0.5 + Double(i) * 0.07), value: gathered)
                }
                Circle()
                    .fill(RadialGradient(colors: [.white, .white.opacity(0.6), .clear], center: .center, startRadius: 0, endRadius: side * 0.5))
                    .frame(width: side, height: side)
                    .scaleEffect(flash ? 1.6 : 0.2)
                    .opacity(flashOut ? 0.2 : (flash ? 1 : 0))
                VStack(spacing: 10) {
                    AsyncImage(url: API.abs("/gifts/scooter.png")) { $0.resizable().scaledToFill() } placeholder: { Color.black }
                        .frame(width: side * 0.8, height: side * 0.8).clipShape(RoundedRectangle(cornerRadius: 22))
                    Text("Самокат Ethic").font(.title.weight(.heavy)).foregroundStyle(.white)
                    Text(serial > 0 ? "№\(serial) из 20" : "Вне тиража").font(.subheadline.bold()).foregroundStyle(.white)
                    Button("Отлично", action: done).buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.black)
                }
                .scaleEffect(show ? 1 : 0.6)
                .opacity(show ? 1 : 0)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .onAppear {
            gathered = true
            withAnimation(.easeOut(duration: 0.35).delay(1.75)) { flash = true }
            withAnimation(.easeOut(duration: 0.75).delay(2.1)) { flashOut = true }
            withAnimation(.spring(response: 0.6, dampingFraction: 0.7).delay(2.15)) { show = true }
        }
    }
}

/// Gifts in a profile, like Telegram: a grid with a ribbon on limited gifts; a tap opens a separate window.
struct ProfileGifts: View {
    let userId: Int64
    @State private var store = Store.shared
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
        .task(id: "\(userId)-\(store.shopVersion)") {
            if let r = try? await API.call("/api/users/\(userId)/gifts"), let list = try? API.decode([OwnedGift].self, r["gifts"]) { gifts = list }
        }
        .sheet(item: $open) { g in
            GiftDetail(gift: g) { open = nil }.presentationDetents([.medium, .large])
        }
    }

    private func cell(_ g: OwnedGift) -> some View {
        ZStack(alignment: .topTrailing) {
            if g.giftId == "scooter" {
                Color.black
                AsyncImage(url: API.abs(g.image)) { $0.resizable().scaledToFill() } placeholder: { Color.clear }
            } else {
                giftGlow
                AsyncImage(url: API.abs(g.image)) { $0.resizable().scaledToFit() } placeholder: { Color.clear }.padding(14)
            }
            if let total = g.total, g.serial > 0 {
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
                if gift.giftId == "scooter" {
                    AsyncImage(url: API.abs(gift.image)) { $0.resizable().scaledToFill() } placeholder: { Color.black }
                        .frame(width: 170, height: 170).clipShape(RoundedRectangle(cornerRadius: 18))
                } else {
                    AsyncImage(url: API.abs(gift.image)) { $0.resizable().scaledToFit() } placeholder: { Color.clear }
                        .padding(10).frame(width: 170, height: 170)
                        .background(giftGlow, in: RoundedRectangle(cornerRadius: 18))
                }
                Text(gift.name).font(.title3.bold())
                if gift.serial < 0 { Text("Вне тиража").font(.footnote.bold()) }
                else if let total = gift.total { Text("№\(gift.serial) из \(total)").font(.footnote.bold()) }
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
