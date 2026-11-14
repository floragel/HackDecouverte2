package com.lync.app.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lync.app.R
import com.lync.app.data.CampusRepository
import com.lync.app.data.location.LocationClient
import com.lync.app.data.model.LocationSharing
import com.lync.app.data.model.MapPin
import com.lync.app.data.model.PinKind
import com.lync.app.data.model.StatusPreset
import com.lync.app.ui.campusViewModel
import com.lync.app.ui.components.Avatar
import com.lync.app.ui.components.pressable
import com.lync.app.ui.components.timeAgo
import com.lync.app.ui.theme.Campus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

// Free, no API key, OK for commercial use (OpenStreetMap data — keep the attribution visible).
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

class MapViewModel(private val repo: CampusRepository, private val location: LocationClient) : ViewModel() {
    val me = repo.me
    val pins = repo.mapPins().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setGhost(ghost: Boolean) = viewModelScope.launch {
        runCatching { repo.setLocationSharing(if (ghost) LocationSharing.OFF else LocationSharing.FRIENDS) }
    }

    fun locationUpdates() = location.updates()

    fun push(lat: Double, lng: Double) = viewModelScope.launch {
        if (repo.me.value?.locationSharing != LocationSharing.OFF) runCatching { repo.pushLocation(lat, lng) }
    }
}

@Composable
fun MapScreen() {
    val vm = campusViewModel { MapViewModel(it.repository, it.location) }
    val me by vm.me.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val c = Campus.colors
    val context = LocalContext.current

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraTick by remember { mutableIntStateOf(0) } // bumps on every camera move -> pins re-project
    var showFriends by remember { mutableStateOf(true) }
    var showClassmates by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>(null) }
    var myPos by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPermission = it.values.any { g -> g } }

    LaunchedEffect(hasPermission) {
        if (hasPermission) vm.locationUpdates().collect { (lat, lng) -> myPos = lat to lng; vm.push(lat, lng) }
    }

    val ghost = me?.locationSharing == LocationSharing.OFF
    val visible = pins.filter { (it.kind == PinKind.FRIEND && showFriends) || (it.kind == PinKind.CLASSMATE && showClassmates) }

    fun flyTo(lat: Double, lng: Double, zoom: Double = 17.0) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom), 700)
    }

    Box(Modifier.fillMaxSize().background(c.surface)) {
        MapLibre(dark = c.isDark, onReady = { map = it }, onCameraMove = { cameraTick++ })

        // ----- Pins (Compose views projected on the map: smooth + fully custom) -----
        val m = map
        if (m != null) {
            val tick = cameraTick // read so pins recompose when the camera moves
            val density = LocalDensity.current
            visible.forEach { pin ->
                val pt = m.projection.toScreenLocation(LatLng(pin.lat, pin.lng))
                FriendPin(
                    pin = pin, selected = selected == pin.profile.id,
                    modifier = Modifier.offset { IntOffset((pt.x - with(density) { 26.dp.toPx() }).toInt(), (pt.y - with(density) { 26.dp.toPx() }).toInt()) },
                    onClick = { selected = pin.profile.id; flyTo(pin.lat, pin.lng) },
                )
            }
            myPos?.let { (lat, lng) ->
                val pt = m.projection.toScreenLocation(LatLng(lat, lng))
                MeDot(ghost, Modifier.offset { IntOffset((pt.x - with(density) { 14.dp.toPx() }).toInt(), (pt.y - with(density) { 14.dp.toPx() }).toInt()) })
            }
        }

        // ----- Top controls -----
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FloatingChip {
                    Text(stringResource(R.string.map_title), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(horizontal = 6.dp))
                }
                Spacer(Modifier.weight(1f))
                GhostToggle(ghost) { vm.setGhost(!ghost) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(stringResource(R.string.map_friends), showFriends, c.accent) { showFriends = !showFriends }
                FilterChip(stringResource(R.string.map_classmates), showClassmates, c.accent2) { showClassmates = !showClassmates }
            }
        }

        // ----- Bottom: people on the map + recenter -----
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                AnimatedVisibility(!hasPermission, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.weight(1f)) {
                    FloatingChip(onClick = { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                        Text(stringResource(R.string.map_permission), style = MaterialTheme.typography.labelLarge, color = c.text, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
                if (hasPermission) Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(48.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(c.bg)
                        .pressable({ myPos?.let { flyTo(it.first, it.second) } ?: me?.let { flyTo(45.4972, -73.5790, 15.5) } }),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.MyLocation, null, tint = c.text) }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.profile.id }) { pin ->
                    PersonChip(pin, selected == pin.profile.id) { selected = pin.profile.id; flyTo(pin.lat, pin.lng) }
                }
            }
        }
    }
}

