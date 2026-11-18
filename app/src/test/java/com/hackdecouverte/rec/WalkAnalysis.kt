package com.hackdecouverte.rec

import com.hackdecouverte.loc.LocParams
import com.hackdecouverte.loc.LocState
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.IndoorMap
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.map.MapTestSupport
import com.hackdecouverte.map.loadIndoorMap
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.sensor.SensorRouter
import java.io.File
import java.util.Locale

/**
 * Gets everything out of one long walk (computer side):
 * 1. **Pseudo ground truth:** the whole walk replayed once with an unlimited history; once the filter has
 *    found the walker, the walk traced back (Zee) is a good estimate of where they really were, even
 *    where no marker was tapped. Checked against the markers when there are some.
 * 2. **Segments:** overlapping windows replayed from a cold start (several seeds, with and without GPS),
 *    each measured against the pseudo truth: time to FOUND, error after it, false FOUNDs.
 * 3. **Carry:** what the app believed (steady / swinging / hold changes) next to the walker's labels.
 * 4. **Steps:** detector, counter, recovered, dropped.
 * 5. **Sweep** (opt-in, `-Dsweep=true`): a few filter settings compared on the same segments.
 */
internal object WalkAnalysis {

    data class Segment(
        val startS: Double, val endS: Double, val seed: Int, val gps: Boolean,
        val foundAfterS: Double?, val medianErrM: Double?, val p90ErrM: Double?, val endErrM: Double?,
        val falseFoundSteps: Int, val foundSteps: Int, val measuredSteps: Int,
        /** Error whenever the filter claims at least APPROXIMATE (it may never reach FOUND in a window). */
        val approxMedianErrM: Double? = null, val approxSteps: Int = 0
    )

    private val maps = HashMap<String, IndoorMap?>()

    fun mapProvider(site: IndoorSite): (String) -> IndoorMap? = { floorId ->
        maps.getOrPut("${site.id}/$floorId") {
            MapTestSupport.open(site.floorPlanAsset(floorId))?.let { loadIndoorMap(FloorPlan.parse(it)) { p -> MapTestSupport.open(p) } }
        }
    }

    fun full(rec: Recording, site: IndoorSite, params: LocParams = LocParams()): ReplayResult =
        WalkReplay(rec, site.origin, mapProvider(site), seed = 1, params = params.copy(historySteps = 1_000_000)).run()

