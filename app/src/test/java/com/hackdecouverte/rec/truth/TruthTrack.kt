package com.hackdecouverte.rec.truth

import com.hackdecouverte.pdr.PointM
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Route point [pointIndex] was reached [tS] seconds after the recording started. */
data class Checkpoint(val pointIndex: Int, val tS: Double)

/** What happens at route point [pointIndex]: porte, demi-tour, salle, escalier, pivot. */
data class RouteLabel(val pointIndex: Int, val label: String)

/** The route the walker traced on the plan (tools/trace-<floor>.html), with optional times and labels. */
data class TruthRoute(val floorId: String, val path: List<PointM>, val checkpoints: List<Checkpoint>, val labels: List<RouteLabel>)

/** Where the walker really was at [tNs] (plan metres) and how far along the route ([arcM]). */
data class TruthPoint(val tNs: Long, val posM: PointM, val arcM: Double)

/**
 * Ground truth per step: the traced route is put back on the clock by matching the PDR steps to it
 * in order (DTW), after the best rotation and the length-ratio scale; checkpoints forbid matches
 * that disagree with a known time. Format v1 (`{"floor","path"}`) and v2 (+ checkpoints, labels).
 */
object TruthTrack {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(walk: File): TruthRoute? {
        val f = File(walk.path.removeSuffix(".txt.gz") + ".truth.json").takeIf { it.isFile } ?: return null
        return parse(f.readText())
    }

