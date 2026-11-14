package com.hackdecouverte.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Google-Maps-like palette for the indoor map. */
internal object MapPalette {
    val land = Color(0xFFF1F3F4)
    val park = Color(0xFFCEEAD6)
    val road = Color.White
    val roadCasing = Color(0xFFDADCE0)
    val footway = Color(0xFFE8EAED)
    val building = Color(0xFFE8EAED)
    val buildingStroke = Color(0xFFDADCE0)
    val focusBuilding = Color.White
    val focusBuildingStroke = Color(0xFFBDC1C6)
    val routeFill = Color(0xFF4285F4)
    val routeCasing = Color(0xFF1967D2)
    val trailRecent = Color(0xFF8AB4F8)
    val trailOld = Color(0xFFD2E3FC)
    val userBlue = Color(0xFF1A73E8)
    val stairs = Color(0xFFE37400)
    val elevator = Color(0xFF8E24AA)
    val pin = Color(0xFFEA4335)
    val pinDark = Color(0xFFB31412)
    val labelText = android.graphics.Color.rgb(60, 64, 67)
}

/** Quadratic-midpoint smoothing of a polyline in screen space. */
internal fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 1 until points.size - 1) {
        val c = points[i]
        val n = points[i + 1]
        path.quadraticTo(c.x, c.y, (c.x + n.x) / 2f, (c.y + n.y) / 2f)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

/**
 * Walked path: a soft light-blue trail whose most recent part is brighter, so it reads as
 * "where I came from" without competing with the route line.
 */
internal fun DrawScope.drawWalkedTrail(points: List<Offset>, widthPx: Float) {
    if (points.size < 2) return
    val fadeCount = minOf(points.size - 1, TRAIL_FADE_SEGMENTS)
    val solidEnd = points.size - fadeCount
    val stroke = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (solidEnd >= 2) {
        drawPath(smoothPath(points.subList(0, solidEnd)), MapPalette.trailOld, style = stroke)
    }
    // Opaque colour ramp (no alpha) so overlapping round caps don't create dark beads
    for (i in (solidEnd - 1).coerceAtLeast(0) until points.size - 1) {
        val t = (i - solidEnd + 1).toFloat() / fadeCount.coerceAtLeast(1)
        val seg = Path().apply {
            moveTo(points[i].x, points[i].y)
            lineTo(points[i + 1].x, points[i + 1].y)
        }
        drawPath(seg, lerp(MapPalette.trailOld, MapPalette.trailRecent, t.coerceIn(0f, 1f)), style = stroke)
    }
}

/** Navigation route: dark-blue casing, blue fill and white direction chevrons. */
internal fun DrawScope.drawRouteLine(points: List<Offset>, density: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(
        path,
        MapPalette.routeCasing,
        style = Stroke(width = 9f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(
        path,
        MapPalette.routeFill,
        style = Stroke(width = 6f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    val spacing = 34f * density
    val half = 2.4f * density
    val chevronStroke = Stroke(width = 1.8f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    var carry = spacing * 0.6f
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val len = hypot(b.x - a.x, b.y - a.y)
        if (len < 1f) continue
        val ux = (b.x - a.x) / len
        val uy = (b.y - a.y) / len
        var d = carry
        while (d < len) {
            val cx = a.x + ux * d
            val cy = a.y + uy * d
            val tip = Offset(cx + ux * half, cy + uy * half)
            val px = -uy
            val py = ux
            val chevron = Path().apply {
                moveTo(cx - ux * half + px * half * 1.2f, cy - uy * half + py * half * 1.2f)
                lineTo(tip.x, tip.y)
                lineTo(cx - ux * half - px * half * 1.2f, cy - uy * half - py * half * 1.2f)
            }
            drawPath(chevron, Color.White, style = chevronStroke)
            d += spacing
        }
        carry = d - len
    }
}

/** Red teardrop destination pin with its tip on [tip]. */
internal fun DrawScope.drawDestinationPin(tip: Offset, density: Float, label: String?) {
    val r = 10f * density
    val center = Offset(tip.x, tip.y - 2.6f * r)
    drawOval(
        color = Color(0x33000000),
        topLeft = Offset(tip.x - 6f * density, tip.y - 2f * density),
        size = androidx.compose.ui.geometry.Size(12f * density, 4f * density)
    )
    val body = Path().apply {
        moveTo(tip.x, tip.y)
        val ang = Math.toRadians(32.0)
        val sx = (r * sin(ang)).toFloat()
        val sy = (r * cos(ang)).toFloat()
        lineTo(center.x - sx, center.y + sy)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(center, r),
            startAngleDegrees = 90f + 32f,
            sweepAngleDegrees = 360f - 64f,
            forceMoveTo = false
        )
        close()
    }
    drawPath(body, MapPalette.pin)
    drawPath(body, MapPalette.pinDark, style = Stroke(width = 1f * density))
    drawCircle(MapPalette.pinDark, radius = r * 0.38f, center = center)
    if (!label.isNullOrBlank()) {
        drawHaloText(label, tip.x, tip.y + 14f * density, 11.5f * density, MapPalette.labelText, bold = true)
    }
}

/** Google-style blue location dot with a pulsing halo and a heading beam. */
internal fun DrawScope.drawUserLocation(
    center: Offset,
    headingScreenRad: Double?,
    pulse: Float,
    density: Float,
    headingValid: Boolean
) {
    val haloR = (14f + 22f * pulse) * density
    drawCircle(MapPalette.userBlue.copy(alpha = 0.22f * (1f - pulse)), radius = haloR, center = center)

    if (headingScreenRad != null) {
        val beamLen = 58f * density
        val halfAngle = Math.toRadians(30.0)
        val beam = Path().apply {
            moveTo(center.x, center.y)
            val steps = 12
            for (k in 0..steps) {
                val a = headingScreenRad - halfAngle + 2 * halfAngle * k / steps
                lineTo(center.x + (beamLen * sin(a)).toFloat(), center.y - (beamLen * cos(a)).toFloat())
            }
            close()
        }
        val beamColor = if (headingValid) MapPalette.userBlue else Color(0xFF9AA0A6)
        drawPath(
            beam,
            Brush.radialGradient(
                colors = listOf(beamColor.copy(alpha = 0.50f), beamColor.copy(alpha = 0f)),
                center = center,
                radius = beamLen
            )
        )
    }

    drawCircle(Color(0x40000000), radius = 11f * density, center = Offset(center.x, center.y + 1.2f * density))
    drawCircle(Color.White, radius = 10.5f * density, center = center)
    drawCircle(MapPalette.userBlue, radius = 7.5f * density, center = center)
}

/** Small "start" marker: white disc with a grey ring. */
internal fun DrawScope.drawStartMarker(center: Offset, density: Float) {
    drawCircle(Color.White, radius = 5.5f * density, center = center)
    drawCircle(Color(0xFF5F6368), radius = 5.5f * density, center = center, style = Stroke(width = 2f * density))
}

/** Rounded-square stair icon with white step glyph. */
internal fun DrawScope.drawStairsIcon(center: Offset, density: Float) {
    val s = 8.5f * density
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(center.x - s - 1.5f * density, center.y - s - 1.5f * density),
        size = androidx.compose.ui.geometry.Size(2 * s + 3f * density, 2 * s + 3f * density),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * density)
    )
    drawRoundRect(
        color = MapPalette.stairs,
        topLeft = Offset(center.x - s, center.y - s),
        size = androidx.compose.ui.geometry.Size(2 * s, 2 * s),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * density)
    )
    val st = s * 0.55f
    val glyph = Path().apply {
        moveTo(center.x - st, center.y + st)
        lineTo(center.x - st / 3f, center.y + st)
        lineTo(center.x - st / 3f, center.y + st / 3f)
        lineTo(center.x + st / 3f, center.y + st / 3f)
        lineTo(center.x + st / 3f, center.y - st / 3f)
        lineTo(center.x + st, center.y - st / 3f)
        lineTo(center.x + st, center.y - st)
    }
    drawPath(glyph, Color.White, style = Stroke(width = 1.6f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Rounded-square elevator icon with up/down triangles. */
internal fun DrawScope.drawElevatorIcon(center: Offset, density: Float) {
    val s = 8.5f * density
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(center.x - s - 1.5f * density, center.y - s - 1.5f * density),
        size = androidx.compose.ui.geometry.Size(2 * s + 3f * density, 2 * s + 3f * density),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * density)
    )
    drawRoundRect(
        color = MapPalette.elevator,
        topLeft = Offset(center.x - s, center.y - s),
        size = androidx.compose.ui.geometry.Size(2 * s, 2 * s),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * density)
    )
    val w = s * 0.42f
    val up = Path().apply {
        moveTo(center.x, center.y - s * 0.62f)
        lineTo(center.x + w, center.y - s * 0.1f)
        lineTo(center.x - w, center.y - s * 0.1f)
        close()
    }
    val down = Path().apply {
        moveTo(center.x, center.y + s * 0.62f)
        lineTo(center.x + w, center.y + s * 0.1f)
        lineTo(center.x - w, center.y + s * 0.1f)
        close()
    }
    drawPath(up, Color.White)
    drawPath(down, Color.White)
}

/** White pill with blue text, used for floor-transfer callouts on the route ("↑ MB-4"). */
internal fun DrawScope.drawRoutePill(anchor: Offset, text: String, density: Float) {
    val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(25, 103, 210)
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        setShadowLayer(3f * density, 0f, 1f * density, android.graphics.Color.argb(70, 0, 0, 0))
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = android.graphics.Color.rgb(25, 103, 210)
    }
    val halfW = txt.measureText(text) / 2f + 8f * density
    val rect = RectF(anchor.x - halfW, anchor.y - 32f * density, anchor.x + halfW, anchor.y - 12f * density)
    val nc = drawContext.canvas.nativeCanvas
    nc.drawRoundRect(rect, 10f * density, 10f * density, bg)
    nc.drawRoundRect(rect, 10f * density, 10f * density, border)
    nc.drawText(text, anchor.x, anchor.y - 18f * density, txt)
}

