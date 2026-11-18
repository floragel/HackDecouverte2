package com.hackdecouverte.rec

import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.map.MapTestSupport
import com.hackdecouverte.map.OccupancyGrid
import com.hackdecouverte.pdr.PointM
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * "Where do you think I walked?": fits the raw PDR path onto the floor plan by brute force (rotation,
 * scale, start position) so it stays in free space and crosses as few walls as possible, then draws
 * the best distinct candidates with times and room labels, for the walker to confirm.
 * `-Dguess=<walk.txt.gz>` (default: the first real walk).
 */
class PathGuessTest {

    private data class Fit(val rotDeg: Int, val scale: Double, val start: PointM, val wallHits: Int, val outside: Int, val pts: List<PointM>)

    @Test
    fun guessThePath() {
        val f = System.getProperty("guess")?.let(::File) ?: File("../recordings/STAN-1_20261118-150346.txt.gz")
        assumeTrue(f.isFile)
        val rec = GZIPInputStream(f.inputStream()).bufferedReader().use(RecordingReader::read)
        val site = IndoorSite.byId(rec.header.siteId)
        val map = WalkAnalysis.mapProvider(site)(rec.header.floorId)!!
        val plan = FloorPlan.parse(MapTestSupport.open(site.floorPlanAsset(rec.header.floorId))!!)
        val g = map.grid
        val r = WalkAnalysis.full(rec, site)
        val steps = r.steps
        val pdr = listOf(PointM(0.0, 0.0)) + steps.map { it.pdr }
        val times = listOf(rec.header.startedNs) + steps.map { it.tNs }

        fun place(rot: Double, scale: Double, start: PointM) = pdr.map { p ->
            val x = p.x * scale; val y = p.y * scale
            PointM(start.x + x * cos(rot) + y * sin(rot), start.y - x * sin(rot) + y * cos(rot))
        }
        fun blocked(p: PointM) = g.isBlocked(p)
        // Start candidates: free cells every metre.
        val starts = ArrayList<PointM>()
        var e = g.minEastM
        while (e < g.minEastM + g.width * g.resM) {
            var n = g.minNorthM
            while (n < g.minNorthM + g.height * g.resM) {
                val p = PointM(e, n)
                if (!blocked(p)) starts += p
                n += 1.0
            }
            e += 1.0
        }
        val coarse = ArrayList<Fit>()
        for (rotDeg in 0 until 360 step 5) for (scale in listOf(0.65, 0.75, 0.85, 0.95, 1.05)) {
            val rot = Math.toRadians(rotDeg.toDouble())
            for (s in starts) {
                val pts = place(rot, scale, s)
                var bad = 0
                for (p in pts) if (blocked(p)) { bad++; if (bad > 12) break }
                if (bad <= 12) coarse += Fit(rotDeg, scale, s, 0, bad, pts)
            }
        }
        // Refine: count wall crossings along each segment (10 cm samples).
        val refined = coarse.sortedBy { it.outside }.take(4000).map { fit ->
            var hits = 0
            for ((a, b) in fit.pts.zipWithNext()) {
                val d = a.distanceTo(b)
                val k = (d / 0.1).toInt().coerceAtLeast(1)
                var inWall = false
                for (i in 1..k) {
                    val p = PointM(a.x + (b.x - a.x) * i / k, a.y + (b.y - a.y) * i / k)
                    val w = g.cellAt(p) == OccupancyGrid.WALL
                    if (w && !inWall) hits++
                    inWall = w
                }
            }
            fit.copy(wallHits = hits)
        }.sortedWith(compareBy({ it.wallHits + it.outside }, { it.pts.first().distanceTo(it.pts.last()) }))
        // Keep distinct ones (rotation 20°+ apart or start 4 m+ apart).
        val best = ArrayList<Fit>()
        for (fit in refined) {
            if (best.none { kotlin.math.abs(((it.rotDeg - fit.rotDeg + 540) % 360) - 180) < 20 && it.start.distanceTo(fit.start) < 4.0 }) best += fit
            if (best.size == 4) break
        }
        val out = File("build/replay/path-guess").apply { mkdirs() }
        val summary = StringBuilder()
        best.forEachIndexed { i, fit ->
            val file = File(out, "guess-${i + 1}.svg")
            file.writeText(svg(map.grid, plan, fit.pts, times, rec.header.startedNs, "Hypothèse ${i + 1} : rotation ${fit.rotDeg}°, pas ×${fit.scale}, ${fit.wallHits} murs traversés, ${fit.outside} points hors plan"))
            summary.append("guess ${i + 1}: rot ${fit.rotDeg}° scale ${fit.scale} start ${fit.start} walls ${fit.wallHits} outside ${fit.outside}\n")
        }
        File(out, "summary.txt").writeText(summary.toString())
        println(summary)
    }

