import SwiftUI
import EventKit
import UniformTypeIdentifiers
import Observation

// MARK: - State (Android CalendarViewModel)

enum CalendarStatus: Equatable {
    case imported(Int), synced(Int), cleared, error(String), errorImport, errorUrl

    var text: String {
        switch self {
        case .imported(let n): L("calendar_msg_imported", n)
        case .synced(let n): L("calendar_msg_synced", n)
        case .cleared: L("calendar_msg_cleared")
        case .error(let t): t
        case .errorImport: L("calendar_msg_error_import")
        case .errorUrl: L("calendar_msg_error_url")
        }
    }
}

@MainActor
@Observable
final class CalendarModel {
    private let repo: CalendarRepository
    var selectedDate = Calendar.current.startOfDay(for: Date())
    var weekDays: [Date] = []
    var summaries: [Date: CalendarDaySummary] = [:]
    var items: [CalendarItem] = []
    var filter: CalendarFilter = .all
    var loading = false
    var showImport = false
    var detail: CalendarItem?
    var status: CalendarStatus?

    init(repo: CalendarRepository) {
        self.repo = repo
        weekDays = Self.week(of: selectedDate)
    }

    var filtered: [CalendarItem] {
        switch filter {
        case .all: items
        case .courses: items.filter { $0.kind == .course }
        case .social: items.filter(\.isSocial)
        }
    }
    var coursesCount: Int { items.filter { $0.kind == .course }.count }
    var socialCount: Int { items.filter(\.isSocial).count }
    var isToday: Bool { Calendar.current.isDateInToday(selectedDate) }

    /// Monday → Sunday of the week containing `date`.
    static func week(of date: Date) -> [Date] {
        let cal = Calendar.current
        let day = cal.startOfDay(for: date)
        let weekday = cal.component(.weekday, from: day) // Sun=1 … Sat=7
        let monday = cal.date(byAdding: .day, value: -((weekday + 5) % 7), to: day)!
        return (0..<7).map { cal.date(byAdding: .day, value: $0, to: monday)! }
    }

    func select(_ date: Date) {
        let d = Calendar.current.startOfDay(for: date)
        if !weekDays.contains(d) { weekDays = Self.week(of: d) }
        selectedDate = d
        Task { await load() }
    }

    func shiftWeek(_ by: Int) {
        let monday = weekDays.first ?? selectedDate
        let next = Calendar.current.date(byAdding: .day, value: 7 * by, to: monday)!
        weekDays = Self.week(of: next)
        selectedDate = next
        Task { await load() }
    }

    func load() async {
        let date = selectedDate, week = weekDays
        let newItems = await repo.items(for: date)
        let newSummaries = await repo.summaries(for: week)
        guard date == selectedDate else { return } // a newer selection won
        withAnimation(Motion.standard) { items = newItems; summaries = newSummaries }
    }

    func importFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url)
            let n = try repo.importIcs(String(decoding: data, as: UTF8.self))
            finish(.imported(n))
        } catch {
            finish((error as? LyncError).map { .error($0.localizedDescription) } ?? .errorImport)
        }
    }

    func importUrl(_ url: String) {
        guard !url.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        loading = true
        Task {
            do { finish(.imported(try await repo.importFromUrl(url))) } catch { finish(.errorUrl) }
        }
    }

    func syncConcordia() { finish(.synced(repo.syncDefaultSchedule())) }

    func clearImported() { repo.clearImported(); finish(.cleared, closeSheet: false) }

    private func finish(_ s: CalendarStatus, closeSheet: Bool = true) {
        loading = false
        if closeSheet { showImport = false }
        withAnimation(Motion.standard) { status = s }
        Task { await load() }
    }
}

// MARK: - Screen

struct CalendarScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var model: CalendarModel?

    var body: some View {
        Group {
            if let model { CalendarContent(model: model) } else { c.bg }
        }
        .task {
            if model == nil { model = CalendarModel(repo: env.calendar) }
            await model?.load()
        }
    }
}

