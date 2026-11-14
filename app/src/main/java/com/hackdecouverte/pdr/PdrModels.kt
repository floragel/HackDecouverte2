package com.hackdecouverte.pdr

import kotlin.math.hypot

enum class PdrPhase {
    IDLE,
    CALIBRATING,
    SETTLING,
    TRACKING,
    PAUSED
}

enum class StepSource {
    NONE,
    HARDWARE,
    FALLBACK
}

enum class PdrWarning {
    ROTATION_SENSOR_MISSING,
    STEP_DETECTOR_MISSING,
    FALLBACK_SENSOR_MISSING,
    ACTIVITY_PERMISSION_DENIED,
    SENSOR_REGISTRATION_FAILED,
    CALIBRATION_FAILED_NO_STEPS,
    CALIBRATION_FAILED_IMPLAUSIBLE_LENGTH,
    CALIBRATION_FAILED_NO_HEADING,
    SESSION_INTERRUPTED
}

data class PointM(val x: Double, val y: Double) {
    operator fun plus(other: PointM) = PointM(x + other.x, y + other.y)

    operator fun minus(other: PointM) = PointM(x - other.x, y - other.y)

    operator fun times(k: Double) = PointM(x * k, y * k)

    fun norm() = hypot(x, y)

    fun distanceTo(other: PointM) = hypot(other.x - x, other.y - y)

    fun isFinite() = x.isFinite() && y.isFinite()
}

data class Milestone(val position: PointM, val distanceM: Double)

data class SensorStatus(
    val rotationAvailable: Boolean = true,
    val stepDetectorAvailable: Boolean = false,
    val fallbackCapable: Boolean = false,
    val permissionNeeded: Boolean = false,
    val permissionGranted: Boolean = false,
    val registrationFailed: Boolean = false
)

data class PdrState(
    val phase: PdrPhase = PdrPhase.IDLE,
    val position: PointM = PointM(0.0, 0.0),
    val origin: PointM = PointM(0.0, 0.0),
    val headingRadians: Double = 0.0,
    val headingValid: Boolean = false,
    val tilted: Boolean = false,
    val lengthPerStepM: Double? = null,
    val currentStepLengthM: Double? = null,
    val cadenceStepsPerMin: Double? = null,
    val baseCadenceHz: Double? = null,
    val stepLengthManual: Boolean = false,
    val distanceM: Double = 0.0,
    val stepCount: Int = 0,
    val calibrationStepCount: Int = 0,
    val droppedStepCount: Int = 0,
    val rawPath: List<PointM> = listOf(PointM(0.0, 0.0)),
    val milestones: List<Milestone> = emptyList(),
    val stepSource: StepSource = StepSource.NONE,
    val rotationSensorAvailable: Boolean = true,
    val stepDetectorAvailable: Boolean = false,
    val fallbackCapable: Boolean = false,
    val activityPermissionNeeded: Boolean = false,
    val activityPermissionGranted: Boolean = false,
    val registrationFailed: Boolean = false,
    val corridorAssistEnabled: Boolean = false,
    val trueNorthAligned: Boolean = false,
    val externalAccuracyM: Double? = null,
    val warnings: Set<PdrWarning> = emptySet()
) {
    val canStart: Boolean
        get() = phase == PdrPhase.IDLE &&
            rotationSensorAvailable &&
            !registrationFailed &&
            headingValid &&
            !tilted &&
            when (stepSource) {
                StepSource.HARDWARE ->
                    stepDetectorAvailable &&
                        (!activityPermissionNeeded || activityPermissionGranted)

                StepSource.FALLBACK -> fallbackCapable
                StepSource.NONE -> false
            }
}
