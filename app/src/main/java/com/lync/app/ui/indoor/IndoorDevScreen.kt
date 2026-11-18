package com.lync.app.ui.indoor

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hackdecouverte.PdrViewModel
import com.hackdecouverte.backend.NavigationSnapshot
import com.hackdecouverte.loc.LocState
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.ui.CalibrationScreen
import com.hackdecouverte.ui.DebugLayer
import com.hackdecouverte.ui.TelemetryScreen
import com.hackdecouverte.ui.confidenceColor
import com.lync.app.R
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.pressable
import com.lync.app.ui.theme.Campus
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home of the debug app: the map with every step of the localization drawn live (occupancy grid,
 * direction map, doors, hypotheses coloured by confidence, zones, GPS, traced walk, raw PDR), layer
 * toggles, and the filter's numbers. Map localization and the permissions are switched on at launch.
 */
@Composable
fun IndoorDevScreen() {
    val (backend, snapshot) = rememberIndoorBackend()
    val vm: PdrViewModel = viewModel()
    val nav = LocalNavigator.current
    val c = Campus.colors
    var buildingPicker by remember { mutableStateOf(false) }
    var layerNames by rememberSaveable { mutableStateOf(DebugLayer.defaults.map { it.name }) }
    val layers = layerNames.mapNotNull { n -> DebugLayer.entries.firstOrNull { it.name == n } }.toSet()

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        r[Manifest.permission.ACTIVITY_RECOGNITION]?.let(backend::onActivityPermissionResult)
        if (Manifest.permission.ACCESS_FINE_LOCATION in r || Manifest.permission.ACCESS_COARSE_LOCATION in r) {
            backend.onLocationPermissionResult(r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        }
    }
    // Debug app: ask once for steps + location, and run the map localization straight away
    LaunchedEffect(Unit) {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && snapshot.pdr.activityPermissionNeeded && !snapshot.pdr.activityPermissionGranted) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (!snapshot.locationPermissionGranted) {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
    LaunchedEffect(backend) { backend.setLocalizationEnabled(true) }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
        // Building + localization switch + settings
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(CircleShape).background(c.surface).pressable({ buildingPicker = true }, 0.97f).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(backend.site.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.indoor_building), tint = c.textMuted)
            }
            Spacer(Modifier.width(8.dp))
            Text("Localisation", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            Switch(checked = snapshot.localizationEnabled, onCheckedChange = backend::setLocalizationEnabled, modifier = Modifier.padding(horizontal = 6.dp))
            CircleIconButton(Icons.Rounded.Settings, stringResource(R.string.profile_settings), { nav.settings() })
        }

        // Layer toggles + confidence legend
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DebugLayer.entries.forEach { layer ->
                FilterChip(
                    selected = layer in layers,
                    onClick = { layerNames = if (layer in layers) layerNames - layer.name else layerNames + layer.name },
                    label = { Text(layer.label) },
                )
            }
        }
        ConfidenceLegend()
        DebugStatus(snapshot)

        Box(Modifier.weight(1f)) {
            // The map stays up while localization runs, even before the first step (hypotheses, GPS, grid)
            val showMap = snapshot.localizationEnabled || snapshot.pdr.phase == PdrPhase.TRACKING || snapshot.pdr.phase == PdrPhase.PAUSED
            when {
                showMap -> TelemetryScreen(
                    snapshot = snapshot,
                    onSelectStartPose = { floorId, nodeId -> backend.setStartPose(floorId, nodeId) },
                    onSelectDestination = backend::setDestination,
                    onSetAccessibleOnly = backend::setAccessibleOnly,
                    onSwitchFloor = { backend.switchActiveFloor(it) },
                    onConfirmUserFloor = { floorId, vg -> backend.confirmUserFloor(floorId, vg) },
                    onReturnToUserFloor = backend::returnViewToUserFloor,
                    onResetPosition = backend::resetPositionToStart,
                    onOpenSetup = backend::stopToSetup,
                    onResumeNewTrace = backend::resumeFromPause,
                    layers = layers,
                    initialZoom = 0.3f, // whole wing / a good part of an MB floor at once
                )
                else -> CalibrationScreen(
                    snapshot = snapshot,
                    onSwitchFloor = { backend.switchActiveFloor(it) },
                    onSelectStartPose = { floorId, nodeId -> backend.setStartPose(floorId, nodeId) },
                    onUseGpsStart = { backend.useGpsAsStartOrigin() },
                    onClearStartPose = backend::clearStartPose,
                    onSelectDestination = backend::setDestination,
                    onSetAccessibleOnly = backend::setAccessibleOnly,
                    onStartWithStepLength = backend::startWithStepLength,
                    onStartCalibration = { backend.startCalibration5m() },
                    onFinishCalibration = backend::finishCalibration5m,
                    onRequestActivityPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) permissions.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                    },
                    onRequestLocationPermission = {
                        permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                )
            }
        }
    }

    if (buildingPicker) {
        BuildingPickerSheet(
            current = backend.site,
            onPick = { site -> buildingPicker = false; vm.selectSite(site) },
            onDismiss = { buildingPicker = false },
        )
    }
}

