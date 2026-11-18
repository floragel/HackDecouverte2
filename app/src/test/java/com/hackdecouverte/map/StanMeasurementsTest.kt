package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.rec.truth.TruthTrack
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Plan D1: the college map against what the walker measured on site (`floor-plans/STAN/measurements.json`). */
class StanMeasurementsTest {
    @Serializable
    data class Distance(val from: String, val to: String, val measuredM: Double, val how: String = "")

    @Serializable
    data class DoorPlace(val node: String, val fromSouthEastEndM: Double)

    @Serializable
    data class Measurements(val distances: List<Distance> = emptyList(), val corridorWidthM: Double? = null, val doors: List<DoorPlace> = emptyList())

    private val measurements: Measurements = Json { ignoreUnknownKeys = true }
        .decodeFromString(Measurements.serializer(), File("../floor-plans/STAN/measurements.json").readText())
    private val site = IndoorSite.STANISLAS
    private val plan = FloorPlan.parse(MapTestSupport.open(site.floorPlanAsset("STAN-1"))!!)
    private val map by lazy { loadIndoorMap(plan) { MapTestSupport.open(it) }!! }

    private fun free(p: PointM) = map.grid.cellAt(p).let { it == OccupancyGrid.FREE || it == OccupancyGrid.PASSABLE }

    /** Wall-to-wall span through [p], narrowest over every direction (no axis convention needed). */
    private fun narrowestSpanM(p: PointM): Double = (0 until 180).minOf { deg ->
        val a = Math.toRadians(deg.toDouble())
        fun reach(sign: Int): Double {
            var d = 0.0
            while (d < 20 && free(PointM(p.x + sign * d * sin(a), p.y + sign * d * cos(a)))) d += 0.02
            return d
        }
        reach(1) + reach(-1)
    }

    @Test
    fun corridorWidthMatchesTheMeasurement() {
        val measured = measurements.corridorWidthM
        assumeTrue("no corridor width measured", measured != null && measured > 0)
        val corridor = plan.nodes.filter { it.type == "corridor" }.sortedBy { it.id }
        // Mid-length nodes (the ends meet stairs and the exit).
        val widths = corridor.subList(corridor.size / 3, 2 * corridor.size / 3).map { narrowestSpanM(it.posM) }.sorted()
        val mapWidth = widths[widths.size / 2]
        // The grid rounds a thin (0.15 m) wall to whole cells: up to one cell of extra clearance on each side.
        val tol = 0.03 * measured!! + 2 * map.grid.resM
        assertTrue(
            "corridor %.2f m on the map vs %.2f m measured: × %.3f on gen_floor.py SCALE".format(mapWidth, measured, mapWidth / measured),
            abs(mapWidth - measured) <= tol
        )
    }

    @Test
    fun mapScaleMatchesTheMeasurements() {
        val ds = measurements.distances.filter { it.measuredM > 0 }
        assumeTrue("no distance measured yet (corridor length E-M-S → E-M-N pending)", ds.isNotEmpty())
        val byId = plan.nodes.associateBy { it.id }
        for (d in ds) {
            val onMap = byId.getValue(d.from).posM.distanceTo(byId.getValue(d.to).posM)
            assertTrue(
                "${d.from} → ${d.to}: %.2f m on the map vs %.2f m measured: × %.3f on gen_floor.py SCALE".format(onMap, d.measuredM, onMap / d.measuredM),
                abs(onMap - d.measuredM) / d.measuredM <= 0.03
            )
        }
    }

    @Test
    fun doorsWhereTheTraceSaysSo() {
        val routes = File("../recordings").listFiles { f -> f.name.endsWith(".txt.gz") }.orEmpty().mapNotNull { TruthTrack.load(it) }
            .filter { it.floorId == "STAN-1" }
        val doorPoints = routes.flatMap { r -> r.labels.filter { it.label == "porte" }.mapNotNull { r.path.getOrNull(it.pointIndex) } }
        assumeTrue("no traced point labelled « porte »", doorPoints.isNotEmpty())
        for (p in doorPoints) {
            val nearest = map.doors.minOf { it.centerM.distanceTo(p) }
            assertTrue("traced door at (%.1f, %.1f): nearest map opening %.2f m".format(p.x, p.y, nearest), nearest <= 0.8)
        }
    }
}
