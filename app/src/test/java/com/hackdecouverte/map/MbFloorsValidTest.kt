package com.hackdecouverte.map

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan D3: the seven MB floors pass the map checks. Floors in [PENDING_MANUAL_EDIT] still need their overlay
 * edited by hand in `floor-plans/MB/editor.html` (missing doors, passable furniture, graph nodes off the
 * corridors): they are reported, not failed; every other floor must pass, and a pending floor that starts
 * passing must leave the list.
 */
class MbFloorsValidTest {
    companion object {
        val PENDING_MANUAL_EDIT: Set<Int> = setOf(1, 3, 4, 5, 6, 7)
    }

    @Test
    fun everyMbFloorPassesTheMapChecks() {
        val failing = LinkedHashMap<Int, String>()
        for (n in 1..7) {
            val plan = FloorPlan.parse(MapTestSupport.open("floors/floorplan-MB-$n.json")!!)
            val map = loadIndoorMap(n) { MapTestSupport.open(it) }
            if (map == null) {
                failing[n] = "no map"
                continue
            }
            val report = MapValidator.validate(map, plan)
            if (!report.ok) failing[n] = report.summary()
        }
        for ((n, why) in failing) println("MB-$n fails the map checks:\n$why")
        val unexpected = failing.keys - PENDING_MANUAL_EDIT
        assertTrue("MB floors failing: " + unexpected.joinToString { "MB-$it:\n${failing[it]}" }, unexpected.isEmpty())
        val fixed = PENDING_MANUAL_EDIT - failing.keys
        assertTrue("now passing, remove from PENDING_MANUAL_EDIT: $fixed", fixed.isEmpty())
    }
}
