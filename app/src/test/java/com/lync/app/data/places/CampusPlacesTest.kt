package com.lync.app.data.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusPlacesTest {
    @Test
    fun calendarLocationsResolveToRooms() {
        val mb = CampusPlaces.resolve("JMSB, MB-2.210")!!
        assertEquals("MB", mb.building.code)
        assertEquals("MB-2.210", mb.roomLabel)
        assertEquals(2, mb.floor)
        assertTrue(mb.hasIndoorMap)

        val h = CampusPlaces.resolve("Hall Building, H-920")!!
        assertEquals("H", h.building.code)
        assertEquals("H-920", h.roomLabel)
        assertEquals(9, h.floor)
        assertFalse(h.hasIndoorMap)

        val ev = CampusPlaces.resolve("EV Building, EV-3.102")!!
        assertEquals("EV", ev.building.code)
        assertEquals(3, ev.floor)
    }

    @Test
    fun shorthandMbRooms() {
        assertEquals("MB-1.437", CampusPlaces.resolve("MB 1.437")!!.roomLabel)
        assertEquals("MB-2.255", CampusPlaces.resolve("mb2255")!!.roomLabel)
        assertEquals("MB-3.210", CampusPlaces.resolve("3.210")!!.roomLabel)
        assertEquals(CampusPlaces.roomKey("MB-2.255"), CampusPlaces.roomKey("M.2.255"))
    }

    @Test
    fun buildingsByNameOrCode() {
        assertEquals("MB", CampusPlaces.resolve("John Molson")!!.building.code)
        assertEquals("LB", CampusPlaces.resolve("Webster Library")!!.building.code)
        assertNull(CampusPlaces.resolve("JMSB")!!.roomLabel)
        assertNotNull(CampusPlaces.resolve("H"))
        assertNull(CampusPlaces.resolve("Café chez Paul"))
        assertNull(CampusPlaces.resolve(""))
    }
}
