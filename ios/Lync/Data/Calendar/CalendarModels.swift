import Foundation

enum CalendarItemKind: String, Codable { case course = "COURSE", socialEvent = "SOCIAL_EVENT", socialPlan = "SOCIAL_PLAN", custom = "CUSTOM" }

/// Imported (.ics) or synced event, persisted locally.
struct StoredCalendarEvent: Codable, Identifiable, Hashable {
    var id: String
    var title: String
    var description: String?
    var kind: CalendarItemKind
    var startsAt: Date
    var endsAt: Date
    var location: String?
    var emoji: String
    var courseCode: String?
    var isImported: Bool = true
}

/// What the calendar shows for a day: courses, campus events, plans, imported events.
struct CalendarItem: Identifiable, Hashable {
    let id: String
    let title: String
    var subtitle: String?
    let kind: CalendarItemKind
    let startsAt: Date
    let endsAt: Date
    var location: String?
    let emoji: String
    var courseCode: String?
    var attendees: [Profile] = []
    var campusEventId: String?
    var isImported: Bool = false

    var durationMinutes: Int { max(15, Int(endsAt.timeIntervalSince(startsAt) / 60)) }
    var isSocial: Bool { kind == .socialEvent || kind == .socialPlan }
}

enum CalendarFilter: Int, CaseIterable { case all, courses, social }

struct CalendarDaySummary: Hashable { let hasCourses: Bool; let hasSocial: Bool; let totalCount: Int }
