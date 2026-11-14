import SwiftUI

/// Full-screen stories: tap right = next, left = previous, swipe down = close. 5 s per story.
struct StoriesScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    let startProfileId: String

    @State private var groups: [StoryGroup] = []
    @State private var g = 0
    @State private var i = 0
    @State private var progress: Double = 0
    @State private var paused = false
    @State private var drag: CGFloat = 0
    private let tick = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Tokens.nightBg.ignoresSafeArea()
            if let group = groups[safe: g], let story = group.stories[safe: i] {
                ZStack(alignment: .top) {
                    content(story)
                    VStack(spacing: 10) {
                        HStack(spacing: 4) {
                            ForEach(group.stories.indices, id: \.self) { k in
                                GeometryReader { geo in
                                    Capsule().fill(.white.opacity(0.35))
                                    Capsule().fill(.white).frame(width: geo.size.width * (k < i ? 1 : k == i ? progress : 0))
                                }
                                .frame(height: 2.5)
                            }
                        }
                        HStack(spacing: 10) {
                            Avatar(url: group.profile.avatarUrl, initials: group.profile.initials, size: 34)
                            Text(group.profile.firstName).font(.text(14, "SemiBold")).foregroundStyle(.white)
                            Text(timeAgo(story.createdAt)).font(.text(12)).foregroundStyle(.white.opacity(0.7))
                            Spacer()
                            Button { router.back() } label: { Image(systemName: "xmark").font(.system(size: 18, weight: .semibold)).foregroundStyle(.white).frame(width: 40, height: 40) }
                        }
                    }
                    .padding(12)
                }
                .clipShape(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous))
                .padding(.vertical, 8)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in paused = true; if v.translation.height > 0 { drag = v.translation.height } }
                    .onEnded { v in
                        paused = false
                        if drag > 140 { router.back(); return }
                        drag = 0
                        if abs(v.translation.width) < 10 && abs(v.translation.height) < 10 { v.location.x < UIScreen.main.bounds.width * 0.33 ? prev() : next() }
                    })
                .offset(y: drag).scaleEffect(1 - min(drag / 3000, 0.1))
                .task(id: group.profile.id) { try? await env.repository.markStorySeen(group.profile.id) }
            }
        }
        .task {
            groups = (try? await env.repository.stories()) ?? []
            g = max(0, groups.firstIndex { $0.profile.id == startProfileId } ?? 0)
            if groups.isEmpty { router.back() }
        }
        .onReceive(tick) { _ in
            guard !paused, !groups.isEmpty else { return }
            progress += 0.05 / 5
            if progress >= 1 { next() }
        }
        .statusBarHidden()
    }

    @ViewBuilder private func content(_ story: Story) -> some View {
        ZStack(alignment: story.imageUrl == nil ? .center : .bottom) {
            if let url = story.imageUrl {
                RemoteImage(url: url) { Color.black }.frame(maxWidth: .infinity, maxHeight: .infinity).clipped()
                if story.text != nil { LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: .center, endPoint: .bottom) }
            } else {
                gradientOf(story.textStyle)
            }
            if let t = story.text {
                Text(t).font(.display(story.imageUrl == nil ? 34 : 24)).foregroundStyle(.white).multilineTextAlignment(.center).padding(32)
            }
        }
    }

    private func next() {
        progress = 0
        guard let group = groups[safe: g] else { return }
        if i < group.stories.count - 1 { i += 1 } else if g < groups.count - 1 { g += 1; i = 0 } else { router.back() }
    }

    private func prev() {
        progress = 0
        if i > 0 { i -= 1 } else if g > 0 { g -= 1; i = 0 }
    }
}

extension Array { subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil } }
