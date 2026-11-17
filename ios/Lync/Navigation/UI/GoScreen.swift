import SwiftUI
import MapKit

/// iOS port of ui/go/GoScreen.kt: one trip, door to door.
/// Outside: walking directions on the street map (OSRM foot route, overview -> Start -> follow camera).
/// As soon as GPS puts you inside the John Molson building, the screen morphs into the floor plan:
/// your floor comes from the barometric altitude above the street, step tracking starts at the door
/// you came through and the route continues to the room. Other buildings: outdoor guidance + room as text.
struct GoScreen: View {
    let query: String
    var body: some View {
        if let place = CampusPlaces.resolve(query) { GoTrip(place: place) } else { PlaceNotFound(query: query) }
    }
}

private enum GoMode { case outdoor, indoor }
enum OutdoorCamera { case overview, follow, free }

private let insideMarginM = 3.0
private let leaveDistanceM = 30.0
private let rerouteOffRouteM = 35.0
private let rerouteMinInterval: TimeInterval = 15
private let arrivedOutdoorM = 18.0
private let indoorWalkSpeedMps = 1.2
private let farFromCampusM = 8_000.0
private let doorRadiusM = 15.0
private let gpsLostAtDoorS = 12.0

private struct GoTrip: View {
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    let place: Place
    private var backend: IndoorNavigationBackend { IndoorEngine.shared }

    @State private var mode: GoMode = .outdoor
    @State private var autoIndoor = true
    @State private var manualIndoor = false
    @State private var started = false
    @State private var camera: OutdoorCamera = .overview
    @State private var lastOutdoorM: PointM?
    @State private var insideStreak = 0
    @State private var outsideStreak = 0
    @State private var welcome: AltitudeFloorEstimate?
    @State private var askFloor = false
    @State private var pickPosition = false
    @State private var wantTracking = false
    @State private var lastOutsideByM = Double.greatestFiniteMagnitude
    @State private var route: WalkRoute?
    @State private var lastRouteAt = Date.distantPast
    @State private var indoorCamera = IndoorCamera()
    @State private var topHeight: CGFloat = 0
    @State private var bottomHeight: CGFloat = 0
    @State private var showSteps = false
    @State private var progressTracker = RouteProgress()

    // MARK: derived

    private var snap: NavigationSnapshot { backend.snapshot }
    private var fix: GpsFix? { snap.latestGpsFix }
    private var me: GeoPoint? { fix.map { GeoPoint(lat: $0.wgs84.latitude, lng: $0.wgs84.longitude) } }

    private var destKey: NodeKey? {
        guard place.hasIndoorMap, let label = place.roomLabel else { return nil }
        let k = CampusPlaces.roomKey(label)
        return snap.destinations.first { $0.type == "room" && CampusPlaces.roomKey($0.label) == k }?.key
    }
    private var destFloor: Int? { destKey.map { IndoorAssets.floorNumber($0.floorId) } ?? place.floor }

    /// Ground-floor bounding box (metres around the elevator core) for "am I inside?".
    private var groundBox: (Double, Double, Double, Double)? {
        guard let ns = snap.floors.first(where: { $0.floorId == "MB-1" })?.nodes, !ns.isEmpty else { return nil }
        return (ns.map(\.posM.x).min()!, ns.map(\.posM.y).min()!, ns.map(\.posM.x).max()!, ns.map(\.posM.y).max()!)
    }
    private func outsideBy(_ p: PointM) -> Double {
        guard let b = groundBox else { return p.norm() - 30 }
        let dx = max(b.0 - insideMarginM - p.x, 0, p.x - b.2 - insideMarginM)
        let dy = max(b.1 - insideMarginM - p.y, 0, p.y - b.3 - insideMarginM)
        return hypot(dx, dy)
    }

    /// MB: the entrance closest to you (graph exits of floor 1); other buildings: their centre.
    private var outdoorTarget: GeoPoint {
        guard place.hasIndoorMap else { return place.point }
        let doors = snap.floors.first { $0.floorId == "MB-1" }?.nodes.filter { $0.type == "exit" || $0.type == "entrance" } ?? []
        if let from = lastOutdoorM ?? fix?.eastNorthM,
           let door = doors.min(by: { $0.posM.distanceTo(from) < $1.posM.distanceTo(from) }) {
            let w = snap.geoOrigin.toWgs84(door.posM)
            return GeoPoint(lat: w.latitude, lng: w.longitude)
        }
        return place.point
    }
    private var progress: WalkProgress? { if let r = route, let me { return WalkProgress.of(r, me) }; return nil }
    private var distToTarget: Double? { me.map { Geo.distanceM($0, outdoorTarget) } }
    private var farAway: Bool { (distToTarget ?? 0) > farFromCampusM }
    private var needRoute: Bool { mode == .outdoor && me != nil && !farAway && (route == nil || (progress?.offRouteM ?? 0) > rerouteOffRouteM) }
    private var arrivedOutside: Bool {
        guard !place.hasIndoorMap, let d = distToTarget else { return false }
        return d <= max(arrivedOutdoorM, place.building.radiusM * 0.6) || (progress?.remainingM ?? .greatestFiniteMagnitude) <= arrivedOutdoorM
    }

