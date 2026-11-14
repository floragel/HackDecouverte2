import Foundation

// Field names mirror supabase/schema.sql (snake_case via CodingKeys).
// Same models as the Android app (app/src/main/java/com/lync/app/data/model/Models.kt).

struct University: Codable, Identifiable, Hashable {
    let id: String
    let slug: String
    let name: String
    let shortName: String
    let emailDomain: String
    let primaryColor: String
    let secondaryColor: String
    let campusLat: Double
    let campusLng: Double

    enum CodingKeys: String, CodingKey {
        case id, slug, name
        case shortName = "short_name", emailDomain = "email_domain"
        case primaryColor = "primary_color", secondaryColor = "secondary_color"
        case campusLat = "campus_lat", campusLng = "campus_lng"
    }
}

enum LocationSharing: String, Codable, CaseIterable {
    case off, friends
    case friendsAndClassmates = "friends_and_classmates"
}

struct Profile: Codable, Identifiable, Hashable {
    var id: String
    var universityId: String
    var username: String
    var fullName: String
    var program: String?
    var year: Int?
    var bio: String?
    var avatarUrl: String?
    var interests: [String] = []
    var statusKey: String?
    var locationSharing: LocationSharing = .friends
    /// 32-bit number drawn as the Lync code on the back of the card (and sent over NFC).
    var lyncCode: Int64?

    enum CodingKeys: String, CodingKey {
        case id, username, program, year, bio, interests
        case universityId = "university_id", fullName = "full_name", avatarUrl = "avatar_url"
        case statusKey = "status_key", locationSharing = "location_sharing", lyncCode = "lync_code"
    }

    init(id: String, universityId: String, username: String, fullName: String, program: String? = nil, year: Int? = nil,
         bio: String? = nil, avatarUrl: String? = nil, interests: [String] = [], statusKey: String? = nil,
         locationSharing: LocationSharing = .friends, lyncCode: Int64? = nil) {
        self.id = id; self.universityId = universityId; self.username = username; self.fullName = fullName
        self.program = program; self.year = year; self.bio = bio; self.avatarUrl = avatarUrl; self.interests = interests
        self.statusKey = statusKey; self.locationSharing = locationSharing; self.lyncCode = lyncCode
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        universityId = try c.decode(String.self, forKey: .universityId)
        username = try c.decode(String.self, forKey: .username)
        fullName = try c.decode(String.self, forKey: .fullName)
        program = try c.decodeIfPresent(String.self, forKey: .program)
        year = try c.decodeIfPresent(Int.self, forKey: .year)
        bio = try c.decodeIfPresent(String.self, forKey: .bio)
        avatarUrl = try c.decodeIfPresent(String.self, forKey: .avatarUrl)
        interests = (try? c.decodeIfPresent([String].self, forKey: .interests)) ?? []
        statusKey = try c.decodeIfPresent(String.self, forKey: .statusKey)
        locationSharing = (try? c.decodeIfPresent(LocationSharing.self, forKey: .locationSharing)) ?? .friends
        lyncCode = try c.decodeIfPresent(Int64.self, forKey: .lyncCode)
    }

    var firstName: String { String(fullName.split(separator: " ").first ?? Substring(fullName)) }
    var initials: String { fullName.split(separator: " ").prefix(2).compactMap { $0.first }.map { String($0).uppercased() }.joined() }
}

struct Course: Codable, Identifiable, Hashable {
    let id: String
    let code: String
    let title: String
}

struct Organization: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let handle: String
    var avatarUrl: String?
    var verified: Bool = false

    enum CodingKeys: String, CodingKey { case id, name, handle, verified; case avatarUrl = "avatar_url" }

    init(id: String, name: String, handle: String, avatarUrl: String? = nil, verified: Bool = false) {
        self.id = id; self.name = name; self.handle = handle; self.avatarUrl = avatarUrl; self.verified = verified
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id); name = try c.decode(String.self, forKey: .name)
        handle = try c.decode(String.self, forKey: .handle)
        avatarUrl = try c.decodeIfPresent(String.self, forKey: .avatarUrl)
        verified = (try? c.decodeIfPresent(Bool.self, forKey: .verified)) ?? false
    }
}

enum PostKind: String, Codable { case photo, text, poll, plan }

struct PollOption: Hashable, Identifiable { let id: String; let text: String; var votes: Int }

struct Poll: Hashable {
    let question: String
    var options: [PollOption]
    var myVote: String?
    let endsAt: Date
    var total: Int { options.reduce(0) { $0 + $1.votes } }
}

/// "Who's coming to the gym at 5?" — the social glue of Lync.
enum PlanActivity: String, CaseIterable, Codable {
    case gym, library, coffee, lunch, study, sport, party, other
    var emoji: String {
        switch self {
        case .gym: "🏋️"; case .library: "📚"; case .coffee: "☕"; case .lunch: "🍜"
        case .study: "✏️"; case .sport: "⚽"; case .party: "🎉"; case .other: "✨"
        }
    }
    static func of(_ key: String?) -> PlanActivity { PlanActivity(rawValue: key ?? "") ?? .other }
}

