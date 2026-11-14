import SwiftUI
import CoreMotion

/// Draws a Lync code. Same geometry as `LyncCode.isInk` (which the decoder relies on).
struct LyncCodeView: View {
    let code: Int64
    var ink: Color
    var data: Color

    var body: some View {
        let bits = LyncCode.encode(code)
        ZStack {
            Canvas { ctx, size in
                let r = min(size.width, size.height) / 2
                let c = CGPoint(x: size.width / 2, y: size.height / 2)
                let finderW = (1 - LyncCode.finderInner) * r
                ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r + finderW / 2, y: c.y - r + finderW / 2, width: 2 * r - finderW, height: 2 * r - finderW)), with: .color(ink), lineWidth: finderW)
                let seg = 2 * Double.pi / Double(LyncCode.segments)
                for (ringR, ring) in [(LyncCode.ringOuter, bits.0), (LyncCode.ringInner, bits.1)] {
                    let rr = ringR * r, stroke = LyncCode.stroke * r
                    func center(_ s: Int) -> CGPoint { let a = LyncCode.segmentAngle(s) - .pi / 2; return CGPoint(x: c.x + rr * cos(a), y: c.y + rr * sin(a)) }
                    guard let start = (0..<LyncCode.segments).first(where: { !ring[$0] }) else {
                        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr)), with: .color(data), lineWidth: stroke); continue
                    }
                    var i = 0
                    while i < LyncCode.segments {
                        let s = (start + i) % LyncCode.segments
                        if !ring[s] {
                            let p = center(s); let d = LyncCode.dot * r
                            ctx.fill(Path(ellipseIn: CGRect(x: p.x - d, y: p.y - d, width: 2 * d, height: 2 * d)), with: .color(data)); i += 1; continue
                        }
                        var len = 0
                        while i + len < LyncCode.segments && ring[(start + i + len) % LyncCode.segments] { len += 1 }
                        if len == 1 {
                            let p = center(s)
                            ctx.fill(Path(ellipseIn: CGRect(x: p.x - stroke / 2, y: p.y - stroke / 2, width: stroke, height: stroke)), with: .color(data))
                        } else {
                            var path = Path()
                            let a0 = Double(s) * seg - .pi / 2
                            path.addArc(center: c, radius: rr, startAngle: .radians(a0), endAngle: .radians(a0 + Double(len - 1) * seg), clockwise: false)
                            ctx.stroke(path, with: .color(data), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                        }
                        i += len
                    }
                }
            }
            GeometryReader { g in LyncMark(size: g.size.width * 0.42, color: ink).position(x: g.size.width / 2, y: g.size.height / 2) }
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

/// Device tilt in [-1, 1] from the gyroscope (CoreMotion attitude), relative to how the phone
/// is held, slowly re-centering. Same feel as Android `rememberTilt()`.
@Observable
final class TiltMotion {
    var x: Double = 0
    var y: Double = 0
    private let manager = CMMotionManager()
    private var basePitch: Double?
    private var baseRoll: Double = 0
    private var users = 0

    func start() {
        users += 1
        guard users == 1, manager.isDeviceMotionAvailable else { return }
        manager.deviceMotionUpdateInterval = 1 / 60
        manager.startDeviceMotionUpdates(to: .main) { [weak self] m, _ in
            guard let self, let a = m?.attitude else { return }
            let landscape = UIDevice.current.orientation.isLandscape
            var pitch = a.pitch, roll = a.roll
            if landscape { swap(&pitch, &roll); if UIDevice.current.orientation == .landscapeRight { roll = -roll } else { pitch = -pitch } }
            if self.basePitch == nil { self.basePitch = pitch; self.baseRoll = roll }
            self.basePitch! += (pitch - self.basePitch!) * 0.004
            self.baseRoll += (roll - self.baseRoll) * 0.004
            let tx = max(-1, min(1, (roll - self.baseRoll) / 0.42)), ty = max(-1, min(1, (pitch - self.basePitch!) / 0.42))
            self.x += (tx - self.x) * 0.18
            self.y += (ty - self.y) * 0.18
        }
    }

    func stop() { users = max(0, users - 1); if users == 0 { manager.stopDeviceMotionUpdates(); basePitch = nil } }
}

