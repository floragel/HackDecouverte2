import SwiftUI
import UIKit

// MARK: - Press feedback: subtle spring scale (Android Modifier.pressable)

struct PressableStyle: ButtonStyle {
    var scale: CGFloat = 0.96
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

extension View {
    /// Wraps the view in a button with the Lync press spring.
    func pressable(scale: CGFloat = 0.96, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { self.contentShape(Rectangle()) }.buttonStyle(PressableStyle(scale: scale))
    }
}

// MARK: - Images (cached, also readable synchronously for ImageRenderer)

final class ImageCache {
    static let shared = ImageCache()
    private let cache = NSCache<NSString, UIImage>()
    func image(_ url: String) -> UIImage? { cache.object(forKey: url as NSString) }
    func load(_ url: String) async -> UIImage? {
        if let i = image(url) { return i }
        guard let u = URL(string: url), let (data, _) = try? await URLSession.shared.data(from: u), let img = UIImage(data: data) else { return nil }
        cache.setObject(img, forKey: url as NSString)
        return img
    }
}

struct RemoteImage<Placeholder: View>: View {
    let url: String?
    @ViewBuilder var placeholder: () -> Placeholder
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image = image ?? url.flatMap({ ImageCache.shared.image($0) }) {
                Image(uiImage: image).resizable().scaledToFill()
            } else { placeholder() }
        }
        .task(id: url) { if let url { image = await ImageCache.shared.load(url) } }
    }
}

struct Avatar: View {
    @Environment(\.palette) private var c
    let url: String?
    let initials: String
    var size: CGFloat
    var ring: Color? = nil
    var square: CGFloat? = nil // corner radius for a rounded square (Lync card)

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: square ?? size / 2, style: .continuous)
        let inner = size - (ring != nil ? 10 : 0)
        RemoteImage(url: url) {
            ZStack { c.accentSoft; Text(initials).font(.text(inner * 0.36, "SemiBold")).foregroundStyle(c.accent) }
        }
        .frame(width: inner, height: inner)
        .clipShape(shape)
        .background(shape.fill(c.surfaceAlt))
        .padding(ring != nil ? 5 : 0)
        .overlay { if let ring { shape.stroke(ring, lineWidth: 2) } }
        .frame(width: size, height: size)
    }
}

// MARK: - Buttons

enum PillStyle { case filled, tonal, outline }

struct Pill: View {
    @Environment(\.palette) private var c
    let text: String
    var style: PillStyle = .filled
    var icon: String? = nil
    var fullWidth = false
    var vertical: CGFloat = 9
    let action: () -> Void

    var body: some View {
        let (bg, fg): (Color, Color) = switch style {
        case .filled: (c.accent, c.onAccent)
        case .tonal: (c.surfaceAlt, c.text)
        case .outline: (.clear, c.text)
        }
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon { Image(systemName: icon).font(.system(size: 14, weight: .semibold)) }
                Text(text).font(TS.labelLarge.font).lineLimit(1)
            }
            .foregroundStyle(fg)
            .padding(.horizontal, fullWidth ? 0 : 16).padding(.vertical, vertical)
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .background(Capsule().fill(bg))
            .overlay { if style == .outline { Capsule().stroke(c.border, lineWidth: 1) } }
            .contentShape(Capsule())
        }
        .buttonStyle(PressableStyle())
    }
}

struct PrimaryButton: View {
    @Environment(\.palette) private var c
    let text: String
    var enabled = true
    var loading = false
    var light = false // white button on a photo
    let action: () -> Void

    var body: some View {
        Button(action: { if enabled && !loading { action() } }) {
            ZStack {
                if loading { ProgressView().tint(light ? Tokens.night : c.onAccent) }
                else { Text(text).lyncText(.titleMedium, light ? Tokens.night : enabled ? c.onAccent : c.textMuted) }
            }
            .frame(maxWidth: .infinity).frame(height: 54)
            .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(light ? .white : enabled ? c.accent : c.surfaceAlt))
        }
        .buttonStyle(PressableStyle(scale: 0.98))
    }
}

/// 40pt round icon button.
struct CircleIconButton: View {
    @Environment(\.palette) private var c
    let icon: String
    var filled = false
    var size: CGFloat = 40
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(filled ? c.bg : c.text)
                .frame(width: size, height: size).background(Circle().fill(filled ? c.text : c.surface))
        }
        .buttonStyle(PressableStyle(scale: 0.9))
    }
}

// MARK: - Structure

/// Large left-aligned page title — no top app bar.
struct ScreenHeader<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: () -> Trailing
    var body: some View {
        HStack(spacing: 8) {
            Text(title).lyncText(.displaySmall).lineLimit(1).minimumScaleFactor(0.7)
            Spacer()
            trailing()
        }
        .padding(.horizontal, Tokens.gutter).frame(height: 64)
    }
}
extension ScreenHeader where Trailing == EmptyView { init(title: String) { self.title = title; self.trailing = { EmptyView() } } }

