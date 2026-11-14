package com.lync.app.data.calendar

import com.lync.app.data.model.CalendarItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsParserTest {

    @Test
    fun parseSimpleCourseEvent() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:COMP 248 - Object-Oriented Programming I
            LOCATION:Hall Building, H-920
            DESCRIPTION:Prof. Adams\, section U
            DTSTART:20261116T101500
            DTEND:20261116T113000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParser.parse(ics)
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("COMP 248 - Object-Oriented Programming I", event.title)
        assertEquals("Hall Building, H-920", event.location)
        assertEquals("Prof. Adams, section U", event.description)
        assertEquals(CalendarItemKind.COURSE, event.kind)
        assertEquals("COMP 248", event.courseCode)
        assertEquals("📚", event.emoji)
    }

    @Test
    fun parseSocialPartyEvent() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:Concordia Robotics 5@7 Gala
            LOCATION:EV Atrium
            DESCRIPTION:End of semester celebration
            DTSTART:20261120T170000
            DTEND:20261120T210000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParser.parse(ics)
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("Concordia Robotics 5@7 Gala", event.title)
        assertEquals(CalendarItemKind.SOCIAL_EVENT, event.kind)
    }

    @Test
    fun parseRecurringWeeklyEvent() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:MATH 203 - Calculus I
            LOCATION:JMSB MB-2.210
            DTSTART:20261102T131500
            DTEND:20261102T143000
            RRULE:FREQ=WEEKLY;BYDAY=TU,TH
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcsParser.parse(ics)
        // 15 weeks * 2 days/week = 30 events
        assertEquals(30, events.size)
        assertTrue(events.all { it.kind == CalendarItemKind.COURSE })
        assertTrue(events.all { it.courseCode == "MATH 203" })
    }
}