/** Red → yellow → green bar explaining the colour of the points. */
@Composable
private fun ConfidenceLegend() {
    val c = Campus.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Confiance des points :", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        Spacer(Modifier.width(6.dp))
        Text("murs", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        Box(
            Modifier.padding(horizontal = 6.dp).width(110.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(0.4, 0.55, 0.7, 0.85, 1.0).map { confidenceColor(it) }))
        )
        Text("couloir", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        Spacer(Modifier.width(10.dp))
        Text("taille = poids", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
    }
}

/** The filter's numbers, refreshed every step. */
@Composable
private fun DebugStatus(snapshot: NavigationSnapshot) {
    val c = Campus.colors
    val loc = snapshot.localization
    val d = snapshot.localizationDebug
    val lines = buildList {
        if (!snapshot.localizationEnabled) {
            add("Localisation par la carte arrêtée")
        } else if (loc == null || d == null) {
            add(snapshot.localizationError?.let { "Carte indisponible : $it" } ?: "Préparation de la carte de ${snapshot.userFloorId ?: "—"}…")
        } else {
            val state = when (loc.state) { LocState.SEARCHING -> "RECHERCHE"; LocState.APPROXIMATE -> "APPROXIMATIF"; LocState.FOUND -> "TROUVÉ" }
            add("$state · ${d.particleCount} pts · ESS ${d.ess.roundToInt()} · ${fmt(d.stepMs, 1)} ms/pas · alertes ${d.alarms}")
            add("pas ×${fmt(d.strideScale, 2)} ±${fmt(d.strideSpread, 2)} · angle tél. ${if (d.offsetKnown) "appris" else "attend une ligne droite"} · retracé ${snapshot.localizationTrace.size} pts")
            add(loc.zones.take(3).mapIndexed { i, z -> "#${i + 1} ${(z.weight * 100).roundToInt()} % r ${fmt(z.radiusM, 1)} m" }.joinToString("  ·  ").ifEmpty { "aucune zone" })
        }
        val fix = snapshot.latestGpsFix
        add(
            if (fix == null) "GPS : aucune position${if (!snapshot.locationPermissionGranted) " (permission refusée)" else ""}"
            else "GPS : ±${fix.accuracyM.roundToInt()} m · ${fix.provider} · il y a ${((SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNs) / 1e9).roundToInt()} s · " +
                "${fmt(fix.eastNorthM.norm(), 0)} m du centre · utilisées ${d?.gpsFixesUsed ?: 0}" +
                (snapshot.localizationSystemCircle?.let { " · disque ${it.second.roundToInt()} m" } ?: "")
        )
        add("PDR : ${snapshot.pdr.phase} · ${snapshot.pdr.stepCount} pas · ${fmt(snapshot.pdr.distanceM, 1)} m · étage ${snapshot.userFloorId ?: "—"}")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = c.textMuted) }
    }
}

private fun fmt(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)
