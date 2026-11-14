import SwiftUI
import Observation

/// Manual dependency injection — mirrors Android `AppContainer`.
@Observable
final class AppEnvironment {
    let repository: CampusRepository
    let calendar: CalendarRepository
    let location: LocationTracker

    var accentHex = Prefs.accentHex
    var accent2Hex = Prefs.accent2Hex
    var themeMode: String = UserDefaults.standard.string(forKey: "theme") ?? "system" { didSet { UserDefaults.standard.set(themeMode, forKey: "theme") } }
    var language: String = Lang.current { didSet { Lang.current = language } }

    init() {
        let repo: CampusRepository
        if SupabaseProvider.isConfigured, let client = SupabaseProvider.create() { repo = SupabaseRepository(client: client) } else { repo = DemoRepository() }
        repository = repo
        calendar = CalendarRepository(campus: repo)
        location = LocationTracker(repo: repo)
    }

    var session: SessionState { repository.session }
    var me: Profile? { repository.session.me }

    func setUniversityColors(_ u: University) {
        withAnimation(.easeInOut(duration: 0.6)) { accentHex = u.primaryColor; accent2Hex = u.secondaryColor }
        Prefs.accentHex = u.primaryColor; Prefs.accent2Hex = u.secondaryColor
    }
}

@main
struct LyncApp: App {
    @State private var env = AppEnvironment()
    var body: some Scene {
        WindowGroup { RootView().environment(env) }
    }
}
