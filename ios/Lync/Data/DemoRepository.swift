import Foundation

/// In-memory backend with realistic Concordia data. Used automatically when no Supabase keys
/// are set, so every feature is demo-able offline. Same data as the Android DemoRepository.
final class DemoRepository: CampusRepository {
    let isDemo = true
    let session = SessionState()

    private let concordia = University(id: "uni-concordia", slug: "concordia", name: "Concordia University", shortName: "Concordia",
                                       emailDomain: "concordia.ca", primaryColor: "#912338", secondaryColor: "#D4A12A",
                                       campusLat: 45.4972, campusLng: -73.5790)

    static func demoCode(_ seed: String) -> Int64 {
        // Java String.hashCode, masked to 32 bits — same codes as Android
        var h: Int32 = 0
        for u in seed.utf16 { h = h &* 31 &+ Int32(u) }
        let v = Int64(UInt32(bitPattern: h))
        return v == 0 ? 1 : v
    }

    private func p(_ id: String, _ name: String, _ user: String, _ program: String, _ year: Int, _ status: String? = nil, _ interests: [String] = [], bio: String? = nil) -> Profile {
        Profile(id: id, universityId: concordia.id, username: user, fullName: name, program: program, year: year, bio: bio,
                avatarUrl: "https://i.pravatar.cc/300?u=\(user)", interests: interests, statusKey: status, lyncCode: Self.demoCode(id))
    }

    private lazy var people: [Profile] = [
        p("u1", "Maya Tremblay", "maya.t", "Computer Science", 1, "coffee", ["Climbing", "UI design", "Matcha"], bio: "Webster lib 6th floor regular"),
        p("u2", "Karim Haddad", "karim", "Software Engineering", 2, "study", ["F1", "Robotics"], bio: "Building robots that mostly work"),
        p("u3", "Léa Nguyen", "lea.ng", "Psychology", 1, "lunch", ["Photography", "K-pop"]),
        p("u4", "Noah Gagnon", "noahg", "Finance", 3, nil, ["Basketball", "Investing"]),
        p("u5", "Inès Benali", "ines.b", "Computer Science", 1, "coffee", ["Hackathons", "Piano"]),
        p("u6", "Lucas Roy", "lucasroy", "Mechanical Engineering", 2, "busy", ["Cars", "3D printing"]),
        p("u7", "Sofia Rossi", "sofia.r", "Design", 1, nil, ["Film", "Typography"]),
        p("u8", "Adam Pelletier", "adamp", "Computer Science", 1, "study", ["Gaming", "Chess"]),
        p("u9", "Chloé Martin", "chloe.m", "Communications", 2, nil, ["Podcasts", "Thrifting"]),
        p("u10", "Yanis Cherif", "yanis", "Data Science", 1, "lunch", ["Soccer", "AI"]),
    ]
    private func person(_ id: String) -> Profile? { people.first { $0.id == id } ?? (session.me?.id == id ? session.me : nil) }

    private let courses = [
        Course(id: "c1", code: "COMP 248", title: "Object-Oriented Programming I"),
        Course(id: "c2", code: "MATH 203", title: "Differential & Integral Calculus I"),
        Course(id: "c3", code: "ENGR 201", title: "Professional Practice"),
        Course(id: "c4", code: "COMP 228", title: "System Hardware"),
    ]
    private let shared: [String: Int] = ["u1": 3, "u5": 2, "u8": 2, "u10": 1, "u2": 1]
    private var connections: [String: ConnectionState] = ["u1": .connected, "u2": .connected, "u3": .connected, "u6": .connected, "u7": .incoming, "u9": .incoming]
    private var following: Set<String> = ["u1", "u2", "u3", "o1"]

    private let clubs = [
        Organization(id: "o1", name: "Concordia Robotics", handle: "conu.robotics", avatarUrl: "https://i.pravatar.cc/300?u=robotics", verified: true),
        Organization(id: "o2", name: "HackConcordia", handle: "hackconcordia", avatarUrl: "https://i.pravatar.cc/300?u=hackconcordia", verified: true),
        Organization(id: "o3", name: "Film Society", handle: "conu.film", avatarUrl: "https://i.pravatar.cc/300?u=film"),
        Organization(id: "o4", name: "Climbing Club", handle: "conu.climb", avatarUrl: "https://i.pravatar.cc/300?u=climb"),
    ]