/// Holographic foil: rainbow film following the tilt, moving glint, sparkles, edge light.
struct Hologram: View {
    var tilt: CGPoint
    var strength: Double = 1
    private static let sparkles: [(Double, Double, Double)] = {
        var s: UInt64 = 7
        func rnd() -> Double { s = s &* 6364136223846793005 &+ 1442695040888963407; return Double((s >> 33) % 10000) / 10000 }
        return (0..<46).map { _ in (rnd(), rnd(), rnd() * 2 * .pi) }
    }()

    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height
            let mag = min(1.4, abs(tilt.x) + abs(tilt.y))
            let k = strength * (0.45 + 0.55 * mag)
            let angle = atan2(tilt.y, tilt.x) + .pi / 4
            ZStack {
                LinearGradient(colors: [0xFF6EC7, 0x7DF9FF, 0xB388FF, 0xFFF07D, 0x7DFFB2, 0xFF6EC7].map { Color(hex: $0).opacity(0.30 * k) },
                               startPoint: UnitPoint(x: 0.5 - cos(angle) * 0.9, y: 0.5 - sin(angle) * 0.9), endPoint: UnitPoint(x: 0.5 + cos(angle) * 0.9, y: 0.5 + sin(angle) * 0.9))
                    .blendMode(.overlay)
                let gl = 0.5 + tilt.x * 0.55 + tilt.y * 0.35
                LinearGradient(stops: [.init(color: .clear, location: max(0, min(1, gl - 0.12))), .init(color: .white.opacity(0.3 * k), location: max(0, min(1, gl))),
                                       .init(color: .clear, location: max(0, min(1, gl + 0.12)))], startPoint: .topLeading, endPoint: .bottomTrailing)
                    .blendMode(.screen)
                Canvas { ctx, _ in
                    for (sx, sy, phase) in Self.sparkles {
                        let b = pow(max(0, cos(phase + tilt.x * 7 + tilt.y * 5)), 3) * min(1, 0.2 + mag)
                        guard b > 0.05 else { continue }
                        let p = CGPoint(x: sx * w, y: sy * h), rr = 1.2 + 2.6 * b
                        ctx.fill(Path(ellipseIn: CGRect(x: p.x - rr, y: p.y - rr, width: 2 * rr, height: 2 * rr)), with: .color(.white.opacity(0.85 * b * strength)))
                        var cross = Path(); cross.move(to: CGPoint(x: p.x - rr * 3, y: p.y)); cross.addLine(to: CGPoint(x: p.x + rr * 3, y: p.y))
                        cross.move(to: CGPoint(x: p.x, y: p.y - rr * 3)); cross.addLine(to: CGPoint(x: p.x, y: p.y + rr * 3))
                        ctx.stroke(cross, with: .color(.white.opacity(0.5 * b * strength)), lineWidth: 0.8)
                    }
                }
                .blendMode(.screen)
                RadialGradient(colors: [.white.opacity(0.2 * k), .clear], center: UnitPoint(x: 0.5 + tilt.x * 0.6, y: 0.5 + tilt.y * 0.6), startRadius: 0, endRadius: w * 0.5)
                    .blendMode(.overlay)
            }
        }
        .allowsHitTesting(false)
    }
}

/// The Lync card front. Everything scales from the card width (k = width / 340).
struct LyncCardFront: View {
    @Environment(\.palette) private var c
    let profile: Profile
    let university: University?
    var tilt: CGPoint = .zero
    var shine: Double = 0

    var body: some View {
        GeometryReader { g in
            let k = g.size.width / 340
            ZStack {
                LinearGradient(colors: [c.accent, c.accent.darker(0.45)], startPoint: .topLeading, endPoint: .bottomTrailing)
                LyncMark(size: 210 * k, color: .white.opacity(0.08))
                    .position(x: g.size.width - 65 * k - tilt.x * 14 * k, y: g.size.height - 55 * k - tilt.y * 14 * k)
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Text(university?.shortName ?? "").font(.display(17 * k, "SemiBold")).foregroundStyle(.white)
                        Spacer()
                        LyncMark(size: 20 * k, color: .white.opacity(0.9))
                    }
                    Spacer()
                    HStack(alignment: .bottom, spacing: 14 * k) {
                        Avatar(url: profile.avatarUrl, initials: profile.initials, size: 70 * k, square: 16 * k)
                            .padding(2.5 * k).background(RoundedRectangle(cornerRadius: 18 * k).fill(.white.opacity(0.22)))
                        VStack(alignment: .leading, spacing: 0) {
                            if let s = StatusPreset.of(profile.statusKey) {
                                Text("\(s.emoji) \(L(s.labelKey))").font(.text(11.5 * k)).foregroundStyle(.white).lineLimit(1)
                                    .padding(.horizontal, 9 * k).padding(.vertical, 4 * k).background(Capsule().fill(.white.opacity(0.18))).padding(.bottom, 8 * k)
                            }
                            Text(profile.fullName).font(.display(22 * k)).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.7)
                            Text(programLine(profile)).font(.text(13 * k)).foregroundStyle(.white.opacity(0.78)).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                }
                .padding(20 * k)
                .offset(x: tilt.x * 3 * k, y: tilt.y * 3 * k)
                if shine > 0 { Hologram(tilt: tilt, strength: shine) }
            }
        }
        .aspectRatio(1.586, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: Tokens.radiusCard, style: .continuous))
    }
}

