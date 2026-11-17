import SwiftUI
import AVFoundation
import Observation

// Directions design: one maneuver card for every screen (big distance, next step, floor badge),
// the full list of steps, a progress bar, voice guidance (EN/FR) and a vibration on each new instruction.

extension NavText {
    /// One step of the list, in words.
    static func step(_ s: RouteStep) -> String {
        switch s {
        case .walk(_, let d, let label):
            let base = L("nav_walk", max(1, Int(d)))
            return label.isEmpty ? base : base + " · " + label
        case .turn(_, let left, let into):
            let base = L(left ? "nav_turn_left" : "nav_turn_right")
            return into.isEmpty ? base : base + " · " + into
        case .floorTransfer(let t): return L(t.viaElevator ? "nav_elevator" : "nav_stairs", floorNumberText(t.toFloorId))
        case .arrive(_, let label): return L("nav_arrived", label)
        }
    }

    static func stepIcon(_ s: RouteStep) -> String {
        switch s {
        case .walk: "arrow.up"
        case .turn(_, let left, _): left ? "arrow.turn.up.left" : "arrow.turn.up.right"
        case .floorTransfer(let t): t.viaElevator ? "arrow.up.arrow.down.square" : "figure.stairs"
        case .arrive: "flag.checkered"
        }
    }

    /// What comes after the current maneuver ("Then: take the elevator to floor 4").
    static func then(_ route: NavigationRoute) -> RouteStep? {
        guard !route.isArrived, route.rejoin == nil else { return nil }
        var i = 0
        if case .walk? = route.steps.first { i = 1 } // the card already says "go straight … then X"
        let next = i + 1
        return route.steps.indices.contains(next) ? route.steps[next] : nil
    }

    /// Floor the current maneuver leads to, if it changes floor.
    static func targetFloor(_ route: NavigationRoute) -> String? {
        let candidates = route.steps.prefix(2)
        for s in candidates { if case .floorTransfer(let t) = s { return floorNumberText(t.toFloorId) } }
        return nil
    }

    /// Distance of the current maneuver.
    static func currentDistance(_ route: NavigationRoute) -> String? {
        // exact metre indoors, same number as the sentence below it
        if case .walk(_, let d, _)? = route.steps.first, !route.isArrived { return "\(max(1, Int(d))) m" }
        return nil
    }

    /// Spoken/vibration key: the instruction without its changing distance, so "12 m" -> "11 m" stays quiet.
    static func guidanceKey(_ route: NavigationRoute) -> String {
        if route.isArrived { return "arrived-\(route.destinationLabel)" }
        if let r = route.rejoin { return "rejoin-\(r)" }
        let kinds = route.steps.prefix(2).map { s -> String in
            switch s {
            case .walk: "walk"
            case .turn(_, let l, _): l ? "left" : "right"
            case .floorTransfer(let t): "floor-\(t.toFloorId)"
            case .arrive(_, let l): "arrive-\(l)"
            }
        }
        return kinds.joined(separator: ">")
    }
}

/// Voice guidance (AVSpeechSynthesizer), in the app language. Off by default, remembered.
@MainActor
@Observable
final class NavVoice {
    static let shared = NavVoice()
    var enabled: Bool = UserDefaults.standard.bool(forKey: "nav_voice") { didSet { UserDefaults.standard.set(enabled, forKey: "nav_voice"); if !enabled { synth.stopSpeaking(at: .immediate) } } }
    @ObservationIgnored private let synth = AVSpeechSynthesizer()
    @ObservationIgnored private var lastKey: String?

    /// Speaks (and vibrates) only when the meaning of the instruction changes.
    func guide(key: String, text: String) {
        guard key != lastKey else { return }
        let first = lastKey == nil
        lastKey = key
        if !first { Haptics.confirm() }
        guard enabled else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        synth.stopSpeaking(at: .word)
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: Lang.current == "fr" ? "fr-CA" : "en-CA")
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 1.02
        synth.speak(u)
    }

    func reset() { lastKey = nil }
}

/// Speaker button for voice guidance.
struct VoiceButton: View {
    private var voice: NavVoice { NavVoice.shared }
    var body: some View {
        MapRoundButton(icon: voice.enabled ? "speaker.wave.2.fill" : "speaker.slash.fill", active: voice.enabled) {
            Haptics.tick(); voice.enabled.toggle()
        }
        .accessibilityLabel(L("nav_voice"))
    }
}

