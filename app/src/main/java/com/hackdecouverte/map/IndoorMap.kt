package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import java.io.InputStream
import kotlin.math.ceil

data class DoorOpening(val centerM: PointM, val widthM: Double, val nodeId: String?)

/** Precise map of one floor: occupancy, clearance, corridor directions and door openings. */
class IndoorMap(
    val floorId: String,
    val grid: OccupancyGrid,
    val clearance: DistanceField,
    val directions: DirectionField,
    val doors: List<DoorOpening>,
    val dominantAxisRad: Double
) {
    /** Indices of cells a person can stand on (free floor or passable furniture). */
    fun freeCellIndices(): IntArray {
        val out = ArrayList<Int>()
        for (i in grid.cells.indices) {
            val v = grid.cells[i]
            if (v == OccupancyGrid.FREE || v == OccupancyGrid.PASSABLE) out.add(i)
        }
        return out.toIntArray()
    }
}

/** Builds a floor's precise map from the aligned SVG, the graph and optional hand edits (spec §6.2, §7). */
object IndoorMapBuilder {
    const val TEXT_MAX_EXTENT_M = 0.75
    const val FACADE_MARGIN_M = 0.8
    const val FACADE_GROW_M = 0.95
    const val GRAPH_DOOR_WIDTH_M = 1.0

    fun build(vector: FloorVectorPlan, plan: FloorPlan, overlay: MapOverlay?, resM: Double = 0.1): IndoorMap {
        val walls = ArrayList<List<PointM>>()
        for (c in 0 until vector.contourCount) {
            val start = vector.contourStartPairIndex[c]
            val n = vector.contourPointCounts[c]
            if (n < 3) continue
            val pts = List(n) { k ->
                PointM(vector.packedEastNorthM[2 * (start + k)].toDouble(), vector.packedEastNorthM[2 * (start + k) + 1].toDouble())
            }
            val ext = maxOf(pts.maxOf { it.x } - pts.minOf { it.x }, pts.maxOf { it.y } - pts.minOf { it.y })
            if (ext >= TEXT_MAX_EXTENT_M) walls.add(pts)
        }
        return build(walls, vector.viewBoundsM, plan, overlay, resM)
    }

    /** Same, from wall contours already filtered (synthetic floors in tests, other sources later). */
    fun build(wallContours: List<List<PointM>>, bounds: MetricBounds, plan: FloorPlan, overlay: MapOverlay?, resM: Double = 0.1): IndoorMap {
        val w = ceil(bounds.widthM / resM).toInt()
        val h = ceil(bounds.heightM / resM).toInt()
        val extra = overlay?.extraWalls.orEmpty().map { poly -> poly.map { PointM(it[0], it[1]) } }
        val filled = OccupancyGrid.rasterize(wallContours + extra, bounds.minEastM, bounds.minNorthM, resM, w, h)
        val cells = ByteArray(w * h) { if (filled[it]) OccupancyGrid.WALL else OccupancyGrid.FREE }
        val grid = OccupancyGrid(bounds.minEastM, bounds.minNorthM, resM, w, h, cells)

        closeFacade(grid)

        val removed = overlay?.removeDoorNodeIds.orEmpty().toSet()
        val doors = ArrayList<DoorOpening>()
        for (node in plan.nodes) {
            if (node.type == "room" && node.id !in removed) doors.add(DoorOpening(node.posM, GRAPH_DOOR_WIDTH_M, node.id))
        }
        for (d in overlay?.doors.orEmpty()) doors.add(DoorOpening(PointM(d.east, d.north), d.widthM, d.nodeId))
        for (d in doors) carveDoor(grid, d)

        val passable = overlay?.passableAreas.orEmpty().map { poly -> poly.map { PointM(it[0], it[1]) } }
        if (passable.isNotEmpty()) {
            val inside = OccupancyGrid.rasterize(passable, bounds.minEastM, bounds.minNorthM, resM, w, h)
            for (i in cells.indices) if (inside[i] && cells[i] == OccupancyGrid.WALL) cells[i] = OccupancyGrid.PASSABLE
        }

        val clearance = DistanceField.compute(grid)
        val directions = DirectionField.compute(grid, clearance, doors.map { it.centerM })
        return IndoorMap(plan.floorId, grid, clearance, directions, doors, plan.dominantCorridorAxisRad)
    }

    /** Outside = what a probe keeping [FACADE_MARGIN_M] from walls reaches from the border, grown back by [FACADE_GROW_M]. */
    private fun closeFacade(grid: OccupancyGrid) {
        val w = grid.width
        val h = grid.height
        val cells = grid.cells
        val raw = DistanceField.compute(grid) { it == OccupancyGrid.WALL }.clearanceM
        val core = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        fun seed(i: Int) { if (!core[i] && raw[i] > FACADE_MARGIN_M) { core[i] = true; queue.add(i) } }
        for (c in 0 until w) { seed(c); seed((h - 1) * w + c) }
        for (r in 0 until h) { seed(r * w); seed(r * w + w - 1) }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val r = i / w; val c = i % w
            if (r > 0) seed(i - w)
            if (r < h - 1) seed(i + w)
            if (c > 0) seed(i - 1)
            if (c < w - 1) seed(i + 1)
        }
        val coreGrid = OccupancyGrid(grid.minEastM, grid.minNorthM, grid.resM, w, h, ByteArray(w * h) { if (core[it]) OccupancyGrid.WALL else OccupancyGrid.FREE })
        val toCore = DistanceField.compute(coreGrid) { it == OccupancyGrid.WALL }.clearanceM
        for (i in cells.indices) {
            if (core[i] || (cells[i] != OccupancyGrid.WALL && toCore[i] <= FACADE_GROW_M)) cells[i] = OccupancyGrid.OUTSIDE
        }
    }

    private fun carveDoor(grid: OccupancyGrid, door: DoorOpening) {
        val center = grid.indexOf(door.centerM)
        if (center < 0) return
        val radius = door.widthM / 2
        val k = ceil(radius / grid.resM).toInt()
        val r0 = center / grid.width
        val c0 = center % grid.width
        for (r in maxOf(0, r0 - k)..minOf(grid.height - 1, r0 + k)) for (c in maxOf(0, c0 - k)..minOf(grid.width - 1, c0 + k)) {
            val i = r * grid.width + c
            if (grid.cells[i] == OccupancyGrid.WALL && grid.centerOf(i).distanceTo(door.centerM) <= radius) grid.cells[i] = OccupancyGrid.FREE
        }
    }
}

/**
 * Loads `floor-plans/MB/MB-floor-0<n>.svg`, `floors/floorplan-MB-<n>.json` and, if present,
 * `maps/MB-<n>-overlay.json` through [open] (Android assets or the test classpath).
 * Null when the SVG or the graph is missing.
 */
fun loadIndoorMap(floorNumber: Int, open: (String) -> InputStream?): IndoorMap? {
    val graph = open("floors/floorplan-MB-$floorNumber.json") ?: return null
    return loadIndoorMap(graph.use { FloorPlan.parse(it) }, open)
}

/** Any building: walls from the plan's own SVG (`svg_asset`), overlay from `maps/<floorId>-overlay.json`. */
fun loadIndoorMap(plan: FloorPlan, open: (String) -> InputStream?): IndoorMap? {
    val svg = plan.svgAsset?.let(open) ?: return null
    val vector = svg.use { FloorVectorPlan.parseSvg(plan.floorId, it) }
    val overlay = open("maps/${plan.floorId}-overlay.json")?.use { MapOverlay.parse(it.bufferedReader().readText()) }
    return IndoorMapBuilder.build(vector, plan, overlay)
}