    static func photo(_ key: String) -> String {
        let photos = ["library": "1521587760476-6c12a4b040da", "hackathon": "1531482615713-2afd69097998", "snow": "1491002052546-bf38f186af56",
                      "robot": "1485827404703-89b55fcc595e", "cinema": "1489599849927-2ee91cede3ba", "city": "1477959858617-67f85cf4f1df",
                      "campus": "1541339907198-e08756dedf3f"]
        return "https://images.unsplash.com/photo-\(photos[key] ?? photos["campus"]!)?w=900&q=80"
    }

    private func base(_ id: String, _ author: String, ago: TimeInterval, likes: Int, org: Bool = false, sponsored: Bool = false) -> FeedPost {
        let o = org ? clubs.first { $0.id == author } : nil
        let pr = org ? nil : person(author)
        return FeedPost(id: id, authorId: author, authorName: o?.name ?? pr!.fullName, authorHandle: o?.handle ?? pr!.username,
                        authorAvatar: o?.avatarUrl ?? pr!.avatarUrl, isOrganization: org, verified: o?.verified ?? false,
                        imageUrl: nil, caption: nil, createdAt: Date().addingTimeInterval(-ago), likeCount: likes, likedByMe: false, sponsored: sponsored)
    }

    private let min: TimeInterval = 60

    private lazy var postsData: [FeedPost] = {
        var a = base("p0", "u5", ago: 4 * min, likes: 6); a.kind = .plan; a.caption = "Leg day, need motivation 😤"
        a.plan = Plan(activity: .gym, place: "Le Gym, EV building", startsAt: Date().addingTimeInterval(50 * min), spots: 4, going: [person("u8")!], imGoing: false)
        var b = base("p1", "u1", ago: 12 * min, likes: 48); b.imageUrl = Self.photo("library"); b.caption = "Study session at the Webster library, 6th floor. Pull up 📚"
        var c = base("p7", "u2", ago: 25 * min, likes: 19); c.kind = .poll
        c.poll = Poll(question: "Best study spot on campus?", options: [PollOption(id: "a", text: "Webster library", votes: 41), PollOption(id: "b", text: "EV 2nd floor", votes: 23),
                                                                           PollOption(id: "c", text: "Hall building cafés", votes: 17), PollOption(id: "d", text: "Home 🛏️", votes: 30)],
                      myVote: nil, endsAt: Date().addingTimeInterval(20 * 60 * min))
        var d = base("p2", "o2", ago: 40 * min, likes: 212, org: true, sponsored: true); d.imageUrl = Self.photo("hackathon"); d.caption = "HackDécouverte II is live! Good luck everyone 🚀"
        var e = base("p8", "u3", ago: 70 * min, likes: 33); e.kind = .text; e.textStyle = 2; e.caption = "Anyone else's 8:45 cancelled or is it just me 🙃"
        var f = base("p9", "u10", ago: 90 * min, likes: 9); f.kind = .plan; f.caption = "MATH 203 midterm grind, bring snacks"
        f.plan = Plan(activity: .library, place: "Webster library, 4th floor", startsAt: Date().addingTimeInterval(120 * min), spots: nil, going: [person("u1")!, person("u5")!, person("u8")!], imGoing: false)
        var g = base("p3", "u3", ago: 120 * min, likes: 96); g.imageUrl = Self.photo("snow"); g.caption = "First snow on campus ❄️"
        var h = base("p4", "u2", ago: 300 * min, likes: 131); h.imageUrl = Self.photo("robot"); h.caption = "Robot arm finally picks up the cup. 3 weeks. Worth it."
        var i = base("p5", "o3", ago: 480 * min, likes: 74, org: true); i.imageUrl = Self.photo("cinema"); i.caption = "Free screening tonight 7pm, H-110. Popcorn on us 🍿"
        var j = base("p6", "u6", ago: 26 * 60 * min, likes: 158); j.imageUrl = Self.photo("city"); j.caption = "Hall building at golden hour"
        return [a, b, c, d, e, f, g, h, i, j]
    }()

    private lazy var commentsByPost: [String: [Comment]] = [
        "p1": [Comment(id: "k1", author: person("u5")!, text: "saving me a seat?", createdAt: Date().addingTimeInterval(-8 * min)),
               Comment(id: "k2", author: person("u8")!, text: "omw 🏃", createdAt: Date().addingTimeInterval(-5 * min))],
        "p4": [Comment(id: "k3", author: person("u6")!, text: "this is so cool", createdAt: Date().addingTimeInterval(-180 * min))],
    ]

