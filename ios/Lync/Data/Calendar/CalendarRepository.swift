import Foundation

/// Student calendar: Concordia course schedule + campus events + plans + imported .ics events.
/// Same logic as Android `CalendarRepository.kt`. Imported events are stored in UserDefaults.
final class CalendarRepository {
    private let campus: CampusRepository
    private let key = "lync_calendar_events"
    private(set) var imported: [StoredCalendarEvent]

    init(campus: CampusRepository) {
        self.campus = campus
        if let data = UserDefaults.standard.data(forKey: key), let list = try? JSONDecoder().decode([StoredCalendarEvent].self, from: data) { imported = list } else { imported = [] }
    }

    private func persist(_ list: [StoredCalendarEvent]) {
        imported = list
        UserDefaults.standard.set(try? JSONEncoder().encode(list), forKey: key)
    }

    private var isFr: Bool { Lang.current == "fr" }

    func clearImported() { persist([]) }

    func importIcs(_ text: String) throws -> Int {
        let parsed = IcsParser.parse(text)
        if parsed.isEmpty { throw LyncError.message(L("calendar_msg_error_import")) }
        persist(imported + parsed)
        return parsed.count
    }

    func importFromUrl(_ urlString: String) async throws -> Int {
        var s = urlString.trimmingCharacters(in: .whitespaces)
        if s.lowercased().hasPrefix("webcal://") { s = "https://" + s.dropFirst(9) }
        guard let url = URL(string: s) else { throw LyncError.message(L("calendar_msg_error_url")) }
        let (data, _) = try await URLSession.shared.data(from: url)
        return try importIcs(String(decoding: data, as: UTF8.self))
    }

    /// Pre-loads a realistic Concordia Fall 2026 schedule.
    func syncDefaultSchedule() -> Int {
        let parsed = IcsParser.parse(concordiaIcs())
        persist(parsed)
        return parsed.count
    }

    func items(for date: Date) async -> [CalendarItem] {
        let cal = Calendar.current
        let dayStart = cal.startOfDay(for: date)
        let dayEnd = cal.date(byAdding: .day, value: 1, to: dayStart)!
        var results = await courseItems(for: date)

        if let events = try? await campus.events() {
            for e in events where (e.startsAt >= dayStart && e.startsAt < dayEnd) || (e.startsAt <= dayStart && e.endsAt >= dayStart) {
                results.append(CalendarItem(id: "campus-ev-" + e.id, title: e.title, subtitle: e.place, kind: .socialEvent, startsAt: e.startsAt, endsAt: e.endsAt,
                                            location: e.place, emoji: e.category.emoji, attendees: e.friendsGoing, campusEventId: e.id))
            }
        }
        if let feed = try? await campus.feed() {
            for post in feed { if let plan = post.plan, plan.startsAt >= dayStart, plan.startsAt < dayEnd {
                let sub = isFr ? "\(plan.going.count) participant·e·s" : "\(plan.going.count) going"
                results.append(CalendarItem(id: "plan-" + post.id, title: "\(plan.activity.emoji) \(plan.place)", subtitle: sub, kind: .socialPlan,
                                            startsAt: plan.startsAt, endsAt: plan.startsAt.addingTimeInterval(3600), location: plan.place, emoji: plan.activity.emoji, attendees: plan.going))
            } }
        }
        for s in imported where s.startsAt >= dayStart && s.startsAt < dayEnd {
            let attendees = s.kind == .course ? await classmates(for: s.courseCode) : []
            results.append(CalendarItem(id: s.id, title: s.title, subtitle: s.description ?? s.location, kind: s.kind, startsAt: s.startsAt, endsAt: s.endsAt,
                                        location: s.location, emoji: s.emoji, courseCode: s.courseCode, attendees: attendees, isImported: true))
        }
        var seen = Set<String>()
        return results.filter { seen.insert($0.id).inserted }.sorted { $0.startsAt < $1.startsAt }
    }

    func summaries(for dates: [Date]) async -> [Date: CalendarDaySummary] {
        var out: [Date: CalendarDaySummary] = [:]
        for d in dates {
            let items = await items(for: d)
            out[Calendar.current.startOfDay(for: d)] = CalendarDaySummary(hasCourses: items.contains { $0.kind == .course }, hasSocial: items.contains { $0.isSocial }, totalCount: items.count)
        }
        return out
    }

