package com.hackdecouverte.loc

import com.hackdecouverte.pdr.PointM
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Groups particles into zones: connected [cellM] cells (8-neighbourhood) that hold weight. */
object ZoneClustering {

    fun zones(east: DoubleArray, north: DoubleArray, headings: DoubleArray, weights: DoubleArray, n: Int, cellM: Double = 3.0): List<LocZone> {
        if (n == 0) return emptyList()
        val cellOf = LongArray(n)
        val members = HashMap<Long, MutableList<Int>>()
        for (i in 0 until n) {
            val key = key(floor(east[i] / cellM).toInt(), floor(north[i] / cellM).toInt())
            cellOf[i] = key
            members.getOrPut(key) { ArrayList() }.add(i)
        }
        val seen = HashSet<Long>()
        val out = ArrayList<LocZone>()
        for (start in members.keys) {
            if (!seen.add(start)) continue
            val particles = ArrayList<Int>()
            val queue = ArrayDeque<Long>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst()
                particles.addAll(members.getValue(k))
                val cx = (k shr 32).toInt(); val cy = k.toInt()
                for (dx in -1..1) for (dy in -1..1) {
                    val nk = key(cx + dx, cy + dy)
                    if (members.containsKey(nk) && seen.add(nk)) queue.add(nk)
                }
            }
            out.add(zoneOf(particles, east, north, headings, weights))
        }
        return out.sortedByDescending { it.weight }
    }

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    private fun zoneOf(idx: List<Int>, east: DoubleArray, north: DoubleArray, headings: DoubleArray, weights: DoubleArray): LocZone {
        var ws = 0.0; var me = 0.0; var mn = 0.0; var s = 0.0; var c = 0.0
        for (i in idx) {
            ws += weights[i]; me += weights[i] * east[i]; mn += weights[i] * north[i]
            s += weights[i] * sin(headings[i]); c += weights[i] * cos(headings[i])
        }
        if (ws <= 0) return LocZone(PointM(east[idx[0]], north[idx[0]]), 0.0, 0.0, headings[idx[0]])
        me /= ws; mn /= ws
        var v = 0.0
        for (i in idx) v += weights[i] * ((east[i] - me) * (east[i] - me) + (north[i] - mn) * (north[i] - mn))
        return LocZone(PointM(me, mn), 2.45 * sqrt(v / ws / 2), ws, atan2(s, c))
    }
}
