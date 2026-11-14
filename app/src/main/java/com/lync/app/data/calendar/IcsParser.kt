package com.lync.app.data.calendar

import com.lync.app.data.model.CalendarItemKind
import com.lync.app.data.model.StoredCalendarEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object IcsParser {

    private val courseCodeRegex = Regex("\\b([A-Z]{3,4}\\s*\\d{3})\\b", RegexOption.IGNORE_CASE)

    fun parse(icsText: String): List<StoredCalendarEvent> {
        val lines = unfoldLines(icsText)
        val events = mutableListOf<StoredCalendarEvent>()

        var inEvent = false
        val currentProps = mutableMapOf<String, String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals("BEGIN:VEVENT", ignoreCase = true)) {
                inEvent = true
                currentProps.clear()
            } else if (trimmed.equals("END:VEVENT", ignoreCase = true)) {
                if (inEvent) {
                    val parsedList = buildEventsFromProperties(currentProps)
                    events.addAll(parsedList)
                }
                inEvent = false
            } else if (inEvent) {
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx > 0) {
                    val rawKey = trimmed.substring(0, colonIdx)
                    val value = trimmed.substring(colonIdx + 1)
                    val key = rawKey.substringBefore(';').uppercase()
                    // Store full prop key with params for TZID/VALUE parsing if needed
                    currentProps[rawKey.uppercase()] = value
                    if (!currentProps.containsKey(key)) {
                        currentProps[key] = value
                    }
                }
            }
        }

        return events
    }

    private fun unfoldLines(text: String): List<String> {
        val rawLines = text.lines()
        val result = mutableListOf<String>()
        var current = StringBuilder()

        for (line in rawLines) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                current.append(line.substring(1))
            } else {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                current.append(line)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    private fun buildEventsFromProperties(props: Map<String, String>): List<StoredCalendarEvent> {
        val summary = unescape(props["SUMMARY"] ?: "Événement sans titre")
        val description = props["DESCRIPTION"]?.let { unescape(it) }
        val location = props["LOCATION"]?.let { unescape(it) }

        val startStr = props.entries.firstOrNull { it.key.startsWith("DTSTART") }?.value
        val endStr = props.entries.firstOrNull { it.key.startsWith("DTEND") }?.value

        val startMillis = parseDate(startStr) ?: System.currentTimeMillis()
        val endMillis = parseDate(endStr) ?: (startMillis + 3600_000L)
        val duration = (endMillis - startMillis).coerceAtLeast(1800_000L)

        val courseCode = extractCourseCode(summary, description)
        val (kind, emoji) = categorize(summary, description, courseCode)

        val rrule = props["RRULE"]
        if (rrule != null && rrule.contains("FREQ=WEEKLY", ignoreCase = true)) {
            return expandWeeklyRecurrence(
                summary = summary,
                description = description,
                location = location,
                startMillis = startMillis,
                duration = duration,
                kind = kind,
                emoji = emoji,
                courseCode = courseCode,
                rrule = rrule,
            )
        }

        return listOf(
            StoredCalendarEvent(
                id = UUID.randomUUID().toString(),
                title = summary,
                description = description,
                kind = kind,
                startsAtMillis = startMillis,
                endsAtMillis = endMillis,
                location = location,
                emoji = emoji,
                courseCode = courseCode,
                isImported = true,
            )
        )
    }

    private fun expandWeeklyRecurrence(
        summary: String,
        description: String?,
        location: String?,
        startMillis: Long,
        duration: Long,
        kind: CalendarItemKind,
        emoji: String,
        courseCode: String?,
        rrule: String,
    ): List<StoredCalendarEvent> {
        val events = mutableListOf<StoredCalendarEvent>()
        val startDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMillis), ZoneId.systemDefault())

        // Extract days of week if specified (BYDAY=MO,WE,FR)
        val byDayPart = rrule.split(';').firstOrNull { it.startsWith("BYDAY=", ignoreCase = true) }
        val daysOfWeek = if (byDayPart != null) {
            val daysStr = byDayPart.substringAfter('=').split(',')
            daysStr.mapNotNull { parseDayOfWeek(it.trim()) }
        } else {
            listOf(startDateTime.dayOfWeek)
        }

        // Expand for standard academic semester (15 weeks)
        val weeks = 15
        val baseDate = startDateTime.toLocalDate()

        for (w in 0 until weeks) {
            val weekStart = baseDate.plusWeeks(w.toLong())
            for (targetDay in daysOfWeek) {
                val dayOffset = targetDay.value - weekStart.dayOfWeek.value
                val date = weekStart.plusDays(dayOffset.toLong())
                val occurrenceStart = date.atTime(startDateTime.toLocalTime())
                val occStartMillis = occurrenceStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                events.add(
                    StoredCalendarEvent(
                        id = UUID.randomUUID().toString(),
                        title = summary,
                        description = description,
                        kind = kind,
                        startsAtMillis = occStartMillis,
                        endsAtMillis = occStartMillis + duration,
                        location = location,
                        emoji = emoji,
                        courseCode = courseCode,
                        isImported = true,
                    )
                )
            }
        }

        return events
    }

    private fun parseDayOfWeek(dayCode: String): DayOfWeek? = when (dayCode.uppercase()) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun extractCourseCode(summary: String, description: String?): String? {
        val fromSummary = courseCodeRegex.find(summary)?.value
        if (fromSummary != null) return fromSummary.uppercase().replace("\\s+".toRegex(), " ")
        if (description != null) {
            val fromDesc = courseCodeRegex.find(description)?.value
            if (fromDesc != null) return fromDesc.uppercase().replace("\\s+".toRegex(), " ")
        }
        return null
    }

    private fun categorize(summary: String, description: String?, courseCode: String?): Pair<CalendarItemKind, String> {
        val combined = "$summary ${description.orEmpty()}".lowercase()

        if (courseCode != null || combined.contains("lecture") || combined.contains("cours") ||
            combined.contains("tutorial") || combined.contains("lab") || combined.contains("midterm") ||
            combined.contains("final exam") || combined.contains("td") || combined.contains("tp") ||
            combined.contains("amphi") || combined.contains("séance")
        ) {
            val emoji = if (combined.contains("lab")) "🔬" else if (combined.contains("exam") || combined.contains("midterm")) "📝" else "📚"
            return CalendarItemKind.COURSE to emoji
        }

        if (combined.contains("gym") || combined.contains("workout") || combined.contains("sport") || combined.contains("soccer") || combined.contains("bouldering") || combined.contains("climb")) {
            return CalendarItemKind.SOCIAL_PLAN to "🏋️"
        }
        if (combined.contains("coffee") || combined.contains("café") || combined.contains("matcha") || combined.contains("boba")) {
            return CalendarItemKind.SOCIAL_PLAN to "☕"
        }
        if (combined.contains("lunch") || combined.contains("dîner") || combined.contains("souper") || combined.contains("pizza") || combined.contains("food")) {
            return CalendarItemKind.SOCIAL_PLAN to "🍜"
        }
        if (combined.contains("party") || combined.contains("soirée") || combined.contains("gala") || combined.contains("apéro") || combined.contains("5@7") || combined.contains("bar") || combined.contains("fest")) {
            return CalendarItemKind.SOCIAL_EVENT to "🎉"
        }
        if (combined.contains("robotics") || combined.contains("hackathon") || combined.contains("tech") || combined.contains("ai")) {
            return CalendarItemKind.SOCIAL_EVENT to "💻"
        }
        if (combined.contains("movie") || combined.contains("film") || combined.contains("cinema") || combined.contains("cinéma")) {
            return CalendarItemKind.SOCIAL_EVENT to "🎬"
        }

        return CalendarItemKind.CUSTOM to "📅"
    }

    private fun parseDate(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        val clean = dateStr.trim()
        val zone = ZoneId.systemDefault()

        return runCatching {
            when {
                clean.endsWith("Z") -> {
                    // e.g. 20261116T101500Z
                    val dtf = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    Instant.from(dtf.parse(clean)).toEpochMilli()
                }
                clean.contains("T") && clean.length == 15 -> {
                    // e.g. 20261116T101500
                    val dtf = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
                    LocalDateTime.parse(clean, dtf).atZone(zone).toInstant().toEpochMilli()
                }
                clean.length == 8 -> {
                    // e.g. 20261116
                    val dtf = DateTimeFormatter.ofPattern("yyyyMMdd")
                    LocalDate.parse(clean, dtf).atStartOfDay(zone).toInstant().toEpochMilli()
                }
                else -> {
                    // try ISO-8601 fallback
                    Instant.parse(clean).toEpochMilli()
                }
            }
        }.getOrNull()
    }

    private fun unescape(text: String): String {
        return text.replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
    }
}
