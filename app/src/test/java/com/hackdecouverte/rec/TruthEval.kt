package com.hackdecouverte.rec

import com.hackdecouverte.pdr.PointM
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Ground truth traced by the walker (tools/trace-<floor>.html → `<walk>.truth.json` next to the
 * recording). A path is scored by order-aware matching (DTW) against it, after the best rotation and
 * scale (the PDR frame and stride are free): mean distance in metres.
 */
internal object TruthEval {
    fun load(walk: File): List<PointM>? {
        val f = File(walk.path.removeSuffix(".txt.gz") + ".truth.json").takeIf { it.isFile } ?: return null
        val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(f.readText().substringAfter("\"path\"").substringBefore("]]")).map { it.value.toDouble() }.toList()
        return nums.chunked(2).filter { it.size == 2 }.map { PointM(it[0], it[1]) }
    }

    private fun resample(t: List<PointM>, step: Double = 0.5): List<PointM> {
        val out = ArrayList<PointM>()
        for ((a, b) in t.zipWithNext()) {
            val n = maxOf(1, (a.distanceTo(b) / step).toInt())
            for (i in 0 until n) out += PointM(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
        }
        out += t.last()
        return out
    }

    private fun dtw(a: List<PointM>, b: List<PointM>): Double {
        var prev = DoubleArray(b.size + 1) { Double.MAX_VALUE }.also { it[0] = 0.0 }
        for (i in 1..a.size) {
            val cur = DoubleArray(b.size + 1) { Double.MAX_VALUE }
            for (j in 1..b.size) {
                val c = hypot(a[i - 1].x - b[j - 1].x, a[i - 1].y - b[j - 1].y)
                cur[j] = c + minOf(prev[j], cur[j - 1], prev[j - 1])
            }
            prev = cur
        }
        return prev[b.size] / (a.size + b.size)
    }

    /** (mean distance m, rotation °, scale) of the best fit of [pdr] (starting at 0,0) onto [truth]. */
    fun score(pdr: List<PointM>, truth: List<PointM>): Triple<Double, Int, Double> {
        val b = resample(truth)
        fun placed(rotDeg: Int, k: Double): List<PointM> {
            val r = Math.toRadians(rotDeg.toDouble())
            return pdr.map { p -> PointM(truth[0].x + k * (p.x * cos(r) + p.y * sin(r)), truth[0].y + k * (-p.x * sin(r) + p.y * cos(r))) }
        }
        // Scale fixed to the length ratio (a free scale lets distorted paths shrink or stretch onto the route).
        val k = truth.zipWithNext().sumOf { (p, q) -> p.distanceTo(q) } / pdr.zipWithNext().sumOf { (p, q) -> p.distanceTo(q) }.coerceAtLeast(1e-6)
        var best = Triple(Double.MAX_VALUE, 0, k)
        for (rot in 0 until 360 step 3) {
            val e = dtw(placed(rot, k), b)
            if (e < best.first) best = Triple(e, rot, k)
        }
        val r0 = best.second
        for (rot in r0 - 2..r0 + 2) {
            val e = dtw(placed(rot, k), b)
            if (e < best.first) best = Triple(e, rot, k)
        }
        return best
    }
}
