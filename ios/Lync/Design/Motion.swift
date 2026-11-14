import SwiftUI
import UIKit

/// Lync motion system — same values as Android `ui/motion/Motion.kt` (design/README.md › Motion).
/// One signature moment (two people connecting); everything else only answers a gesture.
enum Motion {
    static let press = Animation.spring(response: 0.25, dampingFraction: 0.6)
    static let standard = Animation.spring(response: 0.35, dampingFraction: 0.8)
    static let bouncy = Animation.spring(response: 0.4, dampingFraction: 0.5)
    static let stagger = 0.07
    static var reduced: Bool { UIAccessibility.isReduceMotionEnabled }
}

/// tick = selection, confirm = light impact, success = two people connected.
enum Haptics {
    static func tick() { UISelectionFeedbackGenerator().selectionChanged() }
    static func confirm() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
}

/// A number whose digits roll up / down.
struct RollingNumber: View {
    let value: Int
    var style: TS = .titleSmall
    var body: some View {
        Text("\(value)").lyncText(style).contentTransition(.numericText(value: Double(value))).animation(Motion.standard, value: value)
    }
}

/// The Lync mark drawn as its two links, each with its own offset (in 1024 viewBox units).
struct LyncMarkParts: View {
    var size: CGFloat
    var color: Color
    var upright: CGSize = .zero
    var horizontal: CGSize = .zero
    var uprightAlpha: Double = 1
    var horizontalAlpha: Double = 1
    var scale: CGFloat = 1
    var body: some View {
        let k = size / 1024
        ZStack {
            Image("LyncMarkUpright").resizable().renderingMode(.template).foregroundStyle(color)
                .offset(x: upright.width * k, y: upright.height * k).opacity(uprightAlpha)
            Image("LyncMarkHorizontal").resizable().renderingMode(.template).foregroundStyle(color)
                .offset(x: horizontal.width * k, y: horizontal.height * k).opacity(horizontalAlpha)
        }
        .frame(width: size, height: size).scaleEffect(scale)
    }
}

/// Launch: the links pull apart and snap back together ("click"), then "Lync" fades in.
struct SplashView: View {
    var onDone: () -> Void
    @State private var apart = false
    @State private var pop: CGFloat = 1
    @State private var word = false

    var body: some View {
        ZStack {
            Tokens.night.ignoresSafeArea()
            LyncMarkParts(size: 148, color: .white, upright: CGSize(width: 0, height: apart ? -90 : 0), horizontal: CGSize(width: apart ? 140 : 0, height: 0), scale: pop)
            Text("Lync").font(.display(28)).foregroundStyle(.white).opacity(word ? 1 : 0).offset(y: word ? 96 : 106)
        }
        .task {
            if Motion.reduced { onDone(); return }
            try? await Task.sleep(nanoseconds: 120_000_000)
            withAnimation(.easeInOut(duration: 0.17)) { apart = true }
            try? await Task.sleep(nanoseconds: 170_000_000)
            withAnimation(.spring(response: 0.28, dampingFraction: 0.55)) { apart = false }
            try? await Task.sleep(nanoseconds: 200_000_000)
            withAnimation(.easeOut(duration: 0.06)) { pop = 1.07 }
            try? await Task.sleep(nanoseconds: 60_000_000)
            withAnimation(Motion.bouncy) { pop = 1 }
            withAnimation(.easeOut(duration: 0.22)) { word = true }
            try? await Task.sleep(nanoseconds: 380_000_000)
            onDone()
        }
    }
}

/// THE signature moment (~1.5 s): avatars fly in, the two links interlock, a ring bursts, double haptic.
struct ConnectCelebration: View {
    @Environment(\.palette) private var c
    let me: Profile?
    let other: Profile
    let sharedCourses: Int
    var onDone: () -> Void

    @State private var bg = false
    @State private var fly = false
    @State private var links = false
    @State private var click: CGFloat = 1
    @State private var ring: CGFloat = 0
    @State private var text = false

    var body: some View {
        ZStack {
            Tokens.nightBg.opacity(bg ? 0.94 : 0).ignoresSafeArea()
            Circle().stroke(c.accent.opacity(Double(1 - ring) * 0.9), lineWidth: 10 * (1 - ring) + 1)
                .frame(width: 80 + 240 * ring, height: 80 + 240 * ring)
            VStack(spacing: 28) {
                ZStack {
                    avatar(me, bg: .white).offset(x: fly ? -78 : -230).opacity(fly ? 1 : 0)
                    avatar(other, bg: c.accent).offset(x: fly ? 78 : 230).opacity(fly ? 1 : 0)
                    LyncMarkParts(size: 64, color: .white, upright: CGSize(width: links ? 0 : -300, height: 0), horizontal: CGSize(width: links ? 0 : 300, height: 0),
                                  uprightAlpha: links ? 1 : 0, horizontalAlpha: links ? 1 : 0, scale: click)
                }
                .frame(width: 300, height: 120)
                VStack(spacing: 6) {
                    Text(L("celebrate_title", other.firstName)).font(.display(28)).foregroundStyle(.white).multilineTextAlignment(.center)
                    if sharedCourses > 0 { Text(LP("celebrate_courses", sharedCourses)).font(.text(16)).foregroundStyle(.white.opacity(0.75)) }
                }
                .opacity(text ? 1 : 0).offset(y: text ? 0 : 24)
            }
        }
        .task {
            withAnimation(.easeOut(duration: Motion.reduced ? 0 : 0.16)) { bg = true }
            if Motion.reduced { fly = true; links = true; text = true; Haptics.success(); try? await Task.sleep(nanoseconds: 900_000_000); onDone(); return }
            withAnimation(.spring(response: 0.5, dampingFraction: 0.75)) { fly = true }
            try? await Task.sleep(nanoseconds: 220_000_000)
            withAnimation(.easeInOut(duration: 0.32)) { links = true }
            try? await Task.sleep(nanoseconds: 320_000_000)
            Haptics.success()
            withAnimation(.easeOut(duration: 0.62)) { ring = 1 }
            withAnimation(.easeOut(duration: 0.08)) { click = 1.12 }
            try? await Task.sleep(nanoseconds: 80_000_000)
            withAnimation(Motion.bouncy) { click = 1 }
            withAnimation(.easeOut(duration: 0.26)) { text = true }
            try? await Task.sleep(nanoseconds: 800_000_000)
            onDone()
        }
    }

    private func avatar(_ p: Profile?, bg: Color) -> some View {
        Avatar(url: p?.avatarUrl, initials: p?.initials ?? "", size: 76).padding(3).background(Circle().fill(bg))
    }
}
