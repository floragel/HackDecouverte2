import SwiftUI

struct SettingsScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var notifications = Prefs.notifications

    var body: some View {
        let sharing = env.me?.locationSharing ?? .friends
        VStack(spacing: 0) {
            HStack(spacing: 12) { CircleIconButton(icon: "arrow.left") { router.back() }; Text(L("profile_settings")).lyncText(.titleLarge); Spacer() }
                .padding(.horizontal, 12).frame(height: 60)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    section("settings_account")
                    group {
                        navRow(L("profile_edit")) { router.editProfile() }
                        divider
                        ShareLink(item: L("settings_invite_text")) { rowLabel(L("settings_invite"), chevron: true) }
                    }
                    section("settings_location")
                    group {
                        choice(L("settings_loc_friends"), sharing == .friends) { set(.friends) }
                        divider
                        choice(L("settings_loc_classmates"), sharing == .friendsAndClassmates) { set(.friendsAndClassmates) }
                        divider
                        choice(L("map_ghost"), sharing == .off, subtitle: L("profile_ghost_desc")) { set(.off) }
                    }
                    section("settings_appearance")
                    group {
                        ForEach(Array([("system", "settings_theme_system"), ("light", "settings_theme_light"), ("dark", "settings_theme_dark")].enumerated()), id: \.offset) { i, item in
                            if i > 0 { divider }
                            choice(L(item.1), env.themeMode == item.0) { env.themeMode = item.0 }
                        }
                    }
                    section("profile_language")
                    group {
                        choice("English", env.language != "fr") { env.language = "en" }
                        divider
                        choice("Français", env.language == "fr") { env.language = "fr" }
                    }
                    section("settings_notifications")
                    group {
                        Toggle(isOn: $notifications) {
                            VStack(alignment: .leading, spacing: 1) { Text(L("settings_notif_title")).lyncText(.titleSmall); Text(L("settings_notif_desc")).lyncText(.bodySmall, c.textMuted) }
                        }
                        .tint(c.text).padding(.horizontal, 16).padding(.vertical, 10)
                        .onChange(of: notifications) { _, v in Prefs.notifications = v }
                    }
                    section("settings_developer")
                    group { navRow(L("settings_indoor_test")) { router.indoorDev() } }
                    Button { Task { await env.repository.signOut() } } label: {
                        Text(L("profile_sign_out")).lyncText(.titleSmall, .red).frame(maxWidth: .infinity, alignment: .leading).padding(16)
                            .background(RoundedRectangle(cornerRadius: Tokens.radiusControl).fill(c.surface))
                    }
                    .padding(.top, 24)
                    Text("Lync \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")" + (env.repository.isDemo ? " (\(L("demo_mode")))" : ""))
                        .lyncText(.bodySmall, c.textMuted).frame(maxWidth: .infinity).padding(.vertical, 16)
                }
                .padding(.horizontal, Tokens.gutter).padding(.bottom, 32)
            }
        }
        .background(c.bg)
    }

    private func set(_ mode: LocationSharing) { Haptics.tick(); Task { try? await env.repository.setLocationSharing(mode) } }

    private func section(_ key: String) -> some View { Text(L(key)).lyncText(.titleSmall, c.textMuted).padding(.top, 22).padding(.bottom, 8).padding(.leading, 4) }

    private func group<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }.background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(c.surface))
    }

    private var divider: some View { Rectangle().fill(c.border).frame(height: 0.5).padding(.leading, 16) }

    private func rowLabel(_ title: String, chevron: Bool) -> some View {
        HStack { Text(title).lyncText(.titleSmall); Spacer(); if chevron { Image(systemName: "chevron.right").foregroundStyle(c.textMuted) } }
            .padding(.horizontal, 16).padding(.vertical, 15).contentShape(Rectangle())
    }

    private func navRow(_ title: String, _ action: @escaping () -> Void) -> some View { rowLabel(title, chevron: true).onTapGesture(perform: action) }

    private func choice(_ title: String, _ selected: Bool, subtitle: String? = nil, _ action: @escaping () -> Void) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 1) { Text(title).lyncText(.titleSmall); if let subtitle { Text(subtitle).lyncText(.bodySmall, c.textMuted) } }
            Spacer()
            ZStack {
                Circle().fill(selected ? c.text : c.border).frame(width: 22, height: 22)
                if selected { Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundStyle(c.bg) }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 14).contentShape(Rectangle()).onTapGesture(perform: action)
    }
}
