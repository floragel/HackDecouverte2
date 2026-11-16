package com.hackdecouverte.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hackdecouverte.backend.NavigationSnapshot
import com.hackdecouverte.map.BuildingGraph
import com.hackdecouverte.map.DestinationItem
import com.hackdecouverte.map.NodeKey
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PdrState
import com.hackdecouverte.pdr.PdrWarning
import java.util.Locale
import kotlin.math.roundToInt

internal fun warningText(warning: PdrWarning): String = when (warning) {
    PdrWarning.ROTATION_SENSOR_MISSING -> "Capteur d'orientation indisponible"
    PdrWarning.STEP_DETECTOR_MISSING -> "Podomètre matériel absent (repli accéléromètre)"
    PdrWarning.FALLBACK_SENSOR_MISSING -> "Accéléromètre indisponible"
    PdrWarning.ACTIVITY_PERMISSION_DENIED -> "Autorisez l'activité physique pour compter les pas"
    PdrWarning.SENSOR_REGISTRATION_FAILED -> "Erreur d'initialisation capteur"
    PdrWarning.CALIBRATION_FAILED_NO_STEPS -> "Aucun pas détecté pendant la calibration"
    PdrWarning.CALIBRATION_FAILED_IMPLAUSIBLE_LENGTH -> "Longueur de pas hors limites (0,2–1,5 m)"
    PdrWarning.CALIBRATION_FAILED_NO_HEADING -> "Orientation instable en fin de calibration"
    PdrWarning.SESSION_INTERRUPTED -> "Session mise en pause"
}

internal fun compassCardinalFr(azimuthDeg: Double): String {
    val norm = ((azimuthDeg % 360.0) + 360.0) % 360.0
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    val idx = ((norm + 22.5) / 45.0).toInt() % 8
    return "${norm.roundToInt()}° ${dirs[idx]}"
}

