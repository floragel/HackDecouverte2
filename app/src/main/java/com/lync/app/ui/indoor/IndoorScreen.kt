package com.lync.app.ui.indoor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hackdecouverte.PdrViewModel
import com.hackdecouverte.backend.IndoorNavigationBackend
import com.hackdecouverte.backend.NavigationSnapshot
import com.hackdecouverte.map.BuildingGraph
import com.hackdecouverte.map.NavigationRoute
import com.hackdecouverte.map.RejoinHint
import com.hackdecouverte.map.RouteStep
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PdrState
import com.hackdecouverte.pdr.PdrWarning
import com.lync.app.R
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.LocalThemeController
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.PrimaryButton
import com.lync.app.ui.components.pressable
import com.lync.app.ui.motion.rememberHaptics
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens
import java.util.Locale

/** "MB-2" -> "2" */
fun floorNumber(floorId: String): String = floorId.substringAfterLast('-')

/**
 * Starts the indoor engine while the screen is visible and stops the sensors when it is not
 * (the engine is foreground only, see AGENTS.md on the algorythme side).
 */
@Composable
fun rememberIndoorBackend(): Pair<IndoorNavigationBackend, NavigationSnapshot> {
    val vm: PdrViewModel = viewModel()
    val backend = vm.backend
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, backend) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> backend.onForeground()
                Lifecycle.Event.ON_STOP -> backend.onBackground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            backend.onBackground()
        }
    }
    // Keep the screen awake while walking
    val view = LocalView.current
    val phase = snapshot.pdr.phase
    DisposableEffect(phase) {
        view.keepScreenOn = phase == PdrPhase.CALIBRATING || phase == PdrPhase.SETTLING || phase == PdrPhase.TRACKING
        onDispose { view.keepScreenOn = false }
    }
    return backend to snapshot
}

private enum class Picker { FROM, TO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndoorScreen() {
    val (backend, snap) = rememberIndoorBackend()
    val nav = LocalNavigator.current
    val c = Campus.colors
    val haptics = rememberHaptics()
    val camera = remember { IndoorCamera() }
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    var picker by remember { mutableStateOf<Picker?>(null) }

