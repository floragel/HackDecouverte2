package com.lync.app.ui.indoor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hackdecouverte.backend.IndoorNavigationBackend
import java.io.File
import kotlinx.coroutines.delay
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
import com.hackdecouverte.pdr.CarryMode
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
    val recorder by backend.recorderStatus.collectAsState()
    val context = LocalContext.current

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
            // The tracking notification (phone in a pocket) is hidden without it on Android 13+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
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
        RecordBar(backend, snapshot.backgroundTracking)
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
                    onMapTap = if (recorder.recording) { p -> backend.addRecordingMarker(p) } else null,
                    markers = recorder.markerPoints,
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

/**
 * Walk recorder: record / stop, time, markers, Wi-Fi and RTT counts, and sharing the last walk. While
 * recording the screen stays on and a tap on the map drops a ground-truth marker ("I am here").
 */
@Composable
private fun RecordBar(backend: IndoorNavigationBackend, backgroundTracking: Boolean) {
    val c = Campus.colors
    val context = LocalContext.current
    val status by backend.recorderStatus.collectAsState()
    var now by remember { mutableStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(status.recording) {
        while (status.recording) {
            now = SystemClock.elapsedRealtimeNanos()
            delay(1000)
        }
    }
    val view = LocalView.current
    // With the tracking service a recording goes on screen off (phone in a pocket); without it the screen must stay on.
    DisposableEffect(status.recording, backgroundTracking) {
        view.keepScreenOn = status.recording && !backgroundTracking
        onDispose { view.keepScreenOn = false }
    }
    // Wi-Fi RTT needs NEARBY_WIFI_DEVICES on Android 13+: asked once, the recording starts either way.
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { backend.startRecording() }
    val start = {
        val needNearby = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backend.wifiRttSupported &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        if (needNearby) nearby.launch(Manifest.permission.NEARBY_WIFI_DEVICES) else backend.startRecording()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val red = Color(0xFFE53935)
            Row(
                Modifier.clip(CircleShape).background(if (status.recording) red else c.bg).pressable({ if (status.recording) backend.stopRecording() else start() }, 0.96f)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(10.dp).height(10.dp).clip(if (status.recording) RoundedCornerShape(2.dp) else CircleShape).background(if (status.recording) Color.White else red))
                Spacer(Modifier.width(8.dp))
                Text(if (status.recording) "Arrêter" else "Enregistrer la marche", color = if (status.recording) Color.White else c.text, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(10.dp))
            if (status.recording) {
                val s = ((now - status.startedNs) / 1_000_000_000L).coerceAtLeast(0)
                Text(
                    "%d:%02d · %d repère%s · Wi-Fi %d%s".format(Locale.ROOT, s / 60, s % 60, status.markers, if (status.markers > 1) "s" else "", status.wifiScans, if (status.rttOk > 0) " · RTT ${status.rttOk}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = c.text, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                )
            } else {
                val last = status.lastFile
                Text(last?.name ?: "Aucune marche enregistrée", style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
                if (last != null) {
                    Icon(
                        Icons.Rounded.Share, "Partager", tint = c.text,
                        modifier = Modifier.clip(CircleShape).pressable({ shareWalk(context, last) }, 0.9f).padding(6.dp),
                    )
                }
            }
        }
        if (status.recording) {
            // One tap = "this is happening now": ground truth for hold changes, stairs and doors.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WALK_LABELS.forEach { label ->
                    FilterChip(selected = status.lastLabel == label, onClick = { backend.recordLabel(label) }, label = { Text(label) })
                }
            }
        }
        val hint = when {
            status.error != null -> "Erreur : ${status.error}"
            status.recording -> "Touchez la carte là où vous êtes vraiment (départ, bouts de couloir, escalier) ; touchez une étiquette quand vous changez de tenue. " +
                "${status.labels} étiquette(s)." +
                if (backgroundTracking) " L'enregistrement continue écran éteint, téléphone en poche." else ""
            else -> "Posez un repère au départ, à chaque virage et à l'arrivée. Récupération : partager, ou tools/pull-walks.sh."
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = if (status.error != null) Color(0xFFE53935) else c.textMuted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Labels the walker can stamp during a recording (what really happens, for the analysis). */
private val WALK_LABELS = listOf("En main", "Poche", "Main qui balance", "Sac", "Écran éteint", "Arrêt", "Escalier", "Porte", "Virage")

private fun shareWalk(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.walks", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/gzip").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, file.name))
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
            add("pas ×${fmt(d.strideScale, 2)} ±${fmt(d.strideSpread, 2)} · angle tél. ${if (d.offsetKnown) "appris" else "attend une ligne droite"} · retracé ${snapshot.localizationTrace.size} pts" +
                if (d.warmStarted) " · départ : dernière zone" else "")
            add(loc.zones.take(3).mapIndexed { i, z -> "#${i + 1} ${(z.weight * 100).roundToInt()} % r ${fmt(z.radiusM, 1)} m" }.joinToString("  ·  ").ifEmpty { "aucune zone" })
        }
        val fix = snapshot.latestGpsFix
        add(
            if (fix == null) "GPS : aucune position${if (!snapshot.locationPermissionGranted) " (permission refusée)" else ""}"
            else "GPS : ±${fix.accuracyM.roundToInt()} m · ${fix.provider} · il y a ${((SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNs) / 1e9).roundToInt()} s · " +
                "${fmt(fix.eastNorthM.norm(), 0)} m du centre · utilisées ${d?.gpsFixesUsed ?: 0}" +
                (snapshot.localizationSystemCircle?.let { " · disque ${it.second.roundToInt()} m" } ?: "")
        )
        add("PDR : ${snapshot.pdr.phase} · ${snapshot.pdr.stepCount} pas (récupérés ${snapshot.pdr.recoveredStepCount}) · ${fmt(snapshot.pdr.distanceM, 1)} m · étage ${snapshot.userFloorId ?: "—"}")
        val p = snapshot.pdr
        val carry = when {
            p.inHoldTransition -> "changement de tenue…"
            p.carryMode == CarryMode.SWINGING -> "balancé (poche, main qui balance)"
            p.readingPose -> "en main, écran regardé"
            else -> "stable (main, poche de veste, sac)"
        }
        add(if (snapshot.backgroundTracking) "Suivi écran éteint : actif (notification)" else "Suivi écran éteint : non (permission de localisation ?)")
        add("Tenue : $carry · changements ${p.holdChanges} · cap = marche ${if (p.headingAligned) "oui" else "non (angle appris par la carte)"}")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = c.textMuted) }
    }
}

private fun fmt(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)
