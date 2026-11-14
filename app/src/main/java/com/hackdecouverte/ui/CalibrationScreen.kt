package com.hackdecouverte.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hackdecouverte.FacingSelection
import com.hackdecouverte.MapUiState
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PdrState
import com.hackdecouverte.pdr.PdrWarning
import com.hackdecouverte.pdr.StepSource
import java.util.Locale

internal fun warningText(warning: PdrWarning): String = when (warning) {
    PdrWarning.ROTATION_SENSOR_MISSING ->
        "Capteur de rotation indisponible : suivi impossible sur cet appareil"

    PdrWarning.STEP_DETECTOR_MISSING ->
        "Détecteur de pas matériel absent"

    PdrWarning.FALLBACK_SENSOR_MISSING ->
        "Accéléromètre indisponible : pas de repli possible"

    PdrWarning.ACTIVITY_PERMISSION_DENIED ->
        "Permission « reconnaissance d'activité » refusée"

    PdrWarning.SENSOR_REGISTRATION_FAILED ->
        "Échec d'enregistrement d'un capteur"

    PdrWarning.CALIBRATION_FAILED_NO_STEPS ->
        "Calibration échouée : aucun pas détecté"

    PdrWarning.CALIBRATION_FAILED_IMPLAUSIBLE_LENGTH ->
        "Calibration échouée : longueur du pas invraisemblable (hors 0,2–1,5 m)"

    PdrWarning.CALIBRATION_FAILED_NO_HEADING ->
        "Calibration échouée : cap de référence indisponible à la fin"

    PdrWarning.SESSION_INTERRUPTED ->
        "Session interrompue (application passée à l'arrière-plan)"
}

