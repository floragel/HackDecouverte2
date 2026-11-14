package com.hackdecouverte

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.ui.CalibrationScreen
import com.hackdecouverte.ui.TelemetryScreen

class MainActivity : ComponentActivity() {

    private val viewModel: PdrViewModel by viewModels()

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results[Manifest.permission.ACTIVITY_RECOGNITION]?.let {
            viewModel.backend.onActivityPermissionResult(it)
        }
        val locGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (Manifest.permission.ACCESS_FINE_LOCATION in results ||
            Manifest.permission.ACCESS_COARSE_LOCATION in results
        ) {
            viewModel.backend.onLocationPermissionResult(locGranted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PdrApp(
                        viewModel = viewModel,
                        onRequestActivityPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                permissionsLauncher.launch(
                                    arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
                                )
                            }
                        },
                        onRequestLocationPermission = {
                            permissionsLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.backend.onForeground()
    }

    override fun onStop() {
        viewModel.backend.onBackground()
        super.onStop()
    }
}

@Composable
fun PdrApp(
    viewModel: PdrViewModel,
    onRequestActivityPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val phase = snapshot.pdr.phase
    val view = LocalView.current
    DisposableEffect(phase) {
        view.keepScreenOn = phase == PdrPhase.CALIBRATING ||
            phase == PdrPhase.SETTLING ||
            phase == PdrPhase.TRACKING
        onDispose { view.keepScreenOn = false }
    }
    val backend = viewModel.backend
    Scaffold { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (phase) {
                PdrPhase.TRACKING, PdrPhase.PAUSED -> TelemetryScreen(
                    snapshot = snapshot,
                    onSelectStartPose = { floorId, nodeId -> backend.setStartPose(floorId, nodeId) },
                    onSelectDestination = backend::setDestination,
                    onSetAccessibleOnly = backend::setAccessibleOnly,
                    onSwitchFloor = { floorId -> backend.switchActiveFloor(floorId) },
                    onConfirmUserFloor = { floorId, vg -> backend.confirmUserFloor(floorId, vg) },
                    onReturnToUserFloor = backend::returnViewToUserFloor,
                    onResetPosition = backend::resetPositionToStart,
                    onOpenSetup = backend::stopToSetup,
                    onResumeNewTrace = backend::resumeFromPause
                )

                else -> CalibrationScreen(
                    snapshot = snapshot,
                    onSwitchFloor = { floorId -> backend.switchActiveFloor(floorId) },
                    onSelectStartPose = { floorId, nodeId -> backend.setStartPose(floorId, nodeId) },
                    onUseGpsStart = { backend.useGpsAsStartOrigin() },
                    onClearStartPose = backend::clearStartPose,
                    onSelectDestination = backend::setDestination,
                    onSetAccessibleOnly = backend::setAccessibleOnly,
                    onStartWithStepLength = backend::startWithStepLength,
                    onStartCalibration = { backend.startCalibration5m() },
                    onFinishCalibration = backend::finishCalibration5m,
                    onRequestActivityPermission = onRequestActivityPermission,
                    onRequestLocationPermission = onRequestLocationPermission
                )
            }
        }
    }
}
