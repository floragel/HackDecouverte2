import SwiftUI

private enum OnbStep: Int { case welcome, university, about, location, signIn }

struct OnboardingFlow: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.palette) private var c
    @State private var step: OnbStep = .welcome
    @State private var forward = true
    @State private var universities: [University] = []
    @State private var university: University?
    @State private var fullName = ""
    @State private var email = ""
    @State private var password = ""
    @State private var program: String?
    @State private var year: Int? = 1
    @State private var loading = false
    @State private var error: String?

    private var isDemo: Bool { env.repository.isDemo }
    private var aboutValid: Bool { !fullName.trimmingCharacters(in: .whitespaces).isEmpty && (isDemo || (email.contains("@") && password.count >= 6)) }

    var body: some View {
        ZStack {
            switch step {
            case .welcome: welcome.transition(slide)
            case .university: scaffold(L("onb_pick_uni"), bottom: AnyView(PrimaryButton(text: L("onb_continue"), enabled: university != nil) { go(.about) })) { uniList }.transition(slide)
            case .about: scaffold(L("onb_about_you"), bottom: AnyView(PrimaryButton(text: L("onb_continue"), enabled: aboutValid) { go(.location) })) { about }.transition(slide)
            case .location: locationStep.transition(slide)
            case .signIn: scaffold(L("signin_title"), bottom: AnyView(PrimaryButton(text: L("signin_button"), enabled: email.contains("@") && !password.isEmpty, loading: loading) { signIn() })) { signInFields }.transition(slide)
            }
        }
        .background(c.bg.ignoresSafeArea())
        .task { universities = (try? await env.repository.universities()) ?? [] }
    }

    private var slide: AnyTransition {
        .asymmetric(insertion: .move(edge: forward ? .trailing : .leading).combined(with: .opacity), removal: .move(edge: forward ? .leading : .trailing).combined(with: .opacity))
    }

    private func go(_ s: OnbStep) { forward = s.rawValue > step.rawValue || s == .signIn; error = nil; withAnimation(.easeInOut(duration: 0.32)) { step = s } }
    private func back() { go(step == .signIn ? .welcome : OnbStep(rawValue: max(0, step.rawValue - 1))!) ; forward = false }

    // MARK: steps

    private var welcome: some View {
        // The photo lives in a background so its scaledToFill size can't widen the layout.
        ZStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 0) {
                HStack { Wordmark(color: .white); Spacer(); if isDemo { DemoBadge() } }
                Spacer()
                Text(L("onb_title")).font(.display(44, "ExtraBold")).tracking(-1.4).foregroundStyle(.white)
                Text(L("onb_subtitle")).lyncText(.bodyLarge, .white.opacity(0.78)).padding(.top, 14)
                PrimaryButton(text: L("onb_start"), light: true) { go(.university) }.padding(.top, 28)
                Button { go(.signIn) } label: { Text(L("onb_signin")).lyncText(.titleSmall, .white.opacity(0.85)).frame(maxWidth: .infinity).frame(height: 48) }
                    .padding(.top, 6)
            }
            .padding(.horizontal, 24).padding(.vertical, 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            ZStack {
                Color.clear.overlay { Image("OnboardingHero").resizable().scaledToFill() }.clipped()
                LinearGradient(stops: [.init(color: .clear, location: 0.35), .init(color: Tokens.night.opacity(0.55), location: 0.62), .init(color: Tokens.night.opacity(0.96), location: 1)],
                               startPoint: .top, endPoint: .bottom)
            }
            .ignoresSafeArea()
        }
    }

    private func scaffold<Content: View>(_ title: String, bottom: AnyView, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { back() } label: { Image(systemName: "arrow.left").font(.system(size: 20, weight: .semibold)).foregroundStyle(c.text).frame(width: 44, height: 44, alignment: .leading) }
                .padding(.top, 8)
            Text(title).lyncText(.displaySmall).padding(.top, 12).padding(.bottom, 24)
            ScrollView(showsIndicators: false) { content().frame(maxWidth: .infinity, alignment: .leading) }
            bottom.padding(.bottom, 16)
        }
        .padding(.horizontal, 24)
    }

    private var uniList: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(universities) { u in
                let on = university?.id == u.id
                let color = Color(hexString: u.primaryColor) ?? c.accent
                HStack(spacing: 14) {
                    RoundedRectangle(cornerRadius: 12).fill(LinearGradient(colors: [color, Color(hexString: u.secondaryColor) ?? color], startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: 44, height: 44).overlay(Text(String(u.shortName.prefix(1))).font(.display(21, "SemiBold")).foregroundStyle(.white))
                    VStack(alignment: .leading, spacing: 2) { Text(u.name).lyncText(.titleMedium); Text("@\(u.emailDomain)").lyncText(.bodySmall, c.textMuted) }
                    Spacer()
                    if on { Image(systemName: "checkmark").foregroundStyle(color).font(.system(size: 17, weight: .bold)) }
                }
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 18).fill(on ? color.opacity(0.08) : c.surface))
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(on ? color : .clear, lineWidth: 1.5))
                .pressable(scale: 0.98) { university = u; Haptics.tick(); env.setUniversityColors(u) }
            }
            Text(L("onb_more_soon")).lyncText(.bodySmall, c.textMuted).padding(.leading, 4).padding(.top, 6)
        }
    }

    private var about: some View {
        VStack(alignment: .leading, spacing: 12) {
            Field(hint: L("onb_full_name"), text: $fullName)
            if !isDemo {
                Field(hint: L("onb_email_any"), text: $email, keyboard: .emailAddress)
                Field(hint: L("onb_password"), text: $password, secure: true)
            }
            ProgramField(value: $program)
            Text(L("onb_year")).lyncText(.labelLarge, c.textMuted).padding(.leading, 4).padding(.top, 4)
            HStack(spacing: 8) { ForEach(1...4, id: \.self) { y in Pill(text: "\(y)", style: year == y ? .filled : .tonal) { year = y } } }
            if let error { Text(error).lyncText(.bodyMedium, .red).padding(.top, 8) }
        }
    }

    private var locationStep: some View {
        scaffold(L("onb_location_title"), bottom: AnyView(VStack(spacing: 8) {
            PrimaryButton(text: L("onb_location_allow"), loading: loading) {
                env.location.requestPermission()
                finish(.friends)
            }
            Button { finish(.off) } label: { Text(L("onb_location_skip")).lyncText(.titleSmall, c.textMuted).frame(maxWidth: .infinity).frame(height: 48) }
        })) {
            VStack(alignment: .leading, spacing: 40) {
                Text(L("onb_location_body")).lyncText(.bodyLarge, c.textMuted)
                ZStack {
                    Circle().fill(c.accentSoft).frame(width: 160, height: 160)
                    Circle().fill(c.accent.opacity(0.18)).frame(width: 84, height: 84)
                    Image(systemName: "location.fill").font(.system(size: 36)).foregroundStyle(c.accent)
                }
                .frame(maxWidth: .infinity)
            }
        }
    }

    private var signInFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            Field(hint: L("onb_email_any"), text: $email, keyboard: .emailAddress)
            Field(hint: L("onb_password"), text: $password, secure: true)
            if let error { Text(error).lyncText(.bodyMedium, .red).padding(.top, 8) }
        }
    }

    // MARK: actions

    private func finish(_ sharing: LocationSharing) {
        guard let uni = university else { return }
        loading = true
        let mail = email.isEmpty ? "\(fullName.replacingOccurrences(of: " ", with: ".").lowercased())@live.\(uni.emailDomain)" : email
        Task {
            do {
                _ = try await env.repository.signUp(SignUpForm(university: uni, fullName: fullName, email: mail, password: password, program: program ?? "", year: year))
                try? await env.repository.setLocationSharing(sharing)
            } catch {
                self.error = error.localizedDescription; loading = false; go(.about)
            }
        }
    }

    private func signIn() {
        loading = true
        Task {
            do { _ = try await env.repository.signIn(email: email, password: password) }
            catch { self.error = error.localizedDescription; loading = false }
        }
    }
}

