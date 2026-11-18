package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Axial difference between two directions, in [0, π/2] (north and south count as the same axis). */
fun axialDiff(a: Double, b: Double): Double {
    var d = abs(a - b) % PI
    if (d > PI / 2) d = PI - d
    return d
}

/**
 * Local corridor axis (compass heading modulo π, in [0, π)) and how marked it is (0…1), derived
 * from the walls only: structure tensor of the clearance gradient, averaged over ±[windowCells].
 * Coherence fades to 0 near doors, where walking across the corridor is normal.
 */
class DirectionField(val grid: OccupancyGrid, val axisRad: FloatArray, val coherence: FloatArray) {

    fun axisAt(p: PointM): Double {
        val i = grid.indexOf(p)
        return if (i < 0) 0.0 else axisRad[i].toDouble()
    }

    fun coherenceAt(p: PointM): Double {
        val i = grid.indexOf(p)
        return if (i < 0 || OccupancyGrid.isBlockedValue(grid.cells[i])) 0.0 else coherence[i].toDouble()
    }

    companion object {
        fun compute(
            grid: OccupancyGrid,
            clearance: DistanceField,
            doorCentersM: List<PointM>,
            windowCells: Int = 8,
            doorFadeM: Double = 1.5
        ): DirectionField {
            val w = grid.width
            val h = grid.height
            val d = clearance.clearanceM
            val jxx = DoubleArray(w * h)
            val jxy = DoubleArray(w * h)
            val jyy = DoubleArray(w * h)
            for (r in 1 until h - 1) for (c in 1 until w - 1) {
                val i = r * w + c
                if (OccupancyGrid.isBlockedValue(grid.cells[i])) continue
                val gx = (d[i + 1] - d[i - 1]) * 0.5
                val gy = (d[i + w] - d[i - w]) * 0.5 // row index grows northward
                jxx[i] = gx * gx; jxy[i] = gx * gy; jyy[i] = gy * gy
            }
            val sxx = boxSum(jxx, w, h, windowCells)
            val sxy = boxSum(jxy, w, h, windowCells)
            val syy = boxSum(jyy, w, h, windowCells)
            val axis = FloatArray(w * h)
            val coh = FloatArray(w * h)
            for (i in 0 until w * h) {
                val a = sxx[i]; val b = sxy[i]; val cc = syy[i]
                val tr = a + cc
                if (tr < 1e-9) continue
                coh[i] = (sqrt((a - cc) * (a - cc) + 4 * b * b) / tr).toFloat()
                val phi = 0.5 * atan2(2 * b, a - cc) // dominant gradient, math angle (x east, y north)
                val alpha = phi + PI / 2
                var heading = atan2(cos(alpha), sin(alpha)) % PI
                if (heading < 0) heading += PI
                axis[i] = heading.toFloat()
            }
            if (doorCentersM.isNotEmpty()) {
                val k = (doorFadeM / grid.resM).toInt() + 1
                for (door in doorCentersM) {
                    val i0 = grid.indexOf(door)
                    if (i0 < 0) continue
                    val r0 = i0 / w; val c0 = i0 % w
                    for (r in maxOf(0, r0 - k)..minOf(h - 1, r0 + k)) for (c in maxOf(0, c0 - k)..minOf(w - 1, c0 + k)) {
                        val i = r * w + c
                        val dist = grid.centerOf(i).distanceTo(door)
                        coh[i] = (coh[i] * min(1.0, dist / doorFadeM)).toFloat()
                    }
                }
            }
            return DirectionField(grid, axis, coh)
        }

        private fun boxSum(a: DoubleArray, w: Int, h: Int, rad: Int): DoubleArray {
            val s = DoubleArray((w + 1) * (h + 1))
            for (r in 0 until h) {
                var row = 0.0
                for (c in 0 until w) {
                    row += a[r * w + c]
                    s[(r + 1) * (w + 1) + c + 1] = s[r * (w + 1) + c + 1] + row
                }
            }
            val out = DoubleArray(w * h)
            for (r in 0 until h) for (c in 0 until w) {
                val r0 = maxOf(0, r - rad); val r1 = minOf(h, r + rad + 1)
                val c0 = maxOf(0, c - rad); val c1 = minOf(w, c + rad + 1)
                out[r * w + c] = s[r1 * (w + 1) + c1] - s[r0 * (w + 1) + c1] - s[r1 * (w + 1) + c0] + s[r0 * (w + 1) + c0]
            }
            return out
        }
    }
}