    val activityPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        backend.onActivityPermissionResult(it)
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        backend.onLocationPermissionResult(r.values.any { it })
        if (r.values.any { it }) backend.useGpsAsStartOrigin()
    }
    fun askSteps() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    Box(Modifier.fillMaxSize().background(c.surface)) {
        IndoorCanvas(snap, camera, c, Modifier.fillMaxSize(), bottomInsetPx = sheetHeightPx)

        // ----- Top: back + title, floor picker on the right -----
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.shadow(8.dp, CircleShape).clip(CircleShape)) {
                    CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), { nav.back() }, filled = false)
                }
                Spacer(Modifier.width(10.dp))
                FloatingPill {
                    Text(stringResource(R.string.indoor_title), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            WarningChip(snap.pdr)
        }
        FloorPicker(
            floors = snap.floors.map { it.floorId },
            active = snap.activeFloorId,
            onPick = { haptics.tick(); backend.switchActiveFloor(it) },
            modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(top = 72.dp, end = 16.dp),
        )

        // ----- Bottom: map buttons + sheet -----
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { sheetHeightPx = it.height.toFloat() },
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                MapRoundButton(
                    if (camera.headUp) Icons.Rounded.Explore else Icons.Outlined.Explore,
                    stringResource(R.string.indoor_heading_up), active = camera.headUp,
                ) { haptics.tick(); camera.headUp = !camera.headUp }
                ThemeToggleButton()
                MapRoundButton(Icons.Rounded.MyLocation, stringResource(R.string.indoor_recenter)) { camera.recenter() }
            }
            Column(
                Modifier.fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(topStart = Tokens.RadiusCard, topEnd = Tokens.RadiusCard))
                    .clip(RoundedCornerShape(topStart = Tokens.RadiusCard, topEnd = Tokens.RadiusCard))
                    .background(c.bg)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = Tokens.Gutter, vertical = 18.dp),
            ) {
                AnimatedContent(snap.pdr.phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "sheet") { phase ->
                    when (phase) {
                        PdrPhase.IDLE -> SetupSheet(snap, backend, onPick = { picker = it }, onAskSteps = ::askSteps,
                            onUseGps = {
                                if (snap.locationPermissionGranted) backend.useGpsAsStartOrigin()
                                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            })
                        PdrPhase.CALIBRATING -> CalibrationSheet(snap.pdr, onDone = { haptics.confirm(); backend.finishCalibration5m() }, onStop = backend::stopToSetup)
                        PdrPhase.SETTLING -> Text(stringResource(R.string.indoor_settling), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(vertical = 18.dp))
                        PdrPhase.TRACKING, PdrPhase.PAUSED -> TrackingSheet(snap, backend, onPickDestination = { picker = Picker.TO })
                    }
                }
            }
        }
    }

    picker?.let { which ->
        val preferredFloor = snap.activeFloorId ?: snap.userFloorId
        RoomPickerSheet(
            title = stringResource(if (which == Picker.FROM) R.string.indoor_from else R.string.indoor_to),
            items = remember(which, snap.destinations, preferredFloor) {
                snap.destinations
                    .map { RoomItem(it.key.floorId, it.key.nodeId, it.label, it.name) }
                    .sortedBy { if (it.floorId == preferredFloor) 0 else 1 }
            },
            onPick = { item ->
                haptics.tick()
                camera.recenter()
                if (which == Picker.FROM) backend.setStartPose(item.floorId, item.nodeId)
                else backend.setDestination(com.hackdecouverte.map.NodeKey(item.floorId, item.nodeId))
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}

// ---------- Sheet contents ----------

@Composable
private fun SetupSheet(
    snap: NavigationSnapshot,
    backend: IndoorNavigationBackend,
    onPick: (Picker) -> Unit,
    onAskSteps: () -> Unit,
    onUseGps: () -> Unit,
) {
    val c = Campus.colors
    val s = snap.pdr
    val start = snap.startPose?.let { sp ->
        snap.floors.firstOrNull { it.floorId == sp.floorId }?.node(sp.nodeId)
    } ?: snap.startNodeId?.let { id -> snap.activeFloor?.node(id) }
    val dest = snap.destination?.let { key -> snap.destinations.firstOrNull { it.key == key } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.activityPermissionNeeded && !s.activityPermissionGranted) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.RadiusControl)).background(c.accentSoft).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.indoor_steps_permission), style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Pill(stringResource(R.string.indoor_allow), onAskSteps)
            }
        }
        RoomField(
            label = stringResource(R.string.indoor_from),
            value = when {
                start != null -> roomLine(start.label, start.name)
                snap.usingGpsStart -> stringResource(R.string.indoor_from_gps_on)
                else -> null
            },
            hint = stringResource(R.string.indoor_from_hint),
            onClick = { onPick(Picker.FROM) },
            onClear = if (start != null || snap.usingGpsStart) backend::clearStartPose else null,
            trailing = { CircleIconButton(Icons.Rounded.GpsFixed, stringResource(R.string.indoor_from_gps), onUseGps) },
        )
        RoomField(
            label = stringResource(R.string.indoor_to),
            value = dest?.let { roomLine(it.label, it.name) + " · " + stringResource(R.string.indoor_floor, floorNumber(it.key.floorId)) },
            hint = stringResource(R.string.indoor_to_hint),
            onClick = { onPick(Picker.TO) },
            onClear = if (dest != null) ({ backend.setDestination(null) }) else null,
        )
        AccessibleRow(snap.accessibleOnly) { backend.setAccessibleOnly(it) }
        Spacer(Modifier.height(2.dp))
        PrimaryButton(stringResource(R.string.indoor_go), { backend.startWithSavedOrDefaultStepLength() }, enabled = s.canStart)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(stringResource(R.string.indoor_calibrate), { backend.startCalibration5m() }, style = PillStyle.Tonal)
            Spacer(Modifier.width(10.dp))
            snap.savedStepLengthM?.let {
                Text(stringResource(R.string.indoor_step_saved, String.format(Locale.getDefault(), "%.2f", it)), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
        }
    }
}

@Composable
private fun CalibrationSheet(s: PdrState, onDone: () -> Unit, onStop: () -> Unit) {
    val c = Campus.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.indoor_calibrate_walk), style = MaterialTheme.typography.titleLarge, color = c.text)
        Text(stringResource(R.string.indoor_calibrate_steps, s.calibrationStepCount), style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
        Spacer(Modifier.height(4.dp))
        PrimaryButton(stringResource(R.string.indoor_calibrate_done), onDone, enabled = s.calibrationStepCount > 0)
        Pill(stringResource(R.string.indoor_stop), onStop, style = PillStyle.Tonal)
    }
}

