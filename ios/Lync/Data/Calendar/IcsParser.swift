import Foundation

/// Minimal iCalendar (.ics) parser — same behaviour as Android `IcsParser.kt`:
/// unfolds lines, reads VEVENTs, expands weekly RRULEs over a 15-week semester,
/// extracts course codes (e.g. "COMP 248") and guesses the kind + emoji.
enum IcsParser {
    static func parse(_ text: String, calendar: Calendar = .current) -> [StoredCalendarEvent] {
        var events: [StoredCalendarEvent] = []
        var inEvent = false
        var props: [String: String] = [:]
        for line in unfold(text) {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.caseInsensitiveCompare("BEGIN:VEVENT") == .orderedSame { inEvent = true; props = [:] }
            else if t.caseInsensitiveCompare("END:VEVENT") == .orderedSame { if inEvent { events += build(props, calendar) }; inEvent = false }
            else if inEvent, let colon = t.firstIndex(of: ":"), colon > t.startIndex {
                let rawKey = String(t[..<colon]).uppercased()
                let value = String(t[t.index(after: colon)...])
                let key = String(rawKey.split(separator: ";").first ?? Substring(rawKey))
                props[rawKey] = value
                if props[key] == nil { props[key] = value }
            }
        }
        return events
    }

    private static func unfold(_ text: String) -> [String] {
        var result: [String] = []
        var current = ""
        for line in text.components(separatedBy: .newlines) {
            if line.hasPrefix(" ") || line.hasPrefix("\t") { current += String(line.dropFirst()) }
            else { if !current.isEmpty { result.append(current) }; current = line }
        }
        if !current.isEmpty { result.append(current) }
        return result
    }

    private static func build(_ props: [String: String], _ cal: Calendar) -> [StoredCalendarEvent] {
        let summary = unescape(props["SUMMARY"] ?? "Événement sans titre")
        let description = props["DESCRIPTION"].map(unescape)
        let location = props["LOCATION"].map(unescape)
        let startStr = props.first { $0.key.hasPrefix("DTSTART") }?.value
        let endStr = props.first { $0.key.hasPrefix("DTEND") }?.value
        let start = parseDate(startStr) ?? Date()
        let end = parseDate(endStr) ?? start.addingTimeInterval(3600)
        let duration = max(1800, end.timeIntervalSince(start))
        let code = courseCode(summary, description)
        let (kind, emoji) = categorize(summary, description, code)

        if let rrule = props["RRULE"], rrule.uppercased().contains("FREQ=WEEKLY") {
            let byDay = rrule.split(separator: ";").first { $0.uppercased().hasPrefix("BYDAY=") }
            var weekdays: [Int] = byDay.map { $0.split(separator: "=").last!.split(separator: ",").compactMap { weekday(String($0)) } } ?? []
            if weekdays.isEmpty { weekdays = [cal.component(.weekday, from: start)] }
            var out: [StoredCalendarEvent] = []
            let startWeekday = cal.component(.weekday, from: start)
            for w in 0..<15 {
                guard let weekStart = cal.date(byAdding: .day, value: 7 * w, to: start) else { continue }
                for wd in weekdays {
                    // ISO-like offset: Monday-based difference
                    let offset = isoIndex(wd) - isoIndex(startWeekday)
                    guard let occ = cal.date(byAdding: .day, value: offset, to: weekStart) else { continue }
                    out.append(StoredCalendarEvent(id: UUID().uuidString, title: summary, description: description, kind: kind, startsAt: occ,
                                                   endsAt: occ.addingTimeInterval(duration), location: location, emoji: emoji, courseCode: code))
                }
            }
            return out
        }
        return [StoredCalendarEvent(id: UUID().uuidString, title: summary, description: description, kind: kind, startsAt: start, endsAt: end,
                                    location: location, emoji: emoji, courseCode: code)]
    }

    /// Calendar weekday (Sun=1…Sat=7) -> Monday-based index (Mon=1…Sun=7)
    private static func isoIndex(_ wd: Int) -> Int { wd == 1 ? 7 : wd - 1 }

    private static func weekday(_ code: String) -> Int? {
        switch code.uppercased().trimmingCharacters(in: .whitespaces) {
        case "SU": 1; case "MO": 2; case "TU": 3; case "WE": 4; case "TH": 5; case "FR": 6; case "SA": 7
        default: nil
        }
    }

    static func courseCode(_ summary: String, _ description: String?) -> String? {
        let pattern = #"\b([A-Za-z]{3,4}\s*\d{3})\b"#
        for text in [summary, description ?? ""] {
            if let r = text.range(of: pattern, options: .regularExpression) {
                return text[r].uppercased().replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            }
        }
        return nil
    }

    private static func categorize(_ summary: String, _ description: String?, _ code: String?) -> (CalendarItemKind, String) {
        let c = "\(summary) \(description ?? "")".lowercased()
        func has(_ words: String...) -> Bool { words.contains { c.contains($0) } }
        if code != nil || has("lecture", "cours", "tutorial", "lab", "midterm", "final exam", "td", "tp", "amphi", "séance") {
            return (.course, has("lab") ? "🔬" : has("exam", "midterm") ? "📝" : "📚")
        }
        if has("gym", "workout", "sport", "soccer", "bouldering", "climb") { return (.socialPlan, "🏋️") }
        if has("coffee", "café", "matcha", "boba") { return (.socialPlan, "☕") }
        if has("lunch", "dîner", "souper", "pizza", "food") { return (.socialPlan, "🍜") }
        if has("party", "soirée", "gala", "apéro", "5@7", "bar", "fest") { return (.socialEvent, "🎉") }
        if has("robotics", "hackathon", "tech", "ai") { return (.socialEvent, "💻") }
        if has("movie", "film", "cinema", "cinéma") { return (.socialEvent, "🎬") }
        return (.custom, "📅")
    }

    static func parseDate(_ s: String?) -> Date? {
        guard let s = s?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        if s.hasSuffix("Z") { f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyyMMdd'T'HHmmss'Z'"; return f.date(from: s) }
        if s.contains("T") && s.count == 15 { f.timeZone = .current; f.dateFormat = "yyyyMMdd'T'HHmmss"; return f.date(from: s) }
        if s.count == 8 { f.timeZone = .current; f.dateFormat = "yyyyMMdd"; return f.date(from: s) }
        return ISO8601DateFormatter().date(from: s)
    }

    private static func unescape(_ t: String) -> String {
        t.replacingOccurrences(of: "\\n", with: "\n").replacingOccurrences(of: "\\N", with: "\n")
            .replacingOccurrences(of: "\\,", with: ",").replacingOccurrences(of: "\\;", with: ";").replacingOccurrences(of: "\\\\", with: "\\")
    }
}
