import SwiftUI
import MapKit

/// Snap-style friend map (Android MapScreen, MapLibre → MapKit).
/// Friends glide to their new position, ghost mode hides you, room search flies to the building,
/// "Who's here?" groups friends by Concordia building.
struct MapScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.palette) private var c
    @Environment(Router.self) private var router
    @State private var directory: [PlaceSuggestion] = []
    @State private var camera: MapCameraPosition = .region(MKCoordinateRegion(center: .init(latitude: 45.4965, longitude: -73.5785), latitudinalMeters: 900, longitudinalMeters: 900))
    @State private var pins: [MapPin] = []
    @State private var showFriends = true
    @State private var showClassmates = true
    @State private var selected: String?
    @State private var query = ""
    @State private var centered = false
    @FocusState private var searching: Bool

    private var tracker: LocationTracker { env.location }
    private var ghost: Bool { env.me?.locationSharing == .off }
    private var visible: [MapPin] { pins.filter { ($0.kind == .friend && showFriends) || ($0.kind == .classmate && showClassmates) } }
    private var myPos: (lat: Double, lng: Double)? { tracker.position }

    var body: some View {
        ZStack {
            map
            VStack(spacing: 10) {
                topControls
                Spacer()
                if !searching { bottomControls.transition(.opacity) }
            }
            .padding(.bottom, 12)
            .animation(Motion.standard, value: searching)
        }
        .ignoresSafeArea(.keyboard)
        .background(c.surface)
        .task { if directory.isEmpty { directory = await Task.detached(priority: .utility) { CampusDirectory.rooms() }.value } }
        .task {
            for await list in env.repository.mapPins() { withAnimation(.easeInOut(duration: 1.2)) { pins = list } }
        }
        .onAppear { tracker.acquire("map") }
        .onDisappear { tracker.release("map") }
        .onChange(of: tracker.hasPermission) { _, ok in if ok { tracker.acquire("map") } }
        .onChange(of: myPos?.lat) { _, _ in
            // first fix: center on me once
            if !centered, let p = myPos { centered = true; fly(p.lat, p.lng, meters: 450) }
        }
    }

    // MARK: Map

    private var map: some View {
        Map(position: $camera, interactionModes: [.pan, .zoom]) {
            ForEach(visible) { pin in
                Annotation(pin.profile.firstName, coordinate: .init(latitude: pin.lat, longitude: pin.lng), anchor: .center) {
                    FriendPin(pin: pin, selected: selected == pin.id)
                        .pressable(scale: 0.9) { Haptics.tick(); selected = pin.id; fly(pin.lat, pin.lng) }
                }
                .annotationTitles(.hidden)
            }
            if let p = myPos {
                Annotation("", coordinate: .init(latitude: p.lat, longitude: p.lng), anchor: .center) { MeDot(ghost: ghost) }
                    .annotationTitles(.hidden)
            }
        }
        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
        .mapControls { }
        .ignoresSafeArea(edges: .top)
        .onTapGesture { searching = false }
    }

    private func fly(_ lat: Double, _ lng: Double, meters: Double = 250) {
        withAnimation(.easeInOut(duration: 0.7)) {
            camera = .region(MKCoordinateRegion(center: .init(latitude: lat, longitude: lng), latitudinalMeters: meters, longitudinalMeters: meters))
        }
    }

    // MARK: Top

    private var topControls: some View {
        VStack(spacing: 10) {
            HStack {
                Text(L("map_title")).lyncText(.titleLarge).padding(.horizontal, 18).padding(.vertical, 10).floating(c)
                Spacer()
                ghostToggle
            }
            searchBar
            // Concordia buildings + every real JMSB room -> door-to-door directions
            let results = CampusDirectory.search(query, rooms: directory)
            if !results.isEmpty {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(results) { place in
                            SearchResultRow(place: place,
                                            onOpen: { fly(place.building.lat, place.building.lng, meters: 160); searching = false },
                                            onGo: { query = ""; searching = false; router.go(place.query) })
                        }
                    }
                    .padding(.vertical, 4)
                }
                .frame(height: min(CGFloat(results.count) * 58 + 8, 300))
                .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(c.bg).shadow(color: .black.opacity(0.15), radius: 8, y: 3))
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            HStack(spacing: 8) {
                filterChip(L("map_friends"), on: showFriends, dot: c.accent) { showFriends.toggle() }
                filterChip(L("map_classmates"), on: showClassmates, dot: c.accent2) { showClassmates.toggle() }
                Spacer()
                // Indoor navigation in MB (floor plans + step tracking)
                HStack(spacing: 6) {
                    Image(systemName: "door.left.hand.open").font(.system(size: 13, weight: .semibold))
                    Text(L("indoor_open")).lyncText(.labelLarge)
                }
                .foregroundStyle(c.text)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(Capsule().fill(c.bg).shadow(color: .black.opacity(0.15), radius: 6, y: 2))
                .pressable { Haptics.tick(); router.indoor() }
            }
        }
        .padding(16)
        .animation(Motion.standard, value: query.isEmpty)
    }

    private var ghostToggle: some View {
        HStack(spacing: 6) {
            Image(systemName: ghost ? "eye.slash" : "eye").font(.system(size: 15, weight: .semibold))
            Text(L(ghost ? "map_ghost_on" : "map_ghost_off")).font(TS.labelLarge.font)
        }
        .foregroundStyle(ghost ? c.bg : c.text)
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(Capsule().fill(ghost ? c.text : c.bg).shadow(color: .black.opacity(0.18), radius: 8, y: 3))
        .pressable {
            Haptics.confirm()
            let mode: LocationSharing = ghost ? .friends : .off
            Task { try? await env.repository.setLocationSharing(mode) }
        }
        .animation(Motion.standard, value: ghost)
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").font(.system(size: 16)).foregroundStyle(c.textMuted)
            TextField("", text: $query, prompt: Text(L("map_search")).foregroundStyle(c.textMuted))
                .font(TS.bodyMedium.font).foregroundStyle(c.text).tint(c.accent).focused($searching)
                .autocorrectionDisabled().textInputAutocapitalization(.never).submitLabel(.search)
            if !query.isEmpty {
                Button { query = "" } label: { Image(systemName: "xmark.circle.fill").font(.system(size: 16)).foregroundStyle(c.textMuted) }
            }
        }
        .padding(.horizontal, 12).frame(height: 46)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(c.surface).shadow(color: .black.opacity(0.12), radius: 4, y: 2))
    }

    private func filterChip(_ label: String, on: Bool, dot: Color, _ action: @escaping () -> Void) -> some View {
        HStack(spacing: 6) {
            Circle().fill(on ? dot : c.border).frame(width: 8, height: 8)
            Text(label).lyncText(.labelLarge, on ? c.text : c.textMuted)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Capsule().fill(c.bg).shadow(color: .black.opacity(0.15), radius: 6, y: 2))
        .pressable { Haptics.tick(); withAnimation(Motion.standard) { action() } }
    }

    // MARK: Bottom

    private var bottomControls: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                if !tracker.hasPermission {
                    Text(L("map_permission")).lyncText(.labelLarge).padding(.horizontal, 16).padding(.vertical, 10).floating(c)
                        .pressable { tracker.requestPermission() }
                        .transition(.opacity)
                }
                Spacer()
                ThemeToggleButton()
                Image(systemName: "location.fill").font(.system(size: 18)).foregroundStyle(c.text)
                    .frame(width: 48, height: 48).background(Circle().fill(c.bg).shadow(color: .black.opacity(0.18), radius: 8, y: 3))
                    .pressable(scale: 0.9) {
                        Haptics.tick()
                        if let p = myPos { fly(p.lat, p.lng) } else if !tracker.hasPermission { tracker.requestPermission() } else { fly(45.4972, -73.5790, meters: 900) }
                    }
            }
            .padding(.horizontal, 16)

            let presence = Buildings.presence(visible, myPos: myPos.map { ($0.lat, $0.lng) })
            if !presence.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(presence) { bp in
                            BuildingCard(presence: bp).pressable { Haptics.tick(); fly(bp.building.lat, bp.building.lng, meters: 200) }
                        }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 6)
                }
                .transaction { $0.animation = nil } // position ticks must not animate the card row
            }
            if !visible.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(visible) { pin in
                            PersonChip(pin: pin, selected: selected == pin.id).pressable { Haptics.tick(); selected = pin.id; fly(pin.lat, pin.lng) }
                        }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 6)
                }
            }
        }
    }
}

