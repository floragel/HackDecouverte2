package com.lync.app.ui.indoor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hackdecouverte.loc.LocState
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.ui.CalibrationScreen
import com.hackdecouverte.ui.TelemetryScreen
import com.lync.app.R
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.theme.Campus

/**
 * Settings > Developer > Indoor navigation test.
 * Alban's test screens from the algorythme branch, unchanged (French only, debug numbers),
 * so the step and heading algorithm can still be tuned inside the merged app.
 * They pick up the Lync colors and fonts through MaterialTheme.
 */
@Composable
fun IndoorDevScreen() {
    val (backend, snapshot) = rememberIndoorBackend()
    val nav = LocalNavigator.current
    val c = Campus.colors

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        r[Manifest.permission.ACTIVITY_RECOGNITION]?.let(backend::onActivityPermissionResult)
        if (Manifest.permission.ACCESS_FINE_LOCATION in r || Manifest.permission.ACCESS_COARSE_LOCATION in r) {
            backend.onLocationPermissionResult(r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), { nav.back() })
            Text(stringResource(R.string.settings_indoor_test), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(start = 12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.indoor_dev_map_localization), style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.weight(1f))
            Switch(checked = snapshot.localizationEnabled, onCheckedChange = backend::setLocalizationEnabled)
        }
        val loc = snapshot.localization
        val debug = snapshot.localizationDebug
        if (snapshot.localizationEnabled && loc != null && debug != null) {
            val state = stringResource(
                when (loc.state) {
                    LocState.SEARCHING -> R.string.loc_state_searching
                    LocState.APPROXIMATE -> R.string.loc_state_approximate
                    LocState.FOUND -> R.string.loc_state_found
                }
            )
            Text(
                stringResource(R.string.loc_dev_status, state, debug.particleCount, debug.stepMs, debug.alarms, debug.strideScale, debug.strideSpread),
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Box(Modifier.weight(1f)) {
            when (snapshot.pdr.phase) {
                PdrPhase.TRACKING, PdrPhase.PAUSED -> TelemetryScreen(
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
}
