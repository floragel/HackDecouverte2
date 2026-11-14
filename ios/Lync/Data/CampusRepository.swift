import Foundation
import Observation

struct SignUpForm {
    let university: University
    let fullName: String
    let email: String
    let password: String
    let program: String
    let year: Int?
}

/// The signed-in user, observed by every screen (Android: `repository.me` StateFlow).
@Observable
final class SessionState {
    var me: Profile?
}

/**
 * Single entry point the UI talks to — same protocol as the Android `CampusRepository`.
 *  - DemoRepository: in-memory Concordia data (works offline, perfect for demos)
 *  - SupabaseRepository: the real backend
 */
protocol CampusRepository: AnyObject {
    var isDemo: Bool { get }
    var session: SessionState { get }

    func restore() async
    func universities() async throws -> [University]
    func university(_ id: String) async throws -> University?
    func signUp(_ form: SignUpForm) async throws -> Profile
    func signIn(email: String, password: String) async throws -> Profile
    func signOut() async

    // Me
    func updateProfile(_ edit: ProfileEdit) async throws
    func updateStatus(_ key: String?) async throws
    func setLocationSharing(_ mode: LocationSharing) async throws
    func stats(_ profileId: String?) async throws -> ProfileStats
    func myCourses() async throws -> [Course]
    func posts(authorId: String) async throws -> [FeedPost]

    // Instagram side
    func feed() async throws -> [FeedPost]
    func setLiked(_ postId: String, _ liked: Bool) async throws
    func createPost(_ draft: PostDraft, groupId: String?) async throws
    func vote(_ postId: String, optionId: String) async throws
    func setGoing(_ postId: String, _ going: Bool) async throws
    func comments(_ postId: String) async throws -> [Comment]
    func addComment(_ postId: String, text: String) async throws -> Comment
    func stories() async throws -> [StoryGroup]
    func addStory(text: String?, image: Data?, style: Int) async throws
    func markStorySeen(_ profileId: String) async throws

    // LinkedIn side
    func requests() async throws -> [Person]
    func classmates() async throws -> [Person]
    func suggestions() async throws -> [Person]
    func clubs() async throws -> [Organization]
    func search(_ query: String) async throws -> [Person]
    func profile(_ id: String) async throws -> Person?
    /// Lync code (scanned or received over NFC) -> person.
    func profileByCode(_ code: Int64) async throws -> Person?
    func connect(_ profileId: String, source: String) async throws
    func respond(_ profileId: String, accept: Bool) async throws
    func setFollowing(_ profileId: String, _ follow: Bool) async throws

    // Campus life
    func events() async throws -> [CampusEvent]
    func event(_ id: String) async throws -> CampusEvent?
    func setRsvp(_ eventId: String, _ going: Bool) async throws
    func groups() async throws -> [InterestGroup]
    func group(_ id: String) async throws -> InterestGroup?
    func setMember(_ groupId: String, _ join: Bool) async throws
    func groupPosts(_ groupId: String) async throws -> [FeedPost]
    func createGroup(name: String, emoji: String, description: String) async throws -> InterestGroup

    // Snap side
    func pushLocation(lat: Double, lng: Double) async throws
    func clearLocation() async
    /// Live positions of friends (and classmates if they allow it).
    func mapPins() -> AsyncStream<[MapPin]>
}

extension CampusRepository {
    var me: Profile? { session.me }
    func connect(_ profileId: String) async throws { try await connect(profileId, source: "app") }
    func createPost(_ draft: PostDraft) async throws { try await createPost(draft, groupId: nil) }
}

enum LyncError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let m) = self { return m }; return nil }
}

/// ISO-8601 with or without fractional seconds (Postgres timestamptz).
enum ISO {
    private static let frac: ISO8601DateFormatter = { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f }()
    private static let plain = ISO8601DateFormatter()
    static func date(_ s: String?) -> Date {
        guard let s else { return Date(timeIntervalSince1970: 0) }
        return frac.date(from: s) ?? plain.date(from: s) ?? Date(timeIntervalSince1970: 0)
    }
    static func string(_ d: Date) -> String { frac.string(from: d) }
}
