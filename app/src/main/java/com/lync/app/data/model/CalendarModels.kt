package com.lync.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
enum class CalendarItemKind {
    COURSE,
    SOCIAL_EVENT,
    SOCIAL_PLAN,
    CUSTOM,
}

@Serializable
data class StoredCalendarEvent(
    val id: String,
    val title: String,
    val description: String? = null,
    val kind: CalendarItemKind,
    val startsAtMillis: Long,
    val endsAtMillis: Long,
    val location: String? = null,
    val emoji: String,
    val courseCode: String? = null,
    val isImported: Boolean = true,
)

data class CalendarItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val kind: CalendarItemKind,
    val startsAtMillis: Long,
    val endsAtMillis: Long,
    val location: String? = null,
    val emoji: String,
    val courseCode: String? = null,
    val attendees: List<Profile> = emptyList(),
    val campusEventId: String? = null,
    val isImported: Boolean = false,
) {
    val durationMinutes: Long
        get() = ((endsAtMillis - startsAtMillis) / 60000L).coerceAtLeast(15)
}

enum class CalendarFilter {
    ALL,
    COURSES,
    SOCIAL,
}

data class CalendarDaySummary(
    val date: LocalDate,
    val hasCourses: Boolean,
    val hasSocial: Boolean,
    val totalCount: Int,
)
