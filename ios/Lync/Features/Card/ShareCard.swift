import SwiftUI

/// The shareable picture: card front on top, card back (Lync code) below, on the brand night blue.
struct ShareCardImage: View {
    @Environment(\.palette) private var c
    let profile: Profile
    let university: University?
    var body: some View {
        VStack(spacing: 16) {
            LyncCardFront(profile: profile, university: university, tilt: CGPoint(x: 0.35, y: -0.25), shine: 0.8)
            LyncCardBack(profile: profile, university: university)
            Wordmark(color: .white).padding(.top, 4)
        }
        .padding(.horizontal, 22).padding(.vertical, 26)
        .background(ZStack { Tokens.nightBg; RadialGradient(colors: [c.accent.opacity(0.35), .clear], center: .center, startRadius: 0, endRadius: 360) })
    }
}

/// Sheet with the preview; "Share image" sends only the PNG.
struct ShareCardSheet: View {
    @Environment(\.palette) private var c
    @Environment(\.displayScale) private var scale
    let profile: Profile
    let university: University?
    @State private var rendered: UIImage?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L("share_card_title")).lyncText(.titleLarge).padding(.top, 24)
            ShareCardImage(profile: profile, university: university).clipShape(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous))
            if let rendered {
                ShareLink(item: Image(uiImage: rendered), preview: SharePreview("Lync — \(profile.fullName)", image: Image(uiImage: rendered))) {
                    Label(L("share_card_button"), systemImage: "square.and.arrow.up").font(TS.labelLarge.font).foregroundStyle(c.onAccent)
                        .frame(maxWidth: .infinity).padding(.vertical, 14).background(Capsule().fill(c.accent))
                }
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 14)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Tokens.gutter).background(c.bg)
        .presentationDetents([.large])
        .task {
            if let url = profile.avatarUrl { _ = await ImageCache.shared.load(url) } // so the renderer has the photo
            let renderer = ImageRenderer(content: ShareCardImage(profile: profile, university: university).environment(\.palette, c).frame(width: 360))
            renderer.scale = max(3, scale)
            rendered = renderer.uiImage
        }
    }
}
