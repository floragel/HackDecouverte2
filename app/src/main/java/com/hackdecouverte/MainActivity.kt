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
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.ui.CalibrationScreen
import com.hackdecouverte.ui.TelemetryScreen

class MainActivity : ComponentActivity() {

    private val viewModel: PdrViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onPermissionResult(granted) }

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
                                permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }
}

@Composable
fun PdrApp(viewModel: PdrViewModel, onRequestActivityPermission: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mapState by viewModel.mapState.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(state.phase) {
        view.keepScreenOn = state.phase == PdrPhase.CALIBRATING ||
            state.phase == PdrPhase.SETTLING ||
            state.phase == PdrPhase.TRACKING
        onDispose { view.keepScreenOn = false }
    }
    Scaffold { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (state.phase) {
                PdrPhase.TRACKING, PdrPhase.PAUSED -> TelemetryScreen(
                    state = state,
                    floorPlan = viewModel.floorPlan,
                    anchor = mapState.anchor,
                    onResetTrace = viewModel::resetTrace,
                    onRecalibrate = viewModel::recalibrate,
                    onResumeNewTrace = viewModel::resumeWithNewTrace
                )

                else -> CalibrationScreen(
                    state = state,
                    floorPlan = viewModel.floorPlan,
                    mapState = mapState,
                    onSelectSource = { s: StepSource -> viewModel.selectStepSource(s) },
                    onStartCalibration = viewModel::startCalibration,
                    onFinishCalibration = viewModel::finishCalibration,
                    onStartManual = viewModel::startManual,
                    onSelectStartNode = viewModel::selectStartNode,
                    onSelectFacing = viewModel::selectFacing,
                    onClearAnchor = viewModel::clearAnchor,
                    onRequestActivityPermission = onRequestActivityPermission
                )
            }
        }
    }
}
