package com.hackdecouverte.map

import com.hackdecouverte.map.MapTestSupport.bar
import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class IndoorMapBuilderTest {

    /** Cells reachable from [from] without crossing a blocked cell (4-neighbourhood). */
    private fun reachable(map: IndoorMap, from: PointM): BooleanArray {
        val g = map.grid
        val seen = BooleanArray(g.width * g.height)
        val start = g.indexOf(from)
        if (start < 0 || OccupancyGrid.isBlockedValue(g.cells[start])) return seen
        val queue = ArrayDeque<Int>()
        queue.add(start); seen[start] = true
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val r = i / g.width; val c = i % g.width
            for ((nr, nc) in listOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1)) {
                if (nr !in 0 until g.height || nc !in 0 until g.width) continue
                val j = nr * g.width + nc
                if (!seen[j] && !OccupancyGrid.isBlockedValue(g.cells[j])) { seen[j] = true; queue.add(j) }
            }
        }
        return seen
    }

    private fun touchesBorder(map: IndoorMap, seen: BooleanArray): Boolean {
        val g = map.grid
        for (c in 0 until g.width) if (seen[c] || seen[(g.height - 1) * g.width + c]) return true
        for (r in 0 until g.height) if (seen[r * g.width] || seen[r * g.width + g.width - 1]) return true
        return false
    }

    private fun sealedRoomFixture() = MapTestSupport.sealedRoomWalls() to MapTestSupport.sealedRoomPlan()

    private val fixtureBounds = MapTestSupport.fixtureBounds

    @Test
    fun buildsMbFloor2FromBundledAssetsWithoutOverlay() {
        val vector = FloorVectorPlan.parseSvg("MB-2", MapTestSupport.open("floor-plans/MB/MB-floor-02.svg")!!)
        val plan = FloorPlan.parse(MapTestSupport.open("floors/floorplan-MB-2.json")!!)
        val map = IndoorMapBuilder.build(vector, plan, overlay = null)
        val corridors = plan.nodes.filter { it.type == "corridor" }
        val free = corridors.count { !map.grid.isBlocked(it.posM) }
        assertTrue("free corridor nodes $free/${corridors.size}", free >= 0.9 * corridors.size)
        val seen = BooleanArray(map.grid.width * map.grid.height)
        for (n in corridors) {
            val s = reachable(map, n.posM)
            for (i in seen.indices) if (s[i]) seen[i] = true
        }
        assertFalse("facade must be closed", touchesBorder(map, seen))
        assertEquals(42, map.doors.size)
        assertEquals(plan.dominantCorridorAxisRad, map.dominantAxisRad, 1e-12)
    }

    @Test
    fun overlayDoorOpensSealedRoomAndRemovalClosesOne() {
        val (walls, plan) = sealedRoomFixture()
        val roomInside = PointM(5.0, 4.0)
        val corridor = PointM(2.0, -0.5)

        val graphDoor = IndoorMapBuilder.build(walls, fixtureBounds, plan, overlay = null)
        assertTrue(reachable(graphDoor, corridor)[graphDoor.grid.indexOf(roomInside)])

        val removed = MapOverlay(floorId = "T-1", removeDoorNodeIds = listOf("r1"))
        val sealed = IndoorMapBuilder.build(walls, fixtureBounds, plan, removed)
        assertFalse(reachable(sealed, corridor)[sealed.grid.indexOf(roomInside)])
        assertEquals(0, sealed.doors.size)

        val moved = removed.copy(doors = listOf(OverlayDoor(east = 12.0, north = 1.15, nodeId = "r1")))
        val reopened = IndoorMapBuilder.build(walls, fixtureBounds, plan, moved)
        assertTrue(reachable(reopened, corridor)[reopened.grid.indexOf(roomInside)])
        assertEquals(1, reopened.doors.size)
        assertEquals(12.0, reopened.doors[0].centerM.x, 1e-9)
    }

    @Test
    fun passableAreaTurnsWallIntoPassable() {
        val (walls, plan) = sealedRoomFixture()
        val seatRow = bar(PointM(5.0, 5.0), PI / 2, 8.0, 0.6)
        val area = listOf(listOf(0.5, 4.4), listOf(9.5, 4.4), listOf(9.5, 5.6), listOf(0.5, 5.6))
        val withSeats = IndoorMapBuilder.build(walls, fixtureBounds, plan, MapOverlay("T-1", extraWalls = listOf(seatRow.map { listOf(it.x, it.y) })))
        assertTrue(withSeats.grid.segmentBlocked(PointM(5.0, 3.0), PointM(5.0, 6.5)))
        val passable = IndoorMapBuilder.build(
            walls, fixtureBounds, plan,
            MapOverlay("T-1", extraWalls = listOf(seatRow.map { listOf(it.x, it.y) }), passableAreas = listOf(area))
        )
        assertEquals(OccupancyGrid.PASSABLE, passable.grid.cellAt(PointM(5.0, 5.0)))
        assertFalse(passable.grid.segmentBlocked(PointM(5.0, 3.0), PointM(5.0, 6.5)))
    }

    @Test
    fun invalidOverlayJsonFallsBackToGraphDoors() {
        assertNull(MapOverlay.parse("{not json"))
        val ok = MapOverlay.parse("""{"floorId":"MB-2","doors":[{"east":1.0,"north":2.0}]}""")
        assertNotNull(ok)
        assertEquals(0.9, ok!!.doors[0].widthM, 1e-12)
        assertNotNull(loadIndoorMap(2) { MapTestSupport.open(it) })
        assertNull(loadIndoorMap(9) { MapTestSupport.open(it) })
    }

    @Test
    fun buildingOneFloorTakesUnderThreeSecondsOnJvm() {
        loadIndoorMap(2) { MapTestSupport.open(it) }
        val t0 = System.nanoTime()
        loadIndoorMap(2) { MapTestSupport.open(it) }
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("build took $ms ms", ms < 3000)
    }
}