@Composable
internal fun WarningList(state: PdrState) {
    if (state.warnings.isEmpty() && !state.tilted &&
        !(state.rotationSensorAvailable && !state.headingValid)
    ) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.tilted) {
            Text(
                "Inclinez moins le téléphone",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.rotationSensorAvailable && !state.headingValid && !state.tilted) {
            Text(
                "En attente d'un cap valide…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        for (warning in state.warnings) {
            Text(
                warningText(warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun facingLabel(plan: FloorPlan, facing: FacingSelection?): String? = when (facing) {
    is FacingSelection.Neighbor -> plan.node(facing.nodeId)?.let { "Vers ${it.label}" }
    is FacingSelection.Cardinal -> when (facing.degrees) {
        0 -> "Nord plan"
        90 -> "Est plan"
        180 -> "Sud plan"
        else -> "Ouest plan"
    }

    null -> null
}

@Composable
private fun <T> PlanPickerDialog(
    title: String,
    items: List<T>,
    itemText: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items) {
        val q = query.trim()
        if (q.isEmpty()) items else items.filter { itemText(it).contains(q, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Rechercher") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered.size) { i ->
                        Text(
                            itemText(filtered[i]),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(filtered[i]) }
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

@Composable
fun CalibrationScreen(
    state: PdrState,
    floorPlan: FloorPlan?,
    mapState: MapUiState,
    onSelectSource: (StepSource) -> Unit,
    onStartCalibration: () -> Unit,
    onFinishCalibration: () -> Unit,
    onStartManual: (Double) -> Boolean,
    onSelectStartNode: (String?) -> Unit,
    onSelectFacing: (FacingSelection) -> Unit,
    onClearAnchor: () -> Unit,
    onRequestActivityPermission: () -> Unit
) {
    var manualText by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf(false) }
    var startDialog by remember { mutableStateOf(false) }
    var facingDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Calibration PDR", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Marchez 5,0 m en ligne droite, téléphone tenu devant vous, " +
                "haut de l'appareil vers l'avant (sens de la marche), incliné " +
                "entre 20° et 60°. Gardez la même position pendant toute la marche.",
            style = MaterialTheme.typography.bodyMedium
        )

        WarningList(state)

        Text("Source des pas", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.stepSource == StepSource.HARDWARE,
                enabled = state.stepDetectorAvailable &&
                    (!state.activityPermissionNeeded || state.activityPermissionGranted) &&
                    state.phase == PdrPhase.IDLE,
                onClick = { onSelectSource(StepSource.HARDWARE) },
                label = { Text("Détecteur matériel") }
            )
            FilterChip(
                selected = state.stepSource == StepSource.FALLBACK,
                enabled = state.fallbackCapable && state.phase == PdrPhase.IDLE,
                onClick = { onSelectSource(StepSource.FALLBACK) },
                label = { Text("Accéléromètre (expérimental)") }
            )
        }
        if (state.stepDetectorAvailable && state.activityPermissionNeeded &&
            !state.activityPermissionGranted
        ) {
            OutlinedButton(onClick = onRequestActivityPermission) {
                Text("Autoriser la reconnaissance d'activité")
            }
        }

        when (state.phase) {
            PdrPhase.CALIBRATING -> {
                Text(
                    "Pas détectés : ${state.calibrationStepCount}",
                    style = MaterialTheme.typography.headlineMedium
                )
                Button(
                    onClick = onFinishCalibration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Terminer — 5,0 m parcourus")
                }
            }

            PdrPhase.SETTLING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Restez immobile — finalisation…",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            else -> {
                Button(
                    onClick = onStartCalibration,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Démarrer la calibration")
                }
                HorizontalDivider()
                Text(
                    "Option — test rapide sans calibration :",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = {
                            manualText = it
                            manualError = false
                        },
                        label = { Text("Longueur du pas (m)") },
                        isError = manualError,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = state.canStart,
                        onClick = {
                            val value = manualText.replace(',', '.').toDoubleOrNull()
                            manualError = value == null || !onStartManual(value)
                        }
                    ) {
                        Text("Démarrer")
                    }
                }
                if (manualError) {
                    Text(
                        "Valeur valide entre 0,2 et 1,5 m",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (floorPlan != null) {
            HorizontalDivider()
            Text(
                "Position sur le plan ${floorPlan.floorId}",
                style = MaterialTheme.typography.titleSmall
            )
            val startNode = mapState.selectedStartNodeId?.let(floorPlan::node)
            OutlinedButton(
                onClick = { startDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    startNode?.let { "${it.label} — ${it.name}" } ?: "Point de départ…",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = { facingDialog = true },
                enabled = startNode != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    facingLabel(floorPlan, mapState.selectedFacing) ?: "Orientation de départ…",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (mapState.anchor != null && startNode != null) {
                Text(
                    "Ancré : ${startNode.label} → " +
                        "${facingLabel(floorPlan, mapState.selectedFacing)} " +
                        "(ψ ≈ ${String.format(Locale.FRENCH, "%.0f", Math.toDegrees(mapState.anchor.psiRad))}°)",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = onClearAnchor) {
                    Text("Retirer l'ancrage")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (startDialog && floorPlan != null) {
        PlanPickerDialog(
            title = "Point de départ",
            items = floorPlan.labeledNodes,
            itemText = { "${it.label} — ${it.name}" },
            onPick = { onSelectStartNode(it.id) },
            onDismiss = { startDialog = false }
        )
    }
    val dialogStart = mapState.selectedStartNodeId?.let { floorPlan?.node(it) }
    if (facingDialog && floorPlan != null && dialogStart != null) {
        val facingItems = floorPlan.neighbors(dialogStart.id)
            .map { FacingSelection.Neighbor(it.id) as FacingSelection to "Vers ${it.label}" } +
            listOf(
                FacingSelection.Cardinal(0) to "Nord plan",
                FacingSelection.Cardinal(90) to "Est plan",
                FacingSelection.Cardinal(180) to "Sud plan",
                FacingSelection.Cardinal(270) to "Ouest plan"
            )
        PlanPickerDialog(
            title = "Orientation de départ",
            items = facingItems,
            itemText = { it.second },
            onPick = { onSelectFacing(it.first) },
            onDismiss = { facingDialog = false }
        )
    }
}
