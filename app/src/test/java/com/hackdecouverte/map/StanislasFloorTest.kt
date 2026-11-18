package com.hackdecouverte.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Collège Stanislas, McEachran wing, level 100: drawn from the evacuation plan photos. */
class StanislasFloorTest {

    private val site = IndoorSite.STANISLAS
    private fun plan() = FloorPlan.parse(MapTestSupport.open(site.floorPlanAsset("STAN-1"))!!)

    @Test
    fun floorPlanHasTheRoomsStairsAndExit() {
        val plan = plan()
        assertEquals("STAN-1", plan.floorId)
        assertEquals(10, plan.nodes.count { it.type == "room" && it.label.startsWith("M-1") } - 1) // M-105 has two doors
        assertEquals(2, plan.nodes.count { it.type == "stairs" })
        assertEquals(1, plan.nodes.count { it.type == "exit" })
        assertNotNull(plan.svgAsset)
    }

    @Test
    fun wallMapPassesAllChecks() {
        val plan = plan()
        val map = loadIndoorMap(plan) { MapTestSupport.open(it) }
        assertNotNull(map)
        val report = MapValidator.validate(map!!, plan)
        assertTrue(report.summary(), report.ok)
    }

    @Test
    fun everyRoomIsReachableFromTheCorridor() {
        val graph = BuildingGraph(listOf(plan()))
        val corridor = plan().nodes.first { it.type == "corridor" }
        for (room in plan().nodes.filter { it.type == "room" || it.type == "stairs" || it.type == "exit" }) {
            val route = graph.findRouteFromNode(NodeKey("STAN-1", corridor.id), NodeKey("STAN-1", room.id))
            assertNotNull("no route to ${room.label}", route)
        }
    }

    @Test
    fun mbSiteStillLoadsTheMbFloors() {
        assertEquals("floors/floorplan-MB-2.json", IndoorSite.MB.floorPlanAsset("MB-2"))
        assertEquals(IndoorSite.MB, IndoorSite.byId("MB"))
        assertEquals(IndoorSite.STANISLAS, IndoorSite.byId("STAN"))
        assertEquals(IndoorSite.STANISLAS, IndoorSite.byId("unknown"))
    }
}
