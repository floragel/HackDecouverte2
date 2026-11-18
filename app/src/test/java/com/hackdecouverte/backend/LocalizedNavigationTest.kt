package com.hackdecouverte.backend

import com.hackdecouverte.loc.LocEstimate
import com.hackdecouverte.loc.LocState
import com.hackdecouverte.loc.LocZone
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.map.MapTestSupport
import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan E1: routes, arrival and hints from the localized position. */
class LocalizedNavigationTest {
    private val plan = FloorPlan.parse(MapTestSupport.open(IndoorSite.STANISLAS.floorPlanAsset("STAN-1"))!!)

    private fun est(state: LocState, vararg zones: LocZone) = LocEstimate(zones.toList(), state)
    private fun pose(x: Double, state: LocState = LocState.FOUND) = LocalizedPose("STAN-1", PointM(x, 0.0), 0.0, 1.0, state)

    @Test
    fun poseFollowsTheMainZoneOnceApproximate() {
        val p = LocalizedNavigation.pose("STAN-1", est(LocState.APPROXIMATE, LocZone(PointM(3.0, 4.0), 5.0, 0.7, 1.0), LocZone(PointM(-9.0, 0.0), 3.0, 0.3, 0.0)))!!
        assertEquals(PointM(3.0, 4.0), p.positionM)
        assertEquals(5.0, p.radiusM, 0.0)
        assertNull(LocalizedNavigation.pose("STAN-1", est(LocState.SEARCHING)))
        val searching = LocalizedNavigation.pose("STAN-1", est(LocState.SEARCHING, LocZone(PointM(0.0, 0.0), 9.0, 0.2, 0.0)))!!
        assertFalse(LocalizedNavigation.shouldReroute(null, searching, 10))
        assertTrue(LocalizedNavigation.shouldReroute(searching, p, 0))
    }

    @Test
    fun noHeadingUntilFound() {
        val z = LocZone(PointM(0.0, 0.0), 2.0, 0.9, 1.2)
        assertNull(LocalizedNavigation.pose("STAN-1", est(LocState.APPROXIMATE, z))!!.headingRad)
        assertEquals(1.2, LocalizedNavigation.pose("STAN-1", est(LocState.FOUND, z))!!.headingRad!!, 0.0)
    }

    @Test
    fun arrivalWithin2_5mOfADoorOnlyWhenFound() {
        val doors = listOf(PointM(2.0, 0.0))
        assertTrue(LocalizedNavigation.arrived(pose(0.0), doors))
        assertFalse(LocalizedNavigation.arrived(pose(-1.0), doors))
        assertFalse(LocalizedNavigation.arrived(pose(0.0, LocState.APPROXIMATE), doors))
    }

    @Test
    fun routeDoesNotFlapBetweenTwoDoors() {
        val doors = listOf(PointM(-3.0, 0.0), PointM(3.0, 0.0))
        var door: PointM? = null
        val picked = HashSet<PointM>()
        for (i in 0 until 20) {
            val from = PointM(if (i % 2 == 0) -1.0 else 1.0, 0.0)
            door = LocalizedNavigation.stickyDestinationDoor(door, doors, from)
            picked += door
        }
        assertEquals(1, picked.size)
        // Clearly closer to the other door: it switches.
        assertEquals(doors[1], LocalizedNavigation.stickyDestinationDoor(doors[0], doors, PointM(2.5, 0.0)))
    }

    @Test
    fun roomHintGivesOneZonePerDoorOfThatRoom() {
        val zones = LocalizedNavigation.roomHintZones(plan, "M-105")
        assertEquals(2, zones.size)
        for (z in zones) {
            assertEquals(2.0, z.radiusM, 0.0)
            assertEquals(0.5, z.weight, 1e-12)
        }
    }

    /** The search circles after « Je suis à M-105 »: one per door, not one blob over the whole wing. */
    @Test
    fun roomHintShowsOneSearchCirclePerDoor() {
        val site = IndoorSite.STANISLAS
        val c = LocalizationController(com.hackdecouverte.rec.WalkAnalysis.mapProvider(site), kotlin.random.Random(1))
        val hint = LocalizedNavigation.roomHintZones(plan, "M-105")
        c.start("STAN-1", null, WarmStart(hint, 0.0))
        val shown = c.searchZones.take(2)
        assertEquals("${c.searchZones}", 2, shown.size)
        for (h in hint) assertTrue("$shown", shown.any { it.centerM.distanceTo(h.centerM) < 2.0 && it.radiusM < 6.0 })
    }

    @Test
    fun aColdSearchShowsOneCircleOverTheWholeCloud() {
        val c = LocalizationController(com.hackdecouverte.rec.WalkAnalysis.mapProvider(IndoorSite.STANISLAS), kotlin.random.Random(1))
        c.start("STAN-1")
        assertEquals(1, c.searchZones.size)
        assertTrue("${c.searchZones}", c.searchZones[0].radiusM > 8.0)
    }

    @Test
    fun enteringFromTheStreetStartsAtTheNearestEntrance() {
        val exit = plan.nodes.single { it.type == "exit" }
        val toExit = exit.posM.norm().takeIf { it > 0 }?.let { PointM(exit.posM.x / it, exit.posM.y / it) } ?: PointM(1.0, 0.0)
        val fix = PointM(exit.posM.x + 8 * toExit.x, exit.posM.y + 8 * toExit.y)
        val zones = LocalizedNavigation.entranceHintZones(plan, fix)
        assertEquals(1, zones.size)
        assertEquals(exit.posM, zones[0].centerM)
        assertEquals(3.0, zones[0].radiusM, 0.0)
        assertNotNull(zones.singleOrNull())
        assertTrue(LocalizedNavigation.entranceHintZones(plan, PointM(fix.x + 60 * toExit.x, fix.y + 60 * toExit.y)).isEmpty())
    }
}
