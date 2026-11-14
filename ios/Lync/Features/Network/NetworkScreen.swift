import SwiftUI

struct NetworkScreen: View {
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var tab = 0

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: L("net_title")) { CircleIconButton(icon: "qrcode.viewfinder", filled: true) { router.scan() } }
            Segmented(options: [L("net_tab_people"), L("net_tab_events"), L("net_tab_groups")], selected: $tab)
                .padding(.horizontal, Tokens.gutter).padding(.vertical, 6)
            Group {
                switch tab {
                case 1: EventsTab()
                case 2: GroupsTab()
                default: PeopleTab()
                }
            }
            .transition(.opacity)
        }
        .background(c.bg)
    }
}

@Observable
final class PeopleModel {
    var requests: [Person] = []
    var classmates: [Person] = []
    var suggestions: [Person] = []
    var clubs: [Organization] = []
    var followingClubs: Set<String> = []
    var results: [Person] = []

    func load(_ repo: CampusRepository) async {
        async let r = repo.requests(); async let cm = repo.classmates(); async let s = repo.suggestions(); async let cl = repo.clubs()
        requests = (try? await r) ?? []; classmates = (try? await cm) ?? []; suggestions = (try? await s) ?? []; clubs = (try? await cl) ?? []
    }

    func patch(_ id: String, _ f: (inout Person) -> Void) {
        for i in classmates.indices where classmates[i].id == id { f(&classmates[i]) }
        for i in suggestions.indices where suggestions[i].id == id { f(&suggestions[i]) }
        for i in results.indices where results[i].id == id { f(&results[i]) }
    }
}

struct PeopleTab: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var model = PeopleModel()
    @State private var query = ""

    var body: some View {
        VStack(spacing: 0) {
            Field(hint: L("net_search"), text: $query, icon: "magnifyingglass").padding(.horizontal, Tokens.gutter).padding(.vertical, 4)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                        ForEach(model.results) { p in PersonRow(person: p, onConnect: { connect(p) }, onOpen: { router.user(p.id) }) }
                    } else {
                        if !model.requests.isEmpty {
                            SectionHeader(title: L("net_requests")) {
                                Text("\(model.requests.count)").lyncText(.labelMedium, c.onAccent).padding(.horizontal, 8).padding(.vertical, 2).background(Capsule().fill(c.accent))
                            }
                            ForEach(model.requests) { p in RequestRow(person: p, onAccept: { respond(p, true) }, onDecline: { respond(p, false) }, onOpen: { router.user(p.id) }) }
                        }
                        if !model.classmates.isEmpty {
                            SectionHeader(title: L("net_in_your_classes"))
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 10) { ForEach(model.classmates) { p in ClassmateCard(person: p, onOpen: { router.user(p.id) }, onConnect: { connect(p) }) } }
                                    .padding(.horizontal, Tokens.gutter)
                            }
                        }
                        if !model.clubs.isEmpty {
                            SectionHeader(title: L("net_clubs"))
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 10) { ForEach(model.clubs) { o in ClubChip(org: o, following: model.followingClubs.contains(o.id)) { toggleClub(o) } } }
                                    .padding(.horizontal, Tokens.gutter)
                            }
                        }
                        if !model.suggestions.isEmpty {
                            SectionHeader(title: L("net_suggested"))
                            ForEach(model.suggestions) { p in PersonRow(person: p, onConnect: { connect(p) }, onOpen: { router.user(p.id) }) }
                        }
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .task { await model.load(env.repository) }
        .task(id: query) {
            try? await Task.sleep(nanoseconds: 250_000_000) // debounce
            model.results = (try? await env.repository.search(query)) ?? []
        }
    }

    private func connect(_ p: Person) {
        Haptics.confirm()
        withAnimation { model.patch(p.id) { $0.connection = .outgoing } }
        Task { try? await env.repository.connect(p.id) }
    }
    private func respond(_ p: Person, _ accept: Bool) {
        withAnimation { model.requests.removeAll { $0.id == p.id } }
        Task { try? await env.repository.respond(p.id, accept: accept) }
    }
    private func toggleClub(_ o: Organization) {
        let follow = !model.followingClubs.contains(o.id)
        if follow { model.followingClubs.insert(o.id) } else { model.followingClubs.remove(o.id) }
        Task { try? await env.repository.setFollowing(o.id, follow) }
    }
}