    /// What the voice says (and when the phone vibrates): indoor route instruction, or the next street maneuver.
    private var guidance: Guidance? {
        if mode == .indoor, snap.pdr.phase == .tracking, let r = snap.activeRoute {
            return Guidance(key: "in-" + NavText.guidanceKey(r), text: NavText.instruction(r))
        }
        if mode == .outdoor, started, let p = progress, let next = p.nextStep {
            // announce each maneuver once when it comes up (≤ 40 m), and the arrival
            let near = p.distanceToNextStepM <= 40
            return Guidance(key: "out-\(next.at.lat),\(next.at.lng)-\(near)", text: near ? stepText(next) : L("go_then", stepText(next)))
        }
        return nil
    }

    // MARK: body

    var body: some View {
        ZStack {
            Group {
                switch mode {
                case .outdoor:
                    OutdoorMap(route: route, me: me, meBearingDeg: fix?.trueNorthBearingRad.map(radToDeg), destination: outdoorTarget,
                               camera: me == nil && camera == .follow ? .overview : camera, followBearingDeg: progress?.bearingDeg,
                               insets: EdgeInsets(top: topHeight + 40, leading: 32, bottom: bottomHeight + 24, trailing: 32),
                               onUserMovedMap: { if camera != .free { camera = .free } })
                        .transition(.asymmetric(insertion: .opacity.combined(with: .scale(scale: 1.25)), removal: .opacity.combined(with: .scale(scale: 0.85))))
                case .indoor:
                    IndoorCanvas(snapshot: snap, camera: indoorCamera, bottomInset: bottomHeight)
                        .transition(.asymmetric(insertion: .opacity.combined(with: .scale(scale: 0.82)), removal: .opacity.combined(with: .scale(scale: 1.35))))
                }
            }
            .ignoresSafeArea()

            VStack(spacing: 8) {
                topArea
                if mode == .indoor {
                    HStack(spacing: 8) {
                        FloorBadge(snap: snap) { askFloor = true }
                        if snap.pdr.tilted { HintChip(text: L("indoor_tilt")) }
                        else if !snap.pdr.headingValid && snap.pdr.phase != .idle { HintChip(text: L("indoor_heading_wait")) }
                        Spacer()
                    }
                }
            }
            .padding(.horizontal, 12).padding(.top, 8)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { topHeight = $0 }
            .frame(maxHeight: .infinity, alignment: .top)

            if mode == .indoor {
                FloorPicker(floors: snap.floors.map(\.floorId), active: snap.activeFloorId) { Haptics.tick(); backend.switchActiveFloor($0) }
                    .padding(.trailing, 12).padding(.top, topHeight + 16)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }

            if let w = welcome {
                WelcomeCard(place: place, estimate: w, askingFloor: askFloor)
                    .padding(12).frame(maxHeight: .infinity, alignment: .top)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }

            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Spacer()
                    if mode == .indoor, snap.activeRoute != nil { MapRoundButton(icon: "list.bullet") { Haptics.tick(); showSteps = true } }
                    if started || mode == .indoor { VoiceButton() }
                    if mode == .indoor { MapRoundButton(icon: "location.north.line.fill", active: indoorCamera.headUp) { Haptics.tick(); indoorCamera.headUp.toggle() } }
                    ThemeToggleButton()
                    MapRoundButton(icon: "location.fill", active: mode == .outdoor && camera == .follow) {
                        Haptics.tick()
                        if mode == .indoor { indoorCamera.recenter() } else { camera = started && me != nil ? .follow : .overview }
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                tripSheet
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { bottomHeight = $0 }
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .background(c.surface)
        .animation(.easeInOut(duration: 0.55), value: mode)
        .animation(.easeOut(duration: 0.35), value: welcome?.floorNumber)
        .indoorLifecycle(backend)
        .onAppear { if backend.locationUndetermined { backend.requestLocationPermission() } }
        .onChange(of: destKey, initial: true) { _, k in if let k { backend.setDestination(k) } }
        .onChange(of: fix?.elapsedRealtimeNs) { _, _ in handleFix() }
        .task(id: GpsLostKey(mode: mode, auto: autoIndoor, nearDoor: lastOutsideByM <= doorRadiusM)) { await watchGpsLostAtDoor() }
        .onChange(of: TrackingKey(mode: mode, want: wantTracking, canStart: snap.pdr.canStart, phase: snap.pdr.phase), initial: true) { _, _ in autoStartTracking() }
        .task(id: welcome?.floorNumber) { if welcome != nil { try? await Task.sleep(nanoseconds: 5_000_000_000); welcome = nil } }
        .task(id: RouteKey(need: needRoute, target: outdoorTarget)) { await fetchRouteIfNeeded() }
        .onChange(of: guidance) { _, g in if let g { NavVoice.shared.guide(key: g.key, text: g.text) } }
        .onAppear { NavVoice.shared.reset() }
        .sheet(isPresented: Binding(get: { showSteps && snap.activeRoute != nil }, set: { if !$0 { showSteps = false } })) {
            if let r = snap.activeRoute { RouteStepsSheet(route: r).environment(\.palette, c) }
        }
        .sheet(isPresented: Binding(get: { pickPosition && mode == .indoor }, set: { if !$0 { pickPosition = false } })) {
            let first = snap.userFloorId
            RoomPickerSheet(title: L("go_position_title"),
                            items: snap.destinations.map { RoomItem(floorId: $0.key.floorId, nodeId: $0.key.nodeId, label: $0.label, name: $0.name) }
                                .sorted { ($0.floorId == first ? 0 : 1) < ($1.floorId == first ? 0 : 1) }) { item in
                Haptics.confirm()
                // The picked spot becomes the reference; every detected step moves you from there
                backend.setStartPose(item.floorId, item.nodeId)
                wantTracking = true
                indoorCamera.recenter()
                pickPosition = false; askFloor = false; welcome = nil
            }
            .environment(\.palette, c)
        }
        .sheet(isPresented: Binding(get: { askFloor && mode == .indoor && !pickPosition }, set: { if !$0 { askFloor = false } })) {
            FloorAskSheet(floors: snap.floors.map(\.floorId), current: snap.userFloorId, altitude: snap.altitudeFloor) { id in
                Haptics.tick(); backend.confirmUserFloor(id); askFloor = false; welcome = nil
            }
            .environment(\.palette, c)
        }
    }

    // MARK: top

    @ViewBuilder private var topArea: some View {
        if mode == .indoor, let r = snap.activeRoute, snap.pdr.phase == .tracking {
            ManeuverCard(route: r, progress: progressTracker.fraction(r))
                .onTapGesture { showSteps = true }
        } else if mode == .outdoor, started, let p = progress, !arrivedOutside {
            let next = p.nextStep
            let then: WalkStep? = next.flatMap { n in route.flatMap { r in r.steps.firstIndex(of: n).flatMap { r.steps.indices.contains($0 + 1) ? r.steps[$0 + 1] : nil } } }
            ManeuverCard(icon: maneuverIcon(next?.maneuver ?? .straight), distance: NavText.distance(p.distanceToNextStepM),
                         text: next.map(stepText) ?? L("nav_follow"), then: p.distanceToNextStepM < 120 ? then.map(stepText) : nil,
                         thenIcon: p.distanceToNextStepM < 120 ? then.map { maneuverIcon($0.maneuver) } : nil,
                         progress: route.map { $0.lengthM > 0 ? p.alongM / $0.lengthM : 0 })
        } else {
            HStack(spacing: 10) {
                CircleIconButton(icon: "arrow.left") { router.back() }.shadow(color: .black.opacity(0.15), radius: 6, y: 2)
                DestinationChip(place: place, floor: destFloor)
            }
        }
    }

    // MARK: sheet

    private var tripSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            Capsule().fill(c.border).frame(width: 36, height: 4).frame(maxWidth: .infinity)
            JourneyStrip(place: place, indoor: mode == .indoor, arrived: arrivedOutside || snap.activeRoute?.isArrived == true)
                .padding(.vertical, 4)
            switch mode {
            case .outdoor: outdoorSheet
            case .indoor: indoorSheet
            }
        }
        .padding(.horizontal, Tokens.gutter).padding(.top, 10).padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(UnevenRoundedRectangle(topLeadingRadius: Tokens.radiusCard, topTrailingRadius: Tokens.radiusCard, style: .continuous)
            .fill(c.bg).shadow(color: .black.opacity(0.18), radius: 16, y: -2).ignoresSafeArea(edges: .bottom))
    }

