import SwiftUI
import PhotosUI

private enum Kind: CaseIterable { case photo, text, poll, plan
    var label: String { L("compose_\(self)") }
    var icon: String { switch self { case .photo: "photo"; case .text: "textformat"; case .poll: "chart.bar"; case .plan: "calendar" } }
}

private let PlaceSuggestions = ["Webster Library", "Le Gym, EV", "Hall cafeteria", "JMSB atrium", "Loyola PERFORM", "Grey Nuns reading room"]

/// One screen for every kind of post. `storyMode` = 24h story (photo or text only).
struct ComposeScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    let storyMode: Bool

    @State private var kind: Kind = .photo
    @State private var publishing = false
    @State private var error: String?
    @State private var photoItem: PhotosPickerItem?
    @State private var imageData: Data?
    @State private var caption = ""
    @State private var text = ""
    @State private var style = 2
    @State private var question = ""
    @State private var options = ["", ""]
    @State private var hours = 24
    @State private var activity: PlanActivity = .gym
    @State private var whenIndex = 1
    @State private var place = ""
    @State private var spots: Int?
    @State private var note = ""

    private var valid: Bool {
        switch kind {
        case .photo: imageData != nil
        case .text: !text.trimmingCharacters(in: .whitespaces).isEmpty
        case .poll: !question.trimmingCharacters(in: .whitespaces).isEmpty && options.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.count >= 2
        case .plan: !place.trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button { router.back() } label: { Image(systemName: "xmark").font(.system(size: 18, weight: .semibold)).foregroundStyle(c.text).frame(width: 44, height: 44) }
                Text(L(storyMode ? "compose_story_title" : "feed_new_post")).lyncText(.titleLarge)
                Spacer()
                Pill(text: L(storyMode ? "compose_share_story" : "feed_publish"), style: valid ? .filled : .tonal) { if valid && !publishing { publish() } }
            }
            .padding(.horizontal, 12).frame(height: 60)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(storyMode ? [Kind.text, .photo] : Kind.allCases, id: \.self) { k in
                        let on = k == kind
                        Label(k.label, systemImage: k.icon).font(TS.labelLarge.font)
                            .foregroundStyle(on ? c.bg : c.text).padding(.horizontal, 14).padding(.vertical, 9)
                            .background(Capsule().fill(on ? c.text : c.surface))
                            .onTapGesture { withAnimation(Motion.standard) { kind = k } }
                    }
                }
                .padding(.horizontal, Tokens.gutter)
            }
            .padding(.bottom, 16)

            ScrollView { content.padding(.horizontal, Tokens.gutter).padding(.bottom, 32) }
        }
        .background(c.bg)
        .onAppear { if storyMode { kind = .text } }
        .onChange(of: photoItem) { _, item in Task { imageData = try? await item?.loadTransferable(type: Data.self) } }
        .alert(L("error_generic"), isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) { Button("OK") {} } message: { Text(error ?? "") }
    }

    @ViewBuilder private var content: some View {
        switch kind {
        case .photo:
            VStack(spacing: 14) {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    ZStack {
                        RoundedRectangle(cornerRadius: Tokens.radiusMedia).fill(c.surface)
                        if let imageData, let ui = UIImage(data: imageData) { Image(uiImage: ui).resizable().scaledToFill() }
                        else { VStack(spacing: 8) { Image(systemName: "photo.badge.plus").font(.system(size: 36)); Text(L("compose_pick_photo")).lyncText(.titleSmall, c.textMuted) }.foregroundStyle(c.textMuted) }
                    }
                    .aspectRatio(storyMode ? 9 / 14 : 4 / 5, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous))
                }
                if !storyMode { Field(hint: L("feed_caption_hint"), text: $caption, multiline: true) }
            }
        case .text:
            VStack(spacing: 14) {
                ZStack {
                    gradientOf(style)
                    TextField("", text: $text, prompt: Text(L("compose_text_hint")).foregroundStyle(.white.opacity(0.6)), axis: .vertical)
                        .font(.display(28)).foregroundStyle(.white).multilineTextAlignment(.center).tint(.white).padding(28)
                }
                .aspectRatio(storyMode ? 9 / 14 : 1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous))
                HStack(spacing: 12) {
                    ForEach(TextStyles.indices, id: \.self) { i in
                        Circle().fill(gradientOf(i)).frame(width: 38, height: 38)
                            .overlay(Circle().stroke(c.bg, lineWidth: i == style ? 3 : 0).padding(2))
                            .overlay(Circle().stroke(c.text, lineWidth: i == style ? 2 : 0))
                            .onTapGesture { style = i }
                    }
                    Spacer()
                }
            }
        case .poll:
            VStack(alignment: .leading, spacing: 8) {
                TextField("", text: $question, prompt: Text(L("compose_poll_question")).foregroundStyle(c.textMuted), axis: .vertical).lyncText(.headline).padding(.bottom, 8)
                ForEach(options.indices, id: \.self) { i in
                    HStack(spacing: 8) {
                        Field(hint: L("compose_poll_option", i + 1), text: $options[i])
                        if options.count > 2 { CircleIconButton(icon: "minus", size: 36) { options.remove(at: i) } }
                    }
                }
                if options.count < 4 {
                    Button { options.append("") } label: { Label(L("compose_poll_add"), systemImage: "plus").lyncText(.labelLarge, c.accent) }.padding(.vertical, 8)
                }
                label(L("compose_poll_duration"))
                HStack(spacing: 8) { ForEach([(1, "1h"), (6, "6h"), (24, "24h"), (72, "3d")], id: \.0) { h, l in Pill(text: l, style: hours == h ? .filled : .tonal) { hours = h } } }
            }
        case .plan:
            VStack(alignment: .leading, spacing: 8) {
                label(L("compose_plan_what"), top: 0)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                    ForEach(PlanActivity.allCases, id: \.self) { a in
                        let on = a == activity
                        VStack(spacing: 4) { Text(a.emoji).font(.system(size: 24)); Text(planActivityLabel(a)).lyncText(.labelMedium).lineLimit(1) }
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                            .background(RoundedRectangle(cornerRadius: Tokens.radiusControl).fill(on ? c.accentSoft : c.surface))
                            .overlay(RoundedRectangle(cornerRadius: Tokens.radiusControl).stroke(on ? c.accent : .clear, lineWidth: 1.5))
                            .onTapGesture { activity = a }
                    }
                }
                label(L("compose_plan_when"))
                FlowLayout(spacing: 8) {
                    ForEach(Array(["when_now", "when_30", "when_1h", "when_tonight", "when_tomorrow"].enumerated()), id: \.offset) { i, k in Pill(text: L(k), style: whenIndex == i ? .filled : .tonal) { whenIndex = i } }
                }
                label(L("compose_plan_where"))
                Field(hint: L("compose_plan_place_hint"), text: $place, icon: "mappin")
                FlowLayout(spacing: 8) { ForEach(PlaceSuggestions, id: \.self) { s in Pill(text: s, style: place == s ? .filled : .outline, vertical: 7) { place = s } } }
                label(L("compose_plan_spots"))
                HStack {
                    CircleIconButton(icon: "minus") { spots = spots.flatMap { $0 <= 2 ? nil : $0 - 1 } }
                    Text(spots.map(String.init) ?? L("compose_plan_no_limit")).lyncText(.titleMedium).frame(width: 110)
                    CircleIconButton(icon: "plus") { spots = min(30, (spots ?? 1) + 1) }
                }
                label(L("compose_plan_note"))
                Field(hint: L("compose_plan_note_hint"), text: $note, multiline: true)
            }
        }
    }

    private func label(_ t: String, top: CGFloat = 22) -> some View { Text(t).lyncText(.titleSmall, c.textMuted).padding(.top, top).padding(.bottom, 2) }

    private func startTime() -> Date {
        let cal = Calendar.current, now = Date()
        switch whenIndex {
        case 0: return now
        case 1: return now.addingTimeInterval(1800)
        case 2: return now.addingTimeInterval(3600)
        case 3: return cal.date(bySettingHour: 19, minute: 0, second: 0, of: now)!
        default: return cal.date(bySettingHour: 12, minute: 0, second: 0, of: cal.date(byAdding: .day, value: 1, to: now)!)!
        }
    }

    private func publish() {
        publishing = true
        let repo = env.repository
        let image = imageData.flatMap { UIImage(data: $0)?.jpegData(compressionQuality: 0.82) }
        Task {
            do {
                if storyMode {
                    try await repo.addStory(text: kind == .text ? text : nil, image: kind == .photo ? image : nil, style: style)
                } else {
                    switch kind {
                    case .photo: try await repo.createPost(.photo(image: image ?? Data(), caption: caption))
                    case .text: try await repo.createPost(.text(text.trimmingCharacters(in: .whitespaces), style: style))
                    case .poll: try await repo.createPost(.poll(question: question.trimmingCharacters(in: .whitespaces), options: options.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }, hours: hours))
                    case .plan: try await repo.createPost(.plan(activity: activity, place: place.trimmingCharacters(in: .whitespaces), startsAt: startTime(), spots: spots, note: note))
                    }
                }
                Haptics.confirm()
                router.back()
            } catch { self.error = error.localizedDescription; publishing = false }
        }
    }
}

/// Wrapping row layout (chips).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxW = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, width: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x + size.width > maxW, x > 0 { x = 0; y += rowH + spacing; rowH = 0 }
            x += size.width + spacing; rowH = max(rowH, size.height); width = max(width, x)
        }
        return CGSize(width: min(width, maxW), height: y + rowH)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowH: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX { x = bounds.minX; y += rowH + spacing; rowH = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing; rowH = max(rowH, size.height)
        }
    }
}