@Composable
private fun TrackingSheet(snap: NavigationSnapshot, backend: IndoorNavigationBackend, onPickDestination: () -> Unit) {
    val c = Campus.colors
    val s = snap.pdr
    val route = snap.activeRoute
    val headingReady = s.headingValid && !s.tilted
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.phase == PdrPhase.PAUSED) {
            Text(stringResource(R.string.indoor_paused), style = MaterialTheme.typography.titleLarge, color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.indoor_resume), { if (headingReady) backend.resumeFromPause() })
                Pill(stringResource(R.string.indoor_stop), backend::stopToSetup, style = PillStyle.Tonal)
            }
            return@Column
        }
        when {
            route != null -> {
                Text(
                    instructionText(route),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (route.isArrived) c.accent else c.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(route.destinationLabel, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f, fill = false))
                    if (!route.isArrived) {
                        Text("  ·  " + stringResource(R.string.indoor_remaining, route.totalDistanceM.toInt()), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    }
                }
            }
            snap.destination != null && snap.startNodeId == null && !snap.usingGpsStart ->
                Text(stringResource(R.string.indoor_need_start), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            else -> RoomField(stringResource(R.string.indoor_to), null, stringResource(R.string.indoor_to_hint), onPickDestination, null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.indoor_stop), backend::stopToSetup, style = PillStyle.Tonal)
            val suggested = snap.suggestedTransfer
            if (!snap.isViewingUserFloor && snap.activeFloorId != null) {
                val viewed = snap.activeFloorId
                Pill(stringResource(R.string.indoor_floor, floorNumber(viewed)), { backend.confirmUserFloor(viewed) })
            } else if (suggested != null) {
                Pill(
                    stringResource(R.string.indoor_floor, floorNumber(suggested.toFloorId)),
                    { backend.confirmUserFloor(suggested.toFloorId, suggested.verticalGroupId) },
                )
            }
            if (snap.destination != null) Pill(stringResource(R.string.indoor_clear), { backend.setDestination(null) }, style = PillStyle.Outline)
            Pill(stringResource(R.string.indoor_restart), { backend.resetPositionToStart() }, style = PillStyle.Outline)
        }
    }
}

// ---------- Instructions (EN / FR) ----------

@Composable
fun instructionText(route: NavigationRoute): String {
    if (route.isArrived) return stringResource(R.string.nav_arrived, route.destinationLabel)
    when (route.rejoin) {
        RejoinHint.U_TURN -> return stringResource(R.string.nav_uturn)
        RejoinHint.TURN_RIGHT -> return stringResource(R.string.nav_rejoin_right)
        RejoinHint.TURN_LEFT -> return stringResource(R.string.nav_rejoin_left)
        null -> Unit
    }
    return when (val first = route.steps.firstOrNull()) {
        null -> stringResource(R.string.nav_follow)
        is RouteStep.Walk -> {
            val d = first.distanceM.toInt().coerceAtLeast(1)
            when (val next = route.steps.getOrNull(1)) {
                is RouteStep.Turn -> stringResource(if (next.left) R.string.nav_walk_then_left else R.string.nav_walk_then_right, d)
                is RouteStep.FloorTransfer -> stringResource(if (next.viaElevator) R.string.nav_walk_to_elevator else R.string.nav_walk_to_stairs, d)
                is RouteStep.Arrive -> stringResource(R.string.nav_walk_to, d, next.label)
                else -> stringResource(R.string.nav_walk, d)
            }
        }
        is RouteStep.Turn -> stringResource(if (first.left) R.string.nav_turn_left else R.string.nav_turn_right)
        is RouteStep.FloorTransfer -> stringResource(if (first.viaElevator) R.string.nav_elevator else R.string.nav_stairs, floorNumber(first.toFloorId))
        is RouteStep.Arrive -> stringResource(R.string.nav_arrived, first.label)
    }
}

@Composable
private fun warningText(w: PdrWarning): String = stringResource(
    when (w) {
        PdrWarning.ROTATION_SENSOR_MISSING -> R.string.pdr_w_rotation
        PdrWarning.STEP_DETECTOR_MISSING -> R.string.pdr_w_step_missing
        PdrWarning.FALLBACK_SENSOR_MISSING -> R.string.pdr_w_fallback_missing
        PdrWarning.ACTIVITY_PERMISSION_DENIED -> R.string.indoor_steps_permission
        PdrWarning.SENSOR_REGISTRATION_FAILED -> R.string.pdr_w_registration
        PdrWarning.CALIBRATION_FAILED_NO_STEPS -> R.string.pdr_w_cal_no_steps
        PdrWarning.CALIBRATION_FAILED_IMPLAUSIBLE_LENGTH -> R.string.pdr_w_cal_length
        PdrWarning.CALIBRATION_FAILED_NO_HEADING -> R.string.pdr_w_cal_heading
        PdrWarning.SESSION_INTERRUPTED -> R.string.indoor_paused
    },
)