@Composable
private fun MapLibre(dark: Boolean, onReady: (MapLibreMap) -> Unit, onCameraMove: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isCompassEnabled = false
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.cameraPosition = CameraPosition.Builder().target(LatLng(45.4965, -73.5785)).zoom(15.8).build()
                m.addOnCameraMoveListener { onCameraMove() }
                m.addOnCameraIdleListener { onCameraMove() }
                onReady(m)
            }
        }
    }
    LaunchedEffect(dark) {
        mapView.getMapAsync { m -> m.setStyle(if (dark) STYLE_DARK else STYLE_LIGHT) { onCameraMove() } }
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
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun FriendPin(pin: MapPin, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Campus.colors
    val scale by animateFloatAsState(if (selected) 1.18f else 1f, spring(dampingRatio = 0.5f), label = "pin")
    val ring = if (pin.kind == PinKind.FRIEND) c.accent else c.accent2
    Box(modifier.size(52.dp).graphicsLayer { scaleX = scale; scaleY = scale }.pressable(onClick, 0.9f)) {
        Box(Modifier.shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White).padding(2.5.dp).clip(CircleShape).border(2.dp, ring, CircleShape)) {
            Avatar(pin.profile.avatarUrl, pin.profile.initials, 47.dp)
        }
        StatusPreset.of(pin.profile.statusKey)?.let {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(22.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center) { Text(it.emoji, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun MeDot(ghost: Boolean, modifier: Modifier) {
    val c = Campus.colors
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(0.6f, 1.6f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "p")
    val color = if (ghost) c.textMuted else c.accent
    Box(modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (!ghost) Box(Modifier.size(28.dp).graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = (1.6f - pulse) / 1.2f }.clip(CircleShape).background(color.copy(alpha = 0.35f)))
        Box(Modifier.size(18.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(Color.White).padding(3.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun FloatingChip(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Campus.colors
    Box(
        Modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.bg)
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
    ) { content() }
}

@Composable
private fun GhostToggle(ghost: Boolean, onToggle: () -> Unit) {
    val c = Campus.colors
    Row(
        Modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(if (ghost) c.text else c.bg).pressable(onToggle).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (ghost) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = if (ghost) c.bg else c.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(if (ghost) R.string.map_ghost_on else R.string.map_ghost_off), style = MaterialTheme.typography.labelLarge, color = if (ghost) c.bg else c.text)
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, dot: Color, onClick: () -> Unit) {
    val c = Campus.colors
    Row(
        Modifier.shadow(6.dp, CircleShape).clip(CircleShape).background(c.bg).pressable(onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) dot else c.border))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) c.text else c.textMuted)
    }
}

@Composable
private fun PersonChip(pin: MapPin, selected: Boolean, onClick: () -> Unit) {
    val c = Campus.colors
    val ago = timeAgo(pin.updatedAtMillis)
    Row(
        Modifier.shadow(8.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(c.bg)
            .border(1.5.dp, if (selected) c.accent else Color.Transparent, RoundedCornerShape(18.dp))
            .pressable(onClick).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(pin.profile.avatarUrl, pin.profile.initials, 36.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(pin.profile.firstName, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(
                if (ago.endsWith("s")) stringResource(R.string.map_now) else stringResource(R.string.map_updated, ago),
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
            )
        }
    }
}
