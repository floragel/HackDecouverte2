package com.hackdecouverte.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.hackdecouverte.pdr.FallbackStepDetector
import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.pdr.rotationMatrixFromVector
import com.hackdecouverte.pdr.worldVerticalFromRotationMatrix

class PdrSensorAdapter(
    context: Context,
    private val tracker: PdrTracker,
    private val sourceProvider: () -> StepSource,
    private val fallback: FallbackStepDetector = FallbackStepDetector(),
    private val onRegistrationResult: (Boolean) -> Unit = {}
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    val linearAcceleration: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val fallbackCapable: Boolean
        get() = linearAcceleration != null || accelerometer != null

    private var registered = false
    private var upX = 0.0
    private var upY = 0.0
    private var upZ = 1.0
    private var haveUp = false

    init {
        fallback.onStep = { t -> tracker.onStep(t, StepSource.FALLBACK) }
    }

    fun start() {
        if (registered) return
        registered = true
        haveUp = false
        var failed = false
        val rot = rotationSensor
        if (rot != null) {
            failed = !sensorManager.registerListener(
                this, rot, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US
            ) || failed
        }
        when (sourceProvider()) {
            StepSource.HARDWARE -> {
                val step = stepSensor
                if (step != null) {
                    failed = !sensorManager.registerListener(this, step, 0, 0) || failed
                }
            }

            StepSource.FALLBACK -> {
                fallback.start(SystemClock.elapsedRealtimeNanos())
                val lin = linearAcceleration
                if (lin != null) {
                    failed = !sensorManager.registerListener(
                        this, lin, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US
                    ) || failed
                } else {
                    val acc = accelerometer
                    if (acc != null) {
                        failed = !sensorManager.registerListener(
                            this, acc, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US
                        ) || failed
                    }
                }
            }

            StepSource.NONE -> {}
        }
        onRegistrationResult(failed)
    }

    fun stop() {
        if (!registered) return
        registered = false
        sensorManager.unregisterListener(this)
        fallback.stop()
        haveUp = false
    }

    fun refreshRegistrations() {
        if (!registered) return
        stop()
        start()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered) return
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val r = rotationMatrixFromVector(event.values, event.values.size)
                if (r != null) {
                    val up = worldVerticalFromRotationMatrix(r)
                    upX = up.first
                    upY = up.second
                    upZ = up.third
                    haveUp = true
                }
                tracker.onRotationVector(event.timestamp, event.values, event.values.size)
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                tracker.onStep(event.timestamp, StepSource.HARDWARE)
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (haveUp && event.values.size >= 3) {
                    fallback.onLinearAcceleration(
                        event.timestamp,
                        event.values[0].toDouble(),
                        event.values[1].toDouble(),
                        event.values[2].toDouble(),
                        upX, upY, upZ
                    )
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size >= 3) {
                    fallback.onAccelerometer(
                        event.timestamp,
                        event.values[0].toDouble(),
                        event.values[1].toDouble(),
                        event.values[2].toDouble()
                    )
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    companion object {
        private const val SAMPLING_PERIOD_US = 16_667
        private const val MAX_REPORT_LATENCY_US = 0
    }
}
