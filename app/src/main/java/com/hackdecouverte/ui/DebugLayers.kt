package com.hackdecouverte.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.hackdecouverte.loc.LocParticle
import com.hackdecouverte.loc.LocZone
import com.hackdecouverte.map.IndoorMap
import com.hackdecouverte.map.OccupancyGrid
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.sensor.GpsFix
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What the debug map can show; each one is a step of the localization computation. */
enum class DebugLayer(val label: String) {
    GRID("Grille murs"),
    DIRECTIONS("Directions"),
    DOORS("Portes"),
    PARTICLES("Points"),
    ZONES("Zones"),
    GPS("GPS"),
    TRACE("Trajet retracé"),
    PDR("PDR brut");

    companion object {
        val defaults: Set<DebugLayer> = entries.toSet()
    }
}

private val Red = Color(0xFFE53935)
private val Yellow = Color(0xFFFDD835)
private val Green = Color(0xFF43A047)
private val Teal = Color(0xFF00897B)
private val Orange = Color(0xFFFB8C00)
private val GpsBlue = Color(0xFF1A73E8)

/**
 * Confidence score of a hypothesis → red (≤ 0.4: kept walking into walls) → yellow (0.7: walking
 * across corridors, or injected without a past) → green (1: following the corridor).
 */
fun confidenceColor(score: Double): Color {
    val t = ((score - 0.4) / 0.6).coerceIn(0.0, 1.0).toFloat()
    return if (t < 0.5f) lerp(Red, Yellow, t * 2) else lerp(Yellow, Green, (t - 0.5f) * 2)
}

/** Occupancy grid as the filter sees it, one pixel per cell, row 0 = north (top). */
fun renderOccupancy(map: IndoorMap): Bitmap {
    val g = map.grid
    val wall = Color(0x99B71C1C).toArgb()
    val outside = Color(0x33000000).toArgb()
    val passable = Color(0x80FB8C00).toArgb()
    val pixels = IntArray(g.width * g.height)
    for (r in 0 until g.height) {
        val row = (g.height - 1 - r) * g.width
        for (c in 0 until g.width) {
            pixels[row + c] = when (g.cells[r * g.width + c]) {
                OccupancyGrid.WALL -> wall
                OccupancyGrid.OUTSIDE -> outside
                OccupancyGrid.PASSABLE -> passable
                else -> 0
            }
        }
    }
    return Bitmap.createBitmap(pixels, g.width, g.height, Bitmap.Config.ARGB_8888)
}

/** Screen corners (top-left, top-right, bottom-left) of the occupancy bitmap, for setPolyToPoly. */
fun occupancyCorners(map: IndoorMap, toScreen: (PointM) -> Offset): FloatArray {
    val g = map.grid
    val maxE = g.minEastM + g.width * g.resM
    val maxN = g.minNorthM + g.height * g.resM
    val tl = toScreen(PointM(g.minEastM, maxN)); val tr = toScreen(PointM(maxE, maxN)); val bl = toScreen(PointM(g.minEastM, g.minNorthM))
    return floatArrayOf(tl.x, tl.y, tr.x, tr.y, bl.x, bl.y)
}

/** Corridor direction map: a short segment along the axis every [stepM], opacity = coherence. */
internal fun DrawScope.drawDirectionField(map: IndoorMap, view: BoundsM, toScreen: (PointM) -> Offset, density: Float, stepM: Double = 1.5) {
    val g = map.grid
    val x0 = maxOf(view.minX, g.minEastM); val x1 = minOf(view.maxX, g.minEastM + g.width * g.resM)
    val y0 = maxOf(view.minY, g.minNorthM); val y1 = minOf(view.maxY, g.minNorthM + g.height * g.resM)
    var y = y0 + stepM / 2
    while (y < y1) {
        var x = x0 + stepM / 2
        while (x < x1) {
            val p = PointM(x, y)
            if (!g.isBlocked(p)) {
                val c = map.directions.coherenceAt(p)
                if (c >= 0.15) {
                    val a = map.directions.axisAt(p)
                    val h = 0.55
                    drawLine(
                        Teal.copy(alpha = (0.2 + 0.7 * c).toFloat().coerceAtMost(1f)),
                        toScreen(PointM(x - h * sin(a), y - h * cos(a))),
                        toScreen(PointM(x + h * sin(a), y + h * cos(a))),
                        strokeWidth = 1.6f * density
                    )
                }
            }
            x += stepM
        }
        y += stepM
    }
}