    fun parse(text: String): TruthRoute {
        val o = json.parseToJsonElement(text.trim().removePrefix("TRUTH").removePrefix("TRAJET").trim().let { s ->
            // A pasted v1 line "TRAJET STAN-1 [[…]]" → wrap it.
            if (s.startsWith("STAN") || s.startsWith("MB")) """{"floor":"${s.substringBefore(' ')}","path":${s.substringAfter(' ')}}""" else s
        }).jsonObject
        val path = o["path"]!!.jsonArray.map { p -> p.jsonArray.let { PointM(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }
        val cps = (o["checkpoints"]?.jsonArray ?: emptyList()).map { (it as JsonObject).let { c -> Checkpoint(c["i"]!!.jsonPrimitive.int, c["tS"]!!.jsonPrimitive.double) } }
        val labels = (o["labels"]?.jsonArray ?: emptyList()).map { (it as JsonObject).let { c -> RouteLabel(c["i"]!!.jsonPrimitive.int, c["label"]!!.jsonPrimitive.contentOrNull.orEmpty()) } }
        return TruthRoute(o["floor"]?.jsonPrimitive?.contentOrNull ?: "", path, cps, labels)
    }

    private fun length(p: List<PointM>) = p.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }

    private class Resampled(val pts: List<PointM>, val arc: DoubleArray, val arcOfPoint: DoubleArray)

    private fun resample(path: List<PointM>, step: Double): Resampled {
        val pts = ArrayList<PointM>()
        val arc = ArrayList<Double>()
        val arcOfPoint = DoubleArray(path.size)
        var s = 0.0
        for ((i, pair) in path.zipWithNext().withIndex()) {
            val (a, b) = pair
            arcOfPoint[i] = s
            val d = a.distanceTo(b)
            val n = maxOf(1, (d / step).toInt())
            for (k in 0 until n) {
                pts += PointM(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n)
                arc += s + d * k / n
            }
            s += d
        }
        arcOfPoint[path.size - 1] = s
        pts += path.last(); arc += s
        return Resampled(pts, arc.toDoubleArray(), arcOfPoint)
    }

    private fun place(steps: List<PointM>, origin: PointM, rotRad: Double, k: Double) =
        steps.map { p -> PointM(origin.x + k * (p.x * cos(rotRad) + p.y * sin(rotRad)), origin.y + k * (-p.x * sin(rotRad) + p.y * cos(rotRad))) }

    /** Order-aware matching; [forbidden](k, j) cells cost infinity. Returns (mean cost, matched j list per k) when [keepPath]. */
    private fun dtw(a: List<PointM>, b: List<PointM>, forbidden: ((Int, Int) -> Boolean)?, keepPath: Boolean): Pair<Double, List<List<Int>>?> {
        val n = a.size
        val m = b.size
        val inf = Double.MAX_VALUE / 4
        val cost = if (keepPath) Array(n) { DoubleArray(m) } else null
        var prev = DoubleArray(m) { inf }
        for (i in 0 until n) {
            val cur = DoubleArray(m) { inf }
            for (j in 0 until m) {
                if (forbidden?.invoke(i, j) == true) continue
                val c = hypot(a[i].x - b[j].x, a[i].y - b[j].y)
                val best = when {
                    i == 0 && j == 0 -> 0.0
                    i == 0 -> cur[j - 1]
                    j == 0 -> prev[j]
                    else -> minOf(prev[j], cur[j - 1], prev[j - 1])
                }
                if (best < inf) cur[j] = c + best
            }
            cost?.let { System.arraycopy(cur, 0, it[i], 0, m) }
            prev = cur
        }
        val total = prev[m - 1]
        if (!keepPath || total >= inf) return total / (n + m) to null
        val matched = List(n) { ArrayList<Int>() }
        var i = n - 1
        var j = m - 1
        while (true) {
            matched[i] += j
            if (i == 0 && j == 0) break
            val c = cost!!
            val up = if (i > 0) c[i - 1][j] else inf
            val left = if (j > 0) c[i][j - 1] else inf
            val diag = if (i > 0 && j > 0) c[i - 1][j - 1] else inf
            when (minOf(up, left, diag)) {
                diag -> { i--; j-- }
                up -> i--
                else -> j--
            }
        }
        return total / (n + m) to matched
    }

    /** Puts the route on the clock: one [TruthPoint] per step of [steps] (time, PDR position), oldest first. */
    fun align(route: TruthRoute, steps: List<Pair<Long, PointM>>, startNs: Long): List<TruthPoint> {
        if (steps.isEmpty() || route.path.size < 2) return emptyList()
        val pdr = listOf(PointM(0.0, 0.0)) + steps.map { it.second }
        val times = listOf(startNs) + steps.map { it.first }
        val k = length(route.path) / length(pdr).coerceAtLeast(1e-6)
        val coarse = resample(route.path, 0.5).pts
        var bestRot = 0.0
        var bestCost = Double.MAX_VALUE
        fun tryRot(deg: Double) {
            val c = dtw(place(pdr, route.path[0], Math.toRadians(deg), k), coarse, null, false).first
            if (c < bestCost) { bestCost = c; bestRot = deg }
        }
        for (deg in 0 until 360 step 3) tryRot(deg.toDouble())
        val r0 = bestRot
        for (d in -2..2) tryRot(r0 + d)
        val fine = resample(route.path, 0.25)
        val placed = place(pdr, route.path[0], Math.toRadians(bestRot), k)
        val cpConstraints = route.checkpoints.filter { it.pointIndex in route.path.indices }.map { (startNs + (it.tS * 1e9).toLong()) to fine.arcOfPoint[it.pointIndex] }
        val forbidden: ((Int, Int) -> Boolean)? = if (cpConstraints.isEmpty()) null else { kk, jj ->
            cpConstraints.any { (t, s) -> abs(times[kk] - t) <= 2_000_000_000L && abs(fine.arc[jj] - s) > 1.5 }
        }
        val matched = dtw(placed, fine.pts, forbidden, true).second
            ?: dtw(placed, fine.pts, null, true).second!! // contradictory checkpoints: fall back to shape only
        var maxArc = 0.0
        return times.indices.drop(1).map { kk ->
            val js = matched[kk].sorted()
            var j = js[js.size / 2]
            if (fine.arc[j] < maxArc) j = fine.arc.indexOfFirst { it >= maxArc }.coerceAtLeast(0)
            maxArc = fine.arc[j]
            TruthPoint(times[kk], fine.pts[j], fine.arc[j])
        }
    }

    /** Truth at [tNs], interpolated between steps; null outside the track or across a gap of more than 3 s. */
    fun positionAt(track: List<TruthPoint>, tNs: Long): PointM? {
        if (track.isEmpty() || tNs < track.first().tNs || tNs > track.last().tNs) return null
        var lo = 0
        var hi = track.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (track[mid].tNs <= tNs) lo = mid else hi = mid
        }
        val a = track[lo]
        val b = track[hi]
        if (b.tNs - a.tNs > 3_000_000_000L) return null
        val f = if (b.tNs == a.tNs) 0.0 else (tNs - a.tNs).toDouble() / (b.tNs - a.tNs)
        return PointM(a.posM.x + (b.posM.x - a.posM.x) * f, a.posM.y + (b.posM.y - a.posM.y) * f)
    }
}
