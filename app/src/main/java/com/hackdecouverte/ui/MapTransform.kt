package com.hackdecouverte.ui

import com.hackdecouverte.pdr.PointM
import kotlin.math.cos
import kotlin.math.sin

data class BoundsM(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

class MapTransform(
    val focus: PointM,
    val panXPx: Double,
    val panYPx: Double,
    val pxPerMeter: Double,
    val rotationRad: Double,
    val centerXPx: Double,
    val centerYPx: Double
) {
    private val cosR = cos(rotationRad)
    private val sinR = sin(rotationRad)

    fun worldToScreen(p: PointM): PointM {
        val dx = (p.x - focus.x) * pxPerMeter
        val dy = -(p.y - focus.y) * pxPerMeter
        return PointM(
            centerXPx + panXPx + dx * cosR - dy * sinR,
            centerYPx + panYPx + dx * sinR + dy * cosR
        )
    }

    fun screenToWorld(s: PointM): PointM {
        val vx = s.x - centerXPx - panXPx
        val vy = s.y - centerYPx - panYPx
        val dx = vx * cosR + vy * sinR
        val dy = -vx * sinR + vy * cosR
        return PointM(focus.x + dx / pxPerMeter, focus.y - dy / pxPerMeter)
    }

    fun viewBoundsWorld(widthPx: Double, heightPx: Double, marginM: Double): BoundsM {
        val corners = arrayOf(
            screenToWorld(PointM(0.0, 0.0)),
            screenToWorld(PointM(widthPx, 0.0)),
            screenToWorld(PointM(0.0, heightPx)),
            screenToWorld(PointM(widthPx, heightPx))
        )
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (c in corners) {
            if (c.x < minX) minX = c.x
            if (c.y < minY) minY = c.y
            if (c.x > maxX) maxX = c.x
            if (c.y > maxY) maxY = c.y
        }
        return BoundsM(minX - marginM, minY - marginM, maxX + marginM, maxY + marginM)
    }
}