/** Door openings carved in the grid (graph room nodes + overlay doors). */
internal fun DrawScope.drawDoors(map: IndoorMap, toScreen: (PointM) -> Offset, pxPerM: Float, density: Float) {
    for (d in map.doors) {
        val c = toScreen(d.centerM)
        drawCircle(Orange.copy(alpha = 0.85f), radius = (d.widthM / 2 * pxPerM).toFloat().coerceAtLeast(3f * density), center = c, style = Stroke(1.5f * density))
        drawCircle(Orange, radius = 1.8f * density, center = c)
    }
}

/**
 * Hypotheses: colour = confidence score, size = weight relative to the heaviest, and when zoomed in
 * a tick showing the walking direction each one assumes.
 */
internal fun DrawScope.drawParticles(particles: List<LocParticle>, toScreen: (PointM) -> Offset, pxPerM: Float, density: Float) {
    if (particles.isEmpty()) return
    val maxW = particles.maxOf { it.weight }.takeIf { it > 0 } ?: 1.0
    val ticks = pxPerM > 25f
    for (p in particles) {
        val c = toScreen(p.positionM)
        val color = confidenceColor(p.score)
        if (ticks) {
            val tip = toScreen(PointM(p.positionM.x + 0.45 * sin(p.headingRad), p.positionM.y + 0.45 * cos(p.headingRad)))
            drawLine(color.copy(alpha = 0.6f), c, tip, strokeWidth = 1f * density)
        }
        drawCircle(color.copy(alpha = 0.85f), radius = (1.5 + 2.5 * sqrt(p.weight / maxW)).toFloat() * density, center = c)
    }
}

/** Zones: circle per cluster, main one stronger, with its share of the weight and radius. */
internal fun DrawScope.drawZones(zones: List<LocZone>, toScreen: (PointM) -> Offset, pxPerM: Float, density: Float) {
    val violet = Color(0xFF7C3AED)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = violet.toArgb(); textSize = 11f * density; isFakeBoldText = true }
    zones.take(4).forEachIndexed { i, z ->
        val c = toScreen(z.centerM)
        val r = (z.radiusM * pxPerM).toFloat().coerceAtLeast(6f * density)
        drawCircle(violet.copy(alpha = if (i == 0) 0.12f else 0.05f), radius = r, center = c)
        drawCircle(violet.copy(alpha = if (i == 0) 0.9f else 0.45f), radius = r, center = c, style = Stroke((if (i == 0) 2f else 1.2f) * density))
        drawContext.canvas.nativeCanvas.drawText(
            "#${i + 1} ${(z.weight * 100).roundToInt()} % · r ${"%.1f".format(z.radiusM)} m",
            c.x + r * 0.72f, c.y - r * 0.72f, text
        )
    }
}

/** Raw GPS fix (dot + accuracy circle, any accuracy) and the search disk the filter used (dashed). */
internal fun DrawScope.drawGps(fix: GpsFix?, disk: Pair<PointM, Double>?, toScreen: (PointM) -> Offset, pxPerM: Float, density: Float) {
    disk?.let { (center, radius) ->
        drawCircle(
            GpsBlue.copy(alpha = 0.7f), radius = (radius * pxPerM).toFloat(), center = toScreen(center),
            style = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 6f * density)))
        )
    }
    fix ?: return
    val c = toScreen(fix.eastNorthM)
    val r = (fix.accuracyM * pxPerM).toFloat()
    drawCircle(GpsBlue.copy(alpha = 0.07f), radius = r, center = c)
    drawCircle(GpsBlue.copy(alpha = 0.5f), radius = r, center = c, style = Stroke(1f * density))
    drawCircle(Color.White, radius = 5.5f * density, center = c)
    drawCircle(GpsBlue, radius = 4f * density, center = c)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GpsBlue.toArgb(); textSize = 11f * density; isFakeBoldText = true }
    drawContext.canvas.nativeCanvas.drawText("GPS ±${fix.accuracyM.roundToInt()} m (${fix.provider})", c.x + 8f * density, c.y - 8f * density, text)
}

/** Walk recorder ground truth: a black numbered cross where the walker tapped "I am here". */
internal fun DrawScope.drawRecordingMarkers(markers: List<PointM>, toScreen: (PointM) -> Offset, density: Float) {
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 12f * density; isFakeBoldText = true }
    val a = 6f * density
    markers.forEachIndexed { i, m ->
        val c = toScreen(m)
        drawCircle(Color.White.copy(alpha = 0.8f), radius = a + 2f * density, center = c)
        drawLine(Color.Black, Offset(c.x - a, c.y - a), Offset(c.x + a, c.y + a), strokeWidth = 2.5f * density)
        drawLine(Color.Black, Offset(c.x - a, c.y + a), Offset(c.x + a, c.y - a), strokeWidth = 2.5f * density)
        drawContext.canvas.nativeCanvas.drawText("M${i + 1}", c.x + a + 3f * density, c.y - a, text)
    }
}