private struct CalendarContent: View {
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @Bindable var model: CalendarModel
    @State private var calendarEvent: EKEvent?
    private let store = EKEventStore()

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: L("calendar_title")) {
                HStack(spacing: 8) {
                    if !model.isToday {
                        Pill(text: L("calendar_today"), style: .tonal, vertical: 6) { Haptics.tick(); model.select(Date()) }
                            .transition(.opacity.combined(with: .scale))
                    }
                    CircleIconButton(icon: "icloud.and.arrow.down") { Haptics.tick(); model.status = nil; model.showImport = true }
                }
                .animation(Motion.standard, value: model.isToday)
            }

            if let status = model.status {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark").font(.system(size: 15, weight: .bold)).foregroundStyle(c.accent)
                    Text(status.text).lyncText(.bodySmall).frame(maxWidth: .infinity, alignment: .leading)
                    Button { withAnimation(Motion.standard) { model.status = nil } } label: {
                        Image(systemName: "xmark").font(.system(size: 11, weight: .bold)).foregroundStyle(c.text).frame(width: 24, height: 24).background(Circle().fill(c.surface))
                    }
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(c.accentSoft))
                .padding(.horizontal, Tokens.gutter).padding(.vertical, 6)
                .transition(.move(edge: .top).combined(with: .opacity))
            }

            monthNavigator
            weekStrip
            Segmented(options: [L("calendar_filter_all"), L("calendar_filter_courses"), L("calendar_filter_social")],
                      selected: Binding(get: { model.filter.rawValue }, set: { model.filter = CalendarFilter(rawValue: $0) ?? .all }))
                .padding(.horizontal, Tokens.gutter).padding(.top, 14).padding(.bottom, 4)
            dayHeader

            if model.filtered.isEmpty {
                ScrollView { emptyState }
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(model.filtered) { item in
                            CalendarCard(item: item).pressable(scale: 0.98) { Haptics.tick(); model.detail = item }
                                .transition(.opacity)
                        }
                    }
                    .padding(.horizontal, Tokens.gutter).padding(.top, 10).padding(.bottom, 24)
                }
            }
        }
        .background(c.bg)
        // swipe left / right on the screen to change week
        .gesture(DragGesture(minimumDistance: 40).onEnded { v in
            guard abs(v.translation.width) > abs(v.translation.height) * 1.5 else { return }
            Haptics.tick(); model.shiftWeek(v.translation.width < 0 ? 1 : -1)
        })
        .sheet(isPresented: $model.showImport) {
            ImportCalendarSheet(model: model).environment(\.palette, c)
        }
        .sheet(item: $model.detail) { item in
            CalendarDetailSheet(item: item,
                                onMap: { model.detail = nil; router.map() },
                                onEvent: { id in model.detail = nil; router.event(id) },
                                onPerson: { id in model.detail = nil; router.user(id) },
                                onAddToCalendar: {
                                    model.detail = nil
                                    calendarEvent = EventEditView.makeEvent(store: store, title: item.title, location: item.location, notes: item.subtitle, start: item.startsAt, end: item.endsAt)
                                })
                .environment(\.palette, c)
        }
        .sheet(item: Binding(get: { calendarEvent.map(IdentifiedEvent.init) }, set: { if $0 == nil { calendarEvent = nil } })) { wrapped in
            EventEditView(event: wrapped.event, store: store)
        }
    }

    private var monthNavigator: some View {
        let title = model.selectedDate.formatted(Date.FormatStyle().month(.wide).year().locale(Lang.locale))
        return HStack {
            Text(title.prefix(1).uppercased() + title.dropFirst()).lyncText(.titleLarge)
                .contentTransition(.opacity)
            Spacer()
            HStack(spacing: 4) {
                CircleIconButton(icon: "chevron.left", size: 36) { Haptics.tick(); model.shiftWeek(-1) }
                CircleIconButton(icon: "chevron.right", size: 36) { Haptics.tick(); model.shiftWeek(1) }
            }
        }
        .padding(.horizontal, Tokens.gutter).padding(.vertical, 6)
    }

    private var weekStrip: some View {
        HStack(spacing: 4) {
            ForEach(model.weekDays, id: \.self) { date in
                let selected = date == model.selectedDate
                let today = Calendar.current.isDateInToday(date)
                let summary = model.summaries[date]
                let letters = date.formatted(Date.FormatStyle().weekday(.abbreviated).locale(Lang.locale)).replacingOccurrences(of: ".", with: "")
                VStack(spacing: 0) {
                    Text(String(letters.prefix(3)).capitalized(with: Lang.locale)).lyncText(.labelSmall, selected ? c.bg.opacity(0.7) : c.textMuted)
                    Text("\(Calendar.current.component(.day, from: date))").font(.display(21, "Bold")).foregroundStyle(selected ? c.bg : c.text).padding(.top, 4)
                    HStack(spacing: 3) {
                        if summary?.hasCourses == true { Circle().fill(selected ? c.bg : c.accent2).frame(width: 5, height: 5) }
                        if summary?.hasSocial == true { Circle().fill(selected ? c.bg : c.accent).frame(width: 5, height: 5) }
                    }
                    .frame(height: 6).padding(.top, 6)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(selected ? c.text : c.surface))
                .overlay { if today && !selected { RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).stroke(c.accent, lineWidth: 1.5) } }
                .animation(Motion.standard, value: selected)
                .pressable(scale: 0.94) { Haptics.tick(); model.select(date) }
            }
        }
        .padding(.horizontal, Tokens.gutter)
    }

    private var dayHeader: some View {
        let full = model.selectedDate.formatted(Date.FormatStyle().weekday(.wide).day().month(.wide).locale(Lang.locale))
        return HStack {
            Text(full.prefix(1).uppercased() + full.dropFirst()).lyncText(.titleMedium)
            Spacer()
            HStack(spacing: 8) {
                if model.coursesCount > 0 {
                    Text(L(model.coursesCount == 1 ? "calendar_tag_course_one" : "calendar_tag_courses", model.coursesCount))
                        .lyncText(.labelSmall, c.accent).padding(.horizontal, 8).padding(.vertical, 3).background(Capsule().fill(c.accentSoft))
                }
                if model.socialCount > 0 {
                    Text(L("calendar_tag_social", model.socialCount))
                        .lyncText(.labelSmall).padding(.horizontal, 8).padding(.vertical, 3).background(Capsule().fill(c.surfaceAlt))
                }
            }
        }
        .padding(.horizontal, Tokens.gutter).padding(.vertical, 12)
    }

    private var emptyState: some View {
        VStack(spacing: 0) {
            Image(systemName: "calendar").font(.system(size: 30)).foregroundStyle(c.textMuted).frame(width: 68, height: 68).background(Circle().fill(c.surface))
            Text(L("calendar_empty_title")).lyncText(.titleLarge).padding(.top, 16)
            Text(L("calendar_empty_desc")).lyncText(.bodyMedium, c.textMuted).multilineTextAlignment(.center).padding(.horizontal, 24).padding(.top, 6)
            Pill(text: L("calendar_import"), icon: "icloud.and.arrow.down") { Haptics.tick(); model.status = nil; model.showImport = true }.padding(.top, 20)
        }
        .frame(maxWidth: .infinity).padding(.horizontal, Tokens.gutter).padding(.vertical, 48)
    }
}

