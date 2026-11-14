import SwiftUI

struct GroupsTab: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var groups: [InterestGroup] = []
    @State private var creating = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                let mine = groups.filter(\.isMember)
                if !mine.isEmpty {
                    Text(L("groups_yours")).lyncText(.titleLarge).padding(.leading, Tokens.gutter).padding(.top, 14).padding(.bottom, 10)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(mine) { g in
                                VStack(alignment: .leading, spacing: 0) {
                                    Text(g.emoji).font(.system(size: 30))
                                    Text(g.name).lyncText(.titleSmall).lineLimit(2).padding(.top, 10)
                                    Text(LP("groups_members", g.memberCount)).lyncText(.bodySmall, c.textMuted)
                                }
                                .padding(14).frame(width: 118, alignment: .leading)
                                .background(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous).fill(c.surface))
                                .pressable(scale: 0.97) { router.group(g.id) }
                            }
                        }
                        .padding(.horizontal, Tokens.gutter)
                    }
                }
                HStack {
                    Text(L("groups_discover")).lyncText(.titleLarge)
                    Spacer()
                    Pill(text: L("groups_create"), style: .tonal, icon: "plus") { creating = true }
                }
                .padding(.horizontal, Tokens.gutter).padding(.top, 22).padding(.bottom, 8)
                ForEach(groups.filter { !$0.isMember }) { g in
                    HStack(spacing: 12) {
                        Text(g.emoji).font(.system(size: 26)).frame(width: 52, height: 52).background(RoundedRectangle(cornerRadius: 16).fill(c.surface))
                        VStack(alignment: .leading, spacing: 1) {
                            Text(g.name).lyncText(.titleSmall)
                            Text(g.description).lyncText(.bodySmall, c.textMuted).lineLimit(1)
                            Text(LP("groups_members", g.memberCount)).lyncText(.labelMedium, c.textMuted)
                        }
                        Spacer()
                        Pill(text: L("groups_join")) { toggle(g) }
                    }
                    .padding(.horizontal, Tokens.gutter).padding(.vertical, 10)
                    .contentShape(Rectangle()).onTapGesture { router.group(g.id) }
                }
            }
            .padding(.bottom, 24)
        }
        .task { groups = (try? await env.repository.groups()) ?? [] }
        .sheet(isPresented: $creating) {
            CreateGroupSheet { name, emoji, desc in
                Task {
                    if let g = try? await env.repository.createGroup(name: name, emoji: emoji, description: desc) {
                        creating = false; groups.insert(g, at: 0); router.group(g.id)
                    }
                }
            }
        }
    }

    private func toggle(_ g: InterestGroup) {
        Haptics.confirm()
        let join = !g.isMember
        if let i = groups.firstIndex(where: { $0.id == g.id }) { withAnimation { groups[i].isMember = join; groups[i].memberCount += join ? 1 : -1 } }
        Task { try? await env.repository.setMember(g.id, join) }
    }
}

private let GroupEmojis = ["✨", "🧗", "🏀", "⚽", "🎮", "📚", "🎨", "🎸", "📷", "🍳", "🌱", "🤖", "♟️", "🏎️", "💃", "🎬"]

struct CreateGroupSheet: View {
    @Environment(\.palette) private var c
    var onCreate: (String, String, String) -> Void
    @State private var name = ""
    @State private var desc = ""
    @State private var emoji = GroupEmojis[0]
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L("groups_create_title")).lyncText(.titleLarge).padding(.top, 24)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) { ForEach(GroupEmojis, id: \.self) { e in Text(e).font(.system(size: 22)).frame(width: 46, height: 46).background(Circle().fill(e == emoji ? c.accentSoft : c.surface)).onTapGesture { emoji = e } } }
            }
            Field(hint: L("groups_name_hint"), text: $name)
            Field(hint: L("groups_desc_hint"), text: $desc, multiline: true)
            Pill(text: L("groups_create"), style: name.isEmpty ? .tonal : .filled, fullWidth: true, vertical: 14) { if !name.isEmpty { onCreate(name, emoji, desc) } }
            Spacer()
        }
        .padding(.horizontal, Tokens.gutter).background(c.bg).presentationDetents([.medium])
    }
}