    private lazy var storyData: [StoryGroup] = [
        StoryGroup(profile: person("u1")!, stories: [Story(id: "s1", imageUrl: Self.photo("library"), text: "6th floor is packed", textStyle: 0, createdAt: Date().addingTimeInterval(-30 * min))], seen: false),
        StoryGroup(profile: person("u2")!, stories: [Story(id: "s2", imageUrl: nil, text: "Robotics demo at 4pm, EV-11", textStyle: 2, createdAt: Date().addingTimeInterval(-50 * min)),
                                                    Story(id: "s3", imageUrl: Self.photo("robot"), text: nil, textStyle: 0, createdAt: Date().addingTimeInterval(-40 * min))], seen: false),
        StoryGroup(profile: person("u3")!, stories: [Story(id: "s4", imageUrl: Self.photo("snow"), text: "❄️❄️❄️", textStyle: 0, createdAt: Date().addingTimeInterval(-120 * min))], seen: false),
        StoryGroup(profile: person("u6")!, stories: [Story(id: "s5", imageUrl: nil, text: "who has the ENGR 201 notes 🙏", textStyle: 1, createdAt: Date().addingTimeInterval(-180 * min))], seen: false),
    ]
    private var myStories: [Story] = []

    // MARK: session

    private let defaults = UserDefaults.standard
    func restore() async {
        if let data = defaults.data(forKey: "demo_profile"), let p = try? JSONDecoder().decode(Profile.self, from: data) { session.me = p }
    }
    private func persist() {
        if let me = session.me, let data = try? JSONEncoder().encode(me) { defaults.set(data, forKey: "demo_profile") } else { defaults.removeObject(forKey: "demo_profile") }
    }

    func universities() async throws -> [University] { [concordia] }
    func university(_ id: String) async throws -> University? { id == concordia.id ? concordia : nil }

