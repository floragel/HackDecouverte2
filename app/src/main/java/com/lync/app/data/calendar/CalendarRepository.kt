package com.lync.app.data.calendar

import android.content.Context
import com.lync.app.data.CampusRepository
import com.lync.app.data.model.CalendarDaySummary
import com.lync.app.data.model.CalendarItem
import com.lync.app.data.model.CalendarItemKind
import com.lync.app.data.model.Course
import com.lync.app.data.model.Profile
import com.lync.app.data.model.StoredCalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.net.URL
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class CalendarRepository(
    private val context: Context,
    private val campusRepo: CampusRepository,
) {
    private val prefs = context.getSharedPreferences("lync_calendar", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _importedEvents = MutableStateFlow<List<StoredCalendarEvent>>(loadStoredEvents())
    val importedEvents: Flow<List<StoredCalendarEvent>> = _importedEvents.asStateFlow()

    private fun loadStoredEvents(): List<StoredCalendarEvent> {
        val raw = prefs.getString("stored_events", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StoredCalendarEvent>>(raw) }.getOrDefault(emptyList())
    }

    private fun persistStoredEvents(events: List<StoredCalendarEvent>) {
        _importedEvents.value = events
        prefs.edit().putString("stored_events", json.encodeToString(events)).apply()
    }

    suspend fun clearImportedEvents() = withContext(Dispatchers.IO) {
        prefs.edit().remove("stored_events").apply()
        _importedEvents.value = emptyList()
    }

    suspend fun importIcs(icsText: String): Result<Int> = withContext(Dispatchers.Default) {
        runCatching {
            val parsed = IcsParser.parse(icsText)
            if (parsed.isEmpty()) error("Aucun événement valide trouvé dans le calendrier")
            val current = _importedEvents.value.toMutableList()
            current.addAll(parsed)
            persistStoredEvents(current)
            parsed.size
        }
    }

    suspend fun importFromStream(stream: InputStream): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = stream.bufferedReader().use { it.readText() }
            importIcs(text).getOrThrow()
        }
    }

    suspend fun importFromUrl(urlStr: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = if (urlStr.startsWith("webcal://", ignoreCase = true)) {
                "https://" + urlStr.removePrefix("webcal://")
            } else urlStr

            val connection = URL(cleanUrl).openConnection()
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val text = connection.getInputStream().bufferedReader().use { it.readText() }
            importIcs(text).getOrThrow()
        }
    }

    /** Pre-loads realistic Concordia Fall 2026 schedule for the student */
    suspend fun syncDefaultUniversitySchedule(): Result<Int> = withContext(Dispatchers.Default) {
        runCatching {
            val ics = buildConcordiaSemesterIcs()
            val parsed = IcsParser.parse(ics)
            persistStoredEvents(parsed)
            parsed.size
        }
    }

    private val isFr: Boolean
        get() = runCatching { context.resources.configuration.locales[0].language.startsWith("fr") }.getOrDefault(false)

    suspend fun getItemsForDate(date: LocalDate): List<CalendarItem> {
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val results = mutableListOf<CalendarItem>()

        // 1. Academic courses schedule generated from enrolled courses
        val courseItems = generateCourseItemsForDate(date)
        results.addAll(courseItems)

        // 2. Campus events from repository
        val events = runCatching { campusRepo.events() }.getOrDefault(emptyList())
        for (e in events) {
            if (e.startsAtMillis in dayStart until dayEnd || (e.startsAtMillis <= dayStart && e.endsAtMillis >= dayStart)) {
                results.add(
                    CalendarItem(
                        id = "campus-ev-" + e.id,
                        title = e.title,
                        subtitle = e.place,
                        kind = CalendarItemKind.SOCIAL_EVENT,
                        startsAtMillis = e.startsAtMillis,
                        endsAtMillis = e.endsAtMillis,
                        location = e.place,
                        emoji = e.category.emoji,
                        attendees = e.friendsGoing,
                        campusEventId = e.id,
                    )
                )
            }
        }

        // 3. Plans from feed
        val feed = runCatching { campusRepo.feed() }.getOrDefault(emptyList())
        for (post in feed) {
            val plan = post.plan ?: continue
            if (plan.startsAtMillis in dayStart until dayEnd) {
                val participantsSubtitle = if (isFr) "${plan.going.size} participant·e·s" else "${plan.going.size} going"
                results.add(
                    CalendarItem(
                        id = "plan-" + post.id,
                        title = "${plan.activity.emoji} ${plan.place}",
                        subtitle = participantsSubtitle,
                        kind = CalendarItemKind.SOCIAL_PLAN,
                        startsAtMillis = plan.startsAtMillis,
                        endsAtMillis = plan.startsAtMillis + 3600_000L,
                        location = plan.place,
                        emoji = plan.activity.emoji,
                        attendees = plan.going,
                    )
                )
            }
        }

        // 4. Stored / imported custom events
        val imported = _importedEvents.value
        for (stored in imported) {
            if (stored.startsAtMillis in dayStart until dayEnd) {
                // If it's a course, attach classmates
                val attendees = if (stored.kind == CalendarItemKind.COURSE) {
                    getClassmatesForCode(stored.courseCode)
                } else emptyList()

                results.add(
                    CalendarItem(
                        id = stored.id,
                        title = stored.title,
                        subtitle = stored.description ?: stored.location,
                        kind = stored.kind,
                        startsAtMillis = stored.startsAtMillis,
                        endsAtMillis = stored.endsAtMillis,
                        location = stored.location,
                        emoji = stored.emoji,
                        courseCode = stored.courseCode,
                        attendees = attendees,
                        isImported = true,
                    )
                )
            }
        }

        return results.distinctBy { it.id }.sortedBy { it.startsAtMillis }
    }

    suspend fun getDaySummaries(dates: List<LocalDate>): Map<LocalDate, CalendarDaySummary> {
        val summaries = mutableMapOf<LocalDate, CalendarDaySummary>()
        for (date in dates) {
            val items = getItemsForDate(date)
            summaries[date] = CalendarDaySummary(
                date = date,
                hasCourses = items.any { it.kind == CalendarItemKind.COURSE },
                hasSocial = items.any { it.kind == CalendarItemKind.SOCIAL_EVENT || it.kind == CalendarItemKind.SOCIAL_PLAN },
                totalCount = items.size,
            )
        }
        return summaries
    }

    private suspend fun generateCourseItemsForDate(date: LocalDate): List<CalendarItem> {
        val zone = ZoneId.systemDefault()
        val items = mutableListOf<CalendarItem>()
        val dayOfWeek = date.dayOfWeek
        val fr = isFr

        // Standard student schedule for Concordia Fall 2026 semester
        fun at(hour: Int, minute: Int): Long =
            date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

        when (dayOfWeek) {
            DayOfWeek.MONDAY -> {
                // COMP 248 Lecture
                items.add(
                    CalendarItem(
                        id = "course-comp248-mon-${date}",
                        title = if (fr) "COMP 248 — Programmation orientée objet I" else "COMP 248 — Object-Oriented Programming I",
                        subtitle = if (fr) "Cours magistral · Section U" else "Lecture · Section U",
                        kind = CalendarItemKind.COURSE,
                        startsAtMillis = at(10, 15),
                        endsAtMillis = at(11, 30),
                        location = "Hall Building, H-920",
                        emoji = "📚",
                        courseCode = "COMP 248",
                        attendees = getClassmatesForCode("COMP 248"),
                    )
                )
                // COMP 228 Lecture
                items.add(
                    CalendarItem(
                        id = "course-comp228-mon-${date}",
                        title = if (fr) "COMP 228 — Matériel informatique" else "COMP 228 — System Hardware",
                        subtitle = if (fr) "Cours magistral · Section H" else "Lecture · Section H",
                        kind = CalendarItemKind.COURSE,
                        startsAtMillis = at(13, 15),
                        endsAtMillis = at(14, 30),
                        location = "Hall Building, H-820",
                        emoji = "📚",
                        courseCode = "COMP 228",
                        attendees = getClassmatesForCode("COMP 228"),
                    )
                )
            }
            DayOfWeek.TUESDAY -> {
                // MATH 203 Lecture
                items.add(
                    CalendarItem(
                        id = "course-math203-tue-${date}",
                        title = if (fr) "MATH 203 — Calcul différentiel et intégral I" else "MATH 203 — Differential & Integral Calculus I",
                        subtitle = if (fr) "Cours magistral · Section B" else "Lecture · Section B",
                        kind = CalendarItemKind.COURSE,
                        startsAtMillis = at(13, 15),
                        endsAtMillis = at(14, 30),
                        location = "JMSB, MB-2.210",
                        emoji = "📚",
                        courseCode = "MATH 203",
                        attendees = getClassmatesForCode("MATH 203"),
                    )
                )
            }
            DayOfWeek.WEDNESDAY -> {
                // COMP 248 Lecture
                items.add(
                    CalendarItem(
                        id = "course-comp248-wed-${date}",
                        title = if (fr) "COMP 248 — Programmation orientée objet I" else "COMP 248 — Object-Oriented Programming I",
                        subtitle = if (fr) "Cours magistral · Section U" else "Lecture · Section U",
                        kind = CalendarItemKind.COURSE,
                        startsAtMillis = at(10, 15),
                        endsAtMillis = at(11, 30),
                        location = "Hall Building, H-920",
                        emoji = "📚",
                        courseCode = "COMP 248",
                        attendees = getClassmatesForCode("COMP 248"),
                    )
                )
                // ENGR 201 Lecture
                items.add(
                    CalendarItem(
                        id = "course-engr201-wed-${date}",
                        title = if (fr) "ENGR 201 — Pratique professionnelle" else "ENGR 201 — Professional Practice",
                        subtitle = if (fr) "Cours magistral & études de cas" else "Lecture & Case Studies",
                        kind = CalendarItemKind.COURSE,
                        startsAtMillis = at(14, 45),
                        endsAtMillis = at(16, 0),
                        location = "Hall Building, H-531",
                        emoji = "📚",
                        courseCode = "ENGR 201",
                        attendees = getClassmatesForCode("ENGR 201"),
                    )
                )
            }
            DayOfWeek.THURSDAY -> {
                // MATH 203 Tutorial
                items.add(
                    CalendarItem(
                        id = "course-math203-thu-${date}",
                        title = if (fr) "MATH 203 — Tutoriel de calcul" else "MATH 203 — Calculus Tutorial",
                        subtitle = if (fr) "Résolution d'exercices · Salle MB-1.437" else "Problem Solving · Room MB-1.437",
                        kind = CalendarItemKind.COURSE,
                        startsAtMillis = at(13, 15),
                        endsAtMillis = at(14, 30),
                        location = "JMSB, MB-1.437",
                        emoji = "📚",
                        courseCode = "MATH 203",
                        attendees = getClassmatesForCode("MATH 203"),
                    )
                )
            }
            DayOfWeek.FRIDAY -> {
                // COMP 228 Lab
                items.add(
                    CalendarItem(
                        id = "course-comp228-lab-fri-${date}",
                        title = if (fr) "COMP 228 — Labo de matériel informatique" else "COMP 228 — Hardware Lab",
                        subtitle = if (fr) "Circuits FPGA et assembleur · EV-3.102" else "FPGA Boards & Assembly · EV-3.102",
                        kind = CalendarItemKind.COURSE,
                        startsAtMillis = at(11, 45),
                        endsAtMillis = at(13, 30),
                        location = "EV Building, EV-3.102",
                        emoji = "🔬",
                        courseCode = "COMP 228",
                        attendees = getClassmatesForCode("COMP 228"),
                    )
                )
            }
            else -> {}
        }

        return items
    }

    private suspend fun getClassmatesForCode(courseCode: String?): List<Profile> {
        val classmates = runCatching { campusRepo.classmates() }.getOrDefault(emptyList()).map { it.profile }
        if (classmates.isEmpty()) return emptyList()

        return when (courseCode?.uppercase()?.trim()) {
            "COMP 248" -> classmates.filter { it.fullName.contains("Maya") || it.fullName.contains("Adam") }
            "MATH 203" -> classmates.filter { it.fullName.contains("Maya") || it.fullName.contains("Inès") }
            "ENGR 201" -> classmates.filter { it.fullName.contains("Karim") || it.fullName.contains("Maya") }
            "COMP 228" -> classmates.filter { it.fullName.contains("Inès") || it.fullName.contains("Adam") }
            else -> classmates.take(2)
        }
    }

    private fun buildConcordiaSemesterIcs(): String {
        val fr = isFr
        val comp248Summary = if (fr) "COMP 248 - Programmation orientée objet I" else "COMP 248 - Object-Oriented Programming I"
        val comp248Desc = if (fr) "Prof. T. Adams. Salle H-920." else "Prof. T. Adams. Room H-920."
        val math203Summary = if (fr) "MATH 203 - Calcul I" else "MATH 203 - Calculus I"
        val engr201Summary = if (fr) "ENGR 201 - Pratique professionnelle" else "ENGR 201 - Professional Practice"
        val engr201Desc = if (fr) "Pratique professionnelle et éthique de l'ingénieur." else "Professional engineering practice and case studies."
        val comp228LabSummary = if (fr) "COMP 228 - Matériel informatique (Labo)" else "COMP 228 - System Hardware (Lab)"
        val comp228LabDesc = if (fr) "Laboratoire matériel informatique et assembleur." else "Computer hardware assembly and FPGA board lab."
        val studySummary = if (fr) "📚 Session d'étude révision mi-session" else "📚 Midterm review study session"
        val studyDesc = if (fr) "Préparation de l'examen de mi-session avec Maya et Karim." else "Midterm exam preparation with Maya and Karim."
        val climbingSummary = if (fr) "🧗 Séance d'escalade Allez Up" else "🧗 Bouldering session at Allez Up"
        val climbingDesc = if (fr) "Session escalade de bloc du jeudi soir avec le club." else "Thursday night climbing session with the club."
        val partySummary = if (fr) "🎉 5@7 des clubs d'ingénierie" else "🎉 Engineering clubs 5@7 & pizza"
        val partyDesc = if (fr) "Rencontre conviviale pizza et rafraîchissements au lounge EV." else "Social pizza gathering and refreshments at the EV lounge."

        return """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Concordia University//Lync Schedule//EN
BEGIN:VEVENT
UID:comp248-lec@concordia.ca
SUMMARY:$comp248Summary
DESCRIPTION:$comp248Desc
LOCATION:Hall Building, H-920
DTSTART:20261102T101500
DTEND:20261102T113000
RRULE:FREQ=WEEKLY;BYDAY=MO,WE
END:VEVENT
BEGIN:VEVENT
UID:math203-lec@concordia.ca
SUMMARY:$math203Summary
DESCRIPTION:Prof. D. Sen. JMSB.
LOCATION:JMSB, MB-2.210
DTSTART:20261103T131500
DTEND:20261103T143000
RRULE:FREQ=WEEKLY;BYDAY=TU,TH
END:VEVENT
BEGIN:VEVENT
UID:engr201-lec@concordia.ca
SUMMARY:$engr201Summary
DESCRIPTION:$engr201Desc
LOCATION:Hall Building, H-531
DTSTART:20261104T144500
DTEND:20261104T160000
RRULE:FREQ=WEEKLY;BYDAY=WE
END:VEVENT
BEGIN:VEVENT
UID:comp228-lab@concordia.ca
SUMMARY:$comp228LabSummary
DESCRIPTION:$comp228LabDesc
LOCATION:EV Building, EV-3.102
DTSTART:20261106T114500
DTEND:20261106T133000
RRULE:FREQ=WEEKLY;BYDAY=FR
END:VEVENT
BEGIN:VEVENT
UID:study-group-webster@lync.app
SUMMARY:$studySummary
DESCRIPTION:$studyDesc
LOCATION:Webster Library, LB-420
DTSTART:20261117T160000
DTEND:20261117T183000
END:VEVENT
BEGIN:VEVENT
UID:climbing-thurs@lync.app
SUMMARY:$climbingSummary
DESCRIPTION:$climbingDesc
LOCATION:Allez Up, Saint-Henri
DTSTART:20261119T180000
DTEND:20261119T203000
END:VEVENT
BEGIN:VEVENT
UID:conu-robotics-social@lync.app
SUMMARY:$partySummary
DESCRIPTION:$partyDesc
LOCATION:EV Atrium, EV Building
DTSTART:20261120T170000
DTEND:20261120T210000
END:VEVENT
END:VCALENDAR
        """.trimIndent()
    }
}
