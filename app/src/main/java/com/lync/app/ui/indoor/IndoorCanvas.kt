package com.lync.app.ui.indoor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.hackdecouverte.backend.NavigationSnapshot
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.ui.FloorVectorRenderer
import com.hackdecouverte.ui.MapPalette
import com.hackdecouverte.ui.MapTransform
import com.hackdecouverte.ui.drawDestinationPin
import com.hackdecouverte.ui.drawElevatorIcon
import com.hackdecouverte.ui.drawHaloText
import com.hackdecouverte.ui.drawRouteLine
import com.hackdecouverte.ui.drawRoutePill
import com.hackdecouverte.ui.drawStairsIcon
import com.hackdecouverte.ui.drawStartMarker
import com.hackdecouverte.ui.drawUserLocation
import com.hackdecouverte.ui.drawWalkedTrail
import com.hackdecouverte.ui.rememberSmoothHeadingRad
import com.hackdecouverte.ui.screenHeading
import com.lync.app.ui.theme.CampusColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val SCALE_BAR_STEPS_M = intArrayOf(2, 5, 10, 20, 50, 100, 200)
private const val SNAP_DISTANCE_M = 4f
private const val STEP_GLIDE_MS = 420

/**
 * Camera of the indoor plan.
 * `headUp = true` by default so the map rotates continuously with the phone like Google Maps
 * (whatever is in front of the phone stays straight ahead at the top of the screen).
 */
class IndoorCamera {
    var zoom by mutableStateOf(0.85f)
    var pan by mutableStateOf(Offset.Zero)
    var follow by mutableStateOf(true)
    var frozenFocus by mutableStateOf(PointM(0.0, 0.0))
    var headUp by mutableStateOf(true)

    fun recenter() {
        follow = true
        pan = Offset.Zero
        headUp = true
    }
}

/**
 * Indoor MB map canvas with OpenStreetMap campus surroundings, 7-floor cached aligned vector
 * floor plans, walked blue trail, Google-Maps-style route line with chevrons, stair/elevator
 * badges, upright room numbers, and smooth Head-Up phone orientation.
 */