struct Plan: Hashable {
    let activity: PlanActivity
    let place: String
    let startsAt: Date
    let spots: Int?
    var going: [Profile]
    var imGoing: Bool
}

/// What the feed shows. Author is either a student or a club.
struct FeedPost: Identifiable, Hashable {
    let id: String
    let authorId: String
    let authorName: String
    let authorHandle: String
    let authorAvatar: String?
    let isOrganization: Bool
    let verified: Bool
    var imageUrl: String?
    var caption: String?
    let createdAt: Date
    var likeCount: Int
    var likedByMe: Bool
    let sponsored: Bool
    var kind: PostKind = .photo
    var textStyle: Int = 0
    var poll: Poll?
    var plan: Plan?
    var commentCount: Int = 0
}

struct Comment: Identifiable, Hashable { let id: String; let author: Profile; let text: String; let createdAt: Date }

struct Story: Identifiable, Hashable { let id: String; let imageUrl: String?; let text: String?; let textStyle: Int; let createdAt: Date }

struct StoryGroup: Identifiable, Hashable {
    let profile: Profile
    let stories: [Story]
    var seen: Bool
    var id: String { profile.id }
}

/// Everything the composer can publish.
enum PostDraft {
    case photo(image: Data, caption: String)
    case text(String, style: Int)
    case poll(question: String, options: [String], hours: Int)
    case plan(activity: PlanActivity, place: String, startsAt: Date, spots: Int?, note: String)
}

struct ProfileEdit {
    var fullName: String
    var bio: String
    var program: String?
    var year: Int?
    var interests: [String]
    var avatar: Data?
}

struct Program: Codable, Identifiable, Hashable {
    let slug: String
    let name: String
    let degree: String
    var id: String { slug }
    var label: String { degree.isEmpty ? name : "\(name) (\(degree))" }
}

enum ConnectionState { case none, outgoing, incoming, connected }

/// A person card in the Network tab.
struct Person: Identifiable, Hashable {
    var profile: Profile
    var sharedCourses: Int = 0
    var mutuals: Int = 0
    var connection: ConnectionState = .none
    var following: Bool = false
    var id: String { profile.id }
}

enum PinKind { case me, friend, classmate }

struct MapPin: Identifiable, Hashable {
    let profile: Profile
    let lat: Double
    let lng: Double
    let updatedAt: Date
    let kind: PinKind
    var id: String { profile.id }
    /// Position updated in the last 2 minutes.
    var isLive: Bool { Date().timeIntervalSince(updatedAt) < 120 }
}

struct ProfileStats: Hashable { var posts = 0; var connections = 0; var followers = 0 }

/// Deep link shared by text. Scanning uses the Lync code (see Code/LyncCode.swift).
enum CardPayload {
    static let prefix = "https://lync.app/u/"
    static func encode(_ profileId: String) -> String { prefix + profileId }
}

/// Visual styles for text posts / stories (index into this list). Same list as Android.
let TextStyles: [(UInt32, UInt32)] = [
    (0x1A1D29, 0x3A3F55), // night
    (0xFF6B6B, 0xFFB86B), // sunset
    (0x5B8CFF, 0x9B6BFF), // dusk
    (0x1FB58F, 0x9BE15D), // mint
    (0xFFD166, 0xFF9F1C), // honey
]

/// Preset statuses shown on the card and the stories row. Keys are stored in DB.
enum StatusPreset: String, CaseIterable {
    case coffee, study, lunch, busy
    var emoji: String { switch self { case .coffee: "☕"; case .study: "📚"; case .lunch: "🍜"; case .busy: "🎧" } }
    var labelKey: String { "status_\(rawValue)" }
    static func of(_ key: String?) -> StatusPreset? { key.flatMap { StatusPreset(rawValue: $0) } }
}

// MARK: - Campus life

enum EventCategory: String, CaseIterable {
    case social, academic, sport, career, culture, tech
    var emoji: String {
        switch self { case .social: "🎉"; case .academic: "🎓"; case .sport: "🏀"; case .career: "💼"; case .culture: "🎭"; case .tech: "💻" }
    }
    var labelKey: String { "evcat_\(rawValue)" }
    static func of(_ key: String?) -> EventCategory { EventCategory(rawValue: key ?? "") ?? .social }
}

/// Published by a club. Clubs on a paid plan can be "featured".
struct CampusEvent: Identifiable, Hashable {
    let id: String
    let title: String
    let description: String
    let organizer: Organization
    let category: EventCategory
    let startsAt: Date
    let endsAt: Date
    let place: String
    let coverUrl: String?
    var goingCount: Int
    var imGoing: Bool
    var friendsGoing: [Profile]
    let featured: Bool
}

struct InterestGroup: Identifiable, Hashable {
    let id: String
    let name: String
    let emoji: String
    let description: String
    var memberCount: Int
    var isMember: Bool
    var members: [Profile]
}

/// A campus building, used to answer "who's here?".
struct Building: Hashable, Identifiable { let code: String; let name: String; let lat: Double; let lng: Double; let radiusM: Double; var id: String { code } }

/// Friends currently inside a building.
struct BuildingPresence: Identifiable, Hashable { let building: Building; let people: [Profile]; let includesMe: Bool; var id: String { building.code } }
