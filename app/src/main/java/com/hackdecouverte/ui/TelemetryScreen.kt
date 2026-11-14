package com.hackdecouverte.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hackdecouverte.backend.NavigationSnapshot
import com.hackdecouverte.map.NodeKey
import com.hackdecouverte.pdr.PathGeometry
import com.hackdecouverte.pdr.PathSegment
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PointM
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val SCALE_BAR_STEPS_M = intArrayOf(2, 5, 10, 20, 50, 100, 200)

@Composable
fun TelemetryScreen(
    snapshot: NavigationSnapshot,
    onSelectDestination: (NodeKey?) -> Unit,
    onSetAccessibleOnly: (Boolean) -> Unit,
    onSwitchFloor: (String) -> Unit,
    onResetPosition: () -> Unit,
    onOpenSetup: () -> Unit,
    onResumeNewTrace: () -> Unit
) {
    val state = snapshot.pdr
    val floorPlan = snapshot.activeFloor
    val vectorPlan = snapshot.activeFloorVector
    val osm = snapshot.osmSurroundings
    val anchor = snapshot.anchor
    val route = snapshot.activeRoute

    var zoom by remember { mutableStateOf(0.22f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var follow by remember { mutableStateOf(true) }
    var frozenFocus by remember { mutableStateOf(PointM(0.0, 0.0)) }
    var headUp by remember { mutableStateOf(false) }
    var showDestDialog by remember { mutableStateOf(false) }
    var floorBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(vectorPlan?.floorId) {
        floorBitmap = if (vectorPlan != null) {
            FloorVectorRenderer.getOrRender(vectorPlan)
        } else {
            null
        }
    }

    val displayPosition = snapshot.mapPositionM
    val displayHeading = snapshot.mapHeadingRad
    val currentPosition by rememberUpdatedState(displayPosition)
    val segments = remember(state.rawPath) { PathGeometry.smoothedSegments(state.rawPath) }
    val headingReady = state.headingValid && !state.tilted

    val bmpMatrix = remember { Matrix() }
    val bmpPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WarningBanner(state)

        if (state.phase == PdrPhase.PAUSED) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Suivi en pause",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onResumeNewTrace, enabled = headingReady) {
                        Text("Reprendre")
                    }
                }
            }
        }

        if (floorPlan != null) {
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showDestDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                route?.let { "Vers ${it.destinationLabel}" }
                                    ?: "Naviguer vers une salle…",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilterChip(
                            selected = snapshot.accessibleOnly,
                            onClick = { onSetAccessibleOnly(!snapshot.accessibleOnly) },
                            label = { Text("PMR") }
                        )
                        if (snapshot.destination != null) {
                            TextButton(onClick = { onSelectDestination(null) }) {
                                Text("✕")
                            }
                        }
                    }

                    if (route != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                route.nextInstruction,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (route.isArrived) {
                                    Color(0xFF2E7D32)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${fmt(route.totalDistanceM, 0)} m",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    } else if (snapshot.destination != null && snapshot.startNodeId == null && !snapshot.usingGpsStart) {
                        Text(
                            "Choisissez une salle de départ dans Config pour l'itinéraire",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        val stepLen = state.currentStepLengthM ?: state.lengthPerStepM
        val capTxt = if (state.headingValid) compassCardinalFr(Math.toDegrees(displayHeading)) else "—"
        Text(
            "Parcouru : ${fmt(state.distanceM, 1)} m  •  " +
                "Pas : ${state.stepCount}" +
                (stepLen?.let { " (${fmt(it, 2)} m)" } ?: "") +
                "  •  Cap : $capTxt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (snapshot.floors.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (f in snapshot.floors) {
                    FilterChip(
                        selected = f.floorId == snapshot.activeFloorId,
                        onClick = { onSwitchFloor(f.floorId) },
                        label = { Text(f.floorId) }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.04f, 8f)
                        if (panChange != Offset.Zero) {
                            if (follow) {
                                follow = false
                                frozenFocus = currentPosition
                            }
                            pan += panChange
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pxPerM = 56.dp.toPx() * zoom
                val focus = if (follow) displayPosition else frozenFocus
                val rotation = if (headUp) -displayHeading else 0.0
                val transform = MapTransform(
                    focus = focus,
                    panXPx = pan.x.toDouble(),
                    panYPx = pan.y.toDouble(),
                    pxPerMeter = pxPerM.toDouble(),
                    rotationRad = rotation,
                    centerXPx = size.width / 2.0,
                    centerYPx = size.height / 2.0
                )

                fun toScreen(p: PointM): Offset {
                    val s = transform.worldToScreen(p)
                    return Offset(s.x.toFloat(), s.y.toFloat())
                }

                fun disp(p: PointM): PointM = anchor?.toMap(p) ?: p

                val viewBounds = transform.viewBoundsWorld(
                    size.width.toDouble(),
                    size.height.toDouble(),
                    marginM = 25.0
                )

                // 1. OpenStreetMap Base Land
                drawRect(color = Color(0xFFEEF2F6))

                if (osm != null) {
                    // 2. OSM Parks / Green spaces
                    for (park in osm.parks) {
                        if (park.polygonM.none {
                                it.x in viewBounds.minX..viewBounds.maxX &&
                                    it.y in viewBounds.minY..viewBounds.maxY
                            }
                        ) {
                            continue
                        }
                        val poly = Path().apply {
                            moveToOffset(toScreen(park.polygonM.first()))
                            for (i in 1 until park.polygonM.size) {
                                lineToOffset(toScreen(park.polygonM[i]))
                            }
                            close()
                        }
                        drawPath(path = poly, color = Color(0xFFDCECCB))
                    }

                    // 3. OSM Roads (casing + fill)
                    for (road in osm.roads) {
                        if (road.polylineM.none {
                                it.x in viewBounds.minX..viewBounds.maxX &&
                                    it.y in viewBounds.minY..viewBounds.maxY
                            }
                        ) {
                            continue
                        }
                        val roadPath = Path().apply {
                            moveToOffset(toScreen(road.polylineM.first()))
                            for (i in 1 until road.polylineM.size) {
                                lineToOffset(toScreen(road.polylineM[i]))
                            }
                        }
                        val widthPx = (road.widthM.toFloat() * pxPerM).coerceIn(2.5f, 64f)
                        val isFootway = road.kind == "footway" || road.kind == "cycleway"
                        if (!isFootway) {
                            drawPath(
                                path = roadPath,
                                color = Color(0xFFD3DCE6),
                                style = Stroke(
                                    width = widthPx + 2.2f,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                        drawPath(
                            path = roadPath,
                            color = if (isFootway) Color(0xFFE3E8EF) else Color.White,
                            style = Stroke(
                                width = widthPx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }

                    // 4. OSM Surrounding & Focus Buildings
                    val buildingLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(71, 85, 105)
                        textSize = 10.5.dp.toPx()
                        textAlign = Paint.Align.CENTER
                        isFakeBoldText = true
                    }
                    for (b in osm.buildings) {
                        val c = b.labelCenterM
                        if (c.x !in (viewBounds.minX - 60.0)..(viewBounds.maxX + 60.0) ||
                            c.y !in (viewBounds.minY - 60.0)..(viewBounds.maxY + 60.0)
                        ) {
                            continue
                        }
                        val poly = Path().apply {
                            moveToOffset(toScreen(b.polygonM.first()))
                            for (i in 1 until b.polygonM.size) {
                                lineToOffset(toScreen(b.polygonM[i]))
                            }
                            close()
                        }
                        if (b.isFocusBuilding) {
                            drawPath(path = poly, color = Color(0xFFFFFDF9))
                            drawPath(
                                path = poly,
                                color = Color(0xFF94A3B8),
                                style = Stroke(width = 2.4f, join = StrokeJoin.Round)
                            )
                        } else {
                            drawPath(path = poly, color = Color(0xFFDDE4EC))
                            drawPath(
                                path = poly,
                                color = Color(0xFFC5D0DC),
                                style = Stroke(width = 1.2f, join = StrokeJoin.Round)
                            )
                            val tag = b.ref.ifBlank { b.name }
                            if (tag.isNotBlank() && pxPerM >= 2.2f) {
                                val sc = toScreen(c)
                                drawContext.canvas.nativeCanvas.drawText(
                                    tag.take(20),
                                    sc.x,
                                    sc.y + 3.dp.toPx(),
                                    buildingLabelPaint
                                )
                            }
                        }
                    }

                    // 5. Street names along OSM roads
                    if (pxPerM >= 2.6f) {
                        val roadNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(82, 96, 115)
                            textSize = 9.5.dp.toPx()
                            textAlign = Paint.Align.CENTER
                        }
                        val drawnNames = HashSet<String>()
                        for (road in osm.roads) {
                            val name = road.name
                            if (name.isBlank() || name in drawnNames || road.polylineM.size < 2) continue
                            var bestIdx = -1
                            var bestLen = 0f
                            for (i in 0 until road.polylineM.size - 1) {
                                val a = road.polylineM[i]
                                val b = road.polylineM[i + 1]
                                val mx = (a.x + b.x) * 0.5
                                val my = (a.y + b.y) * 0.5
                                if (mx !in viewBounds.minX..viewBounds.maxX ||
                                    my !in viewBounds.minY..viewBounds.maxY
                                ) {
                                    continue
                                }
                                val sa = toScreen(a)
                                val sb = toScreen(b)
                                val segPx = hypot(sb.x - sa.x, sb.y - sa.y)
                                if (segPx > bestLen) {
                                    bestLen = segPx
                                    bestIdx = i
                                }
                            }
                            if (bestIdx >= 0 && bestLen >= 75.dp.toPx()) {
                                drawnNames.add(name)
                                val sa = toScreen(road.polylineM[bestIdx])
                                val sb = toScreen(road.polylineM[bestIdx + 1])
                                val mx = (sa.x + sb.x) * 0.5f
                                val my = (sa.y + sb.y) * 0.5f
                                var deg = Math.toDegrees(atan2((sb.y - sa.y).toDouble(), (sb.x - sa.x).toDouble())).toFloat()
                                if (deg > 90f) deg -= 180f
                                if (deg < -90f) deg += 180f
                                val nc = drawContext.canvas.nativeCanvas
                                nc.save()
                                nc.translate(mx, my)
                                nc.rotate(deg)
                                nc.drawText(name, 0f, 3.dp.toPx(), roadNamePaint)
                                nc.restore()
                            }
                        }
                    }

                    // 6. Metro entrances
                    for (poi in osm.pois) {
                        val sp = toScreen(poi.positionM)
                        drawCircle(color = Color(0xFF0284C7), radius = 4.5.dp.toPx(), center = sp)
                        drawCircle(color = Color.White, radius = 2.dp.toPx(), center = sp)
                    }
                }

                // 7. Aligned MB Vector Floor Plan Background (MB-1..7)
                val bmp = floorBitmap
                if (bmp != null && vectorPlan != null) {
                    val vb = vectorPlan.viewBoundsM
                    val sTL = toScreen(PointM(vb.minEastM, vb.maxNorthM))
                    val sTR = toScreen(PointM(vb.maxEastM, vb.maxNorthM))
                    val sBL = toScreen(PointM(vb.minEastM, vb.minNorthM))
                    val src = floatArrayOf(
                        0f, 0f,
                        bmp.width.toFloat(), 0f,
                        0f, bmp.height.toFloat()
                    )
                    val dst = floatArrayOf(
                        sTL.x, sTL.y,
                        sTR.x, sTR.y,
                        sBL.x, sBL.y
                    )
                    if (bmpMatrix.setPolyToPoly(src, 0, dst, 0, 3)) {
                        drawContext.canvas.nativeCanvas.drawBitmap(bmp, bmpMatrix, bmpPaint)
                    }
                } else if (floorPlan != null) {
                    for (edge in floorPlan.edges) {
                        val a = floorPlan.node(edge.fromId)?.posM ?: continue
                        val b = floorPlan.node(edge.toId)?.posM ?: continue
                        drawLine(
                            color = Color(0xFFCFD8DC),
                            start = toScreen(a),
                            end = toScreen(b),
                            strokeWidth = (1.5f * pxPerM).coerceIn(4f, 16f),
                            cap = StrokeCap.Round
                        )
                    }
                }

                // 8. Destination marker & Navigation Route Polyline
                val routePts = snapshot.activeFloorId?.let { route?.pointsForFloor(it) }.orEmpty()
                if (routePts.size >= 2) {
                    val routePath = Path().apply {
                        moveToOffset(toScreen(routePts.first()))
                        for (i in 1 until routePts.size) {
                            lineToOffset(toScreen(routePts[i]))
                        }
                    }
                    drawPath(
                        path = routePath,
                        color = Color(0x4400897B),
                        style = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = routePath,
                        color = Color(0xFF00897B),
                        style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    val endScreen = toScreen(routePts.last())
                    drawCircle(color = Color(0xFF00897B), radius = 8.dp.toPx(), center = endScreen)
                    drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = endScreen)
                }

                // 9. Optional subtle GPS circle when outdoors/nearby
                snapshot.latestGpsFix?.let { fix ->
                    if (fix.accuracyM <= 35.0 && fix.eastNorthM.norm() <= 350.0) {
                        val gpsScreen = toScreen(fix.eastNorthM)
                        val rPx = (fix.accuracyM.toFloat() * pxPerM).coerceIn(6.dp.toPx(), 70.dp.toPx())
                        drawCircle(color = Color(0x180284C7), radius = rPx, center = gpsScreen)
                    }
                }

                // 10. PDR Trajectory & True-North Heading Cone
                val traceColor = Color(0xFF1565C0)
                if (segments.isNotEmpty()) {
                    val smooth = Path()
                    smooth.moveToOffset(toScreen(disp(segments.first().start)))
                    for (segment in segments) {
                        when (segment) {
                            is PathSegment.Line -> smooth.lineToOffset(toScreen(disp(segment.end)))
                            is PathSegment.Quad -> {
                                val c = toScreen(disp(segment.control))
                                val e = toScreen(disp(segment.end))
                                smooth.quadraticTo(c.x, c.y, e.x, e.y)
                            }
                        }
                    }
                    drawPath(
                        path = smooth,
                        color = traceColor,
                        style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                val originScreen = toScreen(disp(PointM(0.0, 0.0)))
                drawCircle(color = Color(0xFF2E7D32), radius = 4.5.dp.toPx(), center = originScreen)

                val here = toScreen(displayPosition)
                val heading = state.headingRadians
                val coneColor = if (state.headingValid) Color(0x551565C0) else Color(0x5590A4AE)
                val coneLenM = (26.dp.toPx() / pxPerM).coerceIn(1.2f, 9f).toDouble()
                val tipWorld = disp(state.position + PointM(sin(heading) * coneLenM, cos(heading) * coneLenM))
                val tip = toScreen(tipWorld)
                val vx = tip.x - here.x
                val vy = tip.y - here.y
                val vLen = hypot(vx, vy)
                if (vLen > 1f) {
                    val px = -vy / vLen
                    val py = vx / vLen
                    val half = vLen * 0.34f
                    val cone = Path().apply {
                        moveToOffset(tip)
                        lineToOffset(Offset(here.x + px * half, here.y + py * half))
                        lineToOffset(Offset(here.x - px * half, here.y - py * half))
                        close()
                    }
                    drawPath(path = cone, color = coneColor)
                }
                drawCircle(color = Color.White, radius = 9.dp.toPx(), center = here)
                drawCircle(color = traceColor, radius = 6.8.dp.toPx(), center = here)

                // 11. North Badge (top-right) & Metric Scale Bar (bottom-left)
                val compassCenter = Offset(size.width - 24.dp.toPx(), 24.dp.toPx())
                drawCircle(color = Color(0xDDFFFFFF), radius = 16.dp.toPx(), center = compassCenter)
                val northDirX = sin(rotation).toFloat()
                val northDirY = -cos(rotation).toFloat()
                drawLine(
                    color = Color(0xFFDC2626),
                    start = compassCenter,
                    end = Offset(
                        compassCenter.x + northDirX * 11.dp.toPx(),
                        compassCenter.y + northDirY * 11.dp.toPx()
                    ),
                    strokeWidth = 2.6.dp.toPx(),
                    cap = StrokeCap.Round
                )

                val barM = SCALE_BAR_STEPS_M.firstOrNull { it * pxPerM >= 48.dp.toPx() } ?: 200
                val barW = barM * pxPerM
                val barX = 14.dp.toPx()
                val barY = size.height - 14.dp.toPx()
                drawLine(
                    color = Color(0xFF334155),
                    start = Offset(barX, barY),
                    end = Offset(barX + barW, barY),
                    strokeWidth = 2.5f
                )
                val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(51, 65, 85)
                    textSize = 10.dp.toPx()
                    isFakeBoldText = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "$barM m · © OpenStreetMap",
                    barX,
                    barY - 5.dp.toPx(),
                    scalePaint
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    follow = true
                    pan = Offset.Zero
                }
            ) {
                Text("Recentrer")
            }
            FilterChip(
                selected = headUp,
                onClick = { headUp = !headUp },
                label = { Text("Cap en haut") }
            )
            OutlinedButton(onClick = onResetPosition, enabled = headingReady) {
                Text("Reset départ")
            }
            OutlinedButton(onClick = onOpenSetup) {
                Text("Config")
            }
        }
    }

    if (showDestDialog) {
        SearchPickerDialog(
            title = "Naviguer vers",
            items = snapshot.destinations,
            itemText = { "[${it.key.floorId}] ${it.label} — ${it.name}" },
            onPick = { onSelectDestination(it.key) },
            onDismiss = { showDestDialog = false }
        )
    }
}

private fun fmt(value: Double, decimals: Int): String =
    String.format(Locale.FRENCH, "%.${decimals}f", value)

private fun Path.moveToOffset(o: Offset) = moveTo(o.x, o.y)

private fun Path.lineToOffset(o: Offset) = lineTo(o.x, o.y)
