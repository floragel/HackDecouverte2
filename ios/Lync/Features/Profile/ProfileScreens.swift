import SwiftUI
import PhotosUI

struct StatsRow: View {
    @Environment(\.palette) private var c
    let stats: ProfileStats
    var body: some View {
        HStack {
            stat(stats.posts, "profile_posts"); stat(stats.connections, "profile_connections"); stat(stats.followers, "profile_followers")
        }
        .padding(.horizontal, Tokens.gutter).padding(.vertical, 6)
    }
    private func stat(_ v: Int, _ key: String) -> some View {
        VStack(spacing: 0) { RollingNumber(value: v, style: .headline); Text(L(key)).lyncText(.bodySmall, c.textMuted).lineLimit(1) }.frame(maxWidth: .infinity)
    }
}

/// One cell of the 3-column grid; every post kind gets a recognizable thumbnail.
struct PostTile: View {
    @Environment(\.palette) private var c
    let post: FeedPost
    var body: some View {
        Color.clear.aspectRatio(4 / 5, contentMode: .fit)
            .overlay {
                switch post.kind {
                case .photo: RemoteImage(url: post.imageUrl) { c.surface }
                case .text: ZStack { gradientOf(post.textStyle); Text(post.caption ?? "").font(.display(13)).foregroundStyle(.white).multilineTextAlignment(.center).lineLimit(5).padding(10) }
                case .poll: VStack(spacing: 4) { Text("📊").font(.system(size: 22)); Text(post.poll?.question ?? "").lyncText(.labelMedium).multilineTextAlignment(.center).lineLimit(3) }.padding(10).frame(maxWidth: .infinity, maxHeight: .infinity).background(c.surface)
                case .plan: VStack(spacing: 4) { Text(post.plan?.activity.emoji ?? "✨").font(.system(size: 24)); Text(post.plan?.place ?? "").lyncText(.labelMedium).multilineTextAlignment(.center).lineLimit(2) }.padding(10).frame(maxWidth: .infinity, maxHeight: .infinity).background(c.surface)
                }
            }
            .clipped()
    }
}

struct PostGrid: View {
    let posts: [FeedPost]
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) { ForEach(posts) { PostTile(post: $0) } }.padding(.horizontal, 2)
    }
}