struct GroupScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    let id: String
    @State private var group: InterestGroup?
    @State private var posts: [FeedPost] = []
    @State private var draft = ""
    @State private var commentsFor: FeedPost?

    var body: some View {
        VStack(spacing: 0) {
            HStack { CircleIconButton(icon: "arrow.left") { router.back() }; Spacer() }.padding(.horizontal, 12).frame(height: 60)
            if let g = group {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(g.emoji).font(.system(size: 38)).frame(width: 76, height: 76).background(RoundedRectangle(cornerRadius: 24).fill(c.surface))
                            Text(g.name).lyncText(.displaySmall).padding(.top, 14)
                            Text(g.description).lyncText(.bodyLarge, c.textMuted).padding(.top, 6)
                            HStack(spacing: 8) { AvatarStack(people: g.members, size: 30, max: 5, ringColor: c.bg); Text(LP("groups_members", g.memberCount)).lyncText(.bodyMedium, c.textMuted) }.padding(.top, 14)
                            HStack(spacing: 8) {
                                Pill(text: L(g.isMember ? "groups_joined" : "groups_join"), style: g.isMember ? .outline : .filled, fullWidth: true, vertical: 12) {
                                    Haptics.confirm()
                                    let join = !g.isMember
                                    group?.isMember = join; group?.memberCount += join ? 1 : -1
                                    Task { try? await env.repository.setMember(g.id, join) }
                                }
                                ShareLink(item: L("groups_share_text", g.name, "https://lync.app/g/\(g.id)")) {
                                    Text(L("a11y_share")).font(TS.labelLarge.font).foregroundStyle(c.text).frame(maxWidth: .infinity).padding(.vertical, 12).background(Capsule().fill(c.surfaceAlt))
                                }
                            }
                            .padding(.top, 16)
                        }
                        .padding(.horizontal, Tokens.gutter)
                        if g.isMember {
                            HStack(spacing: 8) {
                                Field(hint: L("groups_post_hint"), text: $draft)
                                Pill(text: L("feed_publish"), style: draft.isEmpty ? .tonal : .filled) {
                                    let t = draft.trimmingCharacters(in: .whitespaces)
                                    guard !t.isEmpty else { return }
                                    Task { try? await env.repository.createPost(.text(t, style: 0), groupId: g.id); draft = ""; await reload() }
                                }
                            }
                            .padding(.horizontal, Tokens.gutter).padding(.vertical, 16)
                        }
                        Rectangle().fill(c.border).frame(height: 0.5)
                        if posts.isEmpty { Text(L("groups_empty")).lyncText(.bodyMedium, c.textMuted).padding(Tokens.gutter) }
                        ForEach(posts) { post in PostCard(post: post, actions: actions(post)) }
                    }
                    .padding(.bottom, 24)
                }
            }
            Spacer(minLength: 0)
        }
        .background(c.bg)
        .task { await reload() }
        .sheet(item: $commentsFor) { post in
            CommentsSheet(post: post, load: { (try? await env.repository.comments(post.id)) ?? [] }, add: { try? await env.repository.addComment(post.id, text: $0) },
                          onPerson: { commentsFor = nil; router.user($0) })
        }
    }

    private func reload() async {
        group = try? await env.repository.group(id)
        posts = (try? await env.repository.groupPosts(id)) ?? []
    }

    private func actions(_ post: FeedPost) -> PostActions {
        let repo = env.repository
        return PostActions(
            onAuthor: { router.user(post.authorId) },
            onLike: { force in
                let liked = force ? true : !post.likedByMe
                guard liked != post.likedByMe, let i = posts.firstIndex(where: { $0.id == post.id }) else { return }
                posts[i].likedByMe = liked; posts[i].likeCount += liked ? 1 : -1
                Task { try? await repo.setLiked(post.id, liked) }
            },
            onComments: { commentsFor = post },
            onVote: { opt in Task { try? await repo.vote(post.id, optionId: opt); await reload() } },
            onGoing: { if let plan = post.plan { Task { try? await repo.setGoing(post.id, !plan.imGoing); await reload() } } },
            onPerson: { router.user($0) })
    }
}
