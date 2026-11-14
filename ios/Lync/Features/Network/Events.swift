import SwiftUI
import EventKit
import EventKitUI

/// "Today, 5:00 PM" / "Tomorrow, 6:00 PM" / "Fri 20 Nov, 10:00 AM"
func eventWhen(_ date: Date) -> String {
    let cal = Calendar.current
    let time = date.formatted(Date.FormatStyle(date: .omitted, time: .shortened).locale(Lang.locale))
    if cal.isDateInToday(date) { return L("plan_today", time) }
    if cal.isDateInTomorrow(date) { return L("plan_tomorrow", time) }
    return date.formatted(Date.FormatStyle().weekday(.abbreviated).day().month(.abbreviated).locale(Lang.locale)) + ", " + time
}

struct EventsTab: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var events: [CampusEvent] = []
    @State private var filter: EventCategory?

    var body: some View {
        let shown = events.filter { filter == nil || $0.category == filter }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        Pill(text: L("events_all"), style: filter == nil ? .filled : .tonal) { filter = nil }
                        ForEach(EventCategory.allCases, id: \.self) { cat in
                            Pill(text: "\(cat.emoji)  \(L(cat.labelKey))", style: filter == cat ? .filled : .tonal) { filter = filter == cat ? nil : cat }
                        }
                    }
                    .padding(.horizontal, Tokens.gutter).padding(.vertical, 8)
                }
                let featured = shown.filter(\.featured)
                if !featured.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) { ForEach(featured) { e in FeaturedEvent(event: e, onRsvp: { rsvp(e) }).onTapGesture { router.event(e.id) } } }
                            .padding(.horizontal, Tokens.gutter).padding(.vertical, 6)
                    }
                }
                HStack {
                    Text(L("events_upcoming")).lyncText(.titleLarge)
                    Spacer()
                    Pill(text: L("calendar_my_schedule"), style: .tonal, icon: "calendar", vertical: 6) { router.calendar() }
                }
                .padding(.horizontal, Tokens.gutter).padding(.top, 18).padding(.bottom, 6)
                if shown.isEmpty { Text(L("events_empty")).lyncText(.bodyMedium, c.textMuted).padding(Tokens.gutter) }
                ForEach(shown) { e in EventRow(event: e, onRsvp: { rsvp(e) }).onTapGesture { router.event(e.id) } }
            }
            .padding(.bottom, 24)
        }
        .task { events = (try? await env.repository.events()) ?? [] }
    }

    private func rsvp(_ e: CampusEvent) {
        Haptics.confirm()
        let going = !e.imGoing
        if let i = events.firstIndex(where: { $0.id == e.id }) { withAnimation { events[i].imGoing = going; events[i].goingCount += going ? 1 : -1 } }
        Task { try? await env.repository.setRsvp(e.id, going) }
    }
}

struct RsvpButton: View {
    @Environment(\.palette) private var c
    let going: Bool
    var light = false
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if going { Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)) }
                Text(L(going ? "events_going_me" : "events_rsvp")).font(TS.labelLarge.font)
            }
            .foregroundStyle(going ? (light ? .white : c.text) : (light ? Tokens.night : c.onAccent))
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(Capsule().fill(going ? (light ? .white.opacity(0.2) : c.bg) : (light ? .white : c.accent)))
            .overlay(Capsule().stroke(going && !light ? c.border : .clear))
        }
        .buttonStyle(PressableStyle())
    }
}