/** Text with a white halo so it stays readable over walls and roads. */
internal fun DrawScope.drawHaloText(
    text: String,
    x: Float,
    y: Float,
    sizePx: Float,
    color: Int,
    bold: Boolean = false,
    rotationDeg: Float = 0f
) {
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.28f
        strokeJoin = Paint.Join.ROUND
        this.color = android.graphics.Color.WHITE
        textSize = sizePx
        textAlign = Paint.Align.CENTER
        isFakeBoldText = bold
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = sizePx
        textAlign = Paint.Align.CENTER
        isFakeBoldText = bold
    }
    val nc = drawContext.canvas.nativeCanvas
    if (rotationDeg != 0f) {
        nc.save()
        nc.translate(x, y)
        nc.rotate(rotationDeg)
        nc.drawText(text, 0f, 0f, halo)
        nc.drawText(text, 0f, 0f, fill)
        nc.restore()
    } else {
        nc.drawText(text, x, y, halo)
        nc.drawText(text, x, y, fill)
    }
}

/** Screen-space angle (clockwise from screen-up) of the segment a→b. */
internal fun screenHeading(a: Offset, b: Offset): Double =
    atan2((b.x - a.x).toDouble(), (a.y - b.y).toDouble())

private const val TRAIL_FADE_SEGMENTS = 40