    @ViewBuilder private var outdoorSheet: some View {
        if arrivedOutside {
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 28)).foregroundStyle(c.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("go_arrived_building", place.building.name)).lyncText(.titleLarge)
                    if let hint = roomHint { Text(hint).lyncText(.bodyMedium, c.textMuted) }
                }
            }
            Text(L("go_no_indoor_map")).lyncText(.bodySmall, c.textMuted)
            PrimaryButton(text: L("go_end")) { router.back() }
        } else if !snap.locationPermissionGranted {
            Text(L("go_location_needed")).lyncText(.titleMedium)
            PrimaryButton(text: L("indoor_allow")) { askLocation() }
            insideButton(.tonal)
        } else if farAway, let d = distToTarget {
            Text(L("go_far", NavText.distance(d))).lyncText(.titleMedium)
            Text(L("go_far_hint")).lyncText(.bodyMedium, c.textMuted)
            PrimaryButton(text: L("go_open_maps")) { openInMaps(outdoorTarget, place.title) }
            insideButton(.tonal)
        } else if me == nil || route == nil {
            Text(L("go_locating")).lyncText(.titleMedium)
            Text(place.building.name).lyncText(.bodyMedium, c.textMuted)
            insideButton(.tonal)
        } else if let route {
            let remaining = progress?.remainingM ?? route.lengthM
            let seconds = remaining / WalkingRouter.walkSpeedMps
            HStack(alignment: .lastTextBaseline, spacing: 10) {
                Text(L("go_minutes", max(1, Int((seconds / 60).rounded())))).font(.display(30, "Bold")).foregroundStyle(c.accent)
                Text(NavText.distance(remaining) + "  ·  " + L("go_eta", Date().addingTimeInterval(seconds).formatted(Date.FormatStyle(date: .omitted, time: .shortened).locale(Lang.locale))))
                    .lyncText(.titleSmall, c.textMuted)
            }
            if route.approximate { Text(L("go_approximate")).lyncText(.bodySmall, c.textMuted) }
            if !started {
                PrimaryButton(text: L("go_start")) { Haptics.confirm(); started = true; camera = .follow }
                insideButton(.tonal)
            } else {
                HStack(spacing: 8) {
                    Pill(text: L("go_end"), style: .tonal) { router.back() }
                    if place.hasIndoorMap { Pill(text: L("go_inside"), style: .outline) { enterIndoor(manual: true) } }
                }
            }
        }
    }

    @ViewBuilder private func insideButton(_ style: PillStyle) -> some View {
        if place.hasIndoorMap { Pill(text: L("go_inside"), style: style, fullWidth: true, vertical: 12) { enterIndoor(manual: true) } }
    }

    @ViewBuilder private var indoorSheet: some View {
        let s = snap.pdr
        let r = snap.activeRoute
        if r?.isArrived == true, let r {
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 28)).foregroundStyle(c.accent)
                Text(L("nav_arrived", r.destinationLabel)).lyncText(.titleLarge)
            }
        } else if s.phase == .settling {
            Text(L("indoor_settling")).lyncText(.titleMedium)
        } else if s.phase == .idle || s.phase == .calibrating {
            Text(place.title).lyncText(.titleLarge)
            PrimaryButton(text: L(s.canStart || s.headingValid ? "go_indoor_start" : "indoor_heading_wait"), enabled: s.canStart) {
                backend.startWithSavedOrDefaultStepLength()
            }
        } else if s.phase == .paused {
            Text(L("indoor_paused")).lyncText(.titleMedium)
            // a pause resets the compass frame: resume starts a new trace from the last reference point
            PrimaryButton(text: L(s.headingValid && !s.tilted ? "indoor_resume" : "indoor_heading_wait"), enabled: s.headingValid && !s.tilted) { backend.resumeFromPause() }
        } else if let r {
            let seconds = r.totalDistanceM / indoorWalkSpeedMps
            HStack(alignment: .lastTextBaseline, spacing: 10) {
                Text(L("go_minutes", max(1, Int((seconds / 60).rounded())))).font(.display(30, "Bold")).foregroundStyle(c.accent)
                Text(NavText.distance(r.totalDistanceM) + "  ·  " + r.destinationLabel).lyncText(.titleSmall, c.textMuted).lineLimit(1)
            }
        } else {
            Text(place.title).lyncText(.titleLarge)
        }
        if r?.isArrived != true { PositionRow(snap: snap) { Haptics.tick(); pickPosition = true } }
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Pill(text: L("go_end"), style: .tonal) { router.back() }
                if s.phase == .tracking, let t = snap.suggestedTransfer {
                    Pill(text: L("indoor_floor", floorNumberText(t.toFloorId))) { backend.confirmUserFloor(t.toFloorId, explicitVerticalGroupId: t.verticalGroupId) }
                }
                Pill(text: L("go_outside"), style: .outline) {
                    Haptics.tick()
                    autoIndoor = false
                    backend.stopToSetup()
                    wantTracking = false
                    mode = .outdoor
                    camera = .overview
                }
            }
        }
    }

    private var roomHint: String? {
        guard let room = place.roomLabel else { return nil }
        return destFloor.map { L("go_room_floor", room, $0) } ?? room
    }

    // MARK: logic (same rules as Android)

    private func askLocation() {
        if backend.locationUndetermined { backend.requestLocationPermission() }
        else if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }

    private func enterIndoor(manual: Bool = false) {
        guard place.hasIndoorMap else { return }
        manualIndoor = manual
        let estimate = backend.estimateAltitudeFloor()
        let floorId = snap.floors.contains { $0.floorId == "MB-\(estimate.floorNumber)" } ? "MB-\(estimate.floorNumber)" : "MB-1"
        if let k = destKey { backend.setDestination(k) }
        backend.enterBuilding(floorId, nearM: lastOutdoorM ?? fix?.eastNorthM)
        wantTracking = true
        indoorCamera.recenter()
        welcome = estimate
        // entered by hand or no street pressure to compare: let the user set their own reference point
        pickPosition = manual
        askFloor = !manual && estimate.source == .entranceAssumed && lastOutdoorM == nil
        mode = .indoor
        Haptics.confirm()
    }

    /// Automatic outdoor <-> indoor switch, driven by every GPS fix.
    private func handleFix() {
        guard let f = fix else { return }
        let out = outsideBy(f.eastNorthM)
        if out > 0 && f.accuracyM <= 25 { lastOutdoorM = f.eastNorthM; lastOutsideByM = out }
        switch mode {
        case .outdoor:
            guard place.hasIndoorMap && autoIndoor else { return }
            // indoors GPS gets coarse: accept a wide fix as long as it is centred in the building
            insideStreak = out == 0 && f.accuracyM <= 60 ? insideStreak + 1 : 0
            // right at a door and the fix suddenly degrades: walls block the sky, you just went in
            let degradedAtDoor = lastOutsideByM <= doorRadiusM && f.accuracyM > 30 && out <= doorRadiusM + f.accuracyM
            if insideStreak >= 2 || degradedAtDoor { enterIndoor() }
        case .indoor:
            guard !manualIndoor else { return }
            outsideStreak = out > leaveDistanceM && f.accuracyM <= 10 ? outsideStreak + 1 : 0
            if outsideStreak >= 3 {
                outsideStreak = 0; insideStreak = 0
                backend.stopToSetup()
                wantTracking = false
                mode = .outdoor
                camera = started ? .follow : .overview
            }
        }
    }

    /// GPS often stops completely inside: no fix for a while right after being at a door = inside.
    private func watchGpsLostAtDoor() async {
        guard mode == .outdoor, autoIndoor, place.hasIndoorMap, lastOutsideByM <= doorRadiusM else { return }
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard let last = fix else { continue }
            let ageS = Double(NavClock.nowNs() - last.elapsedRealtimeNs) / 1e9
            if ageS >= gpsLostAtDoorS && outsideBy(last.eastNorthM) <= doorRadiusM { enterIndoor(); return }
        }
    }

    /// Start the pedometer as soon as it can (heading valid, phone flat enough).
    private func autoStartTracking() {
        guard mode == .indoor, wantTracking else { return }
        switch snap.pdr.phase {
        case .tracking, .calibrating, .settling: wantTracking = false
        case .idle: if snap.pdr.canStart && backend.startWithSavedOrDefaultStepLength() { wantTracking = false }
        case .paused: break
        }
    }

    private func fetchRouteIfNeeded() async {
        guard needRoute, let from = me else { return }
        let wait = rerouteMinInterval - Date().timeIntervalSince(lastRouteAt)
        if route != nil && wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1e9)) }
        if Task.isCancelled { return }
        let r = await WalkingRouter.route(from, outdoorTarget)
        if Task.isCancelled { return }
        route = r
        lastRouteAt = Date()
    }

    private func openInMaps(_ to: GeoPoint, _ label: String) {
        let item = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: to.lat, longitude: to.lng)))
        item.name = label
        item.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeTransit])
    }

    // MARK: wording

    private func maneuverIcon(_ m: Maneuver) -> String {
        switch m {
        case .left: "arrow.turn.up.left"
        case .right: "arrow.turn.up.right"
        case .slightLeft: "arrow.up.left"
        case .slightRight: "arrow.up.right"
        case .sharpLeft: "arrow.turn.left.down"
        case .sharpRight: "arrow.turn.right.down"
        case .uturn: "arrow.uturn.down"
        case .arrive: "flag.checkered"
        case .depart: "figure.walk"
        case .straight: "arrow.up"
        }
    }

    private func stepText(_ step: WalkStep) -> String {
        if step.maneuver == .arrive { return L("go_m_arrive", place.building.name) }
        let verb: String
        switch step.maneuver {
        case .depart: verb = L("go_m_depart")
        case .left: verb = L("go_m_left")
        case .right: verb = L("go_m_right")
        case .slightLeft: verb = L("go_m_slight_left")
        case .slightRight: verb = L("go_m_slight_right")
        case .sharpLeft: verb = L("go_m_sharp_left")
        case .sharpRight: verb = L("go_m_sharp_right")
        case .uturn: verb = L("nav_uturn")
        default: verb = L("go_m_straight")
        }
        return step.street.isEmpty ? verb : L("go_on_street", verb, step.street)
    }
}