    private func courseItems(for date: Date) async -> [CalendarItem] {
        let cal = Calendar.current
        let fr = isFr
        func at(_ h: Int, _ m: Int) -> Date { cal.date(bySettingHour: h, minute: m, second: 0, of: date)! }
        let day = ISO8601DateFormatter.string(from: date, timeZone: .current, formatOptions: [.withFullDate])
        func item(_ id: String, _ en: String, _ frT: String, _ subEn: String, _ subFr: String, _ s: (Int, Int), _ e: (Int, Int), _ loc: String, _ emoji: String, _ code: String) async -> CalendarItem {
            CalendarItem(id: "course-\(id)-\(day)", title: fr ? frT : en, subtitle: fr ? subFr : subEn, kind: .course, startsAt: at(s.0, s.1), endsAt: at(e.0, e.1),
                         location: loc, emoji: emoji, courseCode: code, attendees: await classmates(for: code))
        }
        switch cal.component(.weekday, from: date) {
        case 2: // Monday
            return [await item("comp248-mon", "COMP 248 — Object-Oriented Programming I", "COMP 248 — Programmation orientée objet I", "Lecture · Section U", "Cours magistral · Section U", (10, 15), (11, 30), "Hall Building, H-920", "📚", "COMP 248"),
                    await item("comp228-mon", "COMP 228 — System Hardware", "COMP 228 — Matériel informatique", "Lecture · Section H", "Cours magistral · Section H", (13, 15), (14, 30), "Hall Building, H-820", "📚", "COMP 228")]
        case 3: // Tuesday
            return [await item("math203-tue", "MATH 203 — Differential & Integral Calculus I", "MATH 203 — Calcul différentiel et intégral I", "Lecture · Section B", "Cours magistral · Section B", (13, 15), (14, 30), "JMSB, MB-2.210", "📚", "MATH 203")]
        case 4: // Wednesday
            return [await item("comp248-wed", "COMP 248 — Object-Oriented Programming I", "COMP 248 — Programmation orientée objet I", "Lecture · Section U", "Cours magistral · Section U", (10, 15), (11, 30), "Hall Building, H-920", "📚", "COMP 248"),
                    await item("engr201-wed", "ENGR 201 — Professional Practice", "ENGR 201 — Pratique professionnelle", "Lecture & Case Studies", "Cours magistral & études de cas", (14, 45), (16, 0), "Hall Building, H-531", "📚", "ENGR 201")]
        case 5: // Thursday
            return [await item("math203-thu", "MATH 203 — Calculus Tutorial", "MATH 203 — Tutoriel de calcul", "Problem Solving · Room MB-1.437", "Résolution d'exercices · Salle MB-1.437", (13, 15), (14, 30), "JMSB, MB-1.437", "📚", "MATH 203")]
        case 6: // Friday
            return [await item("comp228-lab-fri", "COMP 228 — Hardware Lab", "COMP 228 — Labo de matériel informatique", "FPGA Boards & Assembly · EV-3.102", "Circuits FPGA et assembleur · EV-3.102", (11, 45), (13, 30), "EV Building, EV-3.102", "🔬", "COMP 228")]
        default: return []
        }
    }

    private func classmates(for code: String?) async -> [Profile] {
        let all = ((try? await campus.classmates()) ?? []).map(\.profile)
        if all.isEmpty { return [] }
        func named(_ a: String, _ b: String) -> [Profile] { all.filter { $0.fullName.contains(a) || $0.fullName.contains(b) } }
        switch code?.uppercased().trimmingCharacters(in: .whitespaces) {
        case "COMP 248": return named("Maya", "Adam")
        case "MATH 203": return named("Maya", "Inès")
        case "ENGR 201": return named("Karim", "Maya")
        case "COMP 228": return named("Inès", "Adam")
        default: return Array(all.prefix(2))
        }
    }

    private func concordiaIcs() -> String {
        let fr = isFr
        func ev(_ uid: String, _ summary: String, _ desc: String, _ loc: String, _ start: String, _ end: String, _ rrule: String? = nil) -> String {
            "BEGIN:VEVENT\nUID:\(uid)\nSUMMARY:\(summary)\nDESCRIPTION:\(desc)\nLOCATION:\(loc)\nDTSTART:\(start)\nDTEND:\(end)\n" + (rrule.map { "RRULE:\($0)\n" } ?? "") + "END:VEVENT\n"
        }
        return "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Concordia University//Lync Schedule//EN\n"
            + ev("comp248-lec@concordia.ca", fr ? "COMP 248 - Programmation orientée objet I" : "COMP 248 - Object-Oriented Programming I", fr ? "Prof. T. Adams. Salle H-920." : "Prof. T. Adams. Room H-920.", "Hall Building, H-920", "20261102T101500", "20261102T113000", "FREQ=WEEKLY;BYDAY=MO,WE")
            + ev("math203-lec@concordia.ca", fr ? "MATH 203 - Calcul I" : "MATH 203 - Calculus I", "Prof. D. Sen. JMSB.", "JMSB, MB-2.210", "20261103T131500", "20261103T143000", "FREQ=WEEKLY;BYDAY=TU,TH")
            + ev("engr201-lec@concordia.ca", fr ? "ENGR 201 - Pratique professionnelle" : "ENGR 201 - Professional Practice", fr ? "Pratique professionnelle et éthique de l'ingénieur." : "Professional engineering practice and case studies.", "Hall Building, H-531", "20261104T144500", "20261104T160000", "FREQ=WEEKLY;BYDAY=WE")
            + ev("comp228-lab@concordia.ca", fr ? "COMP 228 - Matériel informatique (Labo)" : "COMP 228 - System Hardware (Lab)", fr ? "Laboratoire matériel informatique et assembleur." : "Computer hardware assembly and FPGA board lab.", "EV Building, EV-3.102", "20261106T114500", "20261106T133000", "FREQ=WEEKLY;BYDAY=FR")
            + ev("study-group-webster@lync.app", fr ? "📚 Session d'étude révision mi-session" : "📚 Midterm review study session", fr ? "Préparation de l'examen de mi-session avec Maya et Karim." : "Midterm exam preparation with Maya and Karim.", "Webster Library, LB-420", "20261117T160000", "20261117T183000")
            + ev("climbing-thurs@lync.app", fr ? "🧗 Séance d'escalade Allez Up" : "🧗 Bouldering session at Allez Up", fr ? "Session escalade de bloc du jeudi soir avec le club." : "Thursday night climbing session with the club.", "Allez Up, Saint-Henri", "20261119T180000", "20261119T203000")
            + ev("conu-robotics-social@lync.app", fr ? "🎉 5@7 des clubs d'ingénierie" : "🎉 Engineering clubs 5@7 & pizza", fr ? "Rencontre conviviale pizza et rafraîchissements au lounge EV." : "Social pizza gathering and refreshments at the EV lounge.", "EV Atrium, EV Building", "20261120T170000", "20261120T210000")
            + "END:VCALENDAR\n"
    }
}
