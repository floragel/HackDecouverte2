package com.lync.app.ui.go

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lync.app.data.places.GeoPoint
import com.lync.app.data.places.WalkRoute
import com.lync.app.ui.theme.Campus
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

// OpenFreeMap (OpenStreetMap data, no key). Liberty = colourful streets + 3D buildings when tilted.
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val SRC_ROUTE = "go-route"
private const val LAYER_CASING = "go-route-casing"
private const val LAYER_LINE = "go-route-line"

/** What the outdoor camera does: show the whole trip, or follow the walker like turn-by-turn navigation. */
enum class OutdoorCamera { Overview, Follow, Free }

/**
 * Street map for the outdoor leg: route line (casing + university colour, dashed when approximate),
 * pulsing "me" dot with walking direction, and the destination flag.
 */
@Composable
fun OutdoorMap(
    route: WalkRoute?,
    me: GeoPoint?,
    meBearingDeg: Double?,
    destination: GeoPoint,
    camera: OutdoorCamera,
    followBearingDeg: Double?,
    padding: PaddingPx,
    onUserMovedMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Campus.colors
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleTick by remember { mutableIntStateOf(0) }
    var cameraTick by remember { mutableIntStateOf(0) }
    val onGesture by rememberUpdatedState(onUserMovedMap)

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isCompassEnabled = false
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.uiSettings.isAttributionEnabled = true
                m.cameraPosition = CameraPosition.Builder().target(LatLng(destination.lat, destination.lng)).zoom(16.0).build()
                m.addOnCameraMoveListener { cameraTick++ }
                m.addOnCameraIdleListener { cameraTick++ }
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onGesture()
                }
                map = m
            }
        }
    }
    LaunchedEffect(c.isDark) {
        mapView.getMapAsync { m ->
            m.setStyle(if (c.isDark) STYLE_DARK else STYLE_LIGHT) { style ->
                addRouteLayers(style)
                styleTick++
            }
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    // Route line data + colours (re-applied after every style switch, which drops custom layers)
    val lineColor = c.accent.toArgb()
    val casingColor = (if (c.isDark) Color(0xFF0B0D14) else Color.White).toArgb()
    LaunchedEffect(route, styleTick, lineColor) {
        val style = map?.style ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        style.getLayerAs<LineLayer>(LAYER_LINE)?.setProperties(
            PropertyFactory.lineColor(lineColor),
            PropertyFactory.lineDasharray(if (route?.approximate == true) arrayOf(1.2f, 1.4f) else arrayOf(1f, 0f)),
        )
        style.getLayerAs<LineLayer>(LAYER_CASING)?.setProperties(PropertyFactory.lineColor(casingColor))
        val source = style.getSourceAs<GeoJsonSource>(SRC_ROUTE) ?: return@LaunchedEffect
        val pts = route?.points.orEmpty()
        if (pts.size >= 2) {
            source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lng, it.lat) })))
        } else {
            source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(emptyList())))
        }
    }

    // Camera
    LaunchedEffect(camera, route, map, padding) {
        val m = map ?: return@LaunchedEffect
        if (camera != OutdoorCamera.Overview) return@LaunchedEffect
        val pts = (route?.points.orEmpty() + listOfNotNull(me, destination)).distinct()
        if (pts.size >= 2) {
            val bounds = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lng)) } }.build()
            m.animateCamera(
                CameraUpdateFactory.newLatLngBounds(bounds, padding.left, padding.top, padding.right, padding.bottom),
                900,
            )
        } else {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(destination.lat, destination.lng)).zoom(17.0).tilt(0.0).bearing(0.0).build()), 900)
        }
    }
    LaunchedEffect(camera, me, followBearingDeg, map) {
        val m = map ?: return@LaunchedEffect
        val p = me ?: return@LaunchedEffect
        if (camera != OutdoorCamera.Follow) return@LaunchedEffect
        m.setPadding(0, padding.top, 0, padding.bottom)
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(p.lat, p.lng)).zoom(18.2).tilt(52.0)
                    .bearing(followBearingDeg ?: m.cameraPosition.bearing).build(),
            ),
            900,
        )
    }
    LaunchedEffect(camera) {
        if (camera != OutdoorCamera.Follow) map?.setPadding(0, 0, 0, 0)
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        val m = map
        if (m != null) {
            @Suppress("UNUSED_VARIABLE") val tick = cameraTick // re-project overlays when the camera moves
            val density = LocalDensity.current
            val dpt = m.projection.toScreenLocation(LatLng(destination.lat, destination.lng))
            DestinationFlag(Modifier.offset { IntOffset((dpt.x - with(density) { 20.dp.toPx() }).toInt(), (dpt.y - with(density) { 50.dp.toPx() }).toInt()) })
            me?.let { p ->
                val pt = m.projection.toScreenLocation(LatLng(p.lat, p.lng))
                val rel = meBearingDeg?.let { (it - m.cameraPosition.bearing).toFloat() }
                WalkerDot(rel, Modifier.offset { IntOffset((pt.x - with(density) { 36.dp.toPx() }).toInt(), (pt.y - with(density) { 36.dp.toPx() }).toInt()) })
            }
        }
    }
}