private struct GpsLostKey: Hashable { let mode: GoMode; let auto: Bool; let nearDoor: Bool }
private struct TrackingKey: Equatable { let mode: GoMode; let want: Bool; let canStart: Bool; let phase: PdrPhase }
private struct RouteKey: Hashable { let need: Bool; let target: GeoPoint }
private struct Guidance: Equatable { let key: String; let text: String }

// MARK: - Pieces

private struct DestinationChip: View {
    @Environment(\.palette) private var c
    let place: Place
    let floor: Int?
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "figure.walk").font(.system(size: 15, weight: .semibold)).foregroundStyle(c.accent)
                .frame(width: 32, height: 32).background(Circle().fill(c.accentSoft))
            VStack(alignment: .leading, spacing: 0) {
                Text(L("go_to", place.title)).lyncText(.titleSmall).lineLimit(1)
                Text(([place.building.name] + (floor.map { [L("indoor_floor", String($0))] } ?? [])).joined(separator: " · ")).lyncText(.bodySmall, c.textMuted).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.leading, 6).padding(.trailing, 16).padding(.vertical, 6)
        .background(Capsule().fill(c.bg).shadow(color: .black.opacity(0.18), radius: 8, y: 3))
    }
}

/// Outside → Building → Room: where you are in the trip.
private struct JourneyStrip: View {
    @Environment(\.palette) private var c
    let place: Place
    let indoor: Bool
    let arrived: Bool
    var body: some View {
        let stages: [(String, String)] = [("figure.walk", L("go_stage_outside")), ("building.2", place.building.code)]
            + (place.roomLabel.map { [("flag.checkered", String($0.split(separator: "-", maxSplits: 1).last ?? Substring($0)))] } ?? [])
        let current = arrived ? stages.count - 1 : indoor ? 1 : 0
        HStack(spacing: 4) {
            ForEach(stages.indices, id: \.self) { i in
                let done = i < current, on = i == current
                HStack(spacing: 5) {
                    Image(systemName: done ? "checkmark.circle.fill" : stages[i].0).font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(on ? c.bg : done ? c.accent : c.textMuted)
                    Text(stages[i].1).lyncText(.labelMedium, on ? c.bg : c.text).lineLimit(1)
                }
                .padding(.horizontal, 10).padding(.vertical, 6).background(Capsule().fill(on ? c.text : c.surface))
                if i < stages.count - 1 { Capsule().fill(i < current ? c.accent : c.border).frame(height: 2).frame(maxWidth: .infinity) }
            }
        }
    }
}

