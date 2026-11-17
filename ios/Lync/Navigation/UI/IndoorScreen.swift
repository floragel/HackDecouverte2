import SwiftUI

/// iOS port of ui/indoor/IndoorScreen.kt: the MB floor plans with step tracking and room-to-room directions.
struct IndoorScreen: View {
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var camera = IndoorCamera()
    @State private var sheetHeight: CGFloat = 0
    @State private var picker: Picker?
    @State private var showSteps = false
    @State private var progressTracker = RouteProgress()
    private var backend: IndoorNavigationBackend { IndoorEngine.shared }

    private enum Picker: Identifiable { case from, to; var id: Int { self == .from ? 0 : 1 } }

    var body: some View {
        let snap = backend.snapshot
        ZStack(alignment: .bottom) {
            IndoorCanvas(snapshot: snap, camera: camera, bottomInset: sheetHeight).ignoresSafeArea()

            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    CircleIconButton(icon: "arrow.left") { router.back() }.shadow(color: .black.opacity(0.15), radius: 6, y: 2)
                    Text(L("indoor_title")).lyncText(.titleLarge).padding(.horizontal, 16).padding(.vertical, 9)
                        .background(Capsule().fill(c.bg).shadow(color: .black.opacity(0.18), radius: 8, y: 3))
                    Spacer()
                }
                if s(snap).phase == .tracking, let r = snap.activeRoute {
                    ManeuverCard(route: r, progress: progressTracker.fraction(r))
                        .padding(.trailing, 56) // floor picker
                        .onTapGesture { showSteps = true }
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
                if let chip = NavText.chip(snap.pdr) { HintChip(text: chip).transition(.opacity) }
                Spacer()
            }
            .padding(16)
            .animation(Motion.standard, value: NavText.chip(snap.pdr))

            FloorPicker(floors: snap.floors.map(\.floorId), active: snap.activeFloorId) { Haptics.tick(); backend.switchActiveFloor($0) }
                .padding(.trailing, 16).padding(.top, 72)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)

            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Spacer()
                    if snap.activeRoute != nil { MapRoundButton(icon: "list.bullet") { Haptics.tick(); showSteps = true } }
                    if snap.pdr.phase == .tracking { VoiceButton() }
                    MapRoundButton(icon: "location.north.line.fill", active: camera.headUp) { Haptics.tick(); camera.headUp.toggle() }
                    ThemeToggleButton()
                    MapRoundButton(icon: "location.fill") { camera.recenter() }
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                VStack(alignment: .leading, spacing: 10) { sheet(snap) }
                    .padding(.horizontal, Tokens.gutter).padding(.top, 18).padding(.bottom, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(UnevenRoundedRectangle(topLeadingRadius: Tokens.radiusCard, topTrailingRadius: Tokens.radiusCard, style: .continuous)
                        .fill(c.bg).shadow(color: .black.opacity(0.15), radius: 10, y: -2).ignoresSafeArea(edges: .bottom))
                    .animation(Motion.standard, value: snap.pdr.phase)
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { sheetHeight = $0 }
        }
        .background(c.surface)
        .indoorLifecycle(backend)
        .onAppear { NavVoice.shared.reset() }
        .onChange(of: snap.activeRoute.map(NavText.guidanceKey)) { _, key in
            if let key, snap.pdr.phase == .tracking, let r = snap.activeRoute { NavVoice.shared.guide(key: key, text: NavText.instruction(r)) }
        }
        .sheet(isPresented: Binding(get: { showSteps && snap.activeRoute != nil }, set: { if !$0 { showSteps = false } })) {
            if let r = snap.activeRoute { RouteStepsSheet(route: r).environment(\.palette, c) }
        }
        .sheet(item: $picker) { which in
            let preferred = snap.activeFloorId ?? snap.userFloorId
            RoomPickerSheet(
                title: L(which == .from ? "indoor_from" : "indoor_to"),
                items: snap.destinations.map { RoomItem(floorId: $0.key.floorId, nodeId: $0.key.nodeId, label: $0.label, name: $0.name) }
                    .sorted { ($0.floorId == preferred ? 0 : 1) < ($1.floorId == preferred ? 0 : 1) }
            ) { item in
                Haptics.tick()
                camera.recenter()
                if which == .from { backend.setStartPose(item.floorId, item.nodeId) }
                else { backend.setDestination(NodeKey(floorId: item.floorId, nodeId: item.nodeId)) }
            }
            .environment(\.palette, c)
        }
    }

    private func s(_ snap: NavigationSnapshot) -> PdrState { snap.pdr }

    @ViewBuilder
    private func sheet(_ snap: NavigationSnapshot) -> some View {
        let s = snap.pdr
        switch s.phase {
        case .idle: setup(snap)
        case .calibrating:
            Text(L("indoor_calibrate_walk")).lyncText(.titleLarge)
            Text(L("indoor_calibrate_steps", s.calibrationStepCount)).lyncText(.bodyLarge, c.textMuted)
            PrimaryButton(text: L("indoor_calibrate_done"), enabled: s.calibrationStepCount > 0) { Haptics.confirm(); backend.finishCalibration5m() }
            Pill(text: L("indoor_stop"), style: .tonal) { backend.stopToSetup() }
        case .settling:
            Text(L("indoor_settling")).lyncText(.titleLarge).padding(.vertical, 18)
        case .tracking, .paused: tracking(snap)
        }
    }

    @ViewBuilder
    private func setup(_ snap: NavigationSnapshot) -> some View {
        let s = snap.pdr
        let start = snap.startPose.flatMap { sp in snap.floors.first { $0.floorId == sp.floorId }?.node(sp.nodeId) }
        let dest = snap.destination.flatMap { k in snap.destinations.first { $0.key == k } }
        RoomField(label: L("indoor_from"),
                  value: start.map { NavText.roomLine($0.label, $0.name) } ?? (snap.usingGpsStart ? L("indoor_from_gps_on") : nil),
                  hint: L("indoor_from_hint"), onTap: { picker = .from },
                  onClear: start != nil || snap.usingGpsStart ? { backend.clearStartPose() } : nil) {
            CircleIconButton(icon: "location.circle") {
                if snap.locationPermissionGranted { backend.useGpsAsStartOrigin() } else { backend.requestLocationPermission() }
            }
        }
        RoomField(label: L("indoor_to"),
                  value: dest.map { NavText.roomLine($0.label, $0.name) + " · " + L("indoor_floor", floorNumberText($0.key.floorId)) },
                  hint: L("indoor_to_hint"), onTap: { picker = .to },
                  onClear: dest != nil ? { backend.setDestination(nil) } : nil) { EmptyView() }
        AccessibleRow(on: snap.accessibleOnly) { backend.setAccessibleOnly($0) }
        PrimaryButton(text: L("indoor_go"), enabled: s.canStart) { Haptics.confirm(); backend.startWithSavedOrDefaultStepLength() }
        #if targetEnvironment(simulator)
        if snap.destination != nil && (start != nil || snap.usingGpsStart) {
            Pill(text: L("nav_sim_walk"), style: .outline, icon: "figure.walk") { backend.startSimulatedWalk() }
        }
        #endif
        HStack(spacing: 10) {
            Pill(text: L("indoor_calibrate"), style: .tonal) { backend.startCalibration5m() }
            if let saved = snap.savedStepLengthM {
                Text(L("indoor_step_saved", String(format: "%.2f", locale: Lang.locale, saved))).lyncText(.bodySmall, c.textMuted)
            }
        }
    }

    @ViewBuilder
    private func tracking(_ snap: NavigationSnapshot) -> some View {
        let s = snap.pdr
        let headingReady = s.headingValid && !s.tilted
        if s.phase == .paused {
            Text(L("indoor_paused")).lyncText(.titleLarge)
            HStack(spacing: 8) {
                Pill(text: L(headingReady ? "indoor_resume" : "indoor_heading_wait")) { if headingReady { backend.resumeFromPause() } }
                Pill(text: L("indoor_stop"), style: .tonal) { backend.stopToSetup() }
            }
        } else {
            if let route = snap.activeRoute {
                HStack(spacing: 4) {
                    Image(systemName: "mappin").font(.system(size: 13)).foregroundStyle(c.accent)
                    Text(route.destinationLabel).lyncText(.titleSmall).lineLimit(1)
                    if !route.isArrived { Text("  ·  " + L("indoor_remaining", Int(route.totalDistanceM))).lyncText(.bodyMedium, c.textMuted) }
                }
            } else if snap.destination != nil && snap.startNodeId == nil && !snap.usingGpsStart {
                Text(L("indoor_need_start")).lyncText(.bodyMedium, c.textMuted)
            } else {
                RoomField(label: L("indoor_to"), value: nil, hint: L("indoor_to_hint"), onTap: { picker = .to }, onClear: nil) { EmptyView() }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Pill(text: L("indoor_stop"), style: .tonal) { backend.stopToSetup() }
                    if backend.isSimulating { Pill(text: L("nav_sim_stop"), style: .outline) { backend.stopSimulatedWalk() } }
                    if !snap.isViewingUserFloor, let viewed = snap.activeFloorId {
                        Pill(text: L("indoor_floor", floorNumberText(viewed))) { backend.confirmUserFloor(viewed) }
                    } else if let t = snap.suggestedTransfer {
                        Pill(text: L("indoor_floor", floorNumberText(t.toFloorId))) { backend.confirmUserFloor(t.toFloorId, explicitVerticalGroupId: t.verticalGroupId) }
                    }
                    if snap.destination != nil { Pill(text: L("indoor_clear"), style: .outline) { backend.setDestination(nil) } }
                    Pill(text: L("indoor_restart"), style: .outline) { backend.resetPositionToStart() }
                }
            }
        }
    }
}

/// "You're at / Going to" field: tap to pick, ✕ to clear, optional trailing button.
struct RoomField<Trailing: View>: View {
    @Environment(\.palette) private var c
    let label: String
    let value: String?
    let hint: String
    let onTap: () -> Void
    let onClear: (() -> Void)?
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(label).lyncText(.labelMedium, c.textMuted)
                    Text(value ?? hint).lyncText(.bodyLarge, value != nil ? c.text : c.textMuted).lineLimit(1)
                }
                Spacer(minLength: 0)
                if let onClear {
                    Image(systemName: "xmark").font(.system(size: 13, weight: .bold)).foregroundStyle(c.textMuted)
                        .frame(width: 28, height: 28).contentShape(Circle()).onTapGesture(perform: onClear)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(c.surface))
            .pressable(scale: 0.98, onTap)
            trailing()
        }
    }
}
