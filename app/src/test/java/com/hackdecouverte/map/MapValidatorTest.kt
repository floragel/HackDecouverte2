package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapValidatorTest {

    private val walls = MapTestSupport.sealedRoomWalls()
    private val bounds = MapTestSupport.fixtureBounds

    @Test
    fun healthySyntheticMapPasses() {
        val plan = MapTestSupport.sealedRoomPlan()
        val report = MapValidator.validate(IndoorMapBuilder.build(walls, bounds, plan, null), plan)
        assertTrue(report.summary(), report.ok)
        assertTrue(report.facadeClosed)
    }

    @Test
    fun edgeThroughWallIsListed() {
        val plan = MapTestSupport.sealedRoomPlan(
            extraNodes = listOf(FloorNode(id = "c3", type = "corridor", posM = PointM(15.0, 4.0))),
            extraEdges = listOf(FloorEdge("c2", "c3"))
        )
        val report = MapValidator.validate(IndoorMapBuilder.build(walls, bounds, plan, null), plan)
        assertFalse(report.ok)
        assertEquals(listOf("c2" to "c3"), report.edgesCrossingWalls)
    }

    @Test
    fun walledDoorIsBlockedAndUnreachable() {
        val plan = MapTestSupport.sealedRoomPlan()
        val map = IndoorMapBuilder.build(walls, bounds, plan, MapOverlay("T-1", removeDoorNodeIds = listOf("r1")))
        val report = MapValidator.validate(map, plan)
        assertEquals(listOf("r1"), report.doorNodesBlocked)
        assertEquals(listOf("r1"), report.unreachableDoorNodes)
    }

    @Test
    fun reportsAllSevenMbFloors() {
        for (n in 1..7) {
            val map = loadIndoorMap(n) { MapTestSupport.open(it) }
            assertNotNull(map)
            val plan = FloorPlan.parse(MapTestSupport.open("floors/floorplan-MB-$n.json")!!)
            println("MB-$n: " + MapValidator.validate(map!!, plan).summary())
        }
    }

    @Test
    fun mbFloor2WithOverlayPassesAllChecks() {
        val map = loadIndoorMap(2) { MapTestSupport.open(it) }!!
        val plan = FloorPlan.parse(MapTestSupport.open("floors/floorplan-MB-2.json")!!)
        val report = MapValidator.validate(map, plan)
        assertTrue(report.summary(), report.ok)
    }
}