private struct FloorBadge: View {
    @Environment(\.palette) private var c
    let snap: NavigationSnapshot
    let onTap: () -> Void
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "arrow.up.and.down").font(.system(size: 14, weight: .semibold)).foregroundStyle(c.accent)
            Text(L("indoor_floor", snap.userFloorId.map(floorNumberText) ?? "?")).lyncText(.labelLarge)
            if let alt = snap.altitudeFloor, let h = alt.heightAboveStreetM, alt.source == .barometerStreetReference {
                Text(String(format: "%+.0f m", h)).lyncText(.labelMedium, c.textMuted)
            }
        }
        .padding(.leading, 10).padding(.trailing, 14).padding(.vertical, 8)
        .background(Capsule().fill(c.bg).shadow(color: .black.opacity(0.18), radius: 8, y: 3))
        .pressable(onTap)
    }
}

/// "Your starting point: MB-2.255 · Floor 2 — Change".
private struct PositionRow: View {
    @Environment(\.palette) private var c
    let snap: NavigationSnapshot
    let onTap: () -> Void
    var body: some View {
        let start = snap.startPose.flatMap { sp in snap.floors.first { $0.floorId == sp.floorId }?.node(sp.nodeId).map { ($0, sp.floorId) } }
        HStack(spacing: 12) {
            Image(systemName: "mappin.and.ellipse").font(.system(size: 16, weight: .semibold)).foregroundStyle(c.accent)
                .frame(width: 36, height: 36).background(Circle().fill(c.accentSoft))
            VStack(alignment: .leading, spacing: 1) {
                Text(L("go_position_label")).lyncText(.labelMedium, c.textMuted)
                Text(start.map { "\($0.0.title) · " + L("indoor_floor", floorNumberText($0.1)) } ?? L("go_position_hint"))
                    .lyncText(.titleSmall, start != nil ? c.text : c.textMuted).lineLimit(1)
            }
            Spacer(minLength: 0)
            Text(L("go_position_change")).lyncText(.labelLarge, c.accent)
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(c.surface))
        .pressable(scale: 0.98, onTap)
    }
}