struct FeaturedEvent: View {
    let event: CampusEvent
    var onRsvp: () -> Void
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            RemoteImage(url: event.coverUrl) { Color.gray.opacity(0.3) }.frame(width: 300, height: 222).clipped()
            LinearGradient(stops: [.init(color: .clear, location: 0.3), .init(color: .black.opacity(0.8), location: 1)], startPoint: .top, endPoint: .bottom)
            Text(L("events_featured")).font(.text(12, "Medium")).foregroundStyle(.white).padding(.horizontal, 10).padding(.vertical, 4)
                .background(Capsule().fill(.white.opacity(0.22))).padding(12).frame(maxHeight: .infinity, alignment: .top)
            VStack(alignment: .leading, spacing: 2) {
                Text(eventWhen(event.startsAt)).font(.text(14, "SemiBold")).foregroundStyle(.white.opacity(0.85))
                Text(event.title).font(.display(21, "SemiBold")).foregroundStyle(.white).lineLimit(2)
                HStack {
                    Text(LP("events_going", event.goingCount)).font(.text(12)).foregroundStyle(.white.opacity(0.8))
                    Spacer()
                    RsvpButton(going: event.imGoing, light: true, action: onRsvp)
                }
                .padding(.top, 8)
            }
            .padding(16)
        }
        .frame(width: 300, height: 222)
        .clipShape(RoundedRectangle(cornerRadius: Tokens.radiusMedia, style: .continuous))
    }
}

struct EventRow: View {
    @Environment(\.palette) private var c
    let event: CampusEvent
    var onRsvp: () -> Void
    var body: some View {
        HStack(spacing: 14) {
            VStack(spacing: 0) {
                Text(event.startsAt.formatted(.dateTime.month(.abbreviated).locale(Lang.locale)).replacingOccurrences(of: ".", with: "")).lyncText(.labelMedium, c.accent)
                Text(event.startsAt.formatted(.dateTime.day())).lyncText(.headline)
            }
            .frame(width: 58, height: 58).background(RoundedRectangle(cornerRadius: Tokens.radiusControl).fill(c.surface))
            VStack(alignment: .leading, spacing: 1) {
                Text(event.title).lyncText(.titleSmall).lineLimit(1)
                Text(eventWhen(event.startsAt)).lyncText(.bodySmall, c.textMuted).lineLimit(1)
                Text(event.place).lyncText(.bodySmall, c.textMuted).lineLimit(1)
                if !event.friendsGoing.isEmpty { FriendsGoing(event: event).padding(.top, 4) }
            }
            Spacer()
            RsvpButton(going: event.imGoing, action: onRsvp)
        }
        .padding(.horizontal, Tokens.gutter).padding(.vertical, 10).contentShape(Rectangle())
    }
}

struct FriendsGoing: View {
    @Environment(\.palette) private var c
    let event: CampusEvent
    var body: some View {
        HStack(spacing: 6) {
            AvatarStack(people: event.friendsGoing, size: 20, ringColor: c.bg)
            Text(event.friendsGoing.count == 1 ? L("events_friend_going", event.friendsGoing[0].firstName)
                 : LP("events_friends_going", event.friendsGoing.count - 1, event.friendsGoing[0].firstName, event.friendsGoing.count - 1))
                .lyncText(.labelMedium, c.accent).lineLimit(1)
        }
    }
}