@Composable
fun IndoorCanvas(
    snapshot: NavigationSnapshot,
    camera: IndoorCamera,
    colors: CampusColors,
    modifier: Modifier = Modifier,
    bottomInsetPx: Float = 0f,
) {
    val state = snapshot.pdr
    val vectorPlan = snapshot.activeFloorVector
    val floorPlan = snapshot.activeFloor
    val anchor = snapshot.anchor
    val route = snapshot.activeRoute
    val targetPosition = snapshot.mapPositionM
    val smoothHeading = rememberSmoothHeadingRad(snapshot.mapHeadingRad)
    val headingReady = state.headingValid && !state.tilted

    var renderedFloorPair by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }
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

    // Smoothly glide the blue location dot between steps
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

    val pulse by rememberInfiniteTransition(label = "indoorPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "indoorPulse"
    )

    val bmpMatrix = remember { Matrix() }
    val wallColorArgb = if (colors.isDark) {
        colors.text.copy(alpha = 0.82f).toArgb()
    } else {
        android.graphics.Color.rgb(95, 99, 104)
    }
    val wallPaint = remember(wallColorArgb) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(wallColorArgb, PorterDuff.Mode.SRC_IN)
        }
    }

    val landColor = if (colors.isDark) colors.surface else MapPalette.land
    val parkColor = if (colors.isDark) Color(0xFF1C2B22) else MapPalette.park
    val roadColor = if (colors.isDark) colors.bg else MapPalette.road
    val roadCasingColor = if (colors.isDark) colors.border else MapPalette.roadCasing
    val footwayColor = if (colors.isDark) colors.surfaceAlt else MapPalette.footway
    val otherBldgColor = if (colors.isDark) colors.surfaceAlt else MapPalette.building
    val otherBldgStroke = if (colors.isDark) colors.border else MapPalette.buildingStroke
    val mbBldgColor = if (colors.isDark) colors.bg else MapPalette.focusBuilding
    val mbBldgStroke = if (colors.isDark) colors.textMuted else MapPalette.focusBuildingStroke

    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    camera.zoom = (camera.zoom * zoomChange).coerceIn(0.04f, 8f)
                    if (panChange != Offset.Zero) {
                        if (camera.follow) {
                            camera.follow = false
                            camera.frozenFocus = currentPosition
                        }
                        camera.pan += panChange
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val density = 1.dp.toPx()
            val viewedFloorId = snapshot.activeFloorId
            val pxPerM = 56.dp.toPx() * camera.zoom
            val routePtsOnViewed = viewedFloorId?.let { route?.pointsForFloor(it) }.orEmpty()
            val defaultFocus = if (snapshot.isViewingUserFloor) {
                displayPosition
            } else {
                routePtsOnViewed.firstOrNull() ?: displayPosition
            }
            val focus = if (camera.follow) defaultFocus else camera.frozenFocus
            val activeHeadUp = camera.headUp && snapshot.isViewingUserFloor && state.headingValid
            val rotation = if (activeHeadUp) -smoothHeading else 0.0
            val visibleHeight = (size.height - bottomInsetPx).coerceAtLeast(size.height * 0.35f)
            val centerYFactor = if (activeHeadUp && camera.follow) 0.58 else 0.50

            val t = MapTransform(
                focus = focus,
                panXPx = camera.pan.x.toDouble(),
                panYPx = camera.pan.y.toDouble(),
                pxPerMeter = pxPerM.toDouble(),
                rotationRad = rotation,
                centerXPx = size.width / 2.0,
                centerYPx = visibleHeight * centerYFactor,
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

            // 1. OpenStreetMap Base Land
            drawRect(landColor)

            // 2. OpenStreetMap Parks, Streets, Campus Buildings & Metro POIs
            snapshot.osmSurroundings?.let { osm ->
                for (p in osm.parks) {
                    if (p.polygonM.any { inView(it) }) {
                        drawPath(polyPath(p.polygonM, true), parkColor)
                    }
                }
                for (road in osm.roads) {
                    if (road.polylineM.none { inView(it) }) continue
                    val path = polyPath(road.polylineM, false)
                    val w = (road.widthM.toFloat() * pxPerM).coerceIn(2.5f, 64f)
                    val footway = road.kind == "footway" || road.kind == "cycleway"
                    if (!footway) {
                        drawPath(path, roadCasingColor, style = Stroke(w + 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    drawPath(path, if (footway) footwayColor else roadColor, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                val bldgTextArgb = if (colors.isDark) colors.textMuted.toArgb() else android.graphics.Color.rgb(112, 117, 122)
                for (b in osm.buildings) {
                    if (!inView(b.labelCenterM, 60.0)) continue
                    val path = polyPath(b.polygonM, true)
                    if (b.isFocusBuilding) {
                        drawPath(path, mbBldgColor)
                        drawPath(path, mbBldgStroke, style = Stroke(2.4f, join = StrokeJoin.Round))
                    } else {
                        drawPath(path, otherBldgColor)
                        drawPath(path, otherBldgStroke, style = Stroke(1.2f, join = StrokeJoin.Round))
                        val tag = b.ref.ifBlank { b.name }
                        if (tag.isNotBlank() && pxPerM >= 2.2f) {
                            val sc = toScreen(b.labelCenterM)
                            if (!colors.isDark) {
                                drawHaloText(tag.take(20), sc.x, sc.y + 3.dp.toPx(), 10.5f * density, bldgTextArgb, bold = true)
                            } else {
                                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = bldgTextArgb; textSize = 10.5f * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true
                                }
                                drawContext.canvas.nativeCanvas.drawText(tag.take(20), sc.x, sc.y + 3.dp.toPx(), p)
                            }
                        }
                    }
                }
                if (pxPerM >= 2.6f && !colors.isDark) {
                    val drawnStreetNames = HashSet<String>()
                    for (road in osm.roads) {
                        val name = road.name
                        if (name.isBlank() || name in drawnStreetNames || road.polylineM.size < 2) continue
                        var bestIdx = -1
                        var bestLen = 0f
                        for (i in 0 until road.polylineM.size - 1) {
                            val a = road.polylineM[i]
                            val b = road.polylineM[i + 1]
                            val mid = PointM((a.x + b.x) * 0.5, (a.y + b.y) * 0.5)
                            if (!inView(mid)) continue
                            val sa = toScreen(a)
                            val sb = toScreen(b)
                            val segPx = hypot(sb.x - sa.x, sb.y - sa.y)
                            if (segPx > bestLen) {
                                bestLen = segPx
                                bestIdx = i
                            }
                        }
                        if (bestIdx >= 0 && bestLen >= 75f * density) {
                            drawnStreetNames.add(name)
                            val sa = toScreen(road.polylineM[bestIdx])
                            val sb = toScreen(road.polylineM[bestIdx + 1])
                            var deg = Math.toDegrees(atan2((sb.y - sa.y).toDouble(), (sb.x - sa.x).toDouble())).toFloat()
                            if (deg > 90f) deg -= 180f
                            if (deg < -90f) deg += 180f
                            drawHaloText(
                                text = name,
                                x = (sa.x + sb.x) * 0.5f,
                                y = (sa.y + sb.y) * 0.5f + 3f * density,
                                sizePx = 9.5f * density,
                                color = android.graphics.Color.rgb(95, 99, 104),
                                rotationDeg = deg
                            )
                        }
                    }
                }
                for (poi in osm.pois) {
                    val sp = toScreen(poi.positionM)
                    drawCircle(Color(0xFF0284C7), 4.5.dp.toPx(), sp)
                    drawCircle(Color.White, 2.dp.toPx(), sp)
                }
            }

            // 3. Aligned MB Vector Floor Plan Background (MB-1..7)
            val pair = renderedFloorPair
            val bmp = vectorPlan?.let { vp ->
                FloorVectorRenderer.getCached(vp.floorId)
                    ?: pair?.takeIf { it.first == vp.floorId }?.second
            }
            if (bmp != null) {
                val b = vectorPlan.viewBoundsM
                val tl = toScreen(PointM(b.minEastM, b.maxNorthM))
                val tr = toScreen(PointM(b.maxEastM, b.maxNorthM))
                val bl = toScreen(PointM(b.minEastM, b.minNorthM))
                val src = floatArrayOf(0f, 0f, bmp.width.toFloat(), 0f, 0f, bmp.height.toFloat())
                val dst = floatArrayOf(tl.x, tl.y, tr.x, tr.y, bl.x, bl.y)
                if (bmpMatrix.setPolyToPoly(src, 0, dst, 0, 3)) {
                    drawContext.canvas.nativeCanvas.drawBitmap(bmp, bmpMatrix, wallPaint)
                }
            } else if (floorPlan != null) {
                for (e in floorPlan.edges) {
                    val a = floorPlan.node(e.fromId)?.posM ?: continue
                    val z = floorPlan.node(e.toId)?.posM ?: continue
                    drawLine(colors.surfaceAlt, toScreen(a), toScreen(z), (1.5f * pxPerM).coerceIn(4f, 16f), StrokeCap.Round)
                }
            }

            // 4. Upright Stairs, Elevators & Room Numbers (always upright even when map rotates!)
            if (floorPlan != null && pxPerM >= 3.0f) {
                val drawnRooms = HashSet<String>()
                val roomTextArgb = if (colors.isDark) colors.text.toArgb() else MapPalette.labelText
                for (node in floorPlan.nodes) {
                    if (!inView(node.posM)) continue
                    val sc = toScreen(node.posM)
                    when (node.type) {
                        "stairs" -> drawStairsIcon(sc, density)
                        "elevator" -> drawElevatorIcon(sc, density)
                        "room" -> if (pxPerM >= 14f && node.label.isNotBlank() && drawnRooms.add(node.label)) {
                            if (!colors.isDark) {
                                drawHaloText(node.label.removePrefix("MB-"), sc.x, sc.y + 3.5f * density, 10.5f * density, roomTextArgb)
                            } else {
                                val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = roomTextArgb; textSize = 10.5f * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true
                                }
                                drawContext.canvas.nativeCanvas.drawText(node.label.removePrefix("MB-"), sc.x, sc.y + 3.5f * density, rp)
                            }
                        }
                    }
                }
            }

            // 5. Walked blue trail (under the active route), on the user's floor
            if (snapshot.isViewingUserFloor && state.rawPath.size >= 2) {
                val trail = ArrayList<Offset>(state.rawPath.size)
                for (i in 0 until state.rawPath.size - 1) {
                    trail.add(toScreen(disp(state.rawPath[i])))
                }
                trail.add(toScreen(displayPosition))
                drawWalkedTrail(trail, 5f * density)
                drawStartMarker(trail.first(), density)
            }

            // 6. Navigation Route (Google-Maps blue with directional chevrons), Destination Pin & Floor Callouts
            if (routePtsOnViewed.size >= 2) {
                val pts = ArrayList<Offset>(routePtsOnViewed.size + 1)
                if (snapshot.isViewingUserFloor && route?.floorSegments?.firstOrNull()?.floorId == viewedFloorId) {
                    pts.add(toScreen(displayPosition))
                }
                routePtsOnViewed.forEach { pts.add(toScreen(it)) }
                drawRouteLine(pts, density)
                if (route != null && route.destination.floorId == viewedFloorId) {
                    drawDestinationPin(pts.last(), density, if (!colors.isDark) route.destinationLabel else null)
                }
            }
            if (route != null && viewedFloorId != null) {
                for (tr in route.transfers) {
                    when (viewedFloorId) {
                        tr.fromFloorId -> {
                            val up = (tr.toFloorId.substringAfterLast('-').toIntOrNull() ?: 0) >
                                (tr.fromFloorId.substringAfterLast('-').toIntOrNull() ?: 0)
                            val glyph = if (tr.viaElevator) "Ascenseur" else "Escalier"
                            drawRoutePill(toScreen(tr.departurePosM), "${if (up) "↑" else "↓"} $glyph ${tr.toFloorId}", density)
                        }
                        tr.toFloorId -> drawRoutePill(toScreen(tr.arrivalPosM), "Depuis ${tr.fromFloorId}", density)
                    }
                }
            }

            // 7. Outdoor high-precision GNSS halo only
            snapshot.latestGpsFix?.let { fix ->
                if (fix.accuracyM <= 12.0 && fix.eastNorthM.norm() <= 350.0) {
                    val r = (fix.accuracyM.toFloat() * pxPerM).coerceIn(8f * density, 70f * density)
                    drawCircle(MapPalette.userBlue.copy(alpha = 0.08f), r, toScreen(fix.eastNorthM))
                }
            }

            // 8. Gliding Blue Location Dot + Heading Beam (points straight UP when Head-Up is active)
            if (snapshot.isViewingUserFloor) {
                val here = toScreen(displayPosition)
                val ahead = toScreen(
                    PointM(
                        displayPosition.x + sin(smoothHeading),
                        displayPosition.y + cos(smoothHeading)
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
    drawLine(Color(0xFFDC2626), center, Offset(center.x + nx * 10.dp.toPx(), center.y + ny * 10.dp.toPx()), 2.4.dp.toPx(), StrokeCap.Round)
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