private struct WelcomeCard: View {
    @Environment(\.palette) private var c
    let place: Place
    let estimate: AltitudeFloorEstimate
    let askingFloor: Bool
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "building.2.fill").font(.system(size: 20)).foregroundStyle(c.onAccent)
                .frame(width: 46, height: 46).background(Circle().fill(c.accent))
            VStack(alignment: .leading, spacing: 2) {
                Text(L("go_welcome", place.building.name)).lyncText(.titleMedium, c.bg)
                Text(askingFloor ? L("go_floor_ask") : estimate.source == .barometerStreetReference ? L("go_floor_altitude", estimate.floorNumber) : L("go_floor_entrance", estimate.floorNumber))
                    .lyncText(.bodyMedium, c.bg.opacity(0.72))
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(c.text).shadow(color: .black.opacity(0.3), radius: 18, y: 6))
    }
}

private struct FloorAskSheet: View {
    @Environment(\.palette) private var c
    let floors: [String]
    let current: String?
    let altitude: AltitudeFloorEstimate?
    let onPick: (String) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L("go_floor_ask")).lyncText(.titleLarge).padding(.top, 28)
            if let alt = altitude, let h = alt.heightAboveStreetM {
                Text(L("go_altitude_hint", String(format: "%.1f", locale: Lang.locale, h), alt.floorNumber)).lyncText(.bodyMedium, c.textMuted)
            }
            HStack(spacing: 8) {
                ForEach(floors.sorted { IndoorAssets.floorNumber($0) < IndoorAssets.floorNumber($1) }, id: \.self) { id in
                    let on = id == current
                    let suggested = altitude.map { String($0.floorNumber) == floorNumberText(id) } ?? false
                    Text(floorNumberText(id)).lyncText(.titleMedium, on ? c.bg : c.text)
                        .frame(maxWidth: .infinity).frame(height: 52)
                        .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(on ? c.text : c.surface))
                        .overlay { if suggested && !on { RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).stroke(c.accent, lineWidth: 2) } }
                        .pressable(scale: 0.92) { onPick(id) }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Tokens.gutter)
        .background(c.bg)
        .presentationDetents([.height(260)])
        .presentationCornerRadius(Tokens.radiusCard)
    }
}

