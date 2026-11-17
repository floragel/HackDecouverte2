import SwiftUI

/// The single indoor engine of the app (Android: PdrViewModel). Screens start/stop its sensors.
@MainActor
enum IndoorEngine {
    static let shared = IndoorNavigationBackend()
}

/// "MB-2" -> "2"
func floorNumberText(_ floorId: String) -> String { floorId.split(separator: "-").last.map(String.init) ?? floorId }

/// Starts the engine while a screen is visible, pauses it in the background, keeps the screen awake while walking.
struct IndoorLifecycle: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase
    let backend: IndoorNavigationBackend

    func body(content: Content) -> some View {
        let phase = backend.snapshot.pdr.phase
        content
            .onAppear { backend.onForeground() }
            .onDisappear { backend.onBackground(); UIApplication.shared.isIdleTimerDisabled = false }
            .onChange(of: scenePhase) { old, new in
                if new == .background { backend.onBackground() }
                if old == .background && new != .background { backend.onForeground() }
            }
            .onChange(of: phase, initial: true) { _, p in Self.keepAwake(p) }
    }

    private static func keepAwake(_ p: PdrPhase) {
        let walking: Set<PdrPhase> = [.calibrating, .settling, .tracking]
        UIApplication.shared.isIdleTimerDisabled = walking.contains(p)
    }
}

extension View {
    func indoorLifecycle(_ backend: IndoorNavigationBackend) -> some View { modifier(IndoorLifecycle(backend: backend)) }
}

// MARK: - Wording (EN / FR), same rules as the Android screens

enum NavText {
    static func instruction(_ route: NavigationRoute) -> String {
        if route.isArrived { return L("nav_arrived", route.destinationLabel) }
        switch route.rejoin {
        case .uTurn: return L("nav_uturn")
        case .turnRight: return L("nav_rejoin_right")
        case .turnLeft: return L("nav_rejoin_left")
        case nil: break
        }
        guard let first = route.steps.first else { return L("nav_follow") }
        switch first {
        case .walk(_, let dist, _):
            let d = max(1, Int(dist))
            guard route.steps.count > 1 else { return L("nav_walk", d) }
            switch route.steps[1] {
            case .turn(_, let left, _): return L(left ? "nav_walk_then_left" : "nav_walk_then_right", d)
            case .floorTransfer(let t): return L(t.viaElevator ? "nav_walk_to_elevator" : "nav_walk_to_stairs", d)
            case .arrive(_, let label): return L("nav_walk_to", d, label)
            default: return L("nav_walk", d)
            }
        case .turn(_, let left, _): return L(left ? "nav_turn_left" : "nav_turn_right")
        case .floorTransfer(let t): return L(t.viaElevator ? "nav_elevator" : "nav_stairs", floorNumberText(t.toFloorId))
        case .arrive(_, let label): return L("nav_arrived", label)
        }
    }

    /// SF Symbol for the next maneuver of an indoor route.
    static func icon(_ route: NavigationRoute) -> String {
        if route.isArrived { return "flag.checkered" }
        switch route.rejoin {
        case .uTurn: return "arrow.uturn.down"
        case .turnLeft: return "arrow.turn.up.left"
        case .turnRight: return "arrow.turn.up.right"
        case nil: break
        }
        var next = route.steps.first
        if case .walk = next, route.steps.count > 1 { next = route.steps[1] }
        switch next {
        case .turn(_, let left, _)?: return left ? "arrow.turn.up.left" : "arrow.turn.up.right"
        case .floorTransfer(let t)?: return t.viaElevator ? "arrow.up.arrow.down.square" : "figure.stairs"
        case .arrive?: return "flag.checkered"
        default: return "arrow.up"
        }
    }

    static func warning(_ w: PdrWarning) -> String {
        switch w {
        case .rotationSensorMissing: return L("pdr_w_rotation")
        case .stepDetectorMissing: return L("pdr_w_step_missing")
        case .fallbackSensorMissing: return L("pdr_w_fallback_missing")
        case .activityPermissionDenied: return L("indoor_steps_permission")
        case .sensorRegistrationFailed: return L("pdr_w_registration")
        case .calibrationFailedNoSteps: return L("pdr_w_cal_no_steps")
        case .calibrationFailedImplausibleLength: return L("pdr_w_cal_length")
        case .calibrationFailedNoHeading: return L("pdr_w_cal_heading")
        case .sessionInterrupted: return L("indoor_paused")
        }
    }

    /// One message at a time, like the Android warning chip. On iPhone the accelerometer *is* the
    /// step counter, so "no step detector" is not a warning there.
    static func chip(_ s: PdrState) -> String? {
        if s.tilted { return L("indoor_tilt") }
        let order: [PdrWarning] = [.rotationSensorMissing, .sensorRegistrationFailed, .fallbackSensorMissing, .calibrationFailedNoSteps,
                                   .calibrationFailedImplausibleLength, .calibrationFailedNoHeading, .sessionInterrupted]
        if let w = order.first(where: { s.warnings.contains($0) }) { return warning(w) }
        if !s.headingValid && s.phase != .idle { return L("indoor_heading_wait") }
        return nil
    }

    static func roomLine(_ label: String, _ name: String) -> String { name.isEmpty || name == label ? label : "\(label) · \(name)" }