data class PaddingPx(val left: Int, val top: Int, val right: Int, val bottom: Int)

private fun addRouteLayers(style: Style) {
    if (style.getSource(SRC_ROUTE) == null) style.addSource(GeoJsonSource(SRC_ROUTE))
    if (style.getLayer(LAYER_CASING) == null) {
        style.addLayer(
            LineLayer(LAYER_CASING, SRC_ROUTE).withProperties(
                PropertyFactory.lineWidth(12f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }
    if (style.getLayer(LAYER_LINE) == null) {
        style.addLayer(
            LineLayer(LAYER_LINE, SRC_ROUTE).withProperties(
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }
}

/** Blue-dot equivalent in the university colour: pulsing halo + direction cone when walking. */
@Composable
private fun WalkerDot(relativeBearingDeg: Float?, modifier: Modifier) {
    val c = Campus.colors
    val t = rememberInfiniteTransition(label = "walker")
    val pulse by t.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1700), RepeatMode.Restart), label = "p")
    Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(72.dp).graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = (1f - pulse) * 1.6f }.clip(CircleShape).background(c.accent.copy(alpha = 0.28f)))
        if (relativeBearingDeg != null) {
            Canvas(Modifier.size(72.dp).graphicsLayer { rotationZ = relativeBearingDeg }) {
                val cx = size.width / 2; val cy = size.height / 2
                val cone = Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx - size.width * 0.26f, cy - size.height * 0.46f)
                    quadraticTo(cx, cy - size.height * 0.56f, cx + size.width * 0.26f, cy - size.height * 0.46f)
                    close()
                }
                drawPath(cone, c.accent.copy(alpha = 0.30f))
            }
        }
        Box(Modifier.size(22.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White).padding(4.dp).clip(CircleShape).background(c.accent))
    }
}

@Composable
private fun DestinationFlag(modifier: Modifier) {
    val c = Campus.colors
    Box(modifier.size(40.dp, 50.dp), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.size(40.dp, 50.dp)) {
            val w = size.width
            val pin = Path().apply {
                moveTo(w / 2, size.height)
                cubicTo(w * 0.1f, size.height * 0.62f, 0f, size.height * 0.48f, 0f, w / 2)
                cubicTo(0f, w * 0.22f, w * 0.22f, 0f, w / 2, 0f)
                cubicTo(w * 0.78f, 0f, w, w * 0.22f, w, w / 2)
                cubicTo(w, size.height * 0.48f, w * 0.9f, size.height * 0.62f, w / 2, size.height)
                close()
            }
            translate(top = 2.dp.toPx()) { drawPath(pin, Color.Black.copy(alpha = 0.22f)) }
            drawPath(pin, c.accent)
            drawCircle(Color.White, radius = w * 0.3f, center = Offset(w / 2, w / 2))
        }
        Icon(Icons.Rounded.Flag, null, tint = c.accent, modifier = Modifier.padding(top = 10.dp).size(20.dp))
    }
}