struct SectionHeader<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: () -> Trailing
    var body: some View {
        HStack { Text(title).lyncText(.titleLarge); Spacer(); trailing() }
            .padding(.horizontal, Tokens.gutter).padding(.top, 22).padding(.bottom, 10)
    }
}
extension SectionHeader where Trailing == EmptyView { init(title: String) { self.title = title; self.trailing = { EmptyView() } } }

struct LyncMark: View {
    var size: CGFloat
    var color: Color
    var body: some View { Image("LyncMark").resizable().renderingMode(.template).foregroundStyle(color).frame(width: size, height: size) }
}

struct Wordmark: View {
    @Environment(\.palette) private var c
    var color: Color? = nil
    var body: some View {
        HStack(spacing: 6) { LyncMark(size: 26, color: color ?? c.text); Text("Lync").lyncText(.headline, color ?? c.text) }
    }
}

struct DemoBadge: View {
    @Environment(\.palette) private var c
    var body: some View {
        Text(L("demo_mode")).lyncText(.labelSmall, c.textMuted).padding(.horizontal, 9).padding(.vertical, 4).background(Capsule().fill(c.surface))
    }
}

/// Segmented control (People / Events / Groups, calendar filters).
struct Segmented: View {
    @Environment(\.palette) private var c
    let options: [String]
    @Binding var selected: Int
    var body: some View {
        HStack(spacing: 0) {
            ForEach(options.indices, id: \.self) { i in
                Text(options[i]).lyncText(.labelLarge, i == selected ? c.text : c.textMuted).lineLimit(1)
                    .frame(maxWidth: .infinity).padding(.vertical, 9)
                    .background(Capsule().fill(i == selected ? c.bg : .clear))
                    .contentShape(Capsule())
                    .onTapGesture { Haptics.tick(); withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { selected = i } }
            }
        }
        .padding(4).background(Capsule().fill(c.surface))
    }
}

struct Tag: View {
    @Environment(\.palette) private var c
    let text: String
    var body: some View { Text(text).lyncText(.labelLarge).padding(.horizontal, 14).padding(.vertical, 8).background(Capsule().fill(c.surface)) }
}

/// Soft input field (no border).
struct Field: View {
    @Environment(\.palette) private var c
    let hint: String
    @Binding var text: String
    var icon: String? = nil
    var multiline = false
    var secure = false
    var keyboard: UIKeyboardType = .default
    @FocusState private var focused: Bool

    private var plain: Bool { keyboard == .emailAddress || keyboard == .URL || secure }

    var body: some View {
        HStack(alignment: multiline ? .top : .center, spacing: 8) {
            if let icon { Image(systemName: icon).foregroundStyle(c.textMuted).font(.system(size: 15)) }
            Group {
                if secure { SecureField("", text: $text, prompt: Text(hint).foregroundStyle(c.textMuted)) }
                else if multiline { TextField("", text: $text, prompt: Text(hint).foregroundStyle(c.textMuted), axis: .vertical).lineLimit(2...5) }
                else { TextField("", text: $text, prompt: Text(hint).foregroundStyle(c.textMuted)) }
            }
            .font(TS.bodyLarge.font).foregroundStyle(c.text).tint(c.accent)
            .keyboardType(keyboard).textInputAutocapitalization(plain ? .never : .sentences)
            .autocorrectionDisabled(plain)
            .focused($focused)
        }
        .padding(.horizontal, 14).padding(.vertical, 13)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(c.surface))
        .contentShape(Rectangle())
        .onTapGesture { focused = true } // the whole field is tappable, not just the text
    }
}

/// "3m", "2h", "4d" — compact like Instagram.
func timeAgo(_ date: Date) -> String {
    let s = max(0, Int(Date().timeIntervalSince(date)))
    switch s {
    case ..<60: return "\(s)s"
    case ..<3600: return "\(s / 60)m"
    case ..<86400: return "\(s / 3600)h"
    case ..<604800: return "\(s / 86400)d"
    default: return "\(s / 604800)w"
    }
}

/// "Computer Science, year 1"
func programLine(_ p: Profile) -> String { [p.program, p.year.map { L("profile_year_short", $0) }].compactMap { $0 }.joined(separator: ", ") }

/// Overlapping avatar stack.
struct AvatarStack: View {
    @Environment(\.palette) private var c
    let people: [Profile]
    var size: CGFloat = 26
    var max = 3
    var ringColor: Color? = nil
    var body: some View {
        HStack(spacing: -size * 0.33) {
            ForEach(people.prefix(max)) { p in Avatar(url: p.avatarUrl, initials: p.initials, size: size).padding(1.5).background(Circle().fill(ringColor ?? c.surface)) }
        }
    }
}

extension View {
    @ViewBuilder func `if`<T: View>(_ condition: Bool, transform: (Self) -> T) -> some View { if condition { transform(self) } else { self } }
}