/// Looks like a field; opens a searchable sheet with every program of the university.
struct ProgramField: View {
    @Environment(\.palette) private var c
    @Binding var value: String?
    @State private var open = false
    @State private var query = ""

    var body: some View {
        HStack {
            Text(value ?? L("onb_program")).lyncText(.bodyLarge, value == nil ? c.textMuted : c.text).lineLimit(1)
            Spacer()
            Image(systemName: "chevron.down").foregroundStyle(c.textMuted)
        }
        .padding(.horizontal, 16).padding(.vertical, 15)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(c.surface))
        .pressable(scale: 0.98) { open = true }
        .sheet(isPresented: $open) {
            VStack(alignment: .leading, spacing: 12) {
                Text(L("program_pick_title")).lyncText(.titleLarge).padding(.top, 24)
                Field(hint: L("program_search"), text: $query, icon: "magnifyingglass")
                List(ProgramsCatalog.search(query)) { p in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.name).lyncText(.titleSmall)
                            if !p.degree.isEmpty { Text(p.degree).lyncText(.bodySmall, c.textMuted) }
                        }
                        Spacer()
                        if value == p.name { Image(systemName: "checkmark").foregroundStyle(c.accent) }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { value = p.name; open = false }
                    .listRowBackground(c.bg)
                }
                .listStyle(.plain)
            }
            .padding(.horizontal, Tokens.gutter)
            .background(c.bg)
            .presentationDetents([.large])
        }
    }
}
