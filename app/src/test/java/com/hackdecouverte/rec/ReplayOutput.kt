package com.hackdecouverte.rec

import com.hackdecouverte.map.OccupancyGrid
import com.hackdecouverte.pdr.PointM
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Text report, step table and picture of a replay (computer side only: java.awt). */
internal object ReplayOutput {

    fun Double?.m(): String = this?.let { "%.1f m".format(Locale.ROOT, it) } ?: "—"

    fun report(name: String, rec: Recording, runs: List<ReplayResult>): String = buildString {
        val h = rec.header
        val r = runs.first()
        val durS = (rec.endNs - h.startedNs) / 1e9
        appendLine("# $name")
        appendLine("site ${h.siteId} · floor ${h.floorId} · ${h.device} (SDK ${h.sdk}) · app ${h.appVersion}")
        appendLine("duration %.0f s · step source ${h.stepSource} · step %.2f m · declination %.1f°".format(Locale.ROOT, durS, h.stepLengthM, h.declinationDeg))
        val kinds = rec.events.groupingBy { if (it is RecEvent.Sample) "S${it.type}${if (it.pipeline) "p" else "x"}" else it::class.simpleName!! }.eachCount()
        appendLine("lines: " + kinds.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" })
        val scans = rec.events.filterIsInstance<RecEvent.WifiScan>()
        if (scans.isNotEmpty()) {
            val aps = scans.flatMap { it.aps }.distinctBy { it.bssid }
            appendLine("wifi: ${scans.size} scans, ${aps.size} APs, ${aps.count { it.ftmResponder }} answer RTT (802.11mc)")
        }
        val ranges = rec.events.filterIsInstance<RecEvent.Rtt>().flatMap { it.ranges }
        if (ranges.isNotEmpty()) appendLine("rtt: ${ranges.size} ranges, ${ranges.count { it.status == 0 }} ok")
        val gnss = rec.events.filterIsInstance<RecEvent.Gnss>()
        if (gnss.isNotEmpty()) {
            val seen = gnss.map { g -> g.satellites.count { it.cn0DbHz > 0 } }
            appendLine("gnss: ${gnss.size} epochs, satellites seen median ${seen.sorted()[seen.size / 2]}, max C/N0 %.0f dB-Hz".format(Locale.ROOT, gnss.maxOf { g -> g.satellites.maxOfOrNull { it.cn0DbHz } ?: 0f }))
        }
        if (gnss.isNotEmpty()) {
            appendLine("sky (mean C/N0 by azimuth, dB-Hz; satellites heard through windows): " + skyByOctant(gnss))
            for (m in rec.markers) {
                val near = gnss.filter { kotlin.math.abs(it.tNs - m.tNs) <= 10_000_000_000L }
                if (near.isNotEmpty()) appendLine("  around M${m.id}: " + skyByOctant(near))
            }
        }
        appendLine()
        appendLine("PDR %.1f m in ${r.steps.size} steps · GPS fixes used ${r.gpsFixesUsed} · learned stride ×%.2f".format(Locale.ROOT, r.distanceM, r.strideScale ?: Double.NaN))
        appendLine("found after: " + runs.joinToString(" ") { run -> run.firstFoundNs?.let { "%.0fs".format(Locale.ROOT, (it - h.startedNs) / 1e9) } ?: "never" } + "  (one per seed)")
        appendLine()
        appendLine("marker      state        online   smoothed  pdr(M1)   gps (±acc)       live")
        for ((i, m) in r.markers.withIndex()) {
            val med = { f: (MarkerResult) -> Double? -> runs.mapNotNull { f(it.markers.getOrNull(i) ?: return@mapNotNull null) }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] } }
            appendLine(
                "%-11s %-12s %-8s %-9s %-9s %-16s %s".format(Locale.ROOT, 
                    "M${m.marker.id} ${m.marker.label}".take(11), m.state ?: "—", med { it.onlineErrM }.m(), med { it.smoothedErrM }.m(),
                    m.pdrFromFirstErrM.m(), m.gpsErrM?.let { "${it.m()} (±${m.gpsAccuracyM?.roundToInt()})" } ?: "—", m.liveErrM.m()
                )
            )
        }
        appendLine("(online / smoothed: median over ${runs.size} seeds)")
    }

    private val OCTANTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** Research probe (GNSS "sky side"): which directions satellites are heard from, mean C/N0 per 45° sector. */
    fun skyByOctant(epochs: List<RecEvent.Gnss>): String {
        val sum = DoubleArray(8); val n = IntArray(8)
        for (e in epochs) for (sat in e.satellites) {
            if (sat.cn0DbHz <= 0f) continue
            val k = (((sat.azimuthDeg + 22.5f) % 360f + 360f) % 360f / 45f).toInt().coerceIn(0, 7)
            sum[k] += sat.cn0DbHz.toDouble(); n[k]++
        }
        return (0 until 8).joinToString(" ") { k -> "${OCTANTS[k]} ${if (n[k] == 0) "—" else "%.0f".format(Locale.ROOT, sum[k] / n[k])}" }
    }

    fun stepsCsv(r: ReplayResult, startNs: Long): String = buildString {
        appendLine("t_s,step,pdr_x,pdr_y,state,est_e,est_n,radius_m")
        for (s in r.steps) {
            appendLine("%.2f,%d,%.2f,%.2f,%s,%s,%s,%s".format(Locale.ROOT, (s.tNs - startNs) / 1e9, s.step, s.pdr.x, s.pdr.y, s.state ?: "",
                s.estimate?.x?.let { "%.2f".format(Locale.ROOT, it) } ?: "", s.estimate?.y?.let { "%.2f".format(Locale.ROOT, it) } ?: "", s.radiusM?.let { "%.2f".format(Locale.ROOT, it) } ?: ""))
        }
    }

    /** SVG picture (opens in any browser): walls, GPS, end hypotheses, live estimate, traced walk, raw PDR, markers. */
    fun picture(rec: Recording, r: ReplayResult, file: File, pxPerM: Double = 18.0) {
        val g = r.map?.grid ?: return
        val margin = 6.0
        val minE = g.minEastM - margin
        val maxN = g.minNorthM + g.height * g.resM + margin
        val wPx = (g.width * g.resM + 2 * margin) * pxPerM
        val mapH = (g.height * g.resM + 2 * margin) * pxPerM
        fun x(p: PointM) = "%.1f".format(Locale.ROOT, (p.x - minE) * pxPerM)
        fun y(p: PointM) = "%.1f".format(Locale.ROOT, (maxN - p.y) * pxPerM)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${wPx.roundToInt()}" height="${(mapH + 60).roundToInt()}" font-family="sans-serif">""")
        sb.append("""<rect width="100%" height="100%" fill="white"/>""")
        // grid, one run of equal cells per rect
        val cell = g.resM * pxPerM
        for (row in 0 until g.height) {
            var col = 0
            while (col < g.width) {
                val v = g.cells[row * g.width + col]
                var end = col + 1
                while (end < g.width && g.cells[row * g.width + end] == v) end++
                val fill = when (v) {
                    OccupancyGrid.WALL -> "#3c3c3c"
                    OccupancyGrid.OUTSIDE -> "#e4e4e4"
                    OccupancyGrid.PASSABLE -> "#ffdcaa"
                    else -> null
                }
                if (fill != null) {
                    val top = PointM(g.minEastM + col * g.resM, g.minNorthM + (row + 1) * g.resM)
                    sb.append("""<rect x="${x(top)}" y="${y(top)}" width="${"%.1f".format(Locale.ROOT, (end - col) * cell + 0.3)}" height="${"%.1f".format(Locale.ROOT, cell + 0.3)}" fill="$fill"/>""")
                }
                col = end
            }
        }
        for ((p, acc) in r.gpsPoints) {
            sb.append("""<circle cx="${x(p)}" cy="${y(p)}" r="${"%.1f".format(Locale.ROOT, acc * pxPerM)}" fill="#1a73e8" fill-opacity="0.06" stroke="#1a73e8" stroke-opacity="0.3"/>""")
            sb.append("""<circle cx="${x(p)}" cy="${y(p)}" r="3" fill="#1a73e8"/>""")
        }
        r.systemCircle?.let { (p, rad) ->
            sb.append("""<circle cx="${x(p)}" cy="${y(p)}" r="${"%.1f".format(Locale.ROOT, rad * pxPerM)}" fill="none" stroke="#1a73e8" stroke-width="1.5" stroke-dasharray="8 6"/>""")
        }
        val maxW = r.particles.maxOfOrNull { it.weight }?.takeIf { it > 0 } ?: 1.0
        for (p in r.particles) {
            val t = ((p.score - 0.4) / 0.6).coerceIn(0.0, 1.0)
            val (cr, cg) = if (t < 0.5) 229 to (57 + (253 - 57) * t * 2).toInt() else (253 - (253 - 67) * (t - 0.5) * 2).toInt() to (216 - (216 - 160) * (t - 0.5) * 2).toInt()
            sb.append("""<circle cx="${x(p.positionM)}" cy="${y(p.positionM)}" r="${"%.1f".format(Locale.ROOT, 1.5 + 2.5 * kotlin.math.sqrt(p.weight / maxW))}" fill="rgb($cr,$cg,53)" fill-opacity="0.85"/>""")
        }
        fun path(pts: List<PointM>, color: String, width: Double, dash: String? = null) {
            if (pts.size < 2) return
            val d = pts.joinToString(" ") { "${x(it)},${y(it)}" }
            sb.append("""<polyline points="$d" fill="none" stroke="$color" stroke-width="$width" stroke-linejoin="round"${dash?.let { " stroke-dasharray=\"$it\"" } ?: ""}/>""")
        }
        rec.markers.firstOrNull()?.let { m1 ->
            val after = r.steps.filter { it.tNs >= m1.tNs }
            val base = r.steps.lastOrNull { it.tNs < m1.tNs }?.pdr ?: after.firstOrNull()?.pdr
            if (base != null) path(listOf(PointM(m1.eastM, m1.northM)) + after.map { PointM(m1.eastM + it.pdr.x - base.x, m1.northM + it.pdr.y - base.y) }, "#787878", 1.5, "4 4")
        }
        path(r.steps.mapNotNull { it.estimate }, "#fb8c00", 2.0)
        path(r.trace, "#7c3aed", 3.0)
        for (m in r.markers) {
            val p = PointM(m.marker.eastM, m.marker.northM)
            val cx = (p.x - minE) * pxPerM; val cy = (maxN - p.y) * pxPerM
            sb.append("""<path d="M${cx - 6},${cy - 6}L${cx + 6},${cy + 6}M${cx - 6},${cy + 6}L${cx + 6},${cy - 6}" stroke="black" stroke-width="2.5"/>""")
            sb.append("""<text x="${cx + 8}" y="${cy - 8}" font-size="13" font-weight="bold">M${m.marker.id}</text>""")
        }
        sb.append("""<text x="10" y="${(mapH + 22).roundToInt()}" font-size="13">× markers · orange: live estimate · violet: walk traced back · grey dashed: raw PDR from M1 · blue: GPS</text>""")
        sb.append("""<text x="10" y="${(mapH + 42).roundToInt()}" font-size="13">points: end-of-walk hypotheses, red→green = confidence, size = weight</text>""")
        sb.append("</svg>")
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }
}
