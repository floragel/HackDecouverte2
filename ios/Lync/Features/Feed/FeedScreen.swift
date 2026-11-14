import SwiftUI

@Observable
final class FeedModel {
    var posts: [FeedPost] = []
    var stories: [StoryGroup] = []
    var presence: [BuildingPresence] = []
    var firstLoad = true

    func refresh(_ repo: CampusRepository) async {
        async let p = repo.feed()
        async let s = repo.stories()
        posts = (try? await p) ?? posts
        stories = (try? await s) ?? stories
        firstLoad = false
    }

    func patch(_ id: String, _ f: (inout FeedPost) -> Void) { if let i = posts.firstIndex(where: { $0.id == id }) { f(&posts[i]) } }
}

struct FeedScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var model = FeedModel()
    @State private var commentsFor: FeedPost?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Wordmark()
                if env.repository.isDemo { DemoBadge() }
                Spacer()
                CircleIconButton(icon: "plus") { router.compose() }
            }
            .padding(.horizontal, Tokens.gutter).frame(height: 64)

            ScrollView {
                LazyVStack(spacing: 0) {
                    StoriesRow(me: env.me, groups: model.stories) { mine in
                        if let mine { router.stories(mine.profile.id) } else { router.compose(story: true) }
                    } onOpen: { router.stories($0) }
                    if !model.presence.isEmpty { WhosHereStrip(presence: model.presence) { router.map() } }
                    if model.firstLoad { ForEach(0..<3, id: \.self) { _ in PostSkeleton() } }
                    ForEach(model.posts) { post in
                        PostCard(post: post, actions: actions(for: post))
                    }
                }
                .padding(.bottom, 24)
            }
            .refreshable { await model.refresh(env.repository) }
        }
        .background(c.bg)
        .task { await model.refresh(env.repository) }
        .task {
            // « Qui est là ? »: friends grouped by building, from live positions
            for await pins in env.repository.mapPins() { model.presence = Buildings.presence(pins.filter { $0.kind == .friend }, myPos: nil) }
        }
        .onChange(of: router.cover) { _, cover in if cover == nil { Task { await model.refresh(env.repository) } } }
        .sheet(item: $commentsFor) { post in
            CommentsSheet(post: post, load: { (try? await env.repository.comments(post.id)) ?? [] },
                          add: { text in
                              let c = try? await env.repository.addComment(post.id, text: text)
                              if c != nil { model.patch(post.id) { $0.commentCount += 1 } }
                              return c
                          }, onPerson: { id in commentsFor = nil; router.user(id) })
        }
    }

    private func actions(for post: FeedPost) -> PostActions {
        let repo = env.repository
        return PostActions(
            onAuthor: { if !post.isOrganization { router.user(post.authorId) } },
            onLike: { force in
                let liked = force ? true : !post.likedByMe
                guard liked != post.likedByMe else { return }
                withAnimation(Motion.bouncy) { model.patch(post.id) { $0.likedByMe = liked; $0.likeCount += liked ? 1 : -1 } }
                Task { try? await repo.setLiked(post.id, liked) }
            },
            onComments: { commentsFor = post },
            onVote: { opt in
                guard var poll = post.poll, poll.myVote == nil else { return }
                poll.myVote = opt; poll.options = poll.options.map { var o = $0; if o.id == opt { o.votes += 1 }; return o }
                model.patch(post.id) { $0.poll = poll }
                Task { try? await repo.vote(post.id, optionId: opt) }
            },
            onGoing: {
                guard var plan = post.plan, let me = env.me else { return }
                let going = !plan.imGoing
                plan.imGoing = going; plan.going = plan.going.filter { $0.id != me.id } + (going ? [me] : [])
                withAnimation(Motion.bouncy) { model.patch(post.id) { $0.plan = plan } }
                Task { try? await repo.setGoing(post.id, going) }
            },
            onPerson: { router.user($0) }
        )
    }
}

struct PostActions {
    var onAuthor: () -> Void
    var onLike: (_ force: Bool) -> Void
    var onComments: () -> Void
    var onVote: (String) -> Void
    var onGoing: () -> Void
    var onPerson: (String) -> Void
}

/// Stories (24h) — "Your story" first, unseen ones get the university-colored ring.
struct StoriesRow: View {
    @Environment(\.palette) private var c
    let me: Profile?
    let groups: [StoryGroup]
    var onMine: (StoryGroup?) -> Void
    var onOpen: (String) -> Void

