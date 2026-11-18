package com.hackdecouverte.sensor

import com.hackdecouverte.map.GeoOrigin
import com.hackdecouverte.pdr.FallbackStepDetector
import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.pdr.rotationMatrixFromVector
import com.hackdecouverte.pdr.worldVerticalFromRotationMatrix
import com.hackdecouverte.pdr.wrapAnglePi
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * What the PDR does with each sensor sample (pure Kotlin, no Android): the phone's [PdrSensorAdapter]
 * and the computer's walk replay both go through here, so a replay runs exactly the phone's code.
 * Sensor type numbers are Android's `Sensor.TYPE_*`.
 */
class SensorRouter(
    private val tracker: PdrTracker,
    private val sourceProvider: () -> StepSource,
    val fallback: FallbackStepDetector = FallbackStepDetector(adaptive = true),
    private val onPressureSample: (timestampNs: Long, pressureHpa: Double) -> Unit = { _, _ -> }
) {
    @Volatile
    var latestWorldAzimuthRad: Double? = null
        private set

    @Volatile
    var magneticDeclinationDeg: Double = GeoOrigin.DEFAULT_MONTREAL_DECLINATION_DEG

    private var compassAccuracy: Int = ACCURACY_MEDIUM
    private var upX = 0.0
    private var upY = 0.0
    private var upZ = 1.0
    private var haveUp = false

    init {
        fallback.onStep = { t -> tracker.onStep(t, StepSource.FALLBACK) }
    }

    /** New sensor registration: the phone's vertical must be seen again before the fallback uses it. */
    fun reset() {
        haveUp = false
    }

    fun onAccuracyChanged(type: Int, accuracy: Int) {
        if (type == TYPE_ROTATION_VECTOR) compassAccuracy = accuracy
    }

    fun onSample(type: Int, timestampNs: Long, values: FloatArray, accuracy: Int) {
        when (type) {
            TYPE_GAME_ROTATION_VECTOR -> {
                val r = rotationMatrixFromVector(values, values.size)
                if (r != null) {
                    val up = worldVerticalFromRotationMatrix(r)
                    upX = up.first
                    upY = up.second
                    upZ = up.third
                    haveUp = true
                }
                tracker.onRotationVector(timestampNs, values, values.size)
            }

            TYPE_ROTATION_VECTOR -> {
                val r = rotationMatrixFromVector(values, values.size)
                val sinYaw = if (r != null) r[1] - r[3] else 0.0
                val cosYaw = if (r != null) r[0] + r[4] else 0.0
                // Any hold: the twist is the phone's heading for every attitude but screen-down (same rule as the game heading).
                val flatEnough = tracker.anyHoldHeading || (r != null && hypot(r[1], r[4]) >= 0.12)
                if (r != null && flatEnough && hypot(sinYaw, cosYaw) >= 0.2) {
                    val magAzimuthRad = atan2(sinYaw, cosYaw)
                    val trueAzimuthRad = wrapAnglePi(magAzimuthRad + Math.toRadians(magneticDeclinationDeg))
                    latestWorldAzimuthRad = trueAzimuthRad
                    val acc = if (accuracy > 0) accuracy else compassAccuracy
                    tracker.onTrueNorthAzimuth(timestampNs, trueAzimuthRad, acc >= ACCURACY_MEDIUM)
                }
            }

            TYPE_STEP_DETECTOR -> tracker.onStep(timestampNs, StepSource.HARDWARE)

            TYPE_PRESSURE -> if (values.isNotEmpty()) onPressureSample(timestampNs, values[0].toDouble())

            TYPE_LINEAR_ACCELERATION -> if (values.size >= 3) {
                val ax = values[0].toDouble()
                val ay = values[1].toDouble()
                val az = values[2].toDouble()
                tracker.onLinearAccelerationMagnitude(timestampNs, sqrt(ax * ax + ay * ay + az * az))
                if (haveUp && sourceProvider() == StepSource.FALLBACK) {
                    fallback.onLinearAcceleration(timestampNs, ax, ay, az, upX, upY, upZ)
                }
            }

            TYPE_ACCELEROMETER -> if (values.size >= 3) {
                val ax = values[0].toDouble()
                val ay = values[1].toDouble()
                val az = values[2].toDouble()
                val total = sqrt(ax * ax + ay * ay + az * az)
                tracker.onLinearAccelerationMagnitude(timestampNs, abs(total - GRAVITY_EARTH))
                if (sourceProvider() == StepSource.FALLBACK) fallback.onAccelerometer(timestampNs, ax, ay, az)
            }
        }
    }

    companion object {
        const val TYPE_ACCELEROMETER = 1
        const val TYPE_MAGNETIC_FIELD = 2
        const val TYPE_GYROSCOPE = 4
        const val TYPE_LIGHT = 5
        const val TYPE_PRESSURE = 6
        const val TYPE_GRAVITY = 9
        const val TYPE_LINEAR_ACCELERATION = 10
        const val TYPE_ROTATION_VECTOR = 11
        const val TYPE_MAGNETIC_FIELD_UNCALIBRATED = 14
        const val TYPE_GAME_ROTATION_VECTOR = 15
        const val TYPE_GYROSCOPE_UNCALIBRATED = 16
        const val TYPE_STEP_DETECTOR = 18
        const val TYPE_STEP_COUNTER = 19
        /** `SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM`. */
        const val ACCURACY_MEDIUM = 2
        /** `SensorManager.GRAVITY_EARTH`. */
        const val GRAVITY_EARTH = 9.80665
    }
}
