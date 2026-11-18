package com.hackdecouverte.loc

import com.hackdecouverte.map.FloorNode
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.IndoorMap
import com.hackdecouverte.map.IndoorMapBuilder
import com.hackdecouverte.map.MapTestSupport
import com.hackdecouverte.map.MetricBounds
import com.hackdecouverte.map.OccupancyGrid
import com.hackdecouverte.pdr.PointM
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan B2: a step clipping a door jamb is "probably the door", not "through a wall". */
class DoorSoftnessTest {

    /** Corridor y ∈ [0, 2] and a room above it behind a 0.3 m partition at y = 2.0–2.3, door node at x = 10. */
    private fun map(doorWidthM: Double = IndoorMapBuilder.GRAPH_DOOR_WIDTH_M): IndoorMap {
        val inner = listOf(PointM(-1.0, -1.0), PointM(21.0, -1.0), PointM(21.0, 8.0), PointM(-1.0, 8.0))
        val outer = listOf(PointM(-1.3, -1.3), PointM(21.3, -1.3), PointM(21.3, 8.3), PointM(-1.3, 8.3))
        val partition = MapTestSupport.bar(PointM(10.0, 2.15), Math.PI / 2, 22.0, 0.3)
        val plan = FloorPlan("SYN", 100.0, 100.0, listOf(FloorNode(id = "r1", type = "room", posM = PointM(10.0, 2.15))), emptyList())
        return IndoorMapBuilder.build(listOf(outer, inner.reversed(), partition), MetricBounds(-4.0, -4.0, 24.0, 11.0), plan, null, graphDoorWidthM = doorWidthM)
    }

    @Test
    fun aStepClippingADoorJambKeepsMoreWeightThanAPlainWall() {
        val p = LocParams(headingNoiseRad = 0.0, lengthNoise = 0.0, offsetDriftRad = 0.0, scaleDrift = 0.0, beta = 0.0, strideScaleMin = 1.0, strideScaleMax = 1.0)
        val f = LocalizationFilter(map(), p, Random(1))
        val pts = List(50) { PointM(11.0, 1.5) } + List(50) { PointM(4.0, 1.5) } + List(100) { PointM(16.0, 1.0) }
        val offsets = List(100) { 0.0 } + List(100) { Math.PI / 2 }
        f.initAt(pts, offsets)
        f.onStep(1.5, 0.0) // north for A and B (through the partition), east for C
        val ps = f.sampleParticles(f.size())
        val a = ps.filter { it.positionM.x in 10.9..11.1 }.map { it.weight }.average()
        val b = ps.filter { it.positionM.x in 3.9..4.1 }.map { it.weight }.average()
        assertEquals(p.doorWallFactor / p.wallFactor, a / b, 1e-6)
    }

    @Test
    fun doorWidthIsTunable() {
        fun opening(m: IndoorMap): Double {
            var free = 0
            var x = 7.0
            while (x <= 13.0) {
                if (m.grid.cellAt(PointM(x, 2.15)) == OccupancyGrid.FREE) free++
                x += 0.05
            }
            return free * 0.05
        }
        assertEquals(1.0, opening(map()), 0.15)
        assertEquals(1.4, opening(map(1.4)), 0.15)
    }
}