    var body: some View {
        let mine = groups.first { $0.profile.id == me?.id }
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 14) {
                    if let me {
                        bubble(me, ring: mine != nil ? c.accent : c.border, label: L("story_yours"), seen: false, plus: mine == nil) { onMine(mine) }
                    }
                    ForEach(groups.filter { $0.profile.id != me?.id }) { g in
                        bubble(g.profile, ring: g.seen ? c.border : c.accent, label: g.profile.firstName, seen: g.seen, plus: false, status: StatusPreset.of(g.profile.statusKey)) { onOpen(g.profile.id) }
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
            }
            Rectangle().fill(c.border).frame(height: 0.5)
        }
    }

    private func bubble(_ p: Profile, ring: Color, label: String, seen: Bool, plus: Bool, status: StatusPreset? = nil, action: @escaping () -> Void) -> some View {
        VStack(spacing: 5) {
            ZStack(alignment: .bottomTrailing) {
                Avatar(url: p.avatarUrl, initials: p.initials, size: 66, ring: ring)
                if plus {
                    Image(systemName: "plus").font(.system(size: 11, weight: .bold)).foregroundStyle(c.bg)
                        .frame(width: 20, height: 20).background(Circle().fill(c.text)).padding(2).background(Circle().fill(c.bg))
                } else if let status {
                    Text(status.emoji).font(.system(size: 11)).frame(width: 20, height: 20).background(Circle().fill(c.surface)).padding(2).background(Circle().fill(c.bg))
                }
            }
            Text(label).lyncText(.labelMedium, seen ? c.textMuted : c.text).lineLimit(1)
        }
        .frame(width: 70)
        .pressable(scale: 0.94, action)
    }
}

/// One line per building with friends in it — tap to open the map.
struct WhosHereStrip: View {
    @Environment(\.palette) private var c
    let presence: [BuildingPresence]
    var onOpen: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("here_title")).lyncText(.titleSmall)
            ForEach(presence.prefix(3)) { bp in
                HStack(spacing: 10) {
                    AvatarStack(people: bp.people, size: 26)
                    (Text(herePeople(bp)).foregroundColor(c.text) + Text("  " + bp.building.name).foregroundColor(c.textMuted)).font(TS.bodyMedium.font).lineLimit(1)
                }
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous).fill(c.surface))
        .padding(.horizontal, 12).padding(.vertical, 10)
        .pressable(scale: 0.99, onOpen)
    }
}

func herePeople(_ bp: BuildingPresence) -> String {
    switch bp.people.count {
    case 1: return bp.people[0].firstName
    case 2: return L("here_two", bp.people[0].firstName, bp.people[1].firstName)
    default: return L("here_many", bp.people[0].firstName, bp.people.count - 1)
    }
}