private struct PlaceNotFound: View {
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    let query: String
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            CircleIconButton(icon: "arrow.left") { router.back() }
            Spacer().frame(height: 24)
            Image(systemName: "map").font(.system(size: 44)).foregroundStyle(c.textMuted)
            Text(L("go_not_found", query)).lyncText(.headline)
            PrimaryButton(text: L("map_title")) { router.map() }
            Spacer()
        }
        .padding(Tokens.gutter)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(c.bg)
    }
}

// MARK: - Street map for the outdoor leg (Android: MapLibre + OpenFreeMap; iOS: MapKit)

struct OutdoorMap: View {
    @Environment(\.palette) private var c
    let route: WalkRoute?
    let me: GeoPoint?
    let meBearingDeg: Double?
    let destination: GeoPoint
    let camera: OutdoorCamera
    let followBearingDeg: Double?
    let insets: EdgeInsets
    let onUserMovedMap: () -> Void
    @State private var position: MapCameraPosition = .automatic
    @State private var mapHeading: Double = 0

    var body: some View {
        Map(position: $position, interactionModes: [.pan, .zoom, .rotate]) {
            if let pts = route?.points, pts.count >= 2 {
                let coords = pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) }
                MapPolyline(coordinates: coords).stroke(c.isDark ? Color(hex: 0x0B0D14) : .white, style: StrokeStyle(lineWidth: 12, lineCap: .round, lineJoin: .round))
                MapPolyline(coordinates: coords).stroke(c.accent, style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round, dash: route?.approximate == true ? [8, 9] : []))
            }
            Annotation("", coordinate: CLLocationCoordinate2D(latitude: destination.lat, longitude: destination.lng), anchor: .bottom) { DestinationFlag() }
                .annotationTitles(.hidden)
            if let me {
                Annotation("", coordinate: CLLocationCoordinate2D(latitude: me.lat, longitude: me.lng), anchor: .center) {
                    WalkerDot(relativeBearingDeg: meBearingDeg.map { $0 - mapHeading })
                }
                .annotationTitles(.hidden)
            }
        }
        .mapStyle(.standard(elevation: .realistic, pointsOfInterest: .including([.university, .publicTransport, .library])))
        .mapControls { MapCompass().mapControlVisibility(.hidden) }
        .simultaneousGesture(DragGesture(minimumDistance: 10).onChanged { _ in onUserMovedMap() })
        .onMapCameraChange(frequency: .continuous) { ctx in mapHeading = ctx.camera.heading }
        .onAppear { applyCamera(animated: false) }
        .onChange(of: CameraKey(camera: camera, routeCount: route?.points.count ?? 0, me: me, bearing: followBearingDeg.map { Int($0) })) { _, _ in applyCamera(animated: true) }
    }

    private struct CameraKey: Equatable { let camera: OutdoorCamera; let routeCount: Int; let me: GeoPoint?; let bearing: Int? }

    private func applyCamera(animated: Bool) {
        let target: MapCameraPosition
        switch camera {
        case .free: return
        case .overview:
            let pts = (route?.points ?? []) + [me, destination].compactMap { $0 }
            if pts.count >= 2 {
                let lats = pts.map(\.lat), lngs = pts.map(\.lng)
                // pad for the cards on top and the sheet at the bottom
                let span = MKCoordinateSpan(latitudeDelta: max(0.002, (lats.max()! - lats.min()!) * 1.9), longitudeDelta: max(0.002, (lngs.max()! - lngs.min()!) * 1.5))
                let centerLat = (lats.max()! + lats.min()!) / 2 - span.latitudeDelta * 0.12
                target = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: centerLat, longitude: (lngs.max()! + lngs.min()!) / 2), span: span))
            } else {
                target = .camera(MapCamera(centerCoordinate: CLLocationCoordinate2D(latitude: destination.lat, longitude: destination.lng), distance: 900))
            }
        case .follow:
            guard let me else { return }
            target = .camera(MapCamera(centerCoordinate: CLLocationCoordinate2D(latitude: me.lat, longitude: me.lng), distance: 320,
                                       heading: followBearingDeg ?? mapHeading, pitch: 52))
        }
        if animated { withAnimation(.easeInOut(duration: 0.9)) { position = target } } else { position = target }
    }
}

