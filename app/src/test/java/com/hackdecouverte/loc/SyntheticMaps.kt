package com.hackdecouverte.loc

import com.hackdecouverte.map.FloorEdge
import com.hackdecouverte.map.FloorNode
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.IndoorMap
import com.hackdecouverte.map.IndoorMapBuilder
import com.hackdecouverte.map.MapOverlay
import com.hackdecouverte.map.MetricBounds
import com.hackdecouverte.pdr.PointM
import kotlin.random.Random

/** Closed synthetic floors (walls 0.3 m thick around the free space) for filter tests. */
internal object SyntheticMaps {

    /** Free space = polygon [inner] (counter-clockwise); walls = [inner] grown by 0.3 m. */
    private fun walled(inner: List<PointM>, outer: List<PointM>): List<List<PointM>> = listOf(outer, inner.reversed())

    private fun build(walls: List<List<PointM>>, bounds: MetricBounds, nodes: List<FloorNode>, edges: List<FloorEdge>, overlay: MapOverlay? = null): IndoorMap =
        IndoorMapBuilder.build(walls, bounds, FloorPlan("SYN", 100.0, 100.0, nodes, edges), overlay)

    /** L corridor A(0,0) → B(0,20) → C(25,20), 2 m wide. */
    fun lCorridor(): IndoorMap {
        val inner = listOf(PointM(-1.0, -1.0), PointM(1.0, -1.0), PointM(1.0, 19.0), PointM(26.0, 19.0), PointM(26.0, 21.0), PointM(-1.0, 21.0))
        val outer = listOf(PointM(-1.3, -1.3), PointM(1.3, -1.3), PointM(1.3, 18.7), PointM(26.3, 18.7), PointM(26.3, 21.3), PointM(-1.3, 21.3))
        val nodes = listOf(
            FloorNode(id = "A", type = "corridor", posM = PointM(0.0, 0.0)),
            FloorNode(id = "B", type = "corridor", posM = PointM(0.0, 20.0)),
            FloorNode(id = "C", type = "corridor", posM = PointM(25.0, 20.0))
        )
        return build(walled(inner, outer), MetricBounds(-4.0, -4.0, 29.0, 24.0), nodes, listOf(FloorEdge("A", "B"), FloorEdge("B", "C")))
    }

    /**
     * Z corridor A(0,0) → B(0,20) → C(25,20) → D(25,35), 2 m wide: the middle leg has a fixed
     * length between two corners, so it pins the step length even from an unknown start.
     */
    fun zCorridor(): IndoorMap {
        val inner = listOf(
            PointM(-1.0, -1.0), PointM(1.0, -1.0), PointM(1.0, 19.0), PointM(26.0, 19.0), PointM(26.0, 36.0),
            PointM(24.0, 36.0), PointM(24.0, 21.0), PointM(-1.0, 21.0)
        )
        val outer = listOf(
            PointM(-1.3, -1.3), PointM(1.3, -1.3), PointM(1.3, 18.7), PointM(26.3, 18.7), PointM(26.3, 36.3),
            PointM(23.7, 36.3), PointM(23.7, 21.3), PointM(-1.3, 21.3)
        )
        val nodes = listOf(
            FloorNode(id = "A", type = "corridor", posM = PointM(0.0, 0.0)),
            FloorNode(id = "B", type = "corridor", posM = PointM(0.0, 20.0)),
            FloorNode(id = "C", type = "corridor", posM = PointM(25.0, 20.0)),
            FloorNode(id = "D", type = "corridor", posM = PointM(25.0, 35.0))
        )
        return build(
            walled(inner, outer), MetricBounds(-4.0, -4.0, 29.0, 39.0), nodes,
            listOf(FloorEdge("A", "B"), FloorEdge("B", "C"), FloorEdge("C", "D"))
        )
    }

    /** Straight north–south corridor x ∈ [-1, 1], y ∈ [-10, 30]. */
    fun straightCorridor(): IndoorMap {
        val inner = listOf(PointM(-1.0, -10.0), PointM(1.0, -10.0), PointM(1.0, 30.0), PointM(-1.0, 30.0))
        val outer = listOf(PointM(-1.3, -10.3), PointM(1.3, -10.3), PointM(1.3, 30.3), PointM(-1.3, 30.3))
        val nodes = listOf(FloorNode(id = "S", type = "corridor", posM = PointM(0.0, -9.0)), FloorNode(id = "N", type = "corridor", posM = PointM(0.0, 29.0)))
        return build(walled(inner, outer), MetricBounds(-4.0, -13.0, 4.0, 33.0), nodes, listOf(FloorEdge("S", "N")))
    }

    /** Closed 12 × 12 m room centred on the origin, no door. */
    fun sealedRoom(): IndoorMap {
        val inner = listOf(PointM(-6.0, -6.0), PointM(6.0, -6.0), PointM(6.0, 6.0), PointM(-6.0, 6.0))
        val outer = listOf(PointM(-6.3, -6.3), PointM(6.3, -6.3), PointM(6.3, 6.3), PointM(-6.3, 6.3))
        return build(walled(inner, outer), MetricBounds(-9.0, -9.0, 9.0, 9.0), listOf(FloorNode(id = "R", type = "corridor", posM = PointM(0.0, 0.0))), emptyList())
    }

    /** [count] points drawn uniformly over the map's free cells. */
    fun uniformPoints(map: IndoorMap, count: Int, random: Random): List<PointM> {
        val free = map.freeCellIndices()
        return List(count) {
            val c = map.grid.centerOf(free[random.nextInt(free.size)])
            PointM(c.x + (random.nextDouble() - 0.5) * map.grid.resM, c.y + (random.nextDouble() - 0.5) * map.grid.resM)
        }
    }
}