    private fun svg(g: OccupancyGrid, plan: FloorPlan, pts: List<PointM>, times: List<Long>, t0: Long, title: String): String {
        val px = 16.0
        val margin = 3.0
        val minE = g.minEastM - margin
        val maxN = g.minNorthM + g.height * g.resM + margin
        val w = (g.width * g.resM + 2 * margin) * px
        val h = (g.height * g.resM + 2 * margin) * px
        fun x(p: PointM) = (p.x - minE) * px
        fun y(p: PointM) = (maxN - p.y) * px
        val sb = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" width="${w.roundToInt()}" height="${(h + 40).roundToInt()}" font-family="sans-serif"><rect width="100%" height="100%" fill="white"/>""")
        for (row in 0 until g.height) {
            var col = 0
            while (col < g.width) {
                val v = g.cells[row * g.width + col]
                var end = col + 1
                while (end < g.width && g.cells[row * g.width + end] == v) end++
                val fill = when (v) { OccupancyGrid.WALL -> "#444"; OccupancyGrid.OUTSIDE -> "#eee"; else -> null }
                if (fill != null) {
                    val top = PointM(g.minEastM + col * g.resM, g.minNorthM + (row + 1) * g.resM)
                    sb.append("""<rect x="${"%.1f".format(Locale.ROOT, x(top))}" y="${"%.1f".format(Locale.ROOT, y(top))}" width="${"%.1f".format(Locale.ROOT, (end - col) * g.resM * px + 0.3)}" height="${"%.1f".format(Locale.ROOT, g.resM * px + 0.3)}" fill="$fill"/>""")
                }
                col = end
            }
        }
        for (n in plan.nodes) if (n.label.isNotBlank() && n.type != "corridor") {
            sb.append("""<text x="${"%.0f".format(Locale.ROOT, x(n.posM) + 4)}" y="${"%.0f".format(Locale.ROOT, y(n.posM) - 4)}" font-size="12" fill="#1565c0">${n.label}</text>""")
        }
        sb.append("""<polyline points="${pts.joinToString(" ") { "%.1f,%.1f".format(Locale.ROOT, x(it), y(it)) }}" fill="none" stroke="#e53935" stroke-width="3" stroke-linejoin="round"/>""")
        var lastLabel: Long? = null
        for ((p, t) in pts.zip(times)) if (lastLabel == null || t - lastLabel >= 10_000_000_000L) {
            lastLabel = t
            sb.append("""<circle cx="${"%.0f".format(Locale.ROOT, x(p))}" cy="${"%.0f".format(Locale.ROOT, y(p))}" r="3" fill="#e53935"/><text x="${"%.0f".format(Locale.ROOT, x(p) + 5)}" y="${"%.0f".format(Locale.ROOT, y(p) + 12)}" font-size="12" font-weight="bold">${((t - t0) / 1e9).roundToInt()} s</text>""")
        }
        sb.append("""<circle cx="${"%.0f".format(Locale.ROOT, x(pts.first()))}" cy="${"%.0f".format(Locale.ROOT, y(pts.first()))}" r="8" fill="#43a047"/><circle cx="${"%.0f".format(Locale.ROOT, x(pts.last()))}" cy="${"%.0f".format(Locale.ROOT, y(pts.last()))}" r="8" fill="none" stroke="#000" stroke-width="3"/>""")
        sb.append("""<text x="10" y="${(h + 25).roundToInt()}" font-size="14">$title · vert = départ, cercle noir = fin</text></svg>""")
        return sb.toString()
    }
}