struct LyncCardBack: View {
    @Environment(\.palette) private var c
    let profile: Profile
    let university: University?
    var tilt: CGPoint = .zero
    var shine: Double = 0

    var body: some View {
        GeometryReader { g in
            let k = g.size.width / 340
            ZStack {
                Color.white
                HStack(spacing: 18 * k) {
                    if let code = profile.lyncCode { LyncCodeView(code: code, ink: Tokens.night, data: c.accent) }
                    VStack(alignment: .leading, spacing: 0) {
                        Text(university?.shortName ?? "").font(.display(14 * k, "SemiBold")).foregroundStyle(Tokens.slate)
                        Spacer()
                        Text(L("card_scan_me")).font(.display(20 * k)).foregroundStyle(Tokens.night)
                        Text(L("card_tap_nfc")).font(.text(12 * k)).foregroundStyle(Tokens.slate).padding(.top, 6 * k)
                        Spacer()
                        Text("@\(profile.username)").font(.text(11 * k)).foregroundStyle(Tokens.slate).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .padding(18 * k)
                if shine > 0 { Hologram(tilt: tilt, strength: shine * 0.6) }
            }
        }
        .aspectRatio(1.586, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: Tokens.radiusCard, style: .continuous))
    }
}

/// The card as a profile header: tilts with the phone, tap to flip to the Lync code.
struct FlippableCard: View {
    let profile: Profile
    let university: University?
    @State private var flipped = false
    @State private var tilt = TiltMotion()

    var body: some View {
        let t = CGPoint(x: tilt.x, y: tilt.y)
        ZStack {
            LyncCardFront(profile: profile, university: university, tilt: t, shine: 0.7).opacity(flipped ? 0 : 1)
            LyncCardBack(profile: profile, university: university, tilt: t, shine: 0.5).rotation3DEffect(.degrees(180), axis: (0, 1, 0)).opacity(flipped ? 1 : 0)
        }
        .rotation3DEffect(.degrees((flipped ? 180 : 0) + tilt.x * 8), axis: (0, 1, 0), perspective: 0.4)
        .rotation3DEffect(.degrees(-tilt.y * 6), axis: (1, 0, 0), perspective: 0.4)
        .padding(.horizontal, Tokens.gutter).padding(.vertical, 4)
        .onTapGesture { Haptics.tick(); withAnimation(.spring(response: 0.55, dampingFraction: 0.75)) { flipped.toggle() } }
        .onAppear { tilt.start() }
        .onDisappear { tilt.stop() }
    }
}

/// Shown automatically, from any screen, when the phone is turned landscape.
struct StudentCardScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.palette) private var c
    let me: Profile
    @State private var university: University?
    @State private var flipped = false
    @State private var tilt = TiltMotion()

    var body: some View {
        let t = CGPoint(x: tilt.x, y: tilt.y)
        let profile = env.me ?? me
        ZStack {
            Tokens.nightBg.ignoresSafeArea()
            RadialGradient(colors: [c.accent.opacity(0.32), .clear], center: .center, startRadius: 0, endRadius: 420).ignoresSafeArea()
            VStack(spacing: 16) {
                ZStack {
                    LyncCardFront(profile: profile, university: university, tilt: t, shine: 1).opacity(flipped ? 0 : 1)
                    LyncCardBack(profile: profile, university: university, tilt: t, shine: 1).rotation3DEffect(.degrees(180), axis: (0, 1, 0)).opacity(flipped ? 1 : 0)
                }
                .frame(maxHeight: UIScreen.main.bounds.height * 0.72)
                .shadow(color: .black.opacity(0.5), radius: 28, y: 10)
                .rotation3DEffect(.degrees((flipped ? 180 : 0) + tilt.x * 14), axis: (0, 1, 0), perspective: 0.4)
                .rotation3DEffect(.degrees(-tilt.y * 12), axis: (1, 0, 0), perspective: 0.4)
                .onTapGesture { Haptics.tick(); withAnimation(.spring(response: 0.55, dampingFraction: 0.75)) { flipped.toggle() } }
                Text(L("card_tap_flip")).font(TS.labelMedium.font).foregroundStyle(.white.opacity(0.45))
            }
        }
        .task { university = try? await env.repository.university(me.universityId) }
        .onAppear { tilt.start() }
        .onDisappear { tilt.stop() }
    }
}