struct ProfileScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var stats = ProfileStats()
    @State private var courses: [Course] = []
    @State private var posts: [FeedPost] = []
    @State private var university: University?
    @State private var tab = 0
    @State private var sharing = false
    @State private var rotate = false

    var body: some View {
        if let p = env.me {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                    HStack(spacing: 8) {
                        Text("@\(p.username)").lyncText(.titleLarge).lineLimit(1)
                        Spacer()
                        CircleIconButton(icon: "square.and.arrow.up") { sharing = true }
                        CircleIconButton(icon: "gearshape") { router.settings() }
                    }
                    .padding(.horizontal, Tokens.gutter).frame(height: 64)
                    FlippableCard(profile: p, university: university)
                    HStack(spacing: 8) {
                        Image(systemName: "rectangle.portrait.rotate").font(.system(size: 13)).rotationEffect(.degrees(rotate ? -90 : 0))
                            .animation(.easeInOut(duration: 1.2).delay(0.6).repeatForever(autoreverses: true), value: rotate)
                        Text(L("profile_card_hint")).lyncText(.labelMedium, c.textMuted)
                    }
                    .foregroundStyle(c.textMuted).frame(maxWidth: .infinity).padding(.vertical, 10).onAppear { rotate = true }
                    if let bio = p.bio { Text(bio).lyncText(.bodyMedium).padding(.horizontal, Tokens.gutter).padding(.vertical, 4) }
                    StatsRow(stats: stats)
                    HStack(spacing: 8) {
                        Pill(text: L("profile_edit"), style: .tonal, fullWidth: true, vertical: 12) { router.editProfile() }
                        Pill(text: L("profile_share_card"), fullWidth: true, vertical: 12) { sharing = true }
                    }
                    .padding(.horizontal, Tokens.gutter).padding(.vertical, 10)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(StatusPreset.allCases, id: \.self) { s in
                                let on = p.statusKey == s.rawValue
                                Pill(text: "\(s.emoji)  \(L(s.labelKey))", style: on ? .filled : .outline) {
                                    Haptics.tick()
                                    Task { try? await env.repository.updateStatus(on ? nil : s.rawValue) }
                                }
                            }
                        }
                        .padding(.horizontal, Tokens.gutter).padding(.vertical, 8)
                    }
                    Section {
                        if tab == 0 {
                            if posts.isEmpty {
                                VStack(spacing: 12) { Text(L("profile_no_posts")).lyncText(.bodyMedium, c.textMuted); Pill(text: L("feed_new_post"), style: .tonal) { router.compose() } }
                                    .frame(maxWidth: .infinity).padding(.vertical, 36)
                            } else { PostGrid(posts: posts) }
                        } else { about(p) }
                    } header: { tabs }
                }
                .padding(.bottom, 32)
            }
            .background(c.bg)
            .task(id: p.id) { await load(p) }
            .onChange(of: router.path) { _, path in if path.isEmpty { Task { await load(p) } } }
            .sheet(isPresented: $sharing) { ShareCardSheet(profile: p, university: university) }
        }
    }

    private var tabs: some View {
        VStack(spacing: 0) {
            HStack {
                ForEach([L("profile_posts"), L("profile_about")].indices, id: \.self) { i in
                    VStack(spacing: 0) {
                        Text([L("profile_posts"), L("profile_about")][i]).lyncText(.titleSmall, tab == i ? c.text : c.textMuted).padding(.vertical, 12)
                        Capsule().fill(c.text).frame(width: 60, height: tab == i ? 2 : 0)
                    }
                    .frame(maxWidth: .infinity).contentShape(Rectangle())
                    .onTapGesture { withAnimation(Motion.standard) { tab = i } }
                }
            }
            .padding(.horizontal, Tokens.gutter)
            Rectangle().fill(c.border).frame(height: 0.5)
        }
        .padding(.top, 12).background(c.bg)
    }

    private func about(_ p: Profile) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if !courses.isEmpty {
                HStack {
                    Text(L("profile_courses")).lyncText(.titleSmall, c.textMuted)
                    Spacer()
                    Pill(text: L("calendar_my_schedule"), style: .tonal, icon: "calendar", vertical: 4) { router.calendar() }
                }
                .padding(.top, 22)
                FlowLayout(spacing: 8) { ForEach(courses) { Tag(text: $0.code) } }
            }
            if !p.interests.isEmpty {
                Text(L("profile_interests")).lyncText(.titleSmall, c.textMuted).padding(.top, 22)
                FlowLayout(spacing: 8) { ForEach(p.interests, id: \.self) { Tag(text: $0) } }
            }
        }
        .padding(.horizontal, Tokens.gutter)
    }

    private func load(_ p: Profile) async {
        let repo = env.repository
        stats = (try? await repo.stats(nil)) ?? stats
        courses = (try? await repo.myCourses()) ?? []
        posts = (try? await repo.posts(authorId: p.id)) ?? []
        university = try? await repo.university(p.universityId)
    }
}

/// Someone else's profile: their card, connect + follow, their posts.
struct UserProfileScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    let id: String
    @State private var person: Person?
    @State private var university: University?
    @State private var stats = ProfileStats()
    @State private var posts: [FeedPost] = []
    @State private var sharing = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                CircleIconButton(icon: "arrow.left") { router.back() }
                Text(person.map { "@\($0.profile.username)" } ?? "").lyncText(.titleLarge).lineLimit(1)
                Spacer()
                if person != nil { CircleIconButton(icon: "square.and.arrow.up") { sharing = true } }
            }
            .padding(.horizontal, 12).frame(height: 64)
            if let p = person {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        FlippableCard(profile: p.profile, university: university)
                        if let bio = p.profile.bio { Text(bio).lyncText(.bodyMedium).padding(.horizontal, Tokens.gutter).padding(.vertical, 10) }
                        StatsRow(stats: stats)
                        HStack(spacing: 8) {
                            let (label, style): (String, PillStyle) = switch p.connection {
                            case .none: ("net_connect", .filled); case .incoming: ("net_accept", .filled)
                            case .outgoing: ("net_pending", .tonal); case .connected: ("net_connected", .outline)
                            }
                            Pill(text: L(label), style: style, fullWidth: true, vertical: 12) {
                                switch p.connection {
                                case .none: Haptics.confirm(); person?.connection = .outgoing; Task { try? await env.repository.connect(p.id) }
                                case .incoming: Haptics.confirm(); person?.connection = .connected; Task { try? await env.repository.respond(p.id, accept: true) }
                                default: break
                                }
                            }
                            Pill(text: L(p.following ? "net_following" : "net_follow"), style: p.following ? .outline : .tonal, fullWidth: true, vertical: 12) {
                                Haptics.tick()
                                let follow = !p.following
                                person?.following = follow; stats.followers += follow ? 1 : -1
                                Task { try? await env.repository.setFollowing(p.id, follow) }
                            }
                        }
                        .padding(.horizontal, Tokens.gutter).padding(.vertical, 10)
                        VStack(alignment: .leading, spacing: 8) {
                            if p.sharedCourses > 0 { Text(L("net_shared_courses", p.sharedCourses)).lyncText(.titleSmall, c.accent) }
                            FlowLayout(spacing: 8) { ForEach(p.profile.interests, id: \.self) { Tag(text: $0) } }
                        }
                        .padding(.horizontal, Tokens.gutter).padding(.vertical, 8)
                        Rectangle().fill(c.border).frame(height: 0.5).padding(.top, 14)
                        PostGrid(posts: posts)
                    }
                    .padding(.bottom, 32)
                }
                .sheet(isPresented: $sharing) { ShareCardSheet(profile: p.profile, university: university) }
            }
            Spacer(minLength: 0)
        }
        .background(c.bg)
        .task {
            if id == env.me?.id { router.back(); return }
            person = try? await env.repository.profile(id)
            if let p = person {
                university = try? await env.repository.university(p.profile.universityId)
                stats = (try? await env.repository.stats(id)) ?? stats
                posts = (try? await env.repository.posts(authorId: id)) ?? []
            }
        }
    }
}