// ---------- Small pieces ----------

private fun roomLine(label: String, name: String) =
    if (name.isBlank() || name == label) label else "$label · $name"

/** Inverted card like « Qui est là ? » on the map: one message at a time. */
@Composable
private fun WarningChip(s: PdrState) {
    val c = Campus.colors
    val text = when {
        s.tilted -> stringResource(R.string.indoor_tilt)
        s.warnings.isNotEmpty() -> warningText(s.warnings.first())
        !s.headingValid && s.phase != PdrPhase.IDLE -> stringResource(R.string.indoor_heading_wait)
        else -> null
    }
    AnimatedVisibility(text != null, enter = fadeIn(), exit = fadeOut()) {
        Text(
            text.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = c.bg,
            modifier = Modifier.shadow(8.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(c.text).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun FloatingPill(content: @Composable () -> Unit) {
    val c = Campus.colors
    Box(Modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.bg).padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
}

@Composable
private fun MapRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    val c = Campus.colors
    Box(
        Modifier.size(48.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(if (active) c.text else c.bg).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (active) c.bg else c.text) }
}

/** Light / dark switch for the maps. Saves the choice like Settings > Appearance does. */
@Composable
fun ThemeToggleButton() {
    val c = Campus.colors
    val theme = LocalThemeController.current
    val haptics = rememberHaptics()
    Box(
        Modifier.size(48.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(c.bg)
            .pressable({ haptics.tick(); theme.set(if (c.isDark) "light" else "dark") }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (c.isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
            stringResource(if (c.isDark) R.string.map_theme_light else R.string.map_theme_dark),
            tint = c.text,
        )
    }
}

/** Vertical floor switcher (7 at the top), floating on the plan. Active floor = ink, like a selected choice. */
@Composable
private fun FloorPicker(floors: List<String>, active: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Campus.colors
    if (floors.size < 2) return
    Column(
        modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.bg).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        floors.sortedByDescending { floorNumber(it).toIntOrNull() ?: 0 }.forEach { id ->
            val on = id == active
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (on) c.text else c.bg).pressable({ onPick(id) }, 0.9f),
                contentAlignment = Alignment.Center,
            ) {
                Text(floorNumber(id), style = MaterialTheme.typography.titleSmall, color = if (on) c.bg else c.text)
            }
        }
    }
}

@Composable
private fun RoomField(
    label: String,
    value: String?,
    hint: String,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Campus.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(Tokens.RadiusControl)).background(c.surface).pressable(onClick, 0.98f)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value ?: hint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value != null) c.text else c.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onClear != null) {
                    Icon(
                        Icons.Rounded.Close, stringResource(R.string.indoor_clear), tint = c.textMuted,
                        modifier = Modifier.size(20.dp).clip(CircleShape).pressable(onClear, 0.9f),
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun AccessibleRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Campus.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.indoor_accessible), style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(stringResource(R.string.indoor_accessible_desc), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.text, checkedThumbColor = c.bg, checkedBorderColor = c.text,
                uncheckedTrackColor = c.border, uncheckedThumbColor = c.bg, uncheckedBorderColor = c.border,
            ),
        )
    }
}

private data class RoomItem(val floorId: String, val nodeId: String, val label: String, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomPickerSheet(title: String, items: List<RoomItem>, onPick: (RoomItem) -> Unit, onDismiss: () -> Unit) {
    val c = Campus.colors
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items) {
        val q = query.trim()
        if (q.isEmpty()) {
            items
        } else {
            items.filter { BuildingGraph.matchesSearchQuery(q, it.label, it.name, it.floorId) }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bg,
        shape = RoundedCornerShape(topStart = Tokens.RadiusCard, topEnd = Tokens.RadiusCard),
    ) {
        Column(Modifier.padding(horizontal = Tokens.Gutter)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.text)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(Tokens.RadiusControl)).background(c.surface).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.indoor_to_hint), style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                Text(stringResource(R.string.indoor_no_result), style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.padding(vertical = 24.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(filtered, key = { it.floorId + "/" + it.nodeId }) { item ->
                    Row(
                        Modifier.fillMaxWidth().pressable({ onPick(item) }, 0.99f).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label.ifBlank { item.name }, style = MaterialTheme.typography.titleSmall, color = c.text)
                            if (item.name.isNotBlank() && item.name != item.label) {
                                Text(item.name, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(
                            stringResource(R.string.indoor_floor, floorNumber(item.floorId)),
                            style = MaterialTheme.typography.labelMedium, color = c.textMuted,
                            modifier = Modifier.clip(CircleShape).background(c.surface).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