struct PostCard: View {
    @Environment(\.palette) private var c
    let post: FeedPost
    let actions: PostActions
    @State private var burst = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Avatar(url: post.authorAvatar, initials: String(post.authorName.prefix(2)).uppercased(), size: 38)
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 4) {
                        Text(post.authorName).lyncText(.titleSmall).lineLimit(1)
                        if post.verified { Image(systemName: "checkmark.seal.fill").font(.system(size: 12)).foregroundStyle(c.accent) }
                    }
                    Text(post.sponsored ? L("feed_sponsored") : "@\(post.authorHandle)").lyncText(.bodySmall, post.sponsored ? c.accent : c.textMuted)
                }
                Spacer()
                Text(timeAgo(post.createdAt)).lyncText(.bodySmall, c.textMuted)
            }
            .padding(.horizontal, 16)
            .pressable(scale: 0.98, actions.onAuthor)

            content

            HStack(spacing: 0) {
                action(post.likedByMe ? "heart.fill" : "heart", tint: post.likedByMe ? c.accent : c.text) { Haptics.tick(); actions.onLike(false) }
                    .symbolEffect(.bounce, value: post.likedByMe)
                action("bubble.right") { actions.onComments() }
                ShareLink(item: "\(L("share_post_text", post.authorName, post.caption ?? post.poll?.question ?? "", "https://lync.app/p/\(post.id)"))") {
                    Image(systemName: "square.and.arrow.up").font(.system(size: 21)).foregroundStyle(c.text).frame(width: 44, height: 44)
                }
            }
            .padding(.horizontal, 10)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 0) { RollingNumber(value: post.likeCount); Text(" " + LP("likes_word", post.likeCount)).lyncText(.titleSmall) }
                if post.kind == .photo || post.kind == .plan, let cap = post.caption {
                    (Text(post.authorHandle).font(.text(14, "SemiBold")) + Text("  " + cap).font(TS.bodyMedium.font)).foregroundStyle(c.text)
                }
                if post.commentCount > 0 {
                    Text(LP("view_comments", post.commentCount)).lyncText(.bodyMedium, c.textMuted).onTapGesture(perform: actions.onComments)
                }
            }
            .padding(.horizontal, 16)
        }
        .padding(.top, 18)
    }

    @ViewBuilder private var content: some View {
        switch post.kind {
        case .photo:
            ZStack {
                RemoteImage(url: post.imageUrl) { LinearGradient(colors: [c.surface, c.surfaceAlt], startPoint: .topLeading, endPoint: .bottomTrailing) }
                heart
            }
            .aspectRatio(4 / 5, contentMode: .fit).frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous))
            .padding(.horizontal, 12)
            .onTapGesture(count: 2) { doubleTap() }
        case .text:
            ZStack {
                gradientOf(post.textStyle)
                Text(post.caption ?? "").font(.display(26)).foregroundStyle(.white).multilineTextAlignment(.center).padding(28)
                heart
            }
            .aspectRatio(1.25, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous))
            .padding(.horizontal, 12)
            .onTapGesture(count: 2) { doubleTap() }
        case .poll: if let poll = post.poll { PollBlock(poll: poll, onVote: { Haptics.confirm(); actions.onVote($0) }) }
        case .plan: if let plan = post.plan { PlanBlock(plan: plan, onGoing: { Haptics.confirm(); actions.onGoing() }, onPerson: actions.onPerson) }
        }
    }

    private var heart: some View {
        Image(systemName: "heart.fill").font(.system(size: 90)).foregroundStyle(.white).shadow(radius: 8)
            .scaleEffect(burst ? 1 : 0.2).opacity(burst ? 1 : 0)
    }

    private func doubleTap() {
        Haptics.confirm(); actions.onLike(true)
        withAnimation(.spring(response: 0.3, dampingFraction: 0.45)) { burst = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { withAnimation(.easeOut(duration: 0.25)) { burst = false } }
    }

    private func action(_ icon: String, tint: Color? = nil, _ f: @escaping () -> Void) -> some View {
        Button(action: f) { Image(systemName: icon).font(.system(size: 22)).foregroundStyle(tint ?? c.text).frame(width: 44, height: 44) }.buttonStyle(PressableStyle(scale: 0.8))
    }
}

struct PollBlock: View {
    @Environment(\.palette) private var c
    let poll: Poll
    var onVote: (String) -> Void
    @State private var shown = false

    var body: some View {
        let voted = poll.myVote != nil
        VStack(alignment: .leading, spacing: 8) {
            Text(poll.question).lyncText(.titleLarge).padding(.bottom, 6)
            ForEach(Array(poll.options.enumerated()), id: \.element.id) { i, o in
                let pct = poll.total == 0 ? 0 : Double(o.votes) / Double(poll.total)
                let mine = o.id == poll.myVote
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Rectangle().fill(mine ? c.accent.opacity(0.22) : c.surfaceAlt)
                            .frame(width: geo.size.width * (voted && shown ? pct : 0))
                            .animation(.easeOut(duration: 0.52).delay(Double(i) * Motion.stagger), value: shown)
                        HStack {
                            Text(o.text).lyncText(.titleSmall).lineLimit(1)
                            Spacer()
                            if mine { Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)).foregroundStyle(c.accent) }
                            if voted { Text("\(Int(pct * 100))%").lyncText(.titleSmall) }
                        }
                        .padding(.horizontal, 14)
                    }
                }
                .frame(height: 46)
                .background(c.bg)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(mine ? c.accent : c.border, lineWidth: 1))
                .onTapGesture { if !voted { onVote(o.id) } }
            }
            let left = max(0, Int(poll.endsAt.timeIntervalSinceNow / 3600))
            Text(LP("votes", poll.total) + ", " + (left > 0 ? L("poll_left", left) : L("poll_closed"))).lyncText(.bodySmall, c.textMuted)
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous).fill(c.surface))
        .padding(.horizontal, 12)
        .onAppear { shown = true }
        .onChange(of: voted) { _, _ in shown = false; DispatchQueue.main.async { shown = true } }
    }
}

func planActivityLabel(_ a: PlanActivity) -> String { L("plan_\(a.rawValue)") }

func planTime(_ date: Date) -> String {
    let diff = Int(date.timeIntervalSinceNow / 60)
    let clock = date.formatted(Date.FormatStyle(date: .omitted, time: .shortened).locale(Lang.locale))
    if diff <= 5 { return L("plan_now") }
    if diff < 60 { return L("plan_in_min", diff, clock) }
    if diff < 20 * 60 { return L("plan_today", clock) }
    return L("plan_tomorrow", clock)
}