private extension View {
    func floating(_ c: Palette) -> some View { background(Capsule().fill(c.bg).shadow(color: .black.opacity(0.18), radius: 8, y: 3)) }
}

private struct FriendPin: View {
    @Environment(\.palette) private var c
    let pin: MapPin
    let selected: Bool

    var body: some View {
        Avatar(url: pin.profile.avatarUrl, initials: pin.profile.initials, size: 44)
            .overlay(Circle().stroke(pin.kind == .friend ? c.accent : c.accent2, lineWidth: 2))
            .padding(2.5).background(Circle().fill(.white)).shadow(color: .black.opacity(0.25), radius: 6, y: 2)
            .overlay(alignment: .bottomTrailing) {
                if pin.isLive { Circle().fill(Tokens.live).frame(width: 9, height: 9).padding(2.5).background(Circle().fill(.white)).offset(x: 2, y: 2) }
            }
            .overlay(alignment: .topTrailing) {
                if let s = StatusPreset.of(pin.profile.statusKey) {
                    Text(s.emoji).font(.system(size: 11)).frame(width: 22, height: 22).background(Circle().fill(.white).shadow(color: .black.opacity(0.2), radius: 3)).offset(x: 4, y: -4)
                }
            }
            .scaleEffect(selected ? 1.18 : 1)
            .animation(Motion.bouncy, value: selected)
    }
}