// MARK: - Pieces

private func badgeText(_ item: CalendarItem) -> String {
    switch item.kind {
    case .course: item.courseCode ?? L("calendar_badge_course")
    case .socialEvent: L("calendar_badge_event")
    case .socialPlan: L("calendar_badge_plan")
    case .custom: L("calendar_badge_imported")
    }
}

private func hhmm(_ d: Date) -> String { d.formatted(Date.FormatStyle().hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).locale(Locale(identifier: "fr_CA"))) }

private struct CalendarCard: View {
    @Environment(\.palette) private var c
    let item: CalendarItem

    var body: some View {
        let isCourse = item.kind == .course
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 0) {
                Text(hhmm(item.startsAt)).font(.text(16, "Bold")).foregroundStyle(c.text)
                Text(hhmm(item.endsAt)).lyncText(.bodySmall, c.textMuted)
                Capsule().fill(isCourse ? c.accent2 : c.accent).frame(width: 4, height: 26).padding(.top, 6)
            }
            .frame(width: 52)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    Text("\(item.emoji)  \(badgeText(item))").lyncText(.labelSmall, isCourse ? c.accent : c.text)
                        .padding(.horizontal, 8).padding(.vertical, 3).background(Capsule().fill(isCourse ? c.accentSoft : c.surfaceAlt))
                    if item.isImported && item.kind != .custom {
                        Text(L("calendar_badge_imported")).lyncText(.labelSmall, c.textMuted)
                            .padding(.horizontal, 6).padding(.vertical, 2).background(Capsule().fill(c.surfaceAlt))
                    }
                }
                Text(item.title).lyncText(.titleSmall).lineLimit(2).multilineTextAlignment(.leading).padding(.top, 6)
                if let loc = item.location {
                    HStack(spacing: 4) {
                        Image(systemName: "mappin").font(.system(size: 11)).foregroundStyle(c.textMuted)
                        Text(loc).lyncText(.bodySmall, c.textMuted).lineLimit(1)
                    }
                    .padding(.top, 4)
                }
                if let first = item.attendees.first {
                    HStack(spacing: 8) {
                        AvatarStack(people: item.attendees, size: 22)
                        Text(attendeesText(item, first)).lyncText(.labelSmall, c.textMuted).lineLimit(1)
                    }
                    .padding(.top, 8)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous).fill(c.surface))
    }

    private func attendeesText(_ item: CalendarItem, _ first: Profile) -> String {
        let n = item.attendees.count
        if item.kind == .course {
            return n == 1 ? L("calendar_attendee_classmate_one", first.firstName) : L("calendar_attendee_classmate_many", first.firstName, n - 1)
        }
        return n == 1 ? L("calendar_attendee_friend_one", first.firstName) : L("calendar_attendee_friend_many", first.firstName, n - 1)
    }
}