/// Blue-dot equivalent in the university colour: pulsing halo + direction cone when walking.
private struct WalkerDot: View {
    @Environment(\.palette) private var c
    let relativeBearingDeg: Double?
    @State private var pulse = false
    var body: some View {
        ZStack {
            Circle().fill(c.accent.opacity(0.28)).frame(width: 72, height: 72)
                .scaleEffect(pulse ? 1 : 0.5).opacity(pulse ? 0 : 0.8)
                .animation(.easeOut(duration: 1.7).repeatForever(autoreverses: false), value: pulse)
            if let b = relativeBearingDeg {
                Path { p in
                    p.move(to: CGPoint(x: 36, y: 36))
                    p.addLine(to: CGPoint(x: 36 - 72 * 0.26, y: 36 - 72 * 0.46))
                    p.addQuadCurve(to: CGPoint(x: 36 + 72 * 0.26, y: 36 - 72 * 0.46), control: CGPoint(x: 36, y: 36 - 72 * 0.56))
                    p.closeSubpath()
                }
                .fill(c.accent.opacity(0.3)).frame(width: 72, height: 72).rotationEffect(.degrees(b))
            }
            Circle().fill(c.accent).frame(width: 14, height: 14).padding(4).background(Circle().fill(.white)).shadow(color: .black.opacity(0.25), radius: 4)
        }
        .frame(width: 72, height: 72)
        .onAppear { pulse = true }
    }
}

private struct DestinationFlag: View {
    @Environment(\.palette) private var c
    var body: some View {
        ZStack(alignment: .top) {
            Image(systemName: "mappin.circle.fill").font(.system(size: 40)).foregroundStyle(.white, c.accent)
                .shadow(color: .black.opacity(0.25), radius: 3, y: 2)
        }
        .padding(.bottom, 2)
    }
}