private struct MeDot: View {
    @Environment(\.palette) private var c
    let ghost: Bool
    @State private var pulse = false

    var body: some View {
        let color = ghost ? c.textMuted : c.accent
        ZStack {
            if !ghost {
                Circle().fill(color.opacity(0.35)).frame(width: 28, height: 28)
                    .scaleEffect(pulse ? 1.6 : 0.6).opacity(pulse ? 0 : 0.85)
                    .animation(.easeOut(duration: 1.6).repeatForever(autoreverses: false), value: pulse)
            }
            Circle().fill(color).frame(width: 12, height: 12).padding(3).background(Circle().fill(.white)).shadow(color: .black.opacity(0.25), radius: 4)
        }
        .frame(width: 28, height: 28)
        .onAppear { pulse = true }
    }
}

private struct BuildingCard: View {
    @Environment(\.palette) private var c
    let presence: BuildingPresence

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L(presence.includesMe ? "here_you_are" : "here_title")).lyncText(.labelMedium, c.bg.opacity(0.6))
            Text(presence.building.name).lyncText(.titleMedium, c.bg).lineLimit(1)
            HStack(spacing: 8) {
                AvatarStack(people: presence.people, size: 24, max: 4, ringColor: c.text)
                Text(people).lyncText(.labelMedium, c.bg).lineLimit(1)
            }
            .padding(.top, 8)
        }
        .padding(14).frame(width: 210, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(c.text).shadow(color: .black.opacity(0.2), radius: 8, y: 3))
    }

    private var people: String {
        let p = presence.people
        switch p.count {
        case 1: return p[0].firstName
        case 2: return L("here_two", p[0].firstName, p[1].firstName)
        default: return L("here_many", p[0].firstName, p.count - 1)
        }
    }
}

private struct PersonChip: View {
    @Environment(\.palette) private var c
    let pin: MapPin
    let selected: Bool

    var body: some View {
        HStack(spacing: 8) {
            Avatar(url: pin.profile.avatarUrl, initials: pin.profile.initials, size: 36)
            VStack(alignment: .leading, spacing: 0) {
                Text(pin.profile.firstName).lyncText(.titleSmall)
                if pin.isLive { Text(L("map_live")).lyncText(.bodySmall, Color(hex: 0x1FA463)) }
                else { Text(L("map_updated", timeAgo(pin.updatedAt))).lyncText(.bodySmall, c.textMuted) }
            }
        }
        .padding(.leading, 6).padding(.trailing, 14).padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(c.bg).shadow(color: .black.opacity(0.18), radius: 8, y: 3))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(selected ? c.accent : .clear, lineWidth: 1.5))
    }
}

/// One search hit: tap the badge to fly there, tap the row or "Directions" for door-to-door guidance.
private struct SearchResultRow: View {
    @Environment(\.palette) private var c
    let place: PlaceSuggestion
    let onOpen: () -> Void
    let onGo: () -> Void
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: place.isRoom ? "door.left.hand.open" : "building.2").font(.system(size: 15)).foregroundStyle(c.accent)
                .frame(width: 36, height: 36).background(Circle().fill(c.accentSoft))
                .onTapGesture(perform: onOpen)
            VStack(alignment: .leading, spacing: 1) {
                Text(place.title).lyncText(.titleSmall).lineLimit(1)
                let sub = [place.subtitle == place.title || place.subtitle.isEmpty ? nil : place.subtitle, place.floor.map { L("indoor_floor", String($0)) }]
                    .compactMap { $0 }.joined(separator: " · ")
                if !sub.isEmpty { Text(sub).lyncText(.bodySmall, c.textMuted).lineLimit(1) }
            }
            Spacer(minLength: 8)
            HStack(spacing: 4) {
                Image(systemName: "figure.walk").font(.system(size: 13, weight: .semibold))
                Text(L("go_directions")).lyncText(.labelLarge, c.onAccent)
            }
            .foregroundStyle(c.onAccent)
            .padding(.horizontal, 12).padding(.vertical, 7).background(Capsule().fill(c.accent))
        }
        .padding(.leading, 14).padding(.trailing, 8).padding(.vertical, 8)
        .pressable(scale: 0.98, onGo)
    }
}