struct PlanBlock: View {
    @Environment(\.palette) private var c
    let plan: Plan
    var onGoing: () -> Void
    var onPerson: (String) -> Void

    var body: some View {
        let full = plan.spots != nil && plan.going.count >= plan.spots! && !plan.imGoing
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                Text(plan.activity.emoji).font(.system(size: 28)).frame(width: 56, height: 56).background(RoundedRectangle(cornerRadius: 18).fill(c.bg))
                VStack(alignment: .leading, spacing: 2) {
                    Text(planActivityLabel(plan.activity)).lyncText(.titleLarge)
                    Label(planTime(plan.startsAt), systemImage: "clock").lyncText(.bodyMedium, c.textMuted)
                }
            }
            Label { Text(plan.place).lyncText(.titleSmall) } icon: { Image(systemName: "mappin.circle.fill").foregroundStyle(c.accent) }
            HStack(spacing: 8) {
                HStack(spacing: -10) {
                    ForEach(plan.going.prefix(4)) { p in
                        Avatar(url: p.avatarUrl, initials: p.initials, size: 30).padding(2).background(Circle().fill(c.surface))
                            .onTapGesture { onPerson(p.id) }
                            .transition(.scale.combined(with: .opacity))
                    }
                }
                Text(plan.spots.map { L("plan_going_spots", plan.going.count, $0) } ?? LP("plan_going", plan.going.count)).lyncText(.bodyMedium, c.textMuted)
                Spacer()
                Button(action: { if !full { onGoing() } }) {
                    HStack(spacing: 4) {
                        if plan.imGoing { Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)) }
                        Text(L(plan.imGoing ? "plan_going_me" : full ? "plan_full" : "plan_join")).font(TS.labelLarge.font)
                    }
                    .foregroundStyle(plan.imGoing ? c.text : c.onAccent)
                    .padding(.horizontal, 16).padding(.vertical, 9)
                    .background(Capsule().fill(plan.imGoing ? c.bg : c.accent))
                    .overlay(Capsule().stroke(plan.imGoing ? c.border : .clear))
                }
                .buttonStyle(PressableStyle())
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous).fill(c.surface))
        .padding(.horizontal, 12)
    }
}

struct PostSkeleton: View {
    @Environment(\.palette) private var c
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) { Circle().fill(c.surface).frame(width: 38, height: 38); Capsule().fill(c.surface).frame(width: 120, height: 12) }.padding(.horizontal, 16)
            RoundedRectangle(cornerRadius: Tokens.radiusMedia).fill(c.surface).aspectRatio(4 / 5, contentMode: .fit).padding(.horizontal, 12)
        }
        .padding(.top, 18)
    }
}

struct CommentsSheet: View {
    @Environment(\.palette) private var c
    let post: FeedPost
    var load: () async -> [Comment]
    var add: (String) async -> Comment?
    var onPerson: (String) -> Void
    @State private var comments: [Comment] = []
    @State private var draft = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("comments_title")).lyncText(.titleLarge).padding(.horizontal, Tokens.gutter).padding(.top, 24).padding(.bottom, 8)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if comments.isEmpty { Text(L("comments_empty")).lyncText(.bodyMedium, c.textMuted).padding(Tokens.gutter) }
                    ForEach(comments) { cm in
                        HStack(alignment: .top, spacing: 10) {
                            Avatar(url: cm.author.avatarUrl, initials: cm.author.initials, size: 34).onTapGesture { onPerson(cm.author.id) }
                            VStack(alignment: .leading, spacing: 2) {
                                (Text(cm.author.username).font(.text(14, "SemiBold")) + Text("  " + cm.text).font(TS.bodyMedium.font)).foregroundStyle(c.text)
                                Text(timeAgo(cm.createdAt)).lyncText(.bodySmall, c.textMuted)
                            }
                        }
                        .padding(.horizontal, Tokens.gutter).padding(.vertical, 10)
                    }
                }
            }
            HStack(spacing: 8) {
                TextField("", text: $draft, prompt: Text(L("comments_hint")).foregroundStyle(c.textMuted))
                    .font(TS.bodyMedium.font).padding(.horizontal, 16).padding(.vertical, 12).background(Capsule().fill(c.surface))
                CircleIconButton(icon: "paperplane.fill", filled: !draft.trimmingCharacters(in: .whitespaces).isEmpty) {
                    let t = draft.trimmingCharacters(in: .whitespaces)
                    guard !t.isEmpty else { return }
                    Task { if let cm = await add(t) { comments.append(cm); draft = "" } }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
        }
        .background(c.bg)
        .presentationDetents([.medium, .large])
        .task { comments = await load() }
    }
}
