package com.lync.app.ui.indoor

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import android.graphics.RectF
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Elevator
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.NodeKey
import com.lync.app.R
import com.hackdecouverte.backend.NavigationSnapshot
import com.hackdecouverte.pdr.PathGeometry
import com.hackdecouverte.pdr.PathSegment
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.ui.MapTransform
import com.lync.app.ui.theme.CampusColors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val SCALE_BAR_STEPS_M = intArrayOf(2, 5, 10, 20, 50, 100, 200)

/** Camera of the indoor plan. `follow` keeps "me" in the middle until the user pans. */
class IndoorCamera {
    var zoom by mutableStateOf(0.22f)
    var pan by mutableStateOf(Offset.Zero)
    var follow by mutableStateOf(true)
    var frozenFocus by mutableStateOf(PointM(0.0, 0.0))
    var headUp by mutableStateOf(false)

    fun recenter() {
        follow = true
        pan = Offset.Zero
    }
}

/**
 * The MB plan, drawn in Lync colors: OpenStreetMap streets around, the aligned floor plan
 * (walls in ink), the route in the university accent and "me" like the dot on the Map tab.
 * Same drawing order and maths as the algorythme test screen (TelemetryScreen).
 */
@Composable
fun IndoorCanvas(
    snapshot: NavigationSnapshot,
    camera: IndoorCamera,
    colors: CampusColors,
    modifier: Modifier = Modifier,
    bottomInsetPx: Float = 0f, // height hidden by the bottom sheet: keeps the scale bar and "me" visible
) {
    val state = snapshot.pdr
    val vectorPlan = snapshot.activeFloorVector
    val floorPlan = snapshot.activeFloor
    val anchor = snapshot.anchor
    val route = snapshot.activeRoute
    val position = snapshot.mapPositionM
    val heading = snapshot.mapHeadingRad

    var floorLayer by remember { mutableStateOf<FloorLayer?>(null) }
    LaunchedEffect(vectorPlan?.floorId) {
        floorLayer = vectorPlan?.let { FloorLayers.get(it) }
    }
    val segments = remember(state.rawPath) { PathGeometry.smoothedSegments(state.rawPath) }
    val bmpMatrix = remember { Matrix() }
    // ALPHA_8 bitmap: drawn in the paint color. The photographed plan stays in the background
    // (furniture can't be told apart from walls); labels, icons and the route sit on top.
    val wallPaint = remember(colors.text, colors.isDark) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color = colors.text.copy(alpha = if (colors.isDark) 0.40f else 0.42f).toArgb()
        }
    }
    val context = LocalContext.current
    val labels = remember(floorPlan, snapshot.destination) { roomLabels(floorPlan, snapshot.destination) }
    val labelFont = remember { runCatching { ResourcesCompat.getFont(context, R.font.figtree) }.getOrNull() }
    val icons = mapOf(
        "elevator" to rememberVectorPainter(Icons.Rounded.Elevator),
        "stairs" to rememberVectorPainter(Icons.Rounded.Stairs),
        "exit" to rememberVectorPainter(Icons.AutoMirrored.Rounded.ExitToApp),
    )
    val park = if (colors.isDark) Color(0xFF1C2521) else Color(0xFFE5ECE2)

    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    camera.zoom = (camera.zoom * zoomChange).coerceIn(0.04f, 8f)
                    if (panChange != Offset.Zero) {
                        if (camera.follow) {
                            camera.follow = false
                            camera.frozenFocus = snapshot.mapPositionM
                        }
                        camera.pan += panChange
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pxPerM = 56.dp.toPx() * camera.zoom
            val focus = if (camera.follow) position else camera.frozenFocus
            val rotation = if (camera.headUp) -heading else 0.0
            val t = MapTransform(
                focus = focus,
                panXPx = camera.pan.x.toDouble(),
                panYPx = camera.pan.y.toDouble(),
                pxPerMeter = pxPerM.toDouble(),
                rotationRad = rotation,
                centerXPx = size.width / 2.0,
                centerYPx = (size.height - bottomInsetPx) / 2.0,
            )

            fun toScreen(p: PointM): Offset = t.worldToScreen(p).let { Offset(it.x.toFloat(), it.y.toFloat()) }
            fun disp(p: PointM): PointM = anchor?.toMap(p) ?: p
            fun polyPath(points: List<PointM>, close: Boolean) = Path().apply {
                moveTo(toScreen(points.first()))
                for (i in 1 until points.size) lineTo(toScreen(points[i]))
                if (close) close()
            }

            val vb = t.viewBoundsWorld(size.width.toDouble(), size.height.toDouble(), marginM = 25.0)
            fun inView(p: PointM, margin: Double = 0.0) =
                p.x in (vb.minX - margin)..(vb.maxX + margin) && p.y in (vb.minY - margin)..(vb.maxY + margin)

            drawRect(colors.surface)

            // Streets and buildings around MB (OpenStreetMap)
            snapshot.osmSurroundings?.let { osm ->
                for (p in osm.parks) if (p.polygonM.any { inView(it) }) drawPath(polyPath(p.polygonM, true), park)
                for (road in osm.roads) {
                    if (road.polylineM.none { inView(it) }) continue
                    val path = polyPath(road.polylineM, false)
                    val w = (road.widthM.toFloat() * pxPerM).coerceIn(2.5f, 64f)
                    val footway = road.kind == "footway" || road.kind == "cycleway"
                    if (!footway) drawPath(path, colors.border, style = Stroke(w + 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, if (footway) colors.surfaceAlt else colors.bg, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colors.textMuted.toArgb(); textSize = 10.5.dp.toPx(); textAlign = Paint.Align.CENTER
                }
                for (b in osm.buildings) {
                    if (!inView(b.labelCenterM, 60.0)) continue
                    val path = polyPath(b.polygonM, true)
                    if (b.isFocusBuilding) {
                        drawPath(path, colors.bg)
                        drawPath(path, colors.textMuted, style = Stroke(2.4f, join = StrokeJoin.Round))
                    } else {
                        drawPath(path, colors.surfaceAlt)
                        drawPath(path, colors.border, style = Stroke(1.2f, join = StrokeJoin.Round))
                        val tag = b.ref.ifBlank { b.name }
                        if (tag.isNotBlank() && pxPerM >= 2.2f) {
                            val sc = toScreen(b.labelCenterM)
                            drawContext.canvas.nativeCanvas.drawText(tag.take(20), sc.x, sc.y + 3.dp.toPx(), labelPaint)
                        }
                    }
                }
                for (poi in osm.pois) {
                    val sp = toScreen(poi.positionM)
                    drawCircle(colors.textMuted, 4.5.dp.toPx(), sp)
                    drawCircle(colors.bg, 2.dp.toPx(), sp)
                }
            }

            // The floor plan, aligned on true north: building footprint, then walls
            val layer = floorLayer?.takeIf { it.floorId == vectorPlan?.floorId }
            if (layer != null && layer.footprint.size >= 3) drawPath(polyPath(layer.footprint, true), colors.bg)
            val bmp = layer?.walls
            if (bmp != null && vectorPlan != null) {
                val b = vectorPlan.viewBoundsM
                val tl = toScreen(PointM(b.minEastM, b.maxNorthM))
                val tr = toScreen(PointM(b.maxEastM, b.maxNorthM))
                val bl = toScreen(PointM(b.minEastM, b.minNorthM))
                val src = floatArrayOf(0f, 0f, bmp.width.toFloat(), 0f, 0f, bmp.height.toFloat())
                val dst = floatArrayOf(tl.x, tl.y, tr.x, tr.y, bl.x, bl.y)
                if (bmpMatrix.setPolyToPoly(src, 0, dst, 0, 3)) drawContext.canvas.nativeCanvas.drawBitmap(bmp, bmpMatrix, wallPaint)
            } else if (floorPlan != null) {
                for (e in floorPlan.edges) {
                    val a = floorPlan.node(e.fromId)?.posM ?: continue
                    val z = floorPlan.node(e.toId)?.posM ?: continue
                    drawLine(colors.surfaceAlt, toScreen(a), toScreen(z), (1.5f * pxPerM).coerceIn(4f, 16f), StrokeCap.Round)
                }
            }

            // Route in the university color
            val routePts = snapshot.activeFloorId?.let { route?.pointsForFloor(it) }.orEmpty()
            if (routePts.size >= 2) {
                val path = polyPath(routePts, false)
                drawPath(path, colors.accent.copy(alpha = 0.22f), style = Stroke(11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, colors.accent, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val end = toScreen(routePts.last())
                drawCircle(Color.White, 10.dp.toPx(), end)
                drawCircle(colors.accent, 7.dp.toPx(), end)
            }

            // Room numbers (upright, one per room, no overlaps) and elevator / stairs / exit badges
            drawRoomLabels(labels, icons, pxPerM, colors, labelFont) { toScreen(it) }

            // GPS accuracy, only when it is precise enough to mean something
            snapshot.latestGpsFix?.let { fix ->
                if (fix.accuracyM <= 35.0 && fix.eastNorthM.norm() <= 350.0) {
                    val r = (fix.accuracyM.toFloat() * pxPerM).coerceIn(6.dp.toPx(), 70.dp.toPx())
                    drawCircle(colors.accent.copy(alpha = 0.08f), r, toScreen(fix.eastNorthM))
                }
            }

            // Where I walked
            if (segments.isNotEmpty()) {
                val trace = Path().apply {
                    moveTo(toScreen(disp(segments.first().start)))
                    for (s in segments) when (s) {
                        is PathSegment.Line -> lineTo(toScreen(disp(s.end)))
                        is PathSegment.Quad -> {
                            val c = toScreen(disp(s.control)); val e = toScreen(disp(s.end))
                            quadraticTo(c.x, c.y, e.x, e.y)
                        }
                    }
                }
                drawPath(trace, colors.textMuted.copy(alpha = 0.55f), style = Stroke(3.dp.toPx() * 0.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // Me: accent dot + heading cone
            val here = toScreen(position)
            val coneLenM = (26.dp.toPx() / pxPerM).coerceIn(1.2f, 9f).toDouble()
            val tip = toScreen(disp(state.position + PointM(sin(state.headingRadians) * coneLenM, cos(state.headingRadians) * coneLenM)))
            val vx = tip.x - here.x; val vy = tip.y - here.y; val len = hypot(vx, vy)
            if (len > 1f) {
                val px = -vy / len; val py = vx / len; val half = len * 0.34f
                val cone = Path().apply {
                    moveTo(tip); lineTo(Offset(here.x + px * half, here.y + py * half)); lineTo(Offset(here.x - px * half, here.y - py * half)); close()
                }
                drawPath(cone, if (state.headingValid) colors.accent.copy(alpha = 0.3f) else colors.textMuted.copy(alpha = 0.3f))
            }
            drawCircle(Color.White, 9.dp.toPx(), here)
            drawCircle(colors.accent, 6.dp.toPx(), here)

            drawNorthAndScale(rotation, pxPerM, colors, bottomInsetPx)
        }
    }
}

private fun DrawScope.drawNorthAndScale(rotation: Double, pxPerM: Float, colors: CampusColors, bottomInsetPx: Float) {
    // North badge and scale bar sit bottom-left, just above the sheet
    val bottom = size.height - bottomInsetPx
    val center = Offset(20.dp.toPx() + 15.dp.toPx(), bottom - 58.dp.toPx())
    drawCircle(colors.bg, 15.dp.toPx(), center)
    drawCircle(colors.border, 15.dp.toPx(), center, style = Stroke(1f))
    val nx = sin(rotation).toFloat(); val ny = -cos(rotation).toFloat()
    drawLine(colors.text, center, Offset(center.x + nx * 10.dp.toPx(), center.y + ny * 10.dp.toPx()), 2.4.dp.toPx(), StrokeCap.Round)
    val nPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.textMuted.toArgb(); textSize = 9.dp.toPx(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    drawContext.canvas.nativeCanvas.drawText("N", center.x - nx * 6.dp.toPx(), center.y - ny * 6.dp.toPx() + 3.dp.toPx(), nPaint)

    val barM = SCALE_BAR_STEPS_M.firstOrNull { it * pxPerM >= 48.dp.toPx() } ?: 200
    val x = 20.dp.toPx(); val y = bottom - 16.dp.toPx()
    drawLine(colors.textMuted, Offset(x, y), Offset(x + barM * pxPerM, y), 2.5f)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.textMuted.toArgb(); textSize = 10.dp.toPx() }
    // OpenStreetMap attribution must stay visible
    drawContext.canvas.nativeCanvas.drawText("$barM m · © OpenStreetMap", x, y - 5.dp.toPx(), p)
}

private fun Path.moveTo(o: Offset) = moveTo(o.x, o.y)
private fun Path.lineTo(o: Offset) = lineTo(o.x, o.y)

/** What gets written on the plan: room numbers and the vertical / exit points. */
class PlanLabel(val text: String, val positionM: PointM, val kind: String, val highlight: Boolean)

private fun roomLabels(plan: FloorPlan?, destination: NodeKey?): List<PlanLabel> {
    if (plan == null) return emptyList()
    val out = ArrayList<PlanLabel>()
    // badges first: they win over room numbers when they collide
    for (n in plan.nodes) if (n.type == "elevator" || n.type == "stairs" || n.type == "exit") {
        out.add(PlanLabel("", n.posM, n.type, false))
    }
    // the destination room first so it is never hidden
    val destLabel = destination?.takeIf { it.floorId == plan.floorId }?.let { plan.node(it.nodeId)?.label }
    val seen = HashSet<String>()
    val rooms = plan.nodes.filter { it.type == "room" && it.label.isNotBlank() }.sortedBy { if (it.label == destLabel) 0 else 1 }
    for (n in rooms) {
        if (!seen.add(n.label)) continue // a room with several doors is written once
        out.add(PlanLabel(n.label.substringAfter('-'), n.posM, "room", n.label == destLabel))
    }
    return out
}

private fun DrawScope.drawRoomLabels(
    labels: List<PlanLabel>,
    icons: Map<String, Painter>,
    pxPerM: Float,
    colors: CampusColors,
    font: android.graphics.Typeface?,
    toScreen: (PointM) -> Offset,
) {
    if (pxPerM < 5.dp.toPx()) return // too far out: only the plan and the route
    val placed = ArrayList<RectF>()
    fun free(r: RectF) = placed.none { RectF.intersects(it, r) }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font; textSize = 12.dp.toPx(); textAlign = Paint.Align.CENTER
    }
    val halo = Paint(text).apply {
        style = Paint.Style.STROKE; strokeWidth = 4.dp.toPx(); strokeJoin = Paint.Join.ROUND; color = colors.bg.toArgb()
    }
    val r = 11.dp.toPx()
    val nc = drawContext.canvas.nativeCanvas
    for (l in labels) {
        val p = toScreen(l.positionM)
        if (p.x < -40 || p.y < -40 || p.x > size.width + 40 || p.y > size.height + 40) continue
        if (l.kind != "room") {
            val box = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
            if (!free(box)) continue
            placed.add(box)
            drawCircle(colors.bg, r, p)
            drawCircle(colors.text, r, p, style = Stroke(1.5.dp.toPx()))
            val painter = icons[l.kind] ?: continue
            val s = 14.dp.toPx()
            translate(p.x - s / 2, p.y - s / 2) {
                with(painter) { draw(Size(s, s), colorFilter = ColorFilter.tint(colors.text)) }
            }
        } else {
            text.isFakeBoldText = l.highlight
            halo.isFakeBoldText = l.highlight
            text.color = (if (l.highlight) colors.accent else colors.text).toArgb()
            val w = text.measureText(l.text)
            val box = RectF(p.x - w / 2 - 4.dp.toPx(), p.y - 9.dp.toPx(), p.x + w / 2 + 4.dp.toPx(), p.y + 7.dp.toPx())
            if (!l.highlight && !free(box)) continue
            placed.add(box)
            val y = p.y + 4.dp.toPx()
            nc.drawText(l.text, p.x, y, halo)
            nc.drawText(l.text, p.x, y, text)
        }
    }
}