struct EditProfileScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var name = ""
    @State private var bio = ""
    @State private var program: String?
    @State private var year: Int?
    @State private var interests: [String] = []
    @State private var newInterest = ""
    @State private var photo: PhotosPickerItem?
    @State private var avatar: Data?
    @State private var saving = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                CircleIconButton(icon: "arrow.left") { router.back() }
                Text(L("profile_edit")).lyncText(.titleLarge)
                Spacer()
                Pill(text: L("action_save"), style: name.isEmpty ? .tonal : .filled) { save() }
            }
            .padding(.horizontal, 12).frame(height: 60)
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    PhotosPicker(selection: $photo, matching: .images) {
                        ZStack(alignment: .bottomTrailing) {
                            if let avatar, let ui = UIImage(data: avatar) { Image(uiImage: ui).resizable().scaledToFill().frame(width: 104, height: 104).clipShape(Circle()) }
                            else { Avatar(url: env.me?.avatarUrl, initials: env.me?.initials ?? "", size: 104) }
                            Image(systemName: "camera.fill").font(.system(size: 13)).foregroundStyle(c.bg).frame(width: 30, height: 30).background(Circle().fill(c.text)).padding(2).background(Circle().fill(c.bg))
                        }
                    }
                    .frame(maxWidth: .infinity)
                    label("onb_full_name"); Field(hint: L("onb_full_name"), text: $name)
                    label("edit_bio"); Field(hint: L("edit_bio_hint"), text: $bio, multiline: true)
                    label("onb_program"); ProgramField(value: $program)
                    label("onb_year")
                    HStack(spacing: 8) {
                        ForEach(1...4, id: \.self) { y in Pill(text: "\(y)", style: year == y ? .filled : .tonal) { year = y } }
                        Pill(text: "4+", style: year == 5 ? .filled : .tonal) { year = 5 }
                    }
                    label("profile_interests")
                    FlowLayout(spacing: 8) {
                        ForEach(interests, id: \.self) { t in
                            HStack(spacing: 6) { Text(t).lyncText(.labelLarge); Image(systemName: "xmark").font(.system(size: 10, weight: .bold)).foregroundStyle(c.textMuted) }
                                .padding(.horizontal, 14).padding(.vertical, 8).background(Capsule().fill(c.surface))
                                .onTapGesture { interests.removeAll { $0 == t } }
                        }
                    }
                    HStack(spacing: 8) {
                        Field(hint: L("edit_add_interest"), text: $newInterest)
                        Pill(text: L("edit_add"), style: .tonal) {
                            let t = newInterest.trimmingCharacters(in: .whitespaces)
                            if !t.isEmpty, !interests.contains(t), interests.count < 8 { interests.append(t) }
                            newInterest = ""
                        }
                    }
                    .padding(.top, 10)
                }
                .padding(.horizontal, Tokens.gutter).padding(.bottom, 40)
            }
        }
        .background(c.bg)
        .onAppear {
            guard let me = env.me else { return }
            name = me.fullName; bio = me.bio ?? ""; program = me.program; year = me.year; interests = me.interests
        }
        .onChange(of: photo) { _, item in Task { avatar = try? await item?.loadTransferable(type: Data.self) } }
    }

    private func label(_ key: String) -> some View { Text(L(key)).lyncText(.titleSmall, c.textMuted).padding(.top, 20) }

    private func save() {
        guard !saving, !name.isEmpty else { return }
        saving = true
        let data = avatar.flatMap { UIImage(data: $0)?.jpegData(compressionQuality: 0.8) }
        Task {
            try? await env.repository.updateProfile(ProfileEdit(fullName: name, bio: bio, program: program, year: year, interests: interests, avatar: data))
            saving = false
            router.back()
        }
    }
}
