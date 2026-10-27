import SwiftUI

@main
struct EthicApp: App {
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
                .tint(.white)
        }
        .onChange(of: phase) { _, p in
            let store = Store.shared
            if p == .active { store.onForeground() } else if p == .background { store.foreground = false }
        }
    }
}

enum Route: Hashable {
    case chat(Int64)
    case user(Int64)
    case group(Int64)
    case editProfile
    case picker(PickMode, Int64)
    case admin
}

enum PickMode: Hashable { case group, addMember, addToCall }

@MainActor
@Observable
final class Nav {
    static let shared = Nav()
    var chatsPath: [Route] = []
    var tab = 0
    var image: URL?
    var callPicker = false

    func openChat(_ id: Int64) { tab = 0; chatsPath = [.chat(id)] }
}

struct RootView: View {
    @State private var store = Store.shared
    @State private var calls = Calls.shared
    @State private var nav = Nav.shared

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if store.booting {
                Image("Logo").resizable().scaledToFit().frame(width: 120)
            } else if !store.loggedIn {
                AuthView()
            } else {
                VStack(spacing: 0) {
                    if let ui = calls.ui, ui.minimized { CallPill(ui: ui) }
                    MainTabs()
                }
            }
            if let ui = calls.ui, !ui.minimized {
                CallView(ui: ui).transition(.move(edge: .bottom)).zIndex(2)
            }
            if let ev = calls.incoming {
                IncomingView(ev: ev).transition(.opacity).zIndex(3)
            }
            if let t = store.toast {
                VStack {
                    Spacer()
                    Text(t).font(.subheadline).padding(.horizontal, 18).padding(.vertical, 12)
                        .glass().padding(.bottom, 100)
                }
                .transition(.opacity).zIndex(4).allowsHitTesting(false)
            }
        }
        .animation(.spring(duration: 0.35), value: calls.ui?.minimized)
        .animation(.easeInOut, value: calls.incoming?.chatId)
        .animation(.easeInOut, value: store.toast)
        .fullScreenCover(item: Binding(get: { nav.image.map { IdURL(url: $0) } }, set: { nav.image = $0?.url })) { ImageViewer(url: $0.url) }
        .sheet(isPresented: $nav.callPicker) { NavigationStack { PickerView(mode: .addToCall, chatId: calls.ui?.chatId ?? 0) } }
        .task { await store.boot() }
        .alert("Доступна версия \(store.update ?? "")", isPresented: Binding(get: { store.update != nil }, set: { if !$0 { store.update = nil } })) {
            Button("Открыть страницу") { if let u = URL(string: "https://github.com/cats1223/ethic-messenger/releases/latest") { UIApplication.shared.open(u) } }
            Button("Позже", role: .cancel) {}
        } message: { Text("Скачайте новый Ethic-Messenger.ipa и установите его через Sideloadly или AltStore поверх текущей версии.") }
    }
}

struct IdURL: Identifiable { let url: URL; var id: String { url.absoluteString } }

struct MainTabs: View {
    @State private var nav = Nav.shared
    @State private var store = Store.shared

    var body: some View {
        TabView(selection: $nav.tab) {
            NavigationStack(path: $nav.chatsPath) {
                ChatsView().navigationDestination(for: Route.self) { destination($0) }
            }
            .tabItem { Label("Чаты", systemImage: "bubble.left.and.bubble.right.fill") }
            .badge(store.totalUnread)
            .tag(0)
            NavigationStack {
                PeopleView().navigationDestination(for: Route.self) { destination($0) }
            }
            .tabItem { Label("Люди", systemImage: "person.2.fill") }
            .tag(1)
            NavigationStack {
                SettingsView().navigationDestination(for: Route.self) { destination($0) }
            }
            .tabItem { Label("Настройки", systemImage: "gearshape.fill") }
            .tag(2)
        }
    }

    @ViewBuilder
    private func destination(_ r: Route) -> some View {
        switch r {
        case .chat(let id): ChatView(chatId: id)
        case .user(let id): UserView(userId: id)
        case .group(let id): GroupView(chatId: id)
        case .editProfile: EditProfileView()
        case .picker(let mode, let id): PickerView(mode: mode, chatId: id)
        case .admin: AdminView()
        }
    }
}
