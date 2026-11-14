import SwiftUI
import Observation

enum Tab: String, CaseIterable { case home, network, calendar, map, profile
    var label: String { L("tab_\(rawValue)") }
    var icon: String { switch self { case .home: "house"; case .network: "person.2"; case .calendar: "calendar"; case .map: "map"; case .profile: "person" } }
}

enum Route: Hashable { case user(String), event(String), group(String), editProfile, settings }
enum Cover: Identifiable, Hashable { case compose(story: Bool), stories(String), scan
    var id: String { switch self { case .compose(let s): "compose\(s)"; case .stories(let id): "stories\(id)"; case .scan: "scan" } }
}

/// Screen-to-screen navigation (Android `Navigator`).
@Observable
final class Router {
    var tab: Tab = .home
    var path: [Route] = []
    var cover: Cover?
    func user(_ id: String) { path.append(.user(id)) }
    func event(_ id: String) { path.append(.event(id)) }
    func group(_ id: String) { path.append(.group(id)) }
    func editProfile() { path.append(.editProfile) }
    func settings() { path.append(.settings) }
    func compose(story: Bool = false) { cover = .compose(story: story) }
    func stories(_ profileId: String) { cover = .stories(profileId) }
    func scan() { cover = .scan }
    func map() { path = []; tab = .map }
    func calendar() { path = []; tab = .calendar }
    func back() { if cover != nil { cover = nil } else if !path.isEmpty { path.removeLast() } }
}

struct RootView: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.colorScheme) private var systemScheme
    @State private var router = Router()
    @State private var ready = false
    @State private var splashDone = false

    private var dark: Bool { env.themeMode == "dark" ? true : env.themeMode == "light" ? false : systemScheme == .dark }

    var body: some View {
        let palette = Palette.make(accentHex: env.accentHex, accent2Hex: env.accent2Hex, dark: dark)
        GeometryReader { geo in
            let landscape = geo.size.width > geo.size.height
            ZStack {
                palette.bg.ignoresSafeArea()
                if ready && splashDone {
                    if let me = env.me {
                        MainView().environment(router)
                        if landscape { StudentCardScreen(me: me).transition(.opacity.combined(with: .scale(scale: 0.96))).zIndex(2) }
                    } else {
                        OnboardingFlow().transition(.opacity)
                    }
                }
                if !splashDone { SplashView { withAnimation(.easeOut(duration: 0.25)) { splashDone = true } }.zIndex(3) }
            }
            .animation(.easeInOut(duration: 0.3), value: landscape)
            .animation(.easeInOut(duration: 0.3), value: env.me?.id)
        }
        .environment(\.palette, palette)
        .preferredColorScheme(env.themeMode == "system" ? nil : (env.themeMode == "dark" ? .dark : .light))
        .id(env.language) // language switch re-renders everything
        .task {
            await env.repository.restore()
            ready = true
        }
        // live location sharing follows the user's setting
        .onChange(of: env.me?.locationSharing, initial: true) { _, _ in syncSharing() }
        .onChange(of: env.me?.id) { _, _ in syncSharing(); retint() }
        .onReceive(NotificationCenter.default.publisher(for: .lyncLocationPermissionChanged)) { _ in syncSharing() }
    }

    private func syncSharing() { env.location.sync(sharingOn: env.me != nil && env.me?.locationSharing != .off) }

    private func retint() {
        guard let id = env.me?.universityId else { return }
        Task { if let u = try? await env.repository.university(id) { env.setUniversityColors(u) } }
    }
}

struct MainView: View {
    @Environment(\.palette) private var c
    @Environment(Router.self) private var router

    var body: some View {
        @Bindable var router = router
        NavigationStack(path: $router.path) {
            VStack(spacing: 0) {
                ZStack {
                    switch router.tab {
                    case .home: FeedScreen()
                    case .network: NetworkScreen()
                    case .calendar: CalendarScreen()
                    case .map: MapScreen()
                    case .profile: ProfileScreen()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                BottomBar(selected: $router.tab)
            }
            .background(c.bg)
            .ignoresSafeArea(.keyboard) // the tab bar stays put under the keyboard, like native tab apps
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Route.self) { route in
                Group {
                    switch route {
                    case .user(let id): UserProfileScreen(id: id)
                    case .event(let id): EventDetailScreen(id: id)
                    case .group(let id): GroupScreen(id: id)
                    case .editProfile: EditProfileScreen()
                    case .settings: SettingsScreen()
                    }
                }
                .toolbar(.hidden, for: .navigationBar)
            }
        }
        .fullScreenCover(item: $router.cover) { cover in
            Group {
                switch cover {
                case .compose(let story): ComposeScreen(storyMode: story)
                case .stories(let id): StoriesScreen(startProfileId: id)
                case .scan: ScannerScreen()
                }
            }
            .environment(router)
        }
    }
}

struct BottomBar: View {
    @Environment(\.palette) private var c
    @Binding var selected: Tab
    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(c.border).frame(height: 0.5)
            HStack {
                ForEach(Tab.allCases, id: \.self) { tab in
                    let on = tab == selected
                    VStack(spacing: 3) {
                        Image(systemName: on ? tab.icon + ".fill" : tab.icon).font(.system(size: 21, weight: on ? .semibold : .regular)).frame(height: 26)
                        Text(tab.label).font(TS.labelSmall.font).lineLimit(1).minimumScaleFactor(0.8)
                        Capsule().fill(c.accent).frame(width: on ? 16 : 0, height: 4)
                    }
                    .foregroundStyle(on ? c.text : c.textMuted)
                    .frame(maxWidth: .infinity).contentShape(Rectangle())
                    .onTapGesture { if !on { Haptics.tick() }; withAnimation(Motion.standard) { selected = tab } }
                }
            }
            .frame(height: 60)
        }
        .background(c.bg)
    }
}