    func signUp(_ form: SignUpForm) async throws -> Profile {
        try? await Task.sleep(nanoseconds: 400_000_000)
        let username = form.email.split(separator: "@").first.map { String($0).lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".")) } ?? "me"
        let p = Profile(id: "me", universityId: form.university.id, username: username, fullName: form.fullName.trimmingCharacters(in: .whitespaces),
                        program: form.program.isEmpty ? nil : form.program, year: form.year, interests: ["Coffee", "Music", "Hackathons"],
                        statusKey: "coffee", lyncCode: Self.demoCode("me-" + form.fullName))
        session.me = p; persist()
        return p
    }

    func signIn(email: String, password: String) async throws -> Profile {
        let local = email.split(separator: "@").first.map(String.init) ?? "student"
        let name = local.split(whereSeparator: { $0 == "." || $0 == "_" }).map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
        return try await signUp(SignUpForm(university: concordia, fullName: name, email: email, password: password, program: "Computer Science", year: 1))
    }

    func signOut() async { session.me = nil; persist() }

    func updateProfile(_ edit: ProfileEdit) async throws {
        guard var m = session.me else { return }
        m.fullName = edit.fullName.trimmingCharacters(in: .whitespaces).isEmpty ? m.fullName : edit.fullName
        m.bio = edit.bio.isEmpty ? nil : edit.bio; m.program = edit.program; m.year = edit.year; m.interests = edit.interests
        if edit.avatar != nil { m.avatarUrl = "https://i.pravatar.cc/300?u=\(UUID().uuidString)" }
        session.me = m; persist()
    }

    func updateStatus(_ key: String?) async throws { session.me?.statusKey = key; persist() }
    func setLocationSharing(_ mode: LocationSharing) async throws { session.me?.locationSharing = mode; persist() }

    func stats(_ profileId: String?) async throws -> ProfileStats {
        let id = profileId ?? session.me?.id
        if id == session.me?.id { return ProfileStats(posts: postsData.filter { $0.authorId == id }.count, connections: connections.values.filter { $0 == .connected }.count, followers: 87) }
        let h = abs((id ?? "").hashValue)
        return ProfileStats(posts: postsData.filter { $0.authorId == id }.count, connections: 40 + h % 64, followers: 120 + h % 256)
    }

    func myCourses() async throws -> [Course] { courses }
    func posts(authorId: String) async throws -> [FeedPost] { postsData.filter { $0.authorId == authorId } }

    func feed() async throws -> [FeedPost] {
        try? await Task.sleep(nanoseconds: 200_000_000)
        return postsData.map { var p = $0; p.commentCount = commentsByPost[$0.id]?.count ?? 0; return p }
    }

    private func update(_ id: String, _ f: (inout FeedPost) -> Void) { if let i = postsData.firstIndex(where: { $0.id == id }) { f(&postsData[i]) } }

    func setLiked(_ postId: String, _ liked: Bool) async throws {
        update(postId) { p in if p.likedByMe != liked { p.likedByMe = liked; p.likeCount += liked ? 1 : -1 } }
    }

    func vote(_ postId: String, optionId: String) async throws {
        update(postId) { p in
            guard var poll = p.poll, poll.myVote == nil else { return }
            poll.myVote = optionId
            poll.options = poll.options.map { var o = $0; if o.id == optionId { o.votes += 1 }; return o }
            p.poll = poll
        }
    }

    func setGoing(_ postId: String, _ going: Bool) async throws {
        guard let m = session.me else { return }
        update(postId) { p in
            guard var plan = p.plan else { return }
            plan.going = plan.going.filter { $0.id != m.id } + (going ? [m] : [])
            plan.imGoing = going; p.plan = plan
        }
    }

    private var groupFeed: [String: [FeedPost]] = [:]
    private var groupFeedSeeded = false

    func createPost(_ draft: PostDraft, groupId: String?) async throws {
        guard let m = session.me else { throw LyncError.message("Not signed in") }
        var post = FeedPost(id: UUID().uuidString, authorId: m.id, authorName: m.fullName, authorHandle: m.username, authorAvatar: m.avatarUrl,
                            isOrganization: false, verified: false, imageUrl: nil, caption: nil, createdAt: Date(), likeCount: 0, likedByMe: false, sponsored: false)
        switch draft {
        case .photo(_, let caption): post.kind = .photo; post.imageUrl = Self.photo("campus"); post.caption = caption.isEmpty ? nil : caption
        case .text(let text, let style): post.kind = .text; post.caption = text; post.textStyle = style
        case .poll(let q, let options, let hours):
            post.kind = .poll
            post.poll = Poll(question: q, options: options.enumerated().map { PollOption(id: "o\($0.offset)", text: $0.element, votes: 0) }, myVote: nil, endsAt: Date().addingTimeInterval(Double(hours) * 3600))
        case .plan(let activity, let place, let startsAt, let spots, let note):
            post.kind = .plan; post.caption = note.isEmpty ? nil : note
            post.plan = Plan(activity: activity, place: place, startsAt: startsAt, spots: spots, going: [m], imGoing: true)
        }
        if let groupId { seedGroups(); groupFeed[groupId, default: []].insert(post, at: 0) } else { postsData.insert(post, at: 0) }
    }

    func comments(_ postId: String) async throws -> [Comment] { commentsByPost[postId] ?? [] }

    func addComment(_ postId: String, text: String) async throws -> Comment {
        guard let m = session.me else { throw LyncError.message("Not signed in") }
        let c = Comment(id: UUID().uuidString, author: m, text: text.trimmingCharacters(in: .whitespaces), createdAt: Date())
        commentsByPost[postId, default: []].append(c)
        update(postId) { $0.commentCount += 1 }
        return c
    }

    func stories() async throws -> [StoryGroup] {
        var mine: [StoryGroup] = []
        if let m = session.me, !myStories.isEmpty { mine = [StoryGroup(profile: m, stories: myStories, seen: true)] }
        return mine + storyData.sorted { !$0.seen && $1.seen }
    }

    func addStory(text: String?, image: Data?, style: Int) async throws {
        myStories.append(Story(id: UUID().uuidString, imageUrl: image != nil ? Self.photo("campus") : nil, text: text, textStyle: style, createdAt: Date()))
    }

    func markStorySeen(_ profileId: String) async throws {
        storyData = storyData.map { var g = $0; if g.profile.id == profileId { g.seen = true }; return g }
    }

    // MARK: network

    private func toPerson(_ pr: Profile) -> Person {
        Person(profile: pr, sharedCourses: shared[pr.id] ?? 0, mutuals: (abs(pr.id.hashValue) % 8) + 1, connection: connections[pr.id] ?? .none, following: following.contains(pr.id))
    }

    func requests() async throws -> [Person] { people.filter { connections[$0.id] == .incoming }.map(toPerson) }
    func classmates() async throws -> [Person] { people.filter { shared[$0.id] != nil }.map(toPerson).sorted { $0.sharedCourses > $1.sharedCourses } }
    func suggestions() async throws -> [Person] { people.filter { connections[$0.id] == nil && shared[$0.id] == nil }.map(toPerson) }
    func clubs() async throws -> [Organization] { clubs }

    func search(_ query: String) async throws -> [Person] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return [] }
        return people.filter { $0.fullName.lowercased().contains(q) || $0.username.contains(q) || ($0.program ?? "").lowercased().contains(q) }.map(toPerson)
    }

    func profile(_ id: String) async throws -> Person? { person(id).map(toPerson) }
    func profileByCode(_ code: Int64) async throws -> Person? { people.first { $0.lyncCode == code }.map(toPerson) }

    func connect(_ profileId: String, source: String) async throws {
        // In the demo, scanning someone's card (or NFC) connects instantly — that's the magic moment.
        connections[profileId] = source == "app" ? .outgoing : .connected
    }

    func respond(_ profileId: String, accept: Bool) async throws { connections[profileId] = accept ? .connected : nil }
    func setFollowing(_ profileId: String, _ follow: Bool) async throws { if follow { following.insert(profileId) } else { following.remove(profileId) } }

    // MARK: campus life

    private func at(_ days: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        var c = Calendar.current.dateComponents([.year, .month, .day], from: Calendar.current.date(byAdding: .day, value: days, to: Date())!)
        c.hour = hour; c.minute = minute
        return Calendar.current.date(from: c)!
    }

    private lazy var eventData: [CampusEvent] = [
        CampusEvent(id: "e1", title: "Robotics open lab", description: "Come see the rover we're sending to the University Rover Challenge. Pizza after 6.", organizer: clubs[0], category: .tech,
                    startsAt: at(0, 17), endsAt: at(0, 20), place: "EV 11.119", coverUrl: Self.photo("robot"), goingCount: 42, imGoing: false, friendsGoing: [person("u2")!, person("u5")!], featured: true),
        CampusEvent(id: "e2", title: "Free movie night: Dune", description: "Big screen, free popcorn, bring a friend.", organizer: clubs[2], category: .culture,
                    startsAt: at(0, 19), endsAt: at(0, 22), place: "H-110", coverUrl: Self.photo("cinema"), goingCount: 88, imGoing: false, friendsGoing: [person("u3")!], featured: false),
        CampusEvent(id: "e3", title: "Bouldering for beginners", description: "No experience needed, shoes provided. Meet in the lobby.", organizer: clubs[3], category: .sport,
                    startsAt: at(1, 18), endsAt: at(1, 20), place: "Le Gym, EV building", coverUrl: Self.photo("campus"), goingCount: 23, imGoing: false, friendsGoing: [person("u1")!], featured: false),
        CampusEvent(id: "e4", title: "Hackathon info session", description: "Everything about HackConcordia's next events, teams and prizes.", organizer: clubs[1], category: .tech,
                    startsAt: at(2, 12), endsAt: at(2, 13), place: "Webster Library, LB-322", coverUrl: Self.photo("hackathon"), goingCount: 61, imGoing: false, friendsGoing: [], featured: false),
        CampusEvent(id: "e5", title: "Career fair: tech & engineering", description: "60 companies hiring interns. Bring your CV.", organizer: clubs[1], category: .career,
                    startsAt: at(4, 10), endsAt: at(4, 16), place: "JMSB atrium", coverUrl: Self.photo("city"), goingCount: 310, imGoing: false,
                    friendsGoing: [person("u2")!, person("u6")!, person("u8")!], featured: true),
    ]

    func events() async throws -> [CampusEvent] { eventData.sorted { $0.startsAt < $1.startsAt } }
    func event(_ id: String) async throws -> CampusEvent? { eventData.first { $0.id == id } }
    func setRsvp(_ eventId: String, _ going: Bool) async throws {
        if let i = eventData.firstIndex(where: { $0.id == eventId }), eventData[i].imGoing != going { eventData[i].imGoing = going; eventData[i].goingCount += going ? 1 : -1 }
    }

    private lazy var groupData: [InterestGroup] = [
        InterestGroup(id: "g1", name: "Climbing crew", emoji: "🧗", description: "Bouldering sessions every week, all levels.", memberCount: 64, isMember: true, members: [person("u1")!, person("u6")!]),
        InterestGroup(id: "g2", name: "F1 & motorsport", emoji: "🏎️", description: "Race weekends, watch parties, hot takes.", memberCount: 41, isMember: false, members: [person("u2")!]),
        InterestGroup(id: "g3", name: "K-pop dance", emoji: "💃", description: "Learn choreos, perform at Shuffle.", memberCount: 87, isMember: false, members: [person("u3")!]),
        InterestGroup(id: "g4", name: "Chess club", emoji: "♟️", description: "Blitz at the library café, Thursdays.", memberCount: 33, isMember: false, members: [person("u8")!]),
        InterestGroup(id: "g5", name: "Photo walks", emoji: "📷", description: "Montreal photo walks, film and digital.", memberCount: 52, isMember: true, members: [person("u3")!, person("u7")!]),
        InterestGroup(id: "g6", name: "AI & machine learning", emoji: "🤖", description: "Paper club, projects, Kaggle teams.", memberCount: 120, isMember: false, members: [person("u10")!, person("u5")!]),
    ]

    private func seedGroups() {
        guard !groupFeedSeeded else { return }
        groupFeedSeeded = true
        var a = base("gp1", "u1", ago: 180 * min, likes: 12); a.kind = .plan; a.caption = "Who's in for a session tonight?"
        a.plan = Plan(activity: .sport, place: "Allez Up, Saint-Henri", startsAt: Date().addingTimeInterval(240 * min), spots: 6, going: [person("u6")!], imGoing: false)
        var b = base("gp2", "u3", ago: 26 * 60 * min, likes: 30); b.imageUrl = Self.photo("city"); b.caption = "Golden hour on Sainte-Catherine"
        groupFeed["g1", default: []].append(a); groupFeed["g5", default: []].append(b)
    }

    func groups() async throws -> [InterestGroup] { groupData.sorted { $0.isMember && !$1.isMember } }
    func group(_ id: String) async throws -> InterestGroup? { groupData.first { $0.id == id } }
    func setMember(_ groupId: String, _ join: Bool) async throws {
        guard let i = groupData.firstIndex(where: { $0.id == groupId }), groupData[i].isMember != join else { return }
        groupData[i].isMember = join; groupData[i].memberCount += join ? 1 : -1
        if let m = session.me { groupData[i].members = join ? [m] + groupData[i].members : groupData[i].members.filter { $0.id != m.id } }
    }
    func groupPosts(_ groupId: String) async throws -> [FeedPost] { seedGroups(); return groupFeed[groupId] ?? [] }
    func createGroup(name: String, emoji: String, description: String) async throws -> InterestGroup {
        guard let m = session.me else { throw LyncError.message("Not signed in") }
        let g = InterestGroup(id: UUID().uuidString, name: name, emoji: emoji, description: description, memberCount: 1, isMember: true, members: [m])
        groupData.insert(g, at: 0)
        return g
    }

    // MARK: map

    func pushLocation(lat: Double, lng: Double) async throws {}
    func clearLocation() async {}

    // Real Concordia SGW / Loyola spots so the demo map looks right.
    private let spots: [String: (Double, Double)] = [
        "u1": (45.49680, -73.57790), "u2": (45.49560, -73.57820), "u3": (45.49725, -73.57890), "u6": (45.49530, -73.57905),
        "u5": (45.49745, -73.57955), "u8": (45.45820, -73.64050), "u10": (45.49610, -73.57700),
    ]

    func mapPins() -> AsyncStream<[MapPin]> {
        AsyncStream { cont in
            let task = Task { [weak self] in
                var t = 0
                while !Task.isCancelled, let self {
                    let pins: [MapPin] = self.spots.compactMap { id, pos in
                        guard let pr = self.person(id) else { return nil }
                        let kind: PinKind
                        if self.connections[id] == .connected { kind = .friend } else if self.shared[id] != nil { kind = .classmate } else { return nil }
                        let a = Double(t + abs(id.hashValue % 100)) * 0.6 // tiny drift so friends look alive
                        return MapPin(profile: pr, lat: pos.0 + sin(a) * 0.00004, lng: pos.1 + cos(a) * 0.00005, updatedAt: Date(), kind: kind)
                    }
                    cont.yield(pins)
                    t += 1
                    try? await Task.sleep(nanoseconds: 4_000_000_000)
                }
            }
            cont.onTermination = { _ in task.cancel() }
        }
    }
}
