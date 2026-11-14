import SwiftUI
import UIKit

// MARK: - Localization (EN default, FR) with an in-app language switch

enum Lang {
    static var current: String {
        get { UserDefaults.standard.string(forKey: "lang") ?? (Locale.preferredLanguages.first?.hasPrefix("fr") == true ? "fr" : "en") }
        set { UserDefaults.standard.set(newValue, forKey: "lang") }
    }
    static var bundle: Bundle {
        Bundle.main.path(forResource: current, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
    }
    static var locale: Locale { Locale(identifier: current == "fr" ? "fr_CA" : "en_CA") }
}

/// Localized string (same keys as Android strings.xml).
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = Lang.bundle.localizedString(forKey: key, value: key, table: nil)
    return args.isEmpty ? format : String(format: format, locale: Lang.locale, arguments: args)
}

/// Plural: `key_one` / `key_other` (French: 0 and 1 are singular).
func LP(_ key: String, _ count: Int, _ args: CVarArg...) -> String {
    let one = Lang.current == "fr" ? count <= 1 : count == 1
    let format = Lang.bundle.localizedString(forKey: key + (one ? "_one" : "_other"), value: key, table: nil)
    return String(format: format, locale: Lang.locale, arguments: args.isEmpty ? [count] : args)
}

// MARK: - Tokens (same values as Android Tokens.kt — see design/CHARTE_GRAPHIQUE.md)

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
    init?(hexString: String) {
        var s = hexString.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(hex: v)
    }
    /// Mix toward black (iOS 17-compatible).
    func darker(_ amount: Double) -> Color {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(self).getRed(&r, green: &g, blue: &b, alpha: &a)
        let k = 1 - amount
        return Color(.sRGB, red: r * k, green: g * k, blue: b * k, opacity: a)
    }
    var luminance: Double {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(self).getRed(&r, green: &g, blue: &b, alpha: &a)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}

enum Tokens {
    static let paper = Color(hex: 0xFFFFFF), mist = Color(hex: 0xF3F4F7), fog = Color(hex: 0xE8EAF0), line = Color(hex: 0xE3E5EB)
    static let night = Color(hex: 0x1A1D29), slate = Color(hex: 0x6B7081)
    static let nightBg = Color(hex: 0x10121A), nightSurface = Color(hex: 0x1A1D29), nightSurfaceAlt = Color(hex: 0x252938)
    static let nightLine = Color(hex: 0x2A2E3D), nightSlate = Color(hex: 0x9398A8)
    static let live = Color(hex: 0x2FD07A)

    static let radiusCard: CGFloat = 28, radiusMedia: CGFloat = 22, radiusControl: CGFloat = 16
    static let gutter: CGFloat = 20
}

/// The whole app is neutral; the ONLY color comes from the student's university.
struct Palette {
    var accent: Color, accent2: Color, onAccent: Color, accentSoft: Color
    var bg: Color, surface: Color, surfaceAlt: Color, text: Color, textMuted: Color, border: Color
    var isDark: Bool

    static func make(accentHex: String, accent2Hex: String, dark: Bool) -> Palette {
        let a = Color(hexString: accentHex) ?? Color(hex: 0x912338)
        let a2 = Color(hexString: accent2Hex) ?? Color(hex: 0xD4A12A)
        let on = a.luminance > 0.5 ? Tokens.night : .white
        return dark
            ? Palette(accent: a, accent2: a2, onAccent: on, accentSoft: a.opacity(0.2), bg: Tokens.nightBg, surface: Tokens.nightSurface, surfaceAlt: Tokens.nightSurfaceAlt,
                      text: .white, textMuted: Tokens.nightSlate, border: Tokens.nightLine, isDark: true)
            : Palette(accent: a, accent2: a2, onAccent: on, accentSoft: a.opacity(0.09), bg: Tokens.paper, surface: Tokens.mist, surfaceAlt: Tokens.fog,
                      text: Tokens.night, textMuted: Tokens.slate, border: Tokens.line, isDark: false)
    }
}

private struct PaletteKey: EnvironmentKey { static let defaultValue = Palette.make(accentHex: "#912338", accent2Hex: "#D4A12A", dark: false) }
extension EnvironmentValues { var palette: Palette { get { self[PaletteKey.self] } set { self[PaletteKey.self] = newValue } } }