struct EventDetailScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    let id: String
    @State private var event: CampusEvent?
    @State private var calendarEvent: EKEvent?
    private let store = EKEventStore()

    var body: some View {
        ZStack(alignment: .top) {
            c.bg.ignoresSafeArea()
            if let e = event {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ZStack(alignment: .bottom) {
                            RemoteImage(url: e.coverUrl) { c.surface }.frame(height: 360).clipped()
                            LinearGradient(stops: [.init(color: .clear, location: 0.55), .init(color: c.bg, location: 1)], startPoint: .top, endPoint: .bottom)
                        }
                        .frame(height: 360)
                        VStack(alignment: .leading, spacing: 0) {
                            Text("\(e.category.emoji)  \(L(e.category.labelKey))").lyncText(.labelLarge, c.accent)
                            Text(e.title).lyncText(.displaySmall).padding(.top, 6)
                            HStack(spacing: 8) {
                                Avatar(url: e.organizer.avatarUrl, initials: String(e.organizer.name.prefix(2)), size: 28)
                                Text(e.organizer.name).lyncText(.titleSmall)
                                if e.organizer.verified { Image(systemName: "checkmark.seal.fill").font(.system(size: 12)).foregroundStyle(c.accent) }
                            }
                            .padding(.top, 14)
                            info("clock", eventWhen(e.startsAt), L("events_until", e.endsAt.formatted(Date.FormatStyle(date: .omitted, time: .shortened).locale(Lang.locale)))).padding(.top, 18)
                            info("mappin", e.place, nil)
                            Text(e.description).lyncText(.bodyLarge).padding(.top, 12)
                            Text(LP("events_going", e.goingCount)).lyncText(.titleSmall, c.textMuted).padding(.top, 18)
                            if !e.friendsGoing.isEmpty { FriendsGoing(event: e).padding(.top, 8) }
                        }
                        .padding(.horizontal, Tokens.gutter).padding(.bottom, 120)
                    }
                }
                .ignoresSafeArea(edges: .top)
                HStack(spacing: 8) {
                    CircleIconButton(icon: "calendar.badge.plus") { addToCalendar(e) }
                    ShareLink(item: L("events_share_text", e.title, e.place, "https://lync.app/e/\(e.id)")) {
                        Image(systemName: "square.and.arrow.up").font(.system(size: 17, weight: .semibold)).foregroundStyle(c.text).frame(width: 40, height: 40).background(Circle().fill(c.surface))
                    }
                    Pill(text: L(e.imGoing ? "events_going_me" : "events_rsvp"), style: e.imGoing ? .outline : .filled, fullWidth: true, vertical: 14) {
                        Haptics.confirm()
                        let going = !e.imGoing
                        event?.imGoing = going; event?.goingCount += going ? 1 : -1
                        Task { try? await env.repository.setRsvp(e.id, going) }
                    }
                }
                .padding(.horizontal, Tokens.gutter).padding(.vertical, 12).background(c.bg)
                .frame(maxHeight: .infinity, alignment: .bottom)
            }
            HStack { CircleIconButton(icon: "arrow.left") { router.back() }; Spacer() }.padding(12)
        }
        .task { event = try? await env.repository.event(id) }
        .sheet(item: Binding(get: { calendarEvent.map(IdentifiedEvent.init) }, set: { if $0 == nil { calendarEvent = nil } })) { wrapped in
            EventEditView(event: wrapped.event, store: store)
        }
    }

    private func info(_ icon: String, _ title: String, _ subtitle: String?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 18)).foregroundStyle(c.text).frame(width: 40, height: 40).background(RoundedRectangle(cornerRadius: 12).fill(c.surface))
            VStack(alignment: .leading, spacing: 1) { Text(title).lyncText(.titleSmall); if let subtitle { Text(subtitle).lyncText(.bodySmall, c.textMuted) } }
        }
        .padding(.vertical, 6)
    }

    private func addToCalendar(_ e: CampusEvent) {
        // iOS 17+: the system edit sheet runs out of process and needs no calendar permission.
        calendarEvent = EventEditView.makeEvent(store: store, title: e.title, location: e.place, notes: e.description, start: e.startsAt, end: e.endsAt)
    }
}

struct IdentifiedEvent: Identifiable { let event: EKEvent; var id: ObjectIdentifier { ObjectIdentifier(event) } }

/// System "add event" sheet, pre-filled.
struct EventEditView: UIViewControllerRepresentable {
    let event: EKEvent
    let store: EKEventStore
    @Environment(\.dismiss) private var dismiss
    static func makeEvent(store: EKEventStore, title: String, location: String?, notes: String?, start: Date, end: Date) -> EKEvent {
        let ev = EKEvent(eventStore: store)
        ev.title = title; ev.location = location; ev.notes = notes; ev.startDate = start; ev.endDate = end
        return ev
    }
    func makeUIViewController(context: Context) -> EKEventEditViewController {
        let vc = EKEventEditViewController(); vc.event = event; vc.eventStore = store; vc.editViewDelegate = context.coordinator; return vc
    }
    func updateUIViewController(_ vc: EKEventEditViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(dismiss: dismiss) }
    final class Coordinator: NSObject, EKEventEditViewDelegate {
        let dismiss: DismissAction
        init(dismiss: DismissAction) { self.dismiss = dismiss }
        func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) { dismiss() }
    }
}
