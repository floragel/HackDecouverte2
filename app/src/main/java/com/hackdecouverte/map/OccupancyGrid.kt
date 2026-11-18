package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * Floor occupancy grid in the shared metric frame (east, north). Cell `(row, col)` lives at
 * `row * width + col`; row 0 is the southern edge (no image flip). Pure Kotlin.
 */
class OccupancyGrid(
    val minEastM: Double,
    val minNorthM: Double,
    val resM: Double,
    val width: Int,
    val height: Int,
    val cells: ByteArray
) {
    init {
        require(cells.size == width * height) { "cells must be width × height" }
    }

    /** Cell index of [p], or −1 outside the grid. */
    fun indexOf(p: PointM): Int {
        val col = floor((p.x - minEastM) / resM).toInt()
        val row = floor((p.y - minNorthM) / resM).toInt()
        return if (col in 0 until width && row in 0 until height) row * width + col else -1
    }

    fun centerOf(index: Int): PointM = PointM(
        minEastM + (index % width + 0.5) * resM,
        minNorthM + (index / width + 0.5) * resM
    )

    fun cellAt(p: PointM): Byte {
        val i = indexOf(p)
        return if (i < 0) OUTSIDE else cells[i]
    }

    fun isBlocked(p: PointM): Boolean = isBlockedValue(cellAt(p))

    /** True if any sample along `a→b` (every `resM / 2`) is blocked. */
    fun segmentBlocked(a: PointM, b: PointM): Boolean {
        val len = hypot(b.x - a.x, b.y - a.y)
        val steps = max(1, ceil(len / (resM / 2)).toInt())
        for (k in 0..steps) {
            val t = k.toDouble() / steps
            if (isBlocked(PointM(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))) return true
        }
        return false
    }

    companion object {
        const val FREE: Byte = 0
        const val WALL: Byte = 1
        const val PASSABLE: Byte = 2
        const val OUTSIDE: Byte = 3

        fun isBlockedValue(v: Byte): Boolean = v == WALL || v == OUTSIDE

        /**
         * Nonzero-winding fill of all [polygons] together, tested at each cell centre (holes
         * drawn in the opposite direction stay empty).
         */
        fun rasterize(
            polygons: List<List<PointM>>,
            minEastM: Double,
            minNorthM: Double,
            resM: Double,
            width: Int,
            height: Int
        ): BooleanArray {
            val out = BooleanArray(width * height)
            val xs = ArrayList<Double>()
            val dirs = ArrayList<Int>()
            for (row in 0 until height) {
                val y = minNorthM + (row + 0.5) * resM
                xs.clear(); dirs.clear()
                for (poly in polygons) {
                    val n = poly.size
                    if (n < 3) continue
                    for (k in 0 until n) {
                        val p = poly[k]
                        val q = poly[(k + 1) % n]
                        if (p.y == q.y) continue
                        val up = q.y > p.y
                        val lo = if (up) p else q
                        val hi = if (up) q else p
                        if (y < lo.y || y >= hi.y) continue
                        xs.add(lo.x + (y - lo.y) / (hi.y - lo.y) * (hi.x - lo.x))
                        dirs.add(if (up) 1 else -1)
                    }
                }
                if (xs.isEmpty()) continue
                val order = xs.indices.sortedBy { xs[it] }
                var winding = 0
                for (j in 0 until order.size - 1) {
                    winding += dirs[order[j]]
                    if (winding == 0) continue
                    val x0 = xs[order[j]]
                    val x1 = xs[order[j + 1]]
                    val c0 = max(0, ceil((x0 - minEastM) / resM - 0.5).toInt())
                    val c1 = minOf(width - 1, ceil((x1 - minEastM) / resM - 0.5).toInt() - 1)
                    for (col in c0..c1) out[row * width + col] = true
                }
            }
            return out
        }
    }
}
