package com.lync.app.code

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import com.lync.app.R
import kotlin.math.PI

/**
 * Draws a Lync code. Same geometry as [LyncCode.isInk] (which the decoder and tests rely on).
 * [ink] = finder ring + mark, [data] = the two data rings (usually the university color).
 */
@Composable
fun LyncCodeView(code: Long, modifier: Modifier = Modifier, ink: Color, data: Color) {
    val bits = remember(code) { LyncCode.encode(code) }
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            // finder ring
            val finderW = (1f - LyncCode.FINDER_INNER) * r
            drawCircle(ink, radius = r - finderW / 2, center = c, style = Stroke(finderW))

            val segDeg = 360f / LyncCode.SEGMENTS
            for ((ringR, ring) in listOf(LyncCode.RING_OUTER to bits.first, LyncCode.RING_INNER to bits.second)) {
                val rr = ringR * r
                val stroke = LyncCode.STROKE * r
                // runs of consecutive 1s become one arc with round caps
                val start = (0 until LyncCode.SEGMENTS).firstOrNull { !ring[it] }
                if (start == null) {
                    drawCircle(data, radius = rr, center = c, style = Stroke(stroke))
                    continue
                }
                var i = 0
                while (i < LyncCode.SEGMENTS) {
                    val s = (start + i) % LyncCode.SEGMENTS
                    if (!ring[s]) {
                        val a = (LyncCode.segmentAngle(s) - PI / 2).toFloat()
                        drawCircle(data, radius = LyncCode.DOT * r, center = Offset(c.x + rr * kotlin.math.cos(a), c.y + rr * kotlin.math.sin(a)))
                        i++
                        continue
                    }
                    var len = 0
                    while (i + len < LyncCode.SEGMENTS && ring[(start + i + len) % LyncCode.SEGMENTS]) len++
                    // arc from center of first 1 to center of last 1 (Compose: 0° = 3 o'clock, clockwise)
                    val startDeg = s * segDeg - 90f
                    val sweep = (len - 1) * segDeg
                    if (len == 1) {
                        // a lone 1 = a round "pill" (Skia drops near-zero arcs, so draw the cap ourselves)
                        val a = (LyncCode.segmentAngle(s) - PI / 2).toFloat()
                        drawCircle(data, radius = stroke / 2, center = Offset(c.x + rr * kotlin.math.cos(a), c.y + rr * kotlin.math.sin(a)))
                        i += len
                        continue
                    }
                    drawArc(
                        data, startAngle = startDeg, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    i += len
                }
            }
        }
        Icon(painterResource(R.drawable.ic_lync_mark), null, tint = ink, modifier = Modifier.fillMaxWidth(0.42f).aspectRatio(1f))
    }
}