// MARK: - Typography: Bricolage Grotesque (display) + Figtree (text), bundled

enum TS {
    case displayLarge, displaySmall, headline, titleLarge, titleMedium, titleSmall, bodyLarge, bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall

    /// (display font?, weight, size, tracking, leading)
    var spec: (display: Bool, weight: String, size: CGFloat, tracking: CGFloat, leading: CGFloat) {
        switch self {
        case .displayLarge: (true, "ExtraBold", 44, -1.4, 0)
        case .displaySmall: (true, "Bold", 34, -0.9, 2)
        case .headline: (true, "Bold", 28, -0.6, 4)
        case .titleLarge: (true, "SemiBold", 21, -0.3, 5)
        case .titleMedium: (false, "SemiBold", 16, 0, 5)
        case .titleSmall: (false, "SemiBold", 14, 0, 5)
        case .bodyLarge: (false, "Regular", 16, 0, 7)
        case .bodyMedium: (false, "Regular", 14, 0, 6)
        case .bodySmall: (false, "Regular", 12, 0, 4)
        case .labelLarge: (false, "SemiBold", 14, 0, 4)
        case .labelMedium: (false, "Medium", 12, 0, 4)
        case .labelSmall: (false, "SemiBold", 11, 0, 2)
        }
    }
    var font: Font { spec.display ? .display(spec.size, spec.weight) : .text(spec.size, spec.weight) }
}

/// The bundled fonts are variable fonts whose named instances have no PostScript names,
/// so `Font.custom("…-Bold")` silently falls back to the system font. Instead we open the
/// default instance and set the variation axes ourselves (weight, and optical size for Bricolage).
enum LyncFonts {
    private static let wght = 0x77676874 // 'wght'
    private static let opsz = 0x6F70737A // 'opsz'
    private static var cache: [String: UIFont] = [:]
    private static let lock = NSLock()

    static func weightValue(_ name: String) -> CGFloat {
        switch name {
        case "Light": 300; case "Medium": 500; case "SemiBold": 600; case "Bold": 700; case "ExtraBold": 800; case "Black": 900
        default: 400
        }
    }

    static func uiFont(display: Bool, size: CGFloat, weight: String) -> UIFont {
        let key = "\(display)-\(size)-\(weight)"
        lock.lock(); defer { lock.unlock() }
        if let f = cache[key] { return f }
        let w = weightValue(weight)
        let base = display ? "BricolageGrotesque-96ptExtraBold" : "Figtree-Light"
        var axes: [Int: CGFloat] = [wght: w]
        if display { axes[opsz] = min(96, max(12, size)) }
        let font: UIFont
        if let b = UIFont(name: base, size: size) {
            let desc = b.fontDescriptor.addingAttributes([UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): axes])
            font = UIFont(descriptor: desc, size: size)
        } else {
            font = .systemFont(ofSize: size, weight: UIFont.Weight((w - 400) / 500))
        }
        cache[key] = font
        return font
    }
}

extension Font {
    static func display(_ size: CGFloat, _ weight: String = "Bold") -> Font { Font(LyncFonts.uiFont(display: true, size: size, weight: weight)) }
    static func text(_ size: CGFloat, _ weight: String = "Regular") -> Font { Font(LyncFonts.uiFont(display: false, size: size, weight: weight)) }
}

extension View {
    func lyncText(_ style: TS, _ color: Color? = nil) -> some View {
        modifier(TextStyleModifier(style: style, color: color))
    }
}

private struct TextStyleModifier: ViewModifier {
    @Environment(\.palette) private var c
    let style: TS
    let color: Color?
    func body(content: Content) -> some View {
        content.font(style.font).tracking(style.spec.tracking).lineSpacing(style.spec.leading).foregroundStyle(color ?? c.text)
    }
}

/// Gradient of text posts / stories.
func gradientOf(_ style: Int) -> LinearGradient {
    let pair = TextStyles[((style % TextStyles.count) + TextStyles.count) % TextStyles.count]
    return LinearGradient(colors: [Color(hex: pair.0), Color(hex: pair.1)], startPoint: .topLeading, endPoint: .bottomTrailing)
}