private struct ImportCalendarSheet: View {
    @Environment(\.palette) private var c
    @Bindable var model: CalendarModel
    @State private var url = ""
    @State private var showUrl = false
    @State private var picking = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("calendar_import")).lyncText(.displaySmall).padding(.top, 28)
                Text(L("calendar_import_desc")).lyncText(.bodyMedium, c.textMuted).padding(.bottom, 10)
                option("doc.badge.arrow.up", L("calendar_import_file"), L("calendar_import_file_desc")) { picking = true }
                option("graduationcap", L("calendar_import_uni"), L("calendar_import_uni_desc"), highlight: true) { Haptics.confirm(); model.syncConcordia() }
                option("link", L("calendar_import_url"), L("calendar_import_url_hint")) { withAnimation(Motion.standard) { showUrl.toggle() } }
                if showUrl {
                    VStack(spacing: 8) {
                        Field(hint: L("calendar_import_url_hint"), text: $url, icon: "link", keyboard: .URL)
                        PrimaryButton(text: L("calendar_import_action"), enabled: !url.trimmingCharacters(in: .whitespaces).isEmpty, loading: model.loading) {
                            Haptics.confirm(); model.importUrl(url)
                        }
                    }
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
                Rectangle().fill(c.border).frame(height: 0.5).padding(.vertical, 8)
                HStack(spacing: 12) {
                    Image(systemName: "trash").font(.system(size: 17)).foregroundStyle(c.textMuted)
                    Text(L("calendar_clear_imported")).lyncText(.bodyMedium, c.textMuted)
                    Spacer()
                }
                .padding(12)
                .pressable { Haptics.confirm(); model.clearImported() }
            }
            .padding(.horizontal, Tokens.gutter).padding(.bottom, 36)
        }
        .background(c.bg)
        .presentationDetents([.medium, .large])
        .presentationCornerRadius(Tokens.radiusCard)
        .fileImporter(isPresented: $picking, allowedContentTypes: [UTType(filenameExtension: "ics") ?? .data, .calendarEvent, .plainText, .data]) { result in
            if case .success(let file) = result { Haptics.confirm(); model.importFile(file) }
        }
    }

    private func option(_ icon: String, _ title: String, _ subtitle: String, highlight: Bool = false, _ action: @escaping () -> Void) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 17, weight: .semibold)).foregroundStyle(highlight ? c.onAccent : c.text)
                .frame(width: 40, height: 40).background(Circle().fill(highlight ? c.accent : c.surfaceAlt))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lyncText(.titleSmall)
                Text(subtitle).lyncText(.bodySmall, c.textMuted).multilineTextAlignment(.leading)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(c.textMuted)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(highlight ? c.accentSoft : c.surface))
        .pressable(scale: 0.98, action)
    }
}

