package com.hackdecouverte.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import com.hackdecouverte.map.FloorVectorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object FloorVectorRenderer {
    private const val PX_PER_METER = 20.0

    // Holds all 7 MB floors simultaneously (~8.5 MB each => ~60 MB max) for 0 ms floor switching
    private val cache = object : LruCache<String, Bitmap>(8) {}

    fun getCached(floorId: String): Bitmap? = synchronized(cache) {
        cache.get(floorId)
    }

    suspend fun getOrRender(plan: FloorVectorPlan): Bitmap = withContext(Dispatchers.Default) {
        synchronized(cache) {
            cache.get(plan.floorId)?.let { return@withContext it }
        }
        val rendered = renderBitmap(plan)
        synchronized(cache) {
            cache.put(plan.floorId, rendered)
        }
        rendered
    }

    private fun renderBitmap(plan: FloorVectorPlan): Bitmap {
        val bounds = plan.viewBoundsM
        val widthPx = (bounds.widthM * PX_PER_METER).toInt().coerceIn(256, 2800)
        val heightPx = (bounds.heightM * PX_PER_METER).toInt().coerceIn(256, 2800)
        val scaleX = widthPx / bounds.widthM
        val scaleY = heightPx / bounds.heightM

        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val path = Path().apply {
            fillType = Path.FillType.WINDING
        }
        val xy = plan.packedEastNorthM
        val starts = plan.contourStartPairIndex
        val counts = plan.contourPointCounts
        val closed = plan.contourClosed

        for (c in 0 until plan.contourCount) {
            val pairStart = starts[c]
            val nPts = counts[c]
            if (nPts < 2) continue
            val i0 = pairStart * 2
            val x0 = ((xy[i0] - bounds.minEastM) * scaleX).toFloat()
            val y0 = ((bounds.maxNorthM - xy[i0 + 1]) * scaleY).toFloat()
            path.moveTo(x0, y0)
            for (k in 1 until nPts) {
                val ik = (pairStart + k) * 2
                val xk = ((xy[ik] - bounds.minEastM) * scaleX).toFloat()
                val yk = ((bounds.maxNorthM - xy[ik + 1]) * scaleY).toFloat()
                path.lineTo(xk, yk)
            }
            if (closed[c]) path.close()
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.rgb(112, 117, 122)
        }
        canvas.drawPath(path, paint)
        return bmp
    }
}