    /** Pseudo truth at [t], interpolated; null outside it or across a gap of more than 3 s. */
    fun truthAt(walk: List<Pair<Long, PointM>>, t: Long): PointM? {
        if (walk.isEmpty() || t < walk.first().first || t > walk.last().first) return null
        var lo = 0
        var hi = walk.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (walk[mid].first <= t) lo = mid else hi = mid
        }
        val (t0, p0) = walk[lo]
        val (t1, p1) = walk[hi]
        if (t1 - t0 > 3_000_000_000L) return null
        val f = if (t1 == t0) 0.0 else (t - t0).toDouble() / (t1 - t0)
        return PointM(p0.x + (p1.x - p0.x) * f, p0.y + (p1.y - p0.y) * f)
    }

    private fun quantile(v: List<Double>, q: Double): Double? = v.sorted().let { if (it.isEmpty()) null else it[((it.size - 1) * q).toInt()] }

    fun segments(
        rec: Recording, site: IndoorSite, truth: List<Pair<Long, PointM>>, windowS: Double = 90.0, strideS: Double = 45.0,
        seeds: IntRange = 1..3, params: LocParams = LocParams(), withoutGps: Boolean = true
    ): List<Segment> {
        val t0 = rec.header.startedNs
        val end = rec.endNs
        val out = ArrayList<Segment>()
        var start = t0
        while (start + (windowS * 0.6 * 1e9).toLong() <= end) {
            val stop = minOf(end, start + (windowS * 1e9).toLong())
            for (gps in if (withoutGps) listOf(true, false) else listOf(true)) for (seed in seeds) {
                val r = WalkReplay(rec, site.origin, mapProvider(site), seed, params, useGps = gps, windowStartNs = start, windowEndNs = stop).run()
                val found = r.firstFoundNs
                val errs = ArrayList<Double>()
                val approx = ArrayList<Double>()
                var falseFound = 0
                var foundSteps = 0
                for (st in r.steps) {
                    val truthP = truthAt(truth, st.tNs) ?: continue
                    val est = st.estimate ?: continue
                    val e = est.distanceTo(truthP)
                    if (found != null && st.tNs >= found) errs += e
                    if (st.state == LocState.APPROXIMATE || st.state == LocState.FOUND) approx += e
                    if (st.state == LocState.FOUND) {
                        foundSteps++
                        if (e > 3.0) falseFound++
                    }
                }
                val last = r.steps.lastOrNull()
                val endErr = last?.let { st -> truthAt(truth, st.tNs)?.let { tp -> st.estimate?.distanceTo(tp) } }
                out += Segment(
                    (start - t0) / 1e9, (stop - t0) / 1e9, seed, gps, found?.let { (it - start) / 1e9 },
                    quantile(errs, 0.5), quantile(errs, 0.9), endErr, falseFound, foundSteps, errs.size,
                    quantile(approx, 0.5), approx.size
                )
            }
            start += (strideS * 1e9).toLong()
        }
        return out
    }

    fun segmentsCsv(segs: List<Segment>): String = buildString {
        appendLine("start_s,end_s,seed,gps,found_after_s,median_err_m,p90_err_m,end_err_m,false_found_steps,found_steps,measured_steps,approx_median_err_m,approx_steps")
        for (s in segs) appendLine(
            listOf(s.startS, s.endS, s.seed, s.gps, s.foundAfterS, s.medianErrM, s.p90ErrM, s.endErrM, s.falseFoundSteps, s.foundSteps, s.measuredSteps, s.approxMedianErrM, s.approxSteps)
                .joinToString(",") { v -> when (v) { null -> ""; is Double -> "%.2f".format(Locale.ROOT, v); else -> v.toString() } }
        )
    }

    /** One line per configuration: share of segments found, median time to FOUND, median error after it, false FOUND share. */
    fun summary(segs: List<Segment>): String {
        fun line(label: String, ss: List<Segment>): String {
            if (ss.isEmpty()) return "$label: —"
            val found = ss.filter { it.foundAfterS != null }
            val tf = quantile(found.mapNotNull { it.foundAfterS }, 0.5)
            val med = quantile(found.mapNotNull { it.medianErrM }, 0.5)
            val p90 = quantile(found.mapNotNull { it.p90ErrM }, 0.5)
            val ff = ss.sumOf { it.falseFoundSteps }.toDouble() / ss.sumOf { it.foundSteps }.coerceAtLeast(1)
            val approx = quantile(ss.mapNotNull { it.approxMedianErrM }, 0.5)
            val endE = quantile(ss.mapNotNull { it.endErrM }, 0.5)
            return "%s: found in %d/%d windows · median time to FOUND %s · error after FOUND median %s, p90 %s · false FOUND %.0f %% of FOUND steps · error while ≥ APPROXIMATE %s · at window end %s".format(
                Locale.ROOT, label, found.size, ss.size, tf?.let { "%.0f s".format(Locale.ROOT, it) } ?: "—",
                med?.let { "%.1f m".format(Locale.ROOT, it) } ?: "—", p90?.let { "%.1f m".format(Locale.ROOT, it) } ?: "—", ff * 100,
                approx?.let { "%.1f m".format(Locale.ROOT, it) } ?: "—", endE?.let { "%.1f m".format(Locale.ROOT, it) } ?: "—"
            )
        }
        return line("with GPS", segs.filter { it.gps }) + "\n" + line("without GPS", segs.filter { !it.gps })
    }

    /** What the app believed about the carry, next to the walker's labels (±5 s around each label). */
    fun carryReport(rec: Recording, full: ReplayResult): String = buildString {
        val t0 = rec.header.startedNs
        fun s(t: Long) = "%6.1f s".format(Locale.ROOT, (t - t0) / 1e9)
        appendLine("Carry as detected (replay):")
        for ((t, mode, transition) in full.carryTimeline) appendLine("  ${s(t)}  $mode${if (transition) " (changing hold)" else ""}")
        appendLine("Hold changes committed: " + full.holdChangeTimes.joinToString(" ") { s(it).trim() }.ifEmpty { "none" })
        val labels = rec.events.filterIsInstance<RecEvent.Note>().filter { it.name == "label" }
        if (labels.isNotEmpty()) {
            appendLine("Walker's labels and the nearest committed hold change:")
            for (l in labels) {
                val near = full.holdChangeTimes.minByOrNull { kotlin.math.abs(it - l.tNs) }
                val dt = near?.let { (it - l.tNs) / 1e9 }
                appendLine("  ${s(l.tNs)}  ${l.detail.padEnd(18)} " + (dt?.let { "hold change %+.1f s away".format(Locale.ROOT, it) } ?: "no hold change"))
            }
        }
        val live = rec.events.filterIsInstance<RecEvent.Note>().filter { it.name == "carry" }
        if (live.isNotEmpty()) appendLine("The phone itself logged ${live.size} carry changes (notes \"carry\").")
    }

    fun stepReport(rec: Recording, full: ReplayResult): String {
        val detector = rec.events.count { it is RecEvent.Sample && it.pipeline && it.type == SensorRouter.TYPE_STEP_DETECTOR }
        val counter = rec.events.filterIsInstance<RecEvent.Sample>().filter { it.pipeline && it.type == SensorRouter.TYPE_STEP_COUNTER }
        val counted = if (counter.size >= 2) (counter.last().values[0] - counter.first().values[0]).toInt() else null
        return "Steps: detector $detector · counter ${counted ?: "—"} · recovered ${full.recoveredSteps} · dropped by the PDR ${full.droppedSteps} · accepted ${full.steps.lastOrNull()?.step ?: 0}"
    }

    /** Filter settings tried by the sweep (name → params). */
    val SWEEP: List<Pair<String, LocParams>> = listOf(
        "default" to LocParams(),
        "walls softer (0.05)" to LocParams(wallFactor = 0.05),
        "walls harder (0.005)" to LocParams(wallFactor = 0.005),
        "corridor pull ×2 (beta 0.4)" to LocParams(beta = 0.4),
        "corridor pull off (beta 0)" to LocParams(beta = 0.0),
        "heading noise 5°" to LocParams(headingNoiseRad = Math.toRadians(5.0)),
        "heading noise 2°" to LocParams(headingNoiseRad = Math.toRadians(2.0)),
        "stride noise 10 %" to LocParams(lengthNoise = 0.10)
    )

    /** Writes analysis.txt, segments.csv and walk-full.svg into [out]; returns the text. */
    fun write(name: String, rec: Recording, out: File, sweep: Boolean): String {
        val site = IndoorSite.byId(rec.header.siteId)
        val full = full(rec, site)
        val truth = full.tracedWalk
        val text = buildString {
            appendLine("# $name — analysis")
            val dur = (rec.endNs - rec.header.startedNs) / 1e9
            appendLine("duration %.0f s · traced walk (pseudo truth) covers %s".format(Locale.ROOT, dur,
                if (truth.isEmpty()) "nothing (never found)" else "%.0f–%.0f s".format(Locale.ROOT, (truth.first().first - rec.header.startedNs) / 1e9, (truth.last().first - rec.header.startedNs) / 1e9)))
            if (full.markers.isNotEmpty()) {
                val errs = full.markers.mapNotNull { m -> truthAt(truth, m.marker.tNs)?.distanceTo(PointM(m.marker.eastM, m.marker.northM)) }
                appendLine("pseudo truth vs markers: ${errs.size}/${full.markers.size} covered, median ${quantile(errs, 0.5)?.let { "%.1f m".format(Locale.ROOT, it) } ?: "—"}, max ${errs.maxOrNull()?.let { "%.1f m".format(Locale.ROOT, it) } ?: "—"}")
            }
            appendLine(stepReport(rec, full))
            appendLine()
            if (truth.isNotEmpty()) {
                val segs = segments(rec, site, truth)
                out.mkdirs()
                File(out, "segments.csv").writeText(segmentsCsv(segs))
                appendLine("Cold starts on ${segs.size / 6} windows of 90 s (every 45 s), 3 seeds, measured against the pseudo truth:")
                appendLine(summary(segs))
                if (sweep) {
                    appendLine()
                    appendLine("Sweep (with GPS, seeds 1–2):")
                    for ((label, p) in SWEEP) {
                        val ss = segments(rec, site, truth, seeds = 1..2, params = p, withoutGps = false)
                        appendLine("  " + summary(ss).lineSequence().first().replaceFirst("with GPS", label))
                    }
                }
            } else {
                appendLine("No pseudo truth: the whole walk was never FOUND. Only the markers can measure it (see report.txt).")
            }
            appendLine()
            append(carryReport(rec, full))
        }
        out.mkdirs()
        File(out, "analysis.txt").writeText(text)
        ReplayOutput.picture(rec, full, File(out, "walk-full.svg"))
        return text
    }
}