/// Google-Maps-like card in the university colour: icon, big distance, instruction, floor badge, "Then: …".
struct ManeuverCard: View {
    @Environment(\.palette) private var c
    let icon: String
    let distance: String?
    let text: String
    var then: String? = nil
    var thenIcon: String? = nil
    var floorBadge: String? = nil
    var progress: Double? = nil
    var arrived = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                Image(systemName: arrived ? "checkmark.circle.fill" : icon).font(.system(size: 34, weight: .semibold)).foregroundStyle(c.onAccent)
                    .frame(width: 46).contentTransition(.symbolEffect(.replace))
                VStack(alignment: .leading, spacing: 1) {
                    if let distance, !arrived { Text(distance).font(.display(28, "Bold")).foregroundStyle(c.onAccent).contentTransition(.numericText()) }
                    Text(text).lyncText(.titleMedium, c.onAccent.opacity(0.94)).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                if let floorBadge {
                    VStack(spacing: 0) {
                        Text(L("indoor_floor_short")).font(.text(10, "SemiBold")).foregroundStyle(c.accent.opacity(0.8))
                        Text(floorBadge).font(.display(26, "Bold")).foregroundStyle(c.accent)
                    }
                    .frame(width: 54, height: 54)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(c.onAccent))
                    .transition(.scale.combined(with: .opacity))
                }
            }
            .padding(.horizontal, 18).padding(.vertical, 16)
            if let progress {
                GeometryReader { g in
                    Capsule().fill(c.onAccent.opacity(0.22))
                        .overlay(alignment: .leading) { Capsule().fill(c.onAccent).frame(width: max(6, g.size.width * min(1, max(0, progress)))) }
                }
                .frame(height: 4).padding(.horizontal, 18).padding(.bottom, 12)
                .animation(Motion.standard, value: progress)
            }
            if let then {
                HStack(spacing: 8) {
                    if let thenIcon { Image(systemName: thenIcon).font(.system(size: 13, weight: .bold)) }
                    Text(L("go_then", then)).lyncText(.labelLarge, c.onAccent).lineLimit(1)
                    Spacer(minLength: 0)
                }
                .foregroundStyle(c.onAccent)
                .padding(.horizontal, 18).padding(.vertical, 9).background(c.accent.darker(0.22))
            }
        }
        .background(c.accent)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .shadow(color: .black.opacity(0.25), radius: 14, y: 4)
        .animation(Motion.standard, value: text)
    }
}

extension ManeuverCard {
    /// The indoor card for a route (same everywhere: trip screen and MB plan).
    init(route: NavigationRoute, progress: Double?) {
        let next = NavText.then(route)
        self.init(icon: NavText.icon(route), distance: NavText.currentDistance(route), text: NavText.instruction(route),
                  then: next.map(NavText.step), thenIcon: next.map(NavText.stepIcon), floorBadge: NavText.targetFloor(route),
                  progress: progress, arrived: route.isArrived)
    }
}

/// Remembers the longest remaining distance seen for a destination, so the bar shows how far along you are.
@MainActor
@Observable
final class RouteProgress {
    private var totals: [NodeKey: Double] = [:]
    func fraction(_ route: NavigationRoute?) -> Double? {
        guard let r = route, !r.isArrived, r.totalDistanceM > 0 else { return route?.isArrived == true ? 1 : nil }
        let total = max(totals[r.destination] ?? 0, r.totalDistanceM)
        if total != totals[r.destination] { totals[r.destination] = total }
        return total > 0 ? 1 - r.totalDistanceM / total : nil
    }
}

/// Every step of the route, with icons, floors and the arrival.
struct RouteStepsSheet: View {
    @Environment(\.palette) private var c
    let route: NavigationRoute

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text(L("nav_steps")).lyncText(.titleLarge)
                Spacer()
                Text(NavText.distance(route.totalDistanceM) + " · " + L("go_minutes", max(1, Int((route.totalDistanceM / 1.2 / 60).rounded())))).lyncText(.titleSmall, c.textMuted)
            }
            .padding(.top, 26).padding(.bottom, 14)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(route.steps.enumerated()), id: \.offset) { i, s in
                        HStack(alignment: .top, spacing: 14) {
                            VStack(spacing: 0) {
                                Image(systemName: NavText.stepIcon(s)).font(.system(size: 15, weight: .bold))
                                    .foregroundStyle(i == 0 ? c.onAccent : c.text)
                                    .frame(width: 36, height: 36)
                                    .background(Circle().fill(i == 0 ? c.accent : c.surface))
                                if i < route.steps.count - 1 { Rectangle().fill(c.border).frame(width: 2).frame(minHeight: 18) }
                            }
                            VStack(alignment: .leading, spacing: 3) {
                                Text(NavText.step(s)).lyncText(i == 0 ? .titleSmall : .bodyLarge).fixedSize(horizontal: false, vertical: true)
                                HStack(spacing: 6) {
                                    Text(L("indoor_floor", floorNumberText(s.floorId))).lyncText(.labelMedium, c.textMuted)
                                    if case .floorTransfer(let t) = s, !t.viaLabel.isEmpty { Text("· " + t.viaLabel).lyncText(.labelMedium, c.textMuted).lineLimit(1) }
                                }
                            }
                            .padding(.top, 7).padding(.bottom, 12)
                            Spacer(minLength: 0)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, Tokens.gutter)
        .background(c.bg)
        .presentationDetents([.medium, .large])
        .presentationCornerRadius(Tokens.radiusCard)
    }
}