private struct CalendarDetailSheet: View {
    @Environment(\.palette) private var c
    let item: CalendarItem
    var onMap: () -> Void
    var onEvent: (String) -> Void
    var onPerson: (String) -> Void
    var onAddToCalendar: () -> Void

    var body: some View {
        let isCourse = item.kind == .course
        let date = item.startsAt.formatted(Date.FormatStyle().weekday(.wide).day().month(.wide).year().locale(Lang.locale))
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("\(item.emoji)  \(badgeText(item))").lyncText(.labelLarge, isCourse ? c.accent : c.text)
                    .padding(.horizontal, 12).padding(.vertical, 6).background(Capsule().fill(isCourse ? c.accentSoft : c.surfaceAlt))
                    .padding(.top, 28)
                Text(item.title).lyncText(.displaySmall).padding(.top, 12)
                if let sub = item.subtitle { Text(sub).lyncText(.bodyLarge, c.textMuted).padding(.top, 4) }
                Rectangle().fill(c.border).frame(height: 0.5).padding(.vertical, 16)

                row(icon: "clock", tint: c.text, title: date.prefix(1).uppercased() + date.dropFirst(),
                    subtitle: "\(hhmm(item.startsAt)) – \(hhmm(item.endsAt)) (\(L("calendar_duration_minutes", item.durationMinutes)))", subtitleColor: c.textMuted)
                if let loc = item.location {
                    row(icon: "mappin", tint: c.accent, title: loc, subtitle: L("calendar_open_map"), subtitleColor: c.accent, chevron: true)
                        .padding(.top, 14)
                        .pressable(onMap)
                }

                if !item.attendees.isEmpty {
                    Text(L(isCourse ? "calendar_classmates_section" : "calendar_friends_section")).lyncText(.titleSmall, c.textMuted).padding(.top, 18).padding(.bottom, 8)
                    VStack(spacing: 8) {
                        ForEach(item.attendees) { p in
                            HStack(spacing: 10) {
                                Avatar(url: p.avatarUrl, initials: p.initials, size: 34)
                                VStack(alignment: .leading, spacing: 0) {
                                    Text(p.fullName).lyncText(.titleSmall)
                                    Text("@\(p.username)").lyncText(.bodySmall, c.textMuted)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(c.textMuted)
                            }
                            .padding(10)
                            .background(RoundedRectangle(cornerRadius: Tokens.radiusControl, style: .continuous).fill(c.surface))
                            .pressable { onPerson(p.id) }
                        }
                    }
                }

                VStack(spacing: 10) {
                    if let id = item.campusEventId { PrimaryButton(text: L("calendar_view_event")) { onEvent(id) } }
                    Pill(text: L("events_add_calendar"), style: .outline, icon: "calendar.badge.plus", fullWidth: true, vertical: 12, action: onAddToCalendar)
                }
                .padding(.top, 24)
            }
            .padding(.horizontal, Tokens.gutter).padding(.bottom, 36)
        }
        .background(c.bg)
        .presentationDetents([.medium, .large])
        .presentationCornerRadius(Tokens.radiusCard)
    }

    private func row(icon: String, tint: Color, title: String, subtitle: String, subtitleColor: Color, chevron: Bool = false) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 16)).foregroundStyle(tint).frame(width: 38, height: 38).background(Circle().fill(c.surface))
            VStack(alignment: .leading, spacing: 1) {
                Text(title).lyncText(.titleSmall)
                Text(subtitle).lyncText(.bodySmall, subtitleColor)
            }
            Spacer(minLength: 0)
            if chevron { Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(c.textMuted) }
        }
    }
}
