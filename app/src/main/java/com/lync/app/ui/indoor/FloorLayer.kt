package com.lync.app.ui.indoor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import com.hackdecouverte.map.FloorVectorPlan
import com.hackdecouverte.pdr.PointM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * A floor plan ready for the Lync map:
 * - [walls]: ALPHA_8 bitmap (tinted at draw time, so light and dark mode share it),
 *   sharper than the test renderer (40 px/m instead of 24),
 * - small ink blobs removed (the upside-down room numbers and symbols photographed on the
 *   evacuation plan) — room numbers are redrawn crisp from the graph instead,
 * - [footprint]: the outer wall outline, filled as "inside the building".
 */
class FloorLayer(val floorId: String, val walls: Bitmap, val pxPerM: Double, val footprint: List<PointM>)

object FloorLayers {
    private const val PX_PER_M = 40.0
    private const val MAX_SIDE = 4096
    /** Ink shapes smaller than this (bounding box, metres) are text or symbols, not walls. */
    private const val MIN_SHAPE_M = 1.0

    private val cache = LruCache<String, FloorLayer>(2)

    suspend fun get(plan: FloorVectorPlan): FloorLayer = withContext(Dispatchers.Default) {
        synchronized(cache) { cache.get(plan.floorId) }?.let { return@withContext it }
        val layer = build(plan)
        synchronized(cache) { cache.put(plan.floorId, layer) }
        layer
    }

    private fun build(plan: FloorVectorPlan): FloorLayer {
        val b = plan.viewBoundsM
        val ppm = minOf(PX_PER_M, MAX_SIDE / b.widthM, MAX_SIDE / b.heightM)
        val w = (b.widthM * ppm).toInt().coerceAtLeast(1)
        val h = (b.heightM * ppm).toInt().coerceAtLeast(1)
        val xy = plan.packedEastNorthM
        val n = plan.contourCount

        // Signed area and bounding box of every contour (metres, north up)
        val area = DoubleArray(n)
        val box = Array(n) { DoubleArray(4) }
        for (c in 0 until n) {
            val s = plan.contourStartPairIndex[c]; val k = plan.contourPointCounts[c]
            var a = 0.0
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (i in 0 until k) {
                val x = xy[(s + i) * 2].toDouble(); val y = xy[(s + i) * 2 + 1].toDouble()
                val j = if (i == 0) k - 1 else i - 1
                a += xy[(s + j) * 2] * y - x * xy[(s + j) * 2 + 1]
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            area[c] = a / 2
            box[c][0] = minX; box[c][1] = minY; box[c][2] = maxX; box[c][3] = maxY
        }
        val biggest = (0 until n).maxByOrNull { abs(area[it]) } ?: return FloorLayer(plan.floorId, Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8), ppm, emptyList())
        val inkSign = if (area[biggest] > 0) 1.0 else -1.0
        val outers = (0 until n).filter { area[it] * inkSign > 0 }
        val keptOuter = outers.filter { max(box[it][2] - box[it][0], box[it][3] - box[it][1]) >= MIN_SHAPE_M }.toHashSet()

        fun inside(px: Double, py: Double, c: Int): Boolean {
            val s = plan.contourStartPairIndex[c]; val k = plan.contourPointCounts[c]
            var inside = false
            var j = k - 1
            for (i in 0 until k) {
                val xi = xy[(s + i) * 2]; val yi = xy[(s + i) * 2 + 1]
                val xj = xy[(s + j) * 2]; val yj = xy[(s + j) * 2 + 1]
                if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        // A hole is kept only if the shape it belongs to is kept (otherwise it would fill solid)
        val keep = BooleanArray(n)
        for (c in keptOuter) keep[c] = true
        for (c in 0 until n) {
            if (area[c] * inkSign > 0) continue
            val s = plan.contourStartPairIndex[c]
            val px = xy[s * 2].toDouble(); val py = xy[s * 2 + 1].toDouble()
            var parent = -1
            for (o in outers) {
                val bx = box[o]
                if (px < bx[0] || px > bx[2] || py < bx[1] || py > bx[3]) continue
                if ((parent < 0 || abs(area[o]) < abs(area[parent])) && inside(px, py, o)) parent = o
            }
            keep[c] = parent in keptOuter
        }

        val path = Path().apply { fillType = Path.FillType.WINDING }
        for (c in 0 until n) {
            if (!keep[c]) continue
            val s = plan.contourStartPairIndex[c]; val k = plan.contourPointCounts[c]
            if (k < 2) continue
            for (i in 0 until k) {
                val x = ((xy[(s + i) * 2] - b.minEastM) * ppm).toFloat()
                val y = ((b.maxNorthM - xy[(s + i) * 2 + 1]) * ppm).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (plan.contourClosed[c]) path.close()
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(bmp).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL })

        val s = plan.contourStartPairIndex[biggest]
        val footprint = List(plan.contourPointCounts[biggest]) { i -> PointM(xy[(s + i) * 2].toDouble(), xy[(s + i) * 2 + 1].toDouble()) }
        return FloorLayer(plan.floorId, bmp, ppm, footprint)
    }
}