    static func distance(_ m: Double) -> String {
        if m < 100 { return "\(Int((m / 5).rounded()) * 5) m" }
        if m < 1000 { return "\(Int((m / 10).rounded()) * 10) m" }
        return String(format: "%.1f km", locale: Lang.locale, m / 1000)
    }
}

// MARK: - Pieces shared by the MB plan and the trip screen

/// Floating round map button (heading-up, recenter…). `active` = inverted.
struct MapRoundButton: View {
    @Environment(\.palette) private var c
    let icon: String
    var active = false
    let action: () -> Void
    var body: some View {
        Image(systemName: icon).font(.system(size: 18, weight: .semibold)).foregroundStyle(active ? c.bg : c.text)
            .frame(width: 48, height: 48)
            .background(Circle().fill(active ? c.text : c.bg).shadow(color: .black.opacity(0.18), radius: 8, y: 3))
            .pressable(scale: 0.9, action)
    }
}

/// Light / dark switch right on the maps (same setting as Settings › Appearance).
struct ThemeToggleButton: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.palette) private var c
    var body: some View {
        MapRoundButton(icon: c.isDark ? "sun.max.fill" : "moon.fill") {
            Haptics.tick()
            withAnimation(Motion.standard) { env.themeMode = c.isDark ? "light" : "dark" }
        }
        .accessibilityLabel(L(c.isDark ? "map_theme_light" : "map_theme_dark"))
    }
}

/// Vertical floor switcher (7 at the top). Active floor = ink.
struct FloorPicker: View {
    @Environment(\.palette) private var c
    let floors: [String]
    let active: String?
    let onPick: (String) -> Void
    var body: some View {
        if floors.count >= 2 {
            VStack(spacing: 2) {
                ForEach(floors.sorted { IndoorAssets.floorNumber($0) > IndoorAssets.floorNumber($1) }, id: \.self) { id in
                    let on = id == active
                    Text(floorNumberText(id)).lyncText(.titleSmall, on ? c.bg : c.text)
                        .frame(width: 40, height: 40).background(Circle().fill(on ? c.text : c.bg))
                        .pressable(scale: 0.9) { onPick(id) }
                }
            }
            .padding(4)
            .background(Capsule().fill(c.bg).shadow(color: .black.opacity(0.18), radius: 8, y: 3))
        }
    }
}

struct RoomItem: Identifiable, Hashable {
    let floorId: String, nodeId: String, label: String, name: String
    var id: String { floorId + "/" + nodeId }
}

/// Searchable room list (rooms, stairs, elevators, exits) of the MB graph.
struct RoomPickerSheet: View {
    @Environment(\.palette) private var c
    @Environment(\.dismiss) private var dismiss
    let title: String
    let items: [RoomItem]
    let onPick: (RoomItem) -> Void
    @State private var query = ""

    var body: some View {
        let q = query.trimmingCharacters(in: .whitespaces)
        let filtered = q.isEmpty ? items : items.filter { BuildingGraph.matchesSearchQuery(q, label: $0.label, name: $0.name, floorId: $0.floorId) }
        VStack(alignment: .leading, spacing: 12) {
            Text(title).lyncText(.titleLarge).padding(.top, 24)
            Field(hint: L("indoor_to_hint"), text: $query, icon: "magnifyingglass")
            if filtered.isEmpty {
                Text(L("indoor_no_result")).lyncText(.bodyMedium, c.textMuted).padding(.vertical, 24)
            }
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(filtered.prefix(400)) { item in
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(item.label.isEmpty ? item.name : item.label).lyncText(.titleSmall)
                                if !item.name.isEmpty && item.name != item.label { Text(item.name).lyncText(.bodySmall, c.textMuted).lineLimit(1) }
                            }
                            Spacer()
                            Text(L("indoor_floor", floorNumberText(item.floorId))).lyncText(.labelMedium, c.textMuted)
                                .padding(.horizontal, 10).padding(.vertical, 4).background(Capsule().fill(c.surface))
                        }
                        .padding(.vertical, 12)
                        .pressable(scale: 0.99) { onPick(item); dismiss() }
                        Rectangle().fill(c.border).frame(height: 0.5)
                    }
                }
            }
        }
        .padding(.horizontal, Tokens.gutter)
        .background(c.bg)
        .presentationDetents([.large])
        .presentationCornerRadius(Tokens.radiusCard)
    }
}

/// Inverted message chip ("Hold your phone a little flatter"…).
struct HintChip: View {
    @Environment(\.palette) private var c
    let text: String
    var body: some View {
        Text(text).lyncText(.labelLarge, c.bg).lineLimit(2)
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(c.text).shadow(color: .black.opacity(0.2), radius: 8, y: 3))
    }
}

/// "Step-free route" switch.
struct AccessibleRow: View {
    @Environment(\.palette) private var c
    let on: Bool
    let onChange: (Bool) -> Void
    var body: some View {
        Toggle(isOn: Binding(get: { on }, set: onChange)) {
            VStack(alignment: .leading, spacing: 1) {
                Text(L("indoor_accessible")).lyncText(.titleSmall)
                Text(L("indoor_accessible_desc")).lyncText(.bodySmall, c.textMuted)
            }
        }
        .tint(c.text)
    }
}
