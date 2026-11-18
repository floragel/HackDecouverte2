package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import kotlin.math.sqrt

/** Distance (metres) from each cell centre to the nearest blocked cell; 0 on blocked cells. */
class DistanceField(val grid: OccupancyGrid, val clearanceM: FloatArray) {

    fun at(p: PointM): Double {
        val i = grid.indexOf(p)
        return if (i < 0) 0.0 else clearanceM[i].toDouble()
    }

    companion object {
        private const val INF = 1e20

        /** Exact Euclidean distance transform (Felzenszwalb–Huttenlocher), columns then rows. */
        fun compute(
            grid: OccupancyGrid,
            blocked: (Byte) -> Boolean = { OccupancyGrid.isBlockedValue(it) }
        ): DistanceField {
            val w = grid.width
            val h = grid.height
            val f = DoubleArray(w * h) { if (blocked(grid.cells[it])) 0.0 else INF }
            val m = maxOf(w, h)
            val input = DoubleArray(m)
            val output = DoubleArray(m)
            val v = IntArray(m)
            val z = DoubleArray(m + 1)
            fun pass(n: Int) {
                var k = 0
                v[0] = 0; z[0] = -INF; z[1] = INF
                for (q in 1 until n) {
                    var s: Double
                    while (true) {
                        val p = v[k]
                        s = ((input[q] + q.toDouble() * q) - (input[p] + p.toDouble() * p)) / (2.0 * q - 2.0 * p)
                        if (s <= z[k] && k > 0) k-- else break
                    }
                    if (s <= z[k]) {
                        // k == 0 and the new parabola dominates everywhere
                        v[0] = q; z[0] = -INF; z[1] = INF
                        continue
                    }
                    k++
                    v[k] = q; z[k] = s; z[k + 1] = INF
                }
                k = 0
                for (q in 0 until n) {
                    while (z[k + 1] < q) k++
                    val d = (q - v[k]).toDouble()
                    output[q] = d * d + input[v[k]]
                }
            }
            for (c in 0 until w) {
                for (r in 0 until h) input[r] = f[r * w + c]
                pass(h)
                for (r in 0 until h) f[r * w + c] = output[r]
            }
            for (r in 0 until h) {
                for (c in 0 until w) input[c] = f[r * w + c]
                pass(w)
                for (c in 0 until w) f[r * w + c] = output[c]
            }
            val res = grid.resM
            return DistanceField(grid, FloatArray(w * h) { (sqrt(f[it]) * res).toFloat() })
        }
    }
}