struct ConnectButton: View {
    let state: ConnectionState
    var onConnect: () -> Void
    var body: some View {
        switch state {
        case .none, .incoming: Pill(text: L("net_connect"), action: onConnect)
        case .outgoing: Pill(text: L("net_pending"), style: .tonal) {}
        case .connected: Pill(text: L("net_connected"), style: .outline) {}
        }
    }
}

struct PersonRow: View {
    @Environment(\.palette) private var c
    let person: Person
    var onConnect: () -> Void
    var onOpen: () -> Void
    var body: some View {
        HStack(spacing: 12) {
            Avatar(url: person.profile.avatarUrl, initials: person.profile.initials, size: 48)
            VStack(alignment: .leading, spacing: 1) {
                Text(person.profile.fullName).lyncText(.titleSmall).lineLimit(1)
                Text(programLine(person.profile)).lyncText(.bodySmall, c.textMuted).lineLimit(1)
                if person.mutuals > 0 { Text(L("net_mutuals", person.mutuals)).lyncText(.bodySmall, c.textMuted) }
            }
            Spacer()
            ConnectButton(state: person.connection, onConnect: onConnect)
        }
        .padding(.horizontal, Tokens.gutter).padding(.vertical, 8)
        .contentShape(Rectangle()).onTapGesture(perform: onOpen)
    }
}

struct RequestRow: View {
    @Environment(\.palette) private var c
    let person: Person
    var onAccept: () -> Void
    var onDecline: () -> Void
    var onOpen: () -> Void
    var body: some View {
        HStack(spacing: 12) {
            Avatar(url: person.profile.avatarUrl, initials: person.profile.initials, size: 48)
            VStack(alignment: .leading, spacing: 1) {
                Text(person.profile.fullName).lyncText(.titleSmall).lineLimit(1)
                Text(programLine(person.profile)).lyncText(.bodySmall, c.textMuted).lineLimit(1)
            }
            Spacer()
            Pill(text: L("net_accept"), action: onAccept)
            CircleIconButton(icon: "xmark", size: 36, action: onDecline)
        }
        .padding(.horizontal, Tokens.gutter).padding(.vertical, 8)
        .contentShape(Rectangle()).onTapGesture(perform: onOpen)
    }
}

struct ClassmateCard: View {
    @Environment(\.palette) private var c
    let person: Person
    var onOpen: () -> Void
    var onConnect: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            Avatar(url: person.profile.avatarUrl, initials: person.profile.initials, size: 64)
            Text(person.profile.fullName).lyncText(.titleSmall).lineLimit(1).padding(.top, 10)
            Text(person.profile.program ?? "").lyncText(.bodySmall, c.textMuted).lineLimit(1)
            Text(L("net_shared_courses", person.sharedCourses)).lyncText(.labelMedium, c.accent)
                .padding(.horizontal, 10).padding(.vertical, 4).background(Capsule().fill(c.accentSoft)).padding(.top, 8)
            ConnectButton(state: person.connection, onConnect: onConnect).padding(.top, 12)
        }
        .padding(14).frame(width: 160)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous).fill(c.surface))
        .onTapGesture(perform: onOpen)
    }
}

struct ClubChip: View {
    @Environment(\.palette) private var c
    let org: Organization
    let following: Bool
    var onToggle: () -> Void
    var body: some View {
        HStack(spacing: 10) {
            Avatar(url: org.avatarUrl, initials: String(org.name.prefix(2)).uppercased(), size: 36)
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 3) {
                    Text(org.name).lyncText(.titleSmall).lineLimit(1)
                    if org.verified { Image(systemName: "checkmark.seal.fill").font(.system(size: 11)).foregroundStyle(c.accent) }
                }
                Text("@\(org.handle)").lyncText(.bodySmall, c.textMuted)
            }
            Pill(text: L(following ? "net_following" : "net_follow"), style: following ? .outline : .filled, vertical: 7, action: onToggle)
        }
        .padding(6).background(Capsule().fill(c.surface))
    }
}
