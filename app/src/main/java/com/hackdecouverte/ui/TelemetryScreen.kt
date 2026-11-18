package com.hackdecouverte.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PointM
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val SCALE_BAR_STEPS_M = intArrayOf(2, 5, 10, 20, 50, 100, 200)

@Composable
fun TelemetryScreen(
    snapshot: NavigationSnapshot,
    onSelectStartPose: (floorId: String, nodeId: String) -> Unit,
    onSelectDestination: (NodeKey?) -> Unit,
    onSetAccessibleOnly: (Boolean) -> Unit,
    onSwitchFloor: (String) -> Unit,
    onConfirmUserFloor: (targetFloorId: String, verticalGroupId: String?) -> Unit,
    onReturnToUserFloor: () -> Unit,
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

    var zoom by remember { mutableStateOf(0.85f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var follow by remember { mutableStateOf(true) }
    var frozenFocus by remember { mutableStateOf(PointM(0.0, 0.0)) }
    var headUp by remember { mutableStateOf(true) }
    var showStartDialog by remember { mutableStateOf(false) }
    var showDestDialog by remember { mutableStateOf(false) }
    var renderedFloorPair by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }

    // Render active floor immediately + pre-warm all other loaded MB floors in background
    LaunchedEffect(vectorPlan?.floorId) {
        val vp = vectorPlan ?: return@LaunchedEffect
        val bmp = FloorVectorRenderer.getOrRender(vp)
        renderedFloorPair = vp.floorId to bmp
    }
    LaunchedEffect(snapshot.loadedFloorVectors.size) {
        for (vp in snapshot.loadedFloorVectors.values) {
            if (FloorVectorRenderer.getCached(vp.floorId) == null) {
                FloorVectorRenderer.getOrRender(vp)
            }
        }
    }

    val targetPosition = snapshot.mapPositionM
    val displayHeading = rememberSmoothHeadingRad(snapshot.mapHeadingRad)
    // Glide the blue dot between steps instead of jumping 0.7 m at a time
    val animPos = remember {
        Animatable(Offset(targetPosition.x.toFloat(), targetPosition.y.toFloat()), Offset.VectorConverter)
    }
    LaunchedEffect(targetPosition) {
        val target = Offset(targetPosition.x.toFloat(), targetPosition.y.toFloat())
        if ((target - animPos.value).getDistance() > SNAP_DISTANCE_M) {
            animPos.snapTo(target)
        } else {
            animPos.animateTo(target, tween(STEP_GLIDE_MS, easing = LinearOutSlowInEasing))
        }
    }
    val displayPosition = PointM(animPos.value.x.toDouble(), animPos.value.y.toDouble())
    val currentPosition by rememberUpdatedState(displayPosition)
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "pulse"
    )
    val headingReady = state.headingValid && !state.tilted

    val bmpMatrix = remember { Matrix() }
    val bmpPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                route?.let { "Vers [${it.destination.floorId}] ${it.destinationLabel}" }
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
                        Surface(
                            color = if (route.isArrived) Color(0xFF188038) else Color(0xFF0B8043),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    route.nextInstruction,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!route.isArrived) {
                                    Text(
                                        "${fmt(route.totalDistanceM, 0)} m",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(start = 10.dp)
                                    )
                                }
                            }
                        }
                    } else if (snapshot.destination != null && !snapshot.hasExplicitStart && state.stepCount == 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Choisissez votre salle actuelle pour tracer l'itinéraire",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { showStartDialog = true }) {
                                Text("Choisir")
                            }
                        }
                    }

                    // Contextual Stair / Elevator Floor Transition Action
                    val suggested = snapshot.suggestedTransfer
                    val nearbyVert = snapshot.nearbyVertical
                    if (suggested != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val modeTxt = if (suggested.viaElevator) "Ascenseur" else "Escalier"
                            Text(
                                if (snapshot.inVerticalTransit) {
                                    "$modeTxt en cours (${fmtSigned(snapshot.floorDeltaAltitudeM)} m)…"
                                } else {
                                    "$modeTxt ${suggested.viaLabel}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    follow = true
                                    pan = Offset.Zero
                                    onConfirmUserFloor(suggested.toFloorId, suggested.verticalGroupId)
                                }
                            ) {
                                Text("Arrivé au ${suggested.toFloorId}")
                            }
                        }
                    } else if (nearbyVert != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val connLbl = nearbyVert.connectorNode.label.ifBlank { "Escalier/Asc." }
                            Text(
                                "Près de $connLbl :",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            nearbyVert.upFloorId?.let { upId ->
                                AssistChip(
                                    onClick = {
                                        follow = true
                                        pan = Offset.Zero
                                        onConfirmUserFloor(upId, nearbyVert.connectorNode.verticalGroupId)
                                    },
                                    label = { Text("↑ $upId") }
                                )
                            }
                            nearbyVert.downFloorId?.let { downId ->
                                AssistChip(
                                    onClick = {
                                        follow = true
                                        pan = Offset.Zero
                                        onConfirmUserFloor(downId, nearbyVert.connectorNode.verticalGroupId)
                                    },
                                    label = { Text("↓ $downId") }
                                )
                            }
                        }
                    }
                }
            }
        }

        val stepLen = state.currentStepLengthM ?: state.lengthPerStepM
        val capTxt = if (state.headingValid) compassCardinalFr(Math.toDegrees(displayHeading)) else "—"
        val transitTxt = if (snapshot.inVerticalTransit || abs(snapshot.floorDeltaAltitudeM) >= 0.8) {
            "  •  Δz ${fmtSigned(snapshot.floorDeltaAltitudeM)} m"
        } else {
            ""
        }
        Text(
            "Étage : ${snapshot.userFloorId ?: "—"}  •  " +
                "Parcouru : ${fmt(state.distanceM, 1)} m  •  " +
                "Pas : ${state.stepCount}" +
                (stepLen?.let { " (${fmt(it, 2)} m)" } ?: "") +
                "  •  Cap : $capTxt$transitTxt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (snapshot.floors.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (f in snapshot.floors) {
                    val isUserFloor = f.floorId == snapshot.userFloorId
                    val isViewedFloor = f.floorId == snapshot.activeFloorId
                    FilterChip(
                        selected = isViewedFloor,
                        onClick = { onSwitchFloor(f.floorId) },
                        label = {
                            Text(if (isUserFloor) "● ${f.floorId}" else f.floorId)
                        }
                    )
                }
            }
        }

        if (!snapshot.isViewingUserFloor && snapshot.activeFloorId != null) {
            val viewedId = snapshot.activeFloorId
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Aperçu $viewedId (vous êtes au ${snapshot.userFloorId ?: "—"})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            follow = true
                            pan = Offset.Zero
                            onConfirmUserFloor(viewedId, null)
                        }
                    ) {
                        Text("Je suis au $viewedId")
                    }
                    TextButton(
                        onClick = {
                            follow = true
                            pan = Offset.Zero
                            onReturnToUserFloor()
                        }
                    ) {
                        Text("Retour")
                    }
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
                val viewedFloorId = snapshot.activeFloorId
                val pxPerM = 56.dp.toPx() * zoom
                val routePtsOnViewed = viewedFloorId?.let { route?.pointsForFloor(it) }.orEmpty()
                val defaultFocus = if (snapshot.isViewingUserFloor) {
                    displayPosition
                } else {
                    routePtsOnViewed.firstOrNull() ?: displayPosition
                }
                val focus = if (follow) defaultFocus else frozenFocus
                val rotation = if (headUp && snapshot.isViewingUserFloor) -displayHeading else 0.0
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
                drawRect(color = MapPalette.land)

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
                        drawPath(path = poly, color = MapPalette.park)
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
                                color = MapPalette.roadCasing,
                                style = Stroke(
                                    width = widthPx + 2.2f,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                        drawPath(
                            path = roadPath,
                            color = if (isFootway) MapPalette.footway else MapPalette.road,
                            style = Stroke(
                                width = widthPx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }

                    // 4. OSM Surrounding & Focus Buildings
                    val buildingLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(128, 134, 139)
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
                            drawPath(path = poly, color = MapPalette.focusBuilding)
                            drawPath(
                                path = poly,
                                color = MapPalette.focusBuildingStroke,
                                style = Stroke(width = 2.4f, join = StrokeJoin.Round)
                            )
                        } else {
                            drawPath(path = poly, color = MapPalette.building)
                            drawPath(
                                path = poly,
                                color = MapPalette.buildingStroke,
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
                                drawHaloText(
                                    name,
                                    mx,
                                    my + 3.dp.toPx(),
                                    roadNamePaint.textSize,
                                    android.graphics.Color.rgb(95, 99, 104),
                                    rotationDeg = deg
                                )
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
                val pair = renderedFloorPair
                val bmp = vectorPlan?.let { vp ->
                    FloorVectorRenderer.getCached(vp.floorId)
                        ?: pair?.takeIf { it.first == vp.floorId }?.second
                }
                if (bmp != null) {
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

                val density = 1.dp.toPx()

                // 8. Stairs, elevators and room numbers on the displayed floor
                if (floorPlan != null && pxPerM >= 3.0f) {
                    val drawnRoomLabels = HashSet<String>()
                    for (node in floorPlan.nodes) {
                        if (node.posM.x !in viewBounds.minX..viewBounds.maxX ||
                            node.posM.y !in viewBounds.minY..viewBounds.maxY
                        ) {
                            continue
                        }
                        val sc = toScreen(node.posM)
                        when (node.type) {
                            "stairs" -> drawStairsIcon(sc, density)
                            "elevator" -> drawElevatorIcon(sc, density)
                            "room" -> if (pxPerM >= 14f && node.label.isNotBlank() && drawnRoomLabels.add(node.label)) {
                                drawHaloText(
                                    node.label.removePrefix("MB-"),
                                    sc.x,
                                    sc.y + 3.5f * density,
                                    10.5f * density,
                                    MapPalette.labelText
                                )
                            }
                        }
                    }
                }

                // 9. Walked trail (under the route), on the user's floor only
                if (snapshot.isViewingUserFloor && state.rawPath.size >= 2) {
                    val trail = ArrayList<Offset>(state.rawPath.size)
                    for (i in 0 until state.rawPath.size - 1) trail.add(toScreen(disp(state.rawPath[i])))
                    trail.add(toScreen(displayPosition))
                    drawWalkedTrail(trail, 5f * density)
                    drawStartMarker(trail.first(), density)
                }

                // 9b. Map localization (developer switch): particles and zones, in the plan frame
                val loc = snapshot.localization
                if (snapshot.localizationEnabled && snapshot.isViewingUserFloor && loc != null) {
                    drawLocalization(
                        particles = snapshot.localizationParticles.map { toScreen(it) },
                        zones = loc.zones.take(3).map { toScreen(it.centerM) to (it.radiusM * transform.pxPerMeter).toFloat() },
                        density = density,
                        trace = snapshot.localizationTrace.map { toScreen(it) },
                        gpsDisk = snapshot.localizationSystemCircle?.let { (c, r) -> toScreen(c) to (r * transform.pxPerMeter).toFloat() }
                    )
                }

                // 10. Navigation route, floor-transfer callouts and destination pin
                if (routePtsOnViewed.size >= 2) {
                    val pts = ArrayList<Offset>(routePtsOnViewed.size + 1)
                    if (snapshot.isViewingUserFloor && route?.floorSegments?.firstOrNull()?.floorId == viewedFloorId) {
                        pts.add(toScreen(displayPosition))
                    }
                    routePtsOnViewed.forEach { pts.add(toScreen(it)) }
                    drawRouteLine(pts, density)
                    if (route != null && route.destination.floorId == viewedFloorId) {
                        drawDestinationPin(pts.last(), density, route.destinationLabel)
                    }
                }
                if (route != null && viewedFloorId != null) {
                    for (tr in route.transfers) {
                        when (viewedFloorId) {
                            tr.fromFloorId -> {
                                val up = extractFloorOrd(tr.toFloorId) > extractFloorOrd(tr.fromFloorId)
                                val glyph = if (tr.viaElevator) "Ascenseur" else "Escalier"
                                drawRoutePill(toScreen(tr.departurePosM), "${if (up) "↑" else "↓"} $glyph ${tr.toFloorId}", density)
                            }
                            tr.toFloorId -> drawRoutePill(toScreen(tr.arrivalPosM), "Depuis ${tr.fromFloorId}", density)
                        }
                    }
                }

                // 11. Precise outdoor GNSS only (indoor fixes are too coarse to be useful)
                snapshot.latestGpsFix?.let { fix ->
                    if (fix.accuracyM <= 12.0 && fix.eastNorthM.norm() <= 350.0) {
                        val rPx = (fix.accuracyM.toFloat() * pxPerM).coerceIn(8f * density, 70f * density)
                        drawCircle(MapPalette.userBlue.copy(alpha = 0.08f), radius = rPx, center = toScreen(fix.eastNorthM))
                    }
                }

                // 12. Blue location dot with heading beam
                if (snapshot.isViewingUserFloor) {
                    val here = toScreen(displayPosition)
                    val ahead = toScreen(
                        PointM(
                            displayPosition.x + sin(displayHeading),
                            displayPosition.y + cos(displayHeading)
                        )
                    )
                    drawUserLocation(
                        center = here,
                        headingScreenRad = if (state.headingValid) screenHeading(here, ahead) else null,
                        pulse = pulse,
                        density = density,
                        headingValid = headingReady
                    )
                }

                // 13. Top-left Active Floor Watermark Pill + Top-right North Badge + Scale Bar
                if (viewedFloorId != null) {
                    val floorBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (snapshot.isViewingUserFloor) {
                            android.graphics.Color.argb(225, 15, 23, 42)
                        } else {
                            android.graphics.Color.argb(225, 180, 83, 9)
                        }
                    }
                    val floorBadgeTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        textSize = 11.5.dp.toPx()
                        isFakeBoldText = true
                    }
                    val badgeLabel = if (snapshot.isViewingUserFloor) {
                        "Plan $viewedFloorId"
                    } else {
                        "Aperçu $viewedFloorId"
                    }
                    val tw = floorBadgeTxt.measureText(badgeLabel)
                    val badgeRect = RectF(
                        10.dp.toPx(),
                        10.dp.toPx(),
                        26.dp.toPx() + tw,
                        34.dp.toPx()
                    )
                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        badgeRect,
                        8.dp.toPx(),
                        8.dp.toPx(),
                        floorBadgeBg
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        badgeLabel,
                        18.dp.toPx(),
                        26.dp.toPx(),
                        floorBadgeTxt
                    )
                }

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
                    if (!snapshot.isViewingUserFloor) {
                        onReturnToUserFloor()
                    }
                }
            ) {
                Text("Recentrer")
            }
            OutlinedButton(onClick = { showStartDialog = true }) {
                val startShort = snapshot.startPose?.let { sp ->
                    snapshot.userFloor?.node(sp.nodeId)?.label?.ifBlank { sp.floorId }
                }
                Text(startShort?.let { "Ici : $it" } ?: "Je suis à…")
            }
            FilterChip(
                selected = headUp,
                onClick = { headUp = !headUp },
                label = { Text("Cap en haut") }
            )
            OutlinedButton(onClick = onResetPosition) {
                Text("Reset départ")
            }
            OutlinedButton(onClick = onOpenSetup) {
                Text("Config")
            }
        }
    }

    if (showStartDialog) {
        RoomSearchDialog(
            title = "Où êtes-vous maintenant ?",
            items = snapshot.destinations,
            preferredFloorId = snapshot.activeFloorId ?: snapshot.userFloorId,
            onPick = {
                follow = true
                pan = Offset.Zero
                onSelectStartPose(it.key.floorId, it.key.nodeId)
            },
            onDismiss = { showStartDialog = false }
        )
    }

    if (showDestDialog) {
        RoomSearchDialog(
            title = "Naviguer vers",
            items = snapshot.destinations,
            preferredFloorId = snapshot.activeFloorId ?: snapshot.userFloorId,
            onPick = { onSelectDestination(it.key) },
            onDismiss = { showDestDialog = false }
        )
    }
}

private const val SNAP_DISTANCE_M = 4f
private const val STEP_GLIDE_MS = 420

private fun extractFloorOrd(floorId: String): Int =
    floorId.substringAfterLast('-', "").toIntOrNull() ?: 0

private fun fmt(value: Double, decimals: Int): String =
    String.format(Locale.FRENCH, "%.${decimals}f", value)

private fun fmtSigned(value: Double): String =
    String.format(Locale.FRENCH, "%+.1f", value)

private fun Path.moveToOffset(o: Offset) = moveTo(o.x, o.y)

private fun Path.lineToOffset(o: Offset) = lineTo(o.x, o.y)
