package com.hackdecouverte.ui

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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.MapAnchor
import com.hackdecouverte.pdr.PathGeometry
import com.hackdecouverte.pdr.PathSegment
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PdrState
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.StepSource
import java.util.Locale
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.cos

private val GRID_STEPS_M = doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun TelemetryScreen(
    state: PdrState,
    floorPlan: FloorPlan?,
    anchor: MapAnchor?,
    onResetTrace: () -> Unit,
    onRecalibrate: () -> Unit,
    onResumeNewTrace: () -> Unit
) {
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var follow by remember { mutableStateOf(true) }
    var frozenFocus by remember { mutableStateOf(PointM(0.0, 0.0)) }
    var headUp by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var showPlan by remember { mutableStateOf(true) }
    val displayPosition = anchor?.toMap(state.position) ?: state.position
    val displayHeading = anchor?.mapHeading(state.headingRadians) ?: state.headingRadians
    val currentPosition by rememberUpdatedState(displayPosition)
    val segments = remember(state.rawPath) { PathGeometry.smoothedSegments(state.rawPath) }
    val headingReady = state.headingValid && !state.tilted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WarningList(state)

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
                        "Trajet conservé — référence perdue",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onResumeNewTrace, enabled = headingReady) {
                        Text("Reprendre (nouveau trajet)")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Metric("Distance", fmt(state.distanceM, 1) + " m")
            val cap = if (state.headingValid) {
                fmt(Math.toDegrees(state.headingRadians), 0) + "°"
            } else {
                "—"
            }
            Metric("Cap relatif", cap)
            if (anchor != null) {
                val capPlan = if (state.headingValid) {
                    fmt(Math.toDegrees(displayHeading), 0) + "°"
                } else {
                    "—"
                }
                Metric("Cap plan", capPlan)
            }
            Metric("Pas", state.stepCount.toString())
            val length = state.lengthPerStepM
            Metric(
                "Longueur du pas",
                if (length == null) {
                    "—"
                } else {
                    fmt(length, 2) + " m" + if (state.stepLengthManual) " (man.)" else ""
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when (state.stepSource) {
                    StepSource.HARDWARE -> "Source : détecteur matériel"
                    StepSource.FALLBACK -> "Source : accéléromètre (expérimental)"
                    StepSource.NONE -> "Source : —"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (state.droppedStepCount > 0) {
                Text(
                    "Pas ignorés : ${state.droppedStepCount}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.2f, 12f)
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

                drawRect(color = Color(0xFFF5F5F5))

                val gridStep = GRID_STEPS_M.firstOrNull { it * pxPerM >= 36.0 } ?: 200.0
                val bounds = transform.viewBoundsWorld(
                    size.width.toDouble(),
                    size.height.toDouble(),
                    marginM = 2.0
                )
                val gridColor = Color(0xFFD8DDE3)
                val axisColor = Color(0xFFB0B8C4)
                var gx = floor(bounds.minX / gridStep) * gridStep
                while (gx <= bounds.maxX) {
                    drawLine(
                        color = if (kotlin.math.abs(gx) < 1e-9) axisColor else gridColor,
                        start = toScreen(PointM(gx, bounds.minY)),
                        end = toScreen(PointM(gx, bounds.maxY)),
                        strokeWidth = 1f
                    )
                    gx += gridStep
                }
                var gy = floor(bounds.minY / gridStep) * gridStep
                while (gy <= bounds.maxY) {
                    drawLine(
                        color = if (kotlin.math.abs(gy) < 1e-9) axisColor else gridColor,
                        start = toScreen(PointM(bounds.minX, gy)),
                        end = toScreen(PointM(bounds.maxX, gy)),
                        strokeWidth = 1f
                    )
                    gy += gridStep
                }

                if (floorPlan != null && showPlan) {
                    for (edge in floorPlan.edges) {
                        val a = floorPlan.node(edge.fromId)?.posM ?: continue
                        val b = floorPlan.node(edge.toId)?.posM ?: continue
                        if (edge.accessible) {
                            drawLine(
                                color = Color(0xFFB0BEC5),
                                start = toScreen(a),
                                end = toScreen(b),
                                strokeWidth = 4f,
                                cap = StrokeCap.Round
                            )
                        } else {
                            drawLine(
                                color = Color(0xFF9E9E9E),
                                start = toScreen(a),
                                end = toScreen(b),
                                strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                            )
                        }
                    }
                    val nodeLabelPaint = Paint().apply {
                        color = android.graphics.Color.DKGRAY
                        textSize = 9.dp.toPx()
                        isAntiAlias = true
                    }
                    for (node in floorPlan.nodes) {
                        val s = toScreen(node.posM)
                        when (node.type) {
                            "corridor" -> drawCircle(
                                color = Color(0xFF90A4AE),
                                radius = 3f,
                                center = s
                            )

                            "stairs" -> drawRect(
                                color = Color(0xFFF57C00),
                                topLeft = Offset(s.x - 5f, s.y - 5f),
                                size = androidx.compose.ui.geometry.Size(10f, 10f)
                            )

                            "elevator" -> drawRoundRect(
                                color = Color(0xFF7E57C2),
                                topLeft = Offset(s.x - 6f, s.y - 6f),
                                size = androidx.compose.ui.geometry.Size(12f, 12f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                            )

                            else -> drawRect(
                                color = Color(0xFFA1887F),
                                topLeft = Offset(s.x - 5f, s.y - 5f),
                                size = androidx.compose.ui.geometry.Size(10f, 10f)
                            )
                        }
                        if (node.type != "corridor" || pxPerM > 20f) {
                            drawContext.canvas.nativeCanvas.drawText(
                                node.label,
                                s.x + 7f,
                                s.y - 7f,
                                nodeLabelPaint
                            )
                        }
                    }
                }

                val traceColor = Color(0xFF1565C0)
                val rawPoints = state.rawPath
                if (showRaw && rawPoints.size >= 2) {
                    val rawPath = Path()
                    rawPath.moveToOffset(toScreen(disp(rawPoints[0])))
                    for (i in 1 until rawPoints.size) {
                        rawPath.lineToOffset(toScreen(disp(rawPoints[i])))
                    }
                    drawPath(
                        path = rawPath,
                        color = Color(0xFF90A4AE),
                        style = Stroke(
                            width = 2f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                        )
                    )
                }

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
                        style = Stroke(
                            width = 5f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                val originScreen = toScreen(disp(PointM(0.0, 0.0)))
                drawCircle(
                    color = Color(0xFF2E7D32),
                    radius = 7.dp.toPx(),
                    center = originScreen
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "Départ",
                    originScreen.x + 8.dp.toPx(),
                    originScreen.y - 8.dp.toPx(),
                    Paint().apply {
                        color = android.graphics.Color.DKGRAY
                        textSize = 10.dp.toPx()
                        isAntiAlias = true
                    }
                )

                for (milestone in state.milestones) {
                    val ms = toScreen(disp(milestone.position))
                    drawCircle(color = Color.White, radius = 6.dp.toPx(), center = ms)
                    drawCircle(
                        color = Color(0xFF37474F),
                        radius = 6.dp.toPx(),
                        center = ms,
                        style = Stroke(width = 2f)
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        fmt(milestone.distanceM, 0) + " m",
                        ms.x + 8.dp.toPx(),
                        ms.y - 8.dp.toPx(),
                        Paint().apply {
                            color = android.graphics.Color.DKGRAY
                            textSize = 10.dp.toPx()
                            isAntiAlias = true
                        }
                    )
                }

                val here = toScreen(displayPosition)
                val heading = state.headingRadians
                val coneColor = if (state.headingValid) {
                    Color(0x551565C0)
                } else {
                    Color(0x5590A4AE)
                }
                val tipWorld = disp(state.position + PointM(sin(heading) * 0.9, cos(heading) * 0.9))
                val tip = toScreen(tipWorld)
                val vx = tip.x - here.x
                val vy = tip.y - here.y
                val vLen = kotlin.math.hypot(vx, vy)
                if (vLen > 1f) {
                    val px = -vy / vLen
                    val py = vx / vLen
                    val half = vLen * 0.28f
                    val cone = Path()
                    cone.moveToOffset(tip)
                    cone.lineToOffset(Offset(here.x + px * half, here.y + py * half))
                    cone.lineToOffset(Offset(here.x - px * half, here.y - py * half))
                    cone.close()
                    drawPath(path = cone, color = coneColor)
                }
                drawCircle(color = traceColor, radius = 8.dp.toPx(), center = here)

                drawContext.canvas.nativeCanvas.drawText(
                    "Grille : " + fmt(gridStep, 1) + " m — Cap relatif, dérive possible",
                    12.dp.toPx(),
                    size.height - 10.dp.toPx(),
                    Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 10.dp.toPx()
                        isAntiAlias = true
                    }
                )
            }
            if (floorPlan != null && showPlan && anchor == null) {
                Text(
                    "Sélectionnez une salle de départ pour ancrer le tracé",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
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
                    zoom = 1f
                }
            ) {
                Text("Recentrer")
            }
            FilterChip(
                selected = !headUp,
                onClick = { headUp = false },
                label = { Text("Axe fixe") }
            )
            FilterChip(
                selected = headUp,
                onClick = { headUp = true },
                label = { Text("Cap en haut") }
            )
            FilterChip(
                selected = showRaw,
                onClick = { showRaw = !showRaw },
                label = { Text("Tracé brut") }
            )
            if (floorPlan != null) {
                FilterChip(
                    selected = showPlan,
                    onClick = { showPlan = !showPlan },
                    label = { Text("Plan") }
                )
            }
            OutlinedButton(onClick = onResetTrace, enabled = headingReady) {
                Text("Reset")
            }
            OutlinedButton(onClick = onRecalibrate) {
                Text("Recalibrer")
            }
        }
    }
}

private fun fmt(value: Double, decimals: Int): String =
    String.format(Locale.FRENCH, "%.${decimals}f", value)

private fun Path.moveToOffset(o: Offset) = moveTo(o.x, o.y)

private fun Path.lineToOffset(o: Offset) = lineTo(o.x, o.y)
