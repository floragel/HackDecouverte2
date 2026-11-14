package com.hackdecouverte

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.SensorStatus
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.sensor.PdrSensorAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PdrViewModel(application: Application) : AndroidViewModel(application) {

    private val tracker = PdrTracker(clock = SystemClock::elapsedRealtimeNanos)
    private val adapter = PdrSensorAdapter(
        context = application,
        tracker = tracker,
        sourceProvider = { state.value.stepSource },
        onRegistrationResult = { failed ->
            registrationFailed = failed
            pushSensorStatus()
        }
    )

    val state: StateFlow<com.hackdecouverte.pdr.PdrState> = tracker.state

    private val permissionNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private var permissionGranted = false
    private var registrationFailed = false
    private var settlingJob: Job? = null

    init {
        permissionGranted = checkPermission()
        pushSensorStatus()
    }

    fun onForeground() {
        permissionGranted = checkPermission()
        pushSensorStatus()
        tracker.onSensorFrameRestart()
        adapter.start()
    }

    fun onBackground() {
        settlingJob?.cancel()
        settlingJob = null
        adapter.stop()
        tracker.pause()
    }

    fun onPermissionResult(granted: Boolean) {
        val previousSource = state.value.stepSource
        permissionGranted = granted
        pushSensorStatus()
        if (state.value.stepSource != previousSource) {
            refreshSensorRegistrations()
        }
    }

    fun selectStepSource(source: StepSource) {
        if (tracker.setStepSource(source)) refreshSensorRegistrations()
    }

    fun startCalibration() {
        tracker.startCalibration()
    }

    fun finishCalibration() {
        if (!tracker.finishCalibration()) return
        if (state.value.stepSource == StepSource.FALLBACK) {
            tracker.finalizeCalibration(SystemClock.elapsedRealtimeNanos())
        } else {
            settlingJob = viewModelScope.launch {
                delay(SETTLING_DELAY_MS)
                if (tracker.state.value.phase == PdrPhase.SETTLING) {
                    tracker.finalizeCalibration(SystemClock.elapsedRealtimeNanos())
                }
            }
        }
    }

    fun startManual(lengthM: Double): Boolean = tracker.startWithManualStepLength(lengthM)

    fun resetTrace() {
        tracker.resetTrace()
    }

    fun recalibrate() {
        settlingJob?.cancel()
        settlingJob = null
        tracker.recalibrate()
        refreshSensorRegistrations()
    }

    fun resumeWithNewTrace() {
        tracker.resumeWithNewTrace()
    }

    private fun refreshSensorRegistrations() {
        tracker.onSensorFrameRestart()
        adapter.refreshRegistrations()
    }

    private fun checkPermission(): Boolean {
        if (!permissionNeeded) return true
        return ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pushSensorStatus() {
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = adapter.rotationSensor != null,
                stepDetectorAvailable = adapter.stepSensor != null,
                fallbackCapable = adapter.fallbackCapable,
                permissionNeeded = permissionNeeded,
                permissionGranted = permissionGranted,
                registrationFailed = registrationFailed
            )
        )
    }

    companion object {
        private const val SETTLING_DELAY_MS = 2_000L
    }
}
