package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OccupancyGridTest {

    private fun gridOf(polygons: List<List<PointM>>, minE: Double, minN: Double, w: Int, h: Int): OccupancyGrid {
        val filled = OccupancyGrid.rasterize(polygons, minE, minN, 0.1, w, h)
        return OccupancyGrid(minE, minN, 0.1, w, h, ByteArray(w * h) { if (filled[it]) OccupancyGrid.WALL else OccupancyGrid.FREE })
    }

    @Test
    fun rasterizeFillsSquareAndKeepsReversedHoleEmpty() {
        val outer = listOf(PointM(0.0, 0.0), PointM(4.0, 0.0), PointM(4.0, 4.0), PointM(0.0, 4.0))
        val hole = listOf(PointM(1.0, 1.0), PointM(1.0, 3.0), PointM(3.0, 3.0), PointM(3.0, 1.0))
        val g = gridOf(listOf(outer, hole), -1.0, -1.0, 60, 60)
        assertTrue(g.isBlocked(PointM(0.5, 0.5)))
        assertFalse(g.isBlocked(PointM(2.0, 2.0)))
        assertFalse(g.isBlocked(PointM(4.5, 4.5)))
        assertTrue(g.isBlocked(PointM(10.0, 10.0)))
        assertEquals(OccupancyGrid.OUTSIDE, g.cellAt(PointM(10.0, 10.0)))
    }

    @Test
    fun segmentBlockedDetectsThinWall() {
        val wall = listOf(PointM(2.0, -1.0), PointM(2.2, -1.0), PointM(2.2, 3.0), PointM(2.0, 3.0))
        val g = gridOf(listOf(wall), 0.0, -2.0, 50, 60)
        assertTrue(g.segmentBlocked(PointM(1.0, 1.0), PointM(3.0, 1.0)))
        assertFalse(g.segmentBlocked(PointM(1.0, 1.0), PointM(1.9, 1.0)))
    }

    @Test
    fun indexAndCenterRoundTrip() {
        val g = gridOf(emptyList(), -5.0, -5.0, 100, 80)
        val p = PointM(1.234, -2.345)
        val c = g.centerOf(g.indexOf(p))
        assertTrue(p.distanceTo(c) <= 0.05 * Math.sqrt(2.0) + 1e-9)
        assertEquals(-1, g.indexOf(PointM(100.0, 0.0)))
    }
}