@Composable
internal fun WarningBanner(state: PdrState) {
    val actionableWarnings = state.warnings.filter {
        it != PdrWarning.STEP_DETECTOR_MISSING && it != PdrWarning.SESSION_INTERRUPTED
    }
    if (!state.tilted && actionableWarnings.isEmpty() &&
        !(state.rotationSensorAvailable && !state.headingValid)
    ) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.tilted) {
            Text(
                "Inclinez légèrement moins le téléphone",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        } else if (state.rotationSensorAvailable && !state.headingValid) {
            Text(
                "Initialisation de l'orientation…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        for (w in actionableWarnings) {
            Text(
                warningText(w),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun RoomSearchDialog(
    title: String,
    items: List<DestinationItem>,
    preferredFloorId: String?,
    onPick: (DestinationItem) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items, preferredFloorId) {
        val q = query.trim()
        val matching = if (q.isEmpty()) {
            items
        } else {
            items.filter { BuildingGraph.matchesSearchQuery(q, it.label, it.name, it.key.floorId) }
        }
        matching.sortedWith(
            compareBy(
                { if (q.isEmpty() && it.key.floorId == preferredFloorId) 0 else 1 },
                { if (q.isNotEmpty() && it.label.contains(q, ignoreCase = true)) 0 else 1 },
                { it.type != "room" }
            )
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Salle / escalier (ex: MB-2.255, M.2.255, 4.206)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(filtered.size) { i ->
                        val item = filtered[i]
                        val detail = item.name.takeIf { it.isNotBlank() && it != item.label }
                        val line = if (detail != null) {
                            "[${item.key.floorId}] ${item.label} — $detail"
                        } else {
                            "[${item.key.floorId}] ${item.label}"
                        }
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(item)
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalibrationScreen(
    snapshot: NavigationSnapshot,
    onSwitchFloor: (floorId: String) -> Unit,
    onSelectStartPose: (floorId: String, nodeId: String) -> Unit,
    onUseGpsStart: () -> Unit,
    onClearStartPose: () -> Unit,
    onSelectDestination: (NodeKey?) -> Unit,
    onSetAccessibleOnly: (Boolean) -> Unit,
    onStartWithStepLength: (Double) -> Boolean,
    onStartCalibration: () -> Unit,
    onFinishCalibration: () -> Unit,
    onRequestActivityPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit
) {
    val state = snapshot.pdr
    val plan = snapshot.activeFloor
    val defaultStep = snapshot.savedStepLengthM ?: 0.74
    var stepText by rememberSaveable {
        mutableStateOf(String.format(Locale.FRENCH, "%.3f", defaultStep))
    }
    LaunchedEffect(snapshot.savedStepLengthM) {
        snapshot.savedStepLengthM?.let {
            stepText = String.format(Locale.FRENCH, "%.3f", it)
        }
    }

    var stepError by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showDestPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Navigation Indoor MB (Test Backend)", style = MaterialTheme.typography.headlineSmall)

        WarningBanner(state)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.stepDetectorAvailable && state.activityPermissionNeeded && !state.activityPermissionGranted) {
                Button(
                    onClick = onRequestActivityPermission,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Autoriser Pas")
                }
            }
            if (!snapshot.locationPermissionGranted) {
                OutlinedButton(
                    onClick = onRequestLocationPermission,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Autoriser GPS (Nord)")
                }
            }
        }

        val headingDeg = Math.toDegrees(snapshot.mapHeadingRad)
        val fix = snapshot.latestGpsFix
        val gnssTag = if (fix != null) {
            val galileoTxt = if (fix.galileoUsed > 0) "+Galileo(${fix.galileoUsed})" else ""
            val l5Txt = if (fix.dualFrequencyUsed > 0) " L1/E5" else ""
            "GPS$galileoTxt$l5Txt ±${fix.accuracyM.roundToInt()} m"
        } else {
            null
        }
        val baroTag = if (snapshot.barometerAvailable) " • Baromètre étages actif" else ""
        val northStatus = when {
            state.trueNorthAligned && gnssTag != null ->
                "Nord Vrai auto ($gnssTag) • ${compassCardinalFr(headingDeg)}$baroTag"
            state.trueNorthAligned ->
                "Nord Vrai auto (Boussole Montréal) • ${compassCardinalFr(headingDeg)}$baroTag"
            else ->
                "Acquisition du Nord du téléphone…$baroTag"
        }
        Text(
            northStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )

        if (plan != null) {
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "1. Salle de départ (recommandé en intérieur)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (snapshot.floors.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (f in snapshot.floors) {
                                FilterChip(
                                    selected = f.floorId == snapshot.userFloorId,
                                    onClick = { onSwitchFloor(f.floorId) },
                                    label = { Text(f.floorId) }
                                )
                            }
                        }
                    }

                    val startItem = snapshot.startPose?.let { sp ->
                        snapshot.destinations.firstOrNull { it.key == sp }
                            ?: snapshot.userFloor?.node(sp.nodeId)?.let { n ->
                                DestinationItem(sp, n.label.ifBlank { n.name }, n.name, n.type)
                            }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showStartPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            val label = when {
                                startItem != null ->
                                    "Départ : [${startItem.key.floorId}] ${startItem.label}"
                                snapshot.usingGpsStart ->
                                    "Départ : Position GPS extérieure"
                                else ->
                                    "Choisir ma salle de départ (ex: MB-2.255)…"
                            }
                            Text(
                                label,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (startItem != null || snapshot.usingGpsStart) {
                            TextButton(onClick = onClearStartPose) {
                                Text("✕")
                            }
                        }
                    }

                    if (fix != null && !snapshot.usingGpsStart) {
                        TextButton(onClick = onUseGpsStart) {
                            Text("En extérieur ? Utiliser ma position GPS (±${fix.accuracyM.roundToInt()} m)")
                        }
                    }
                }
            }

            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "2. Destination (optionnel, multi-étages MB-1..7)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    val destItem = snapshot.destinations.firstOrNull { it.key == snapshot.destination }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showDestPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                destItem?.let { "[${it.key.floorId}] ${it.label} — ${it.name}" }
                                    ?: "Choisir une salle d'arrivée…",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (destItem != null) {
                            TextButton(onClick = { onSelectDestination(null) }) {
                                Text("✕")
                            }
                        }
                    }
                    FilterChip(
                        selected = snapshot.accessibleOnly,
                        onClick = { onSetAccessibleOnly(!snapshot.accessibleOnly) },
                        label = { Text("Accessible PMR (ascenseurs uniquement)") }
                    )
                }
            }
        }

        HorizontalDivider()

        when (state.phase) {
            PdrPhase.CALIBRATING -> {
                Text(
                    "Marchez 5,0 m droit devant vous — Pas : ${state.calibrationStepCount}",
                    style = MaterialTheme.typography.titleMedium
                )
                Button(
                    onClick = onFinishCalibration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Valider 5,0 m")
                }
            }

            PdrPhase.SETTLING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Restez immobile — calcul du pas…")
                }
            }

            else -> {
                Text("3. Foulée & Lancement (Nord auto, sans calibration d'angle)", style = MaterialTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = stepText,
                        onValueChange = {
                            stepText = it
                            stepError = false
                        },
                        label = { Text("Pas de base (m)") },
                        isError = stepError,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = state.canStart,
                        onClick = {
                            val v = stepText.replace(',', '.').toDoubleOrNull()
                            stepError = v == null || !onStartWithStepLength(v)
                        }
                    ) {
                        Text("Ouvrir Carte")
                    }
                }
                if (stepError) {
                    Text(
                        "Entrez une longueur entre 0,20 et 1,50 m",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = onStartCalibration,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Calibrer la foulée sur 5,0 m")
                }
            }
        }
    }

    if (showStartPicker) {
        RoomSearchDialog(
            title = "Choisir ma salle de départ",
            items = snapshot.destinations,
            preferredFloorId = snapshot.userFloorId ?: snapshot.activeFloorId,
            onPick = { onSelectStartPose(it.key.floorId, it.key.nodeId) },
            onDismiss = { showStartPicker = false }
        )
    }

    if (showDestPicker) {
        RoomSearchDialog(
            title = "Aller vers",
            items = snapshot.destinations,
            preferredFloorId = snapshot.userFloorId ?: snapshot.activeFloorId,
            onPick = { onSelectDestination(it.key) },
            onDismiss = { showDestPicker = false }
        )
    }
}
