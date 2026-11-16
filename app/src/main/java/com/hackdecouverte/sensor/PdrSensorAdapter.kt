package com.hackdecouverte.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.hackdecouverte.map.GeoOrigin
import com.hackdecouverte.pdr.FallbackStepDetector
import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.pdr.rotationMatrixFromVector
import com.hackdecouverte.pdr.worldVerticalFromRotationMatrix
import com.hackdecouverte.pdr.wrapAnglePi
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

class PdrSensorAdapter(
    context: Context,
    private val tracker: PdrTracker,
    private val sourceProvider: () -> StepSource,
    private val fallback: FallbackStepDetector = FallbackStepDetector(),
    private val onPressureSample: (timestampNs: Long, pressureHpa: Double) -> Unit = { _, _ -> },
    private val onRegistrationResult: (Boolean) -> Unit = {}
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val compassRotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    val linearAcceleration: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val fallbackCapable: Boolean
        get() = linearAcceleration != null || accelerometer != null

    @Volatile
    var latestWorldAzimuthRad: Double? = null
        private set

    @Volatile
    var magneticDeclinationDeg: Double = GeoOrigin.DEFAULT_MONTREAL_DECLINATION_DEG

    private var compassAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
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
        compassRotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, 0)
        }
        pressureSensor?.let {
            sensorManager.registerListener(this, it, PRESSURE_SAMPLING_PERIOD_US, 0)
        }
        val activeSource = sourceProvider()
        when (activeSource) {
            StepSource.HARDWARE -> {
                val step = stepSensor
                if (step != null) {
                    failed = !sensorManager.registerListener(this, step, 0, 0) || failed
                }
                linearAcceleration?.let {
                    sensorManager.registerListener(
                        this, it, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US
                    )
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

            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = rotationMatrixFromVector(event.values, event.values.size)
                val sinYaw = if (r != null) r[1] - r[3] else 0.0
                val cosYaw = if (r != null) r[0] + r[4] else 0.0
                if (r != null && hypot(r[1], r[4]) >= 0.12 && hypot(sinYaw, cosYaw) >= 0.2) {
                    val magAzimuthRad = atan2(sinYaw, cosYaw)
                    val trueAzimuthRad = wrapAnglePi(
                        magAzimuthRad + Math.toRadians(magneticDeclinationDeg)
                    )
                    latestWorldAzimuthRad = trueAzimuthRad
                    val acc = if (event.accuracy > 0) event.accuracy else compassAccuracy
                    val highConfidence = acc >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                    tracker.onTrueNorthAzimuth(event.timestamp, trueAzimuthRad, highConfidence)
                }
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                tracker.onStep(event.timestamp, StepSource.HARDWARE)
            }

            Sensor.TYPE_PRESSURE -> {
                if (event.values.isNotEmpty()) {
                    onPressureSample(event.timestamp, event.values[0].toDouble())
                }
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (event.values.size >= 3) {
                    val ax = event.values[0].toDouble()
                    val ay = event.values[1].toDouble()
                    val az = event.values[2].toDouble()
                    val mag = sqrt(ax * ax + ay * ay + az * az)
                    tracker.onLinearAccelerationMagnitude(event.timestamp, mag)
                    if (haveUp && sourceProvider() == StepSource.FALLBACK) {
                        fallback.onLinearAcceleration(event.timestamp, ax, ay, az, upX, upY, upZ)
                    }
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size >= 3) {
                    val ax = event.values[0].toDouble()
                    val ay = event.values[1].toDouble()
                    val az = event.values[2].toDouble()
                    val total = sqrt(ax * ax + ay * ay + az * az)
                    tracker.onLinearAccelerationMagnitude(
                        event.timestamp,
                        kotlin.math.abs(total - SensorManager.GRAVITY_EARTH)
                    )
                    if (sourceProvider() == StepSource.FALLBACK) {
                        fallback.onAccelerometer(event.timestamp, ax, ay, az)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            compassAccuracy = accuracy
        }
    }

    companion object {
        private const val SAMPLING_PERIOD_US = 16_667
        private const val PRESSURE_SAMPLING_PERIOD_US = 100_000
        private const val MAX_REPORT_LATENCY_US = 0
    }
}
