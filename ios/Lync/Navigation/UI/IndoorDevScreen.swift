import SwiftUI

/// Settings › Developer › Indoor navigation test: the engine's raw numbers (steps, heading, step length,
/// cadence, barometer, GPS, warnings) and its controls, over the plan. iOS version of the Android
/// TelemetryScreen + CalibrationScreen test harness.
struct IndoorDevScreen: View {
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var camera = IndoorCamera()
    @State private var stepLength = 0.74
    @State private var picker: Bool? // true = start, false = destination
    private var backend: IndoorNavigationBackend { IndoorEngine.shared }

    var body: some View {
        let snap = backend.snapshot
        let s = snap.pdr
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                CircleIconButton(icon: "arrow.left") { router.back() }
                Text(L("dev_title")).lyncText(.titleLarge)
                Spacer()
            }
            .padding(.horizontal, 12).frame(height: 60)

            ZStack(alignment: .topTrailing) {
                IndoorCanvas(snapshot: snap, camera: camera)
                FloorPicker(floors: snap.floors.map(\.floorId), active: snap.activeFloorId) { backend.switchActiveFloor($0) }.padding(10)
            }
            .frame(height: 300)
            .clipped()

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    grid([
                        ("Phase", "\(s.phase)"), ("Source", "\(s.stepSource)"),
                        ("Pas", "\(s.stepCount)  (rejetés \(s.droppedStepCount))"), ("Distance", String(format: "%.2f m", s.distanceM)),
                        ("Longueur", fmt(s.currentStepLengthM, "%.2f m") + " / base " + fmt(s.lengthPerStepM, "%.2f")),
                        ("Cadence", fmt(s.cadenceStepsPerMin, "%.0f pas/min") + " · base " + fmt(s.baseCadenceHz.map { $0 * 60 }, "%.0f")),
                        ("Cap", String(format: "%.1f°", radToDeg(snap.mapHeadingRad)) + (s.headingValid ? "" : " ✕") + (s.tilted ? " (incliné)" : "")),
                        ("Nord vrai", s.trueNorthAligned ? "aligné" : "—"),
                        ("Position", String(format: "%.1f, %.1f m", snap.mapPositionM.x, snap.mapPositionM.y)),
                        ("Étage", "\(snap.userFloorId ?? "?")  (vu \(snap.activeFloorId ?? "?"))"),
                        ("Baromètre", snap.barometerAvailable ? String(format: "Δ %.2f m", snap.floorDeltaAltitudeM) + (snap.inVerticalTransit ? " · transit" : "") : "absent"),
                        ("Altitude", snap.altitudeFloor.map { a in "étage \(a.floorNumber)" + (a.heightAboveStreetM.map { String(format: " · %+.1f m", $0) } ?? " (supposé)") } ?? "—"),
                        ("GPS", snap.latestGpsFix.map { String(format: "±%.0f m · %.0f m du MB · décl. %.1f°", $0.accuracyM, $0.eastNorthM.norm(), $0.magneticDeclinationDeg) } ?? (snap.locationPermissionGranted ? "en attente" : "non autorisé")),
                        ("Couloir", s.corridorAssistEnabled ? "assistance ON" : "OFF"),
                    ])
                    if !s.warnings.isEmpty {
                        Text(s.warnings.map(NavText.warning).sorted().joined(separator: "\n")).lyncText(.bodySmall, .orange)
                    }
                    Text(L("nav_ios_step_note")).lyncText(.bodySmall, c.textMuted)

                    HStack(spacing: 8) {
                        Pill(text: snap.startPose.flatMap { k in snap.destinations.first { $0.key == k }?.label } ?? L("indoor_from"), style: .tonal) { picker = true }
                        Pill(text: snap.destination.flatMap { k in snap.destinations.first { $0.key == k }?.label } ?? L("indoor_to"), style: .tonal) { picker = false }
                    }
                    AccessibleRow(on: snap.accessibleOnly) { backend.setAccessibleOnly($0) }

                    Pill(text: backend.isSimulating ? L("nav_sim_stop") : L("nav_sim_walk"), style: .outline, icon: "figure.walk") {
                        if backend.isSimulating { backend.stopSimulatedWalk() } else { backend.startSimulatedWalk() }
                    }
                    switch s.phase {
                    case .idle:
                        HStack { Text(String(format: "Pas : %.2f m", stepLength)).lyncText(.titleSmall); Slider(value: $stepLength, in: 0.4...1.1, step: 0.01).tint(c.accent) }
                        HStack(spacing: 8) {
                            Pill(text: "Démarrer") { backend.startWithStepLength(stepLength) }
                            Pill(text: "Calibrer 5 m", style: .tonal) { backend.startCalibration5m() }
                            Pill(text: "GPS", style: .outline) { if snap.locationPermissionGranted { backend.useGpsAsStartOrigin() } else { backend.requestLocationPermission() } }
                        }
                    case .calibrating:
                        Text("Calibration : \(s.calibrationStepCount) pas").lyncText(.titleSmall)
                        HStack(spacing: 8) {
                            Pill(text: "J'ai marché 5 m") { backend.finishCalibration5m() }
                            Pill(text: "Arrêter", style: .tonal) { backend.stopToSetup() }
                        }
                    case .settling:
                        Text(L("indoor_settling")).lyncText(.titleSmall)
                    case .tracking, .paused:
                        if let r = snap.activeRoute { Text(r.nextInstruction).lyncText(.titleSmall) }
                        HStack(spacing: 8) {
                            if s.phase == .paused { Pill(text: "Reprendre (nouveau trajet)") { backend.resumeFromPause() } }
                            Pill(text: "Réinitialiser", style: .tonal) { backend.resetPositionToStart() }
                            Pill(text: "Couloir", style: .outline) { backend.toggleCorridorAssist() }
                            Pill(text: "Arrêter", style: .tonal) { backend.stopToSetup() }
                        }
                    }
                }
                .padding(Tokens.gutter)
            }
        }
        .background(c.bg)
        .indoorLifecycle(backend)
        .onAppear { if let saved = snap.savedStepLengthM { stepLength = saved } }
        .sheet(item: Binding(get: { picker.map { PickerWhich(start: $0) } }, set: { picker = $0?.start })) { which in
            RoomPickerSheet(title: L(which.start ? "indoor_from" : "indoor_to"),
                            items: snap.destinations.map { RoomItem(floorId: $0.key.floorId, nodeId: $0.key.nodeId, label: $0.label, name: $0.name) }) { item in
                if which.start { backend.setStartPose(item.floorId, item.nodeId) } else { backend.setDestination(NodeKey(floorId: item.floorId, nodeId: item.nodeId)) }
            }
            .environment(\.palette, c)
        }
    }

    private struct PickerWhich: Identifiable { let start: Bool; var id: Bool { start } }

    private func fmt(_ v: Double?, _ f: String) -> String { v.map { String(format: f, $0) } ?? "—" }

    private func grid(_ rows: [(String, String)]) -> some View {
        VStack(spacing: 0) {
            ForEach(rows.indices, id: \.self) { i in
                HStack(alignment: .top) {
                    Text(rows[i].0).lyncText(.labelMedium, c.textMuted).frame(width: 92, alignment: .leading)
                    Text(rows[i].1).font(.system(size: 13, design: .monospaced)).foregroundStyle(c.text)
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 5)
                if i < rows.count - 1 { Rectangle().fill(c.border).frame(height: 0.5) }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(c.surface))
    }
}
