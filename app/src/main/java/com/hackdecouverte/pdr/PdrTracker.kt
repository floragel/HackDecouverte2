package com.hackdecouverte.pdr

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PdrTracker(
    private val clock: () -> Long,
    private val orientation: OrientationProcessor = OrientationProcessor(),
    calibrationDistanceM: Double = 5.0,
    milestoneIntervalM: Double = 5.0
) {
    private val calibrationDistanceM = calibrationDistanceM
    private val milestoneTracker = MilestoneTracker(milestoneIntervalM)

    private val _state = MutableStateFlow(PdrState())
    val state: StateFlow<PdrState> = _state

    private var sensorStatus = SensorStatus()
    private var referenceYaw: Double? = null
    private var displayedHeading = 0.0
    private var position = PointM(0.0, 0.0)
    private var distanceM = 0.0
    private var stepCount = 0
    private var droppedSteps = 0
    private var lengthPerStepM: Double? = null
    private var stepLengthManual = false
    private var stepSource = StepSource.NONE
    private var phase = PdrPhase.IDLE
    private val warnings = LinkedHashSet<PdrWarning>()

    private val points = ArrayList<PointM>().apply { add(PointM(0.0, 0.0)) }
    private var calibStartNs = 0L
    private var calibFinishNs = Long.MAX_VALUE
    private var calibSteps = 0
    private val countedCalibSteps = HashSet<Long>()
    private var maxStepTimestampSeenNs = Long.MIN_VALUE
    private var sessionCutoffNs = Long.MIN_VALUE
    private var lastTrackedStepNs = Long.MIN_VALUE

    fun onRotationVector(timestampNs: Long, values: FloatArray, size: Int = values.size) {
        orientation.onRotationVector(timestampNs, values, size)
        publish()
    }

    fun onSensorStatus(status: SensorStatus) {
        sensorStatus = status
        warnings.remove(PdrWarning.ROTATION_SENSOR_MISSING)
        warnings.remove(PdrWarning.STEP_DETECTOR_MISSING)
        warnings.remove(PdrWarning.FALLBACK_SENSOR_MISSING)
        warnings.remove(PdrWarning.ACTIVITY_PERMISSION_DENIED)
        warnings.remove(PdrWarning.SENSOR_REGISTRATION_FAILED)
        if (!status.rotationAvailable) warnings.add(PdrWarning.ROTATION_SENSOR_MISSING)
        if (!status.stepDetectorAvailable) warnings.add(PdrWarning.STEP_DETECTOR_MISSING)
        if (!status.fallbackCapable) warnings.add(PdrWarning.FALLBACK_SENSOR_MISSING)
        if (status.stepDetectorAvailable && status.permissionNeeded &&
            !status.permissionGranted
        ) {
            warnings.add(PdrWarning.ACTIVITY_PERMISSION_DENIED)
        }
        if (status.registrationFailed) warnings.add(PdrWarning.SENSOR_REGISTRATION_FAILED)
        if (stepSource == StepSource.NONE) {
            if (usable(StepSource.HARDWARE)) stepSource = StepSource.HARDWARE
        } else if (!usable(stepSource)) {
            stepSource = StepSource.NONE
        }
        publish()
    }

    fun onStep(timestampNs: Long, source: StepSource) {
        if (source == StepSource.NONE || source != stepSource ||
            sensorStatus.registrationFailed
        ) {
            return
        }
        when (phase) {
            PdrPhase.CALIBRATING, PdrPhase.SETTLING -> {
                if (timestampNs in calibStartNs..calibFinishNs && countedCalibSteps.add(timestampNs)) {
                    calibSteps++
                }
                maxStepTimestampSeenNs = max(maxStepTimestampSeenNs, timestampNs)
                publish()
            }

            PdrPhase.TRACKING -> {
                maxStepTimestampSeenNs = max(maxStepTimestampSeenNs, timestampNs)
                val length = lengthPerStepM
                val mean = orientation.meanHeadingAt(timestampNs)
                val reference = referenceYaw
                if (length == null || mean == null || reference == null ||
                    timestampNs <= sessionCutoffNs || timestampNs <= lastTrackedStepNs
                ) {
                    droppedSteps++
                    publish()
                    return
                }
                lastTrackedStepNs = timestampNs
                val theta = wrapAnglePi(mean - reference)
                val previous = position
                val previousDistance = distanceM
                position += PointM(length * sin(theta), length * cos(theta))
                distanceM += length
                stepCount++
                points.add(position)
                milestoneTracker.onSegment(previous, previousDistance, position, distanceM)
                publish()
            }

            else -> {
                maxStepTimestampSeenNs = max(maxStepTimestampSeenNs, timestampNs)
            }
        }
    }

    fun setStepSource(source: StepSource): Boolean {
        if (phase != PdrPhase.IDLE) return false
        if (source != StepSource.NONE && !usable(source)) return false
        stepSource = source
        publish()
        return true
    }

    fun startCalibration(): Boolean {
        if (phase != PdrPhase.IDLE) return false
        if (!sensorStatus.rotationAvailable || sensorStatus.registrationFailed) return false
        if (!usable(stepSource)) return false
        if (orientation.meanHeadingAt(clock()) == null) return false
        calibStartNs = clock()
        calibFinishNs = Long.MAX_VALUE
        calibSteps = 0
        countedCalibSteps.clear()
        warnings.remove(PdrWarning.CALIBRATION_FAILED_NO_STEPS)
        warnings.remove(PdrWarning.CALIBRATION_FAILED_IMPLAUSIBLE_LENGTH)
        warnings.remove(PdrWarning.CALIBRATION_FAILED_NO_HEADING)
        warnings.remove(PdrWarning.SESSION_INTERRUPTED)
        phase = PdrPhase.CALIBRATING
        publish()
        return true
    }

    fun finishCalibration(): Boolean {
        if (phase != PdrPhase.CALIBRATING) return false
        calibFinishNs = clock()
        phase = PdrPhase.SETTLING
        publish()
        return true
    }

    fun finalizeCalibration(nowNs: Long): Boolean {
        if (phase != PdrPhase.SETTLING) return false
        val n = countedCalibSteps.size
        if (n == 0) {
            return failCalibration(PdrWarning.CALIBRATION_FAILED_NO_STEPS)
        }
        val length = calibrationDistanceM / n
        if (length !in MIN_STEP_LENGTH_M..MAX_STEP_LENGTH_M) {
            return failCalibration(PdrWarning.CALIBRATION_FAILED_IMPLAUSIBLE_LENGTH)
        }
        val reference = orientation.meanHeadingAt(nowNs)
            ?: return failCalibration(PdrWarning.CALIBRATION_FAILED_NO_HEADING)
        referenceYaw = reference
        lengthPerStepM = length
        stepLengthManual = false
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, nowNs)
        phase = PdrPhase.TRACKING
        publish()
        return true
    }

    fun startWithManualStepLength(lengthM: Double): Boolean {
        if (phase != PdrPhase.IDLE) return false
        if (!lengthM.isFinite() || lengthM !in MIN_STEP_LENGTH_M..MAX_STEP_LENGTH_M) return false
        if (!sensorStatus.rotationAvailable || sensorStatus.registrationFailed) return false
        if (!usable(stepSource)) return false
        val reference = orientation.meanHeadingAt(clock()) ?: return false
        referenceYaw = reference
        lengthPerStepM = lengthM
        stepLengthManual = true
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, clock())
        warnings.remove(PdrWarning.SESSION_INTERRUPTED)
        phase = PdrPhase.TRACKING
        publish()
        return true
    }

    fun resetTrace(): Boolean {
        if (phase != PdrPhase.TRACKING && phase != PdrPhase.PAUSED) return false
        if (!sensorStatus.rotationAvailable || sensorStatus.registrationFailed) return false
        val reference = orientation.meanHeadingAt(clock()) ?: return false
        referenceYaw = reference
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, clock())
        publish()
        return true
    }

    fun onSensorFrameRestart() {
        orientation.clear()
        referenceYaw = null
        when (phase) {
            PdrPhase.CALIBRATING, PdrPhase.SETTLING -> {
                calibFinishNs = Long.MAX_VALUE
                phase = PdrPhase.IDLE
                warnings.add(PdrWarning.SESSION_INTERRUPTED)
            }

            PdrPhase.TRACKING -> {
                phase = PdrPhase.PAUSED
                warnings.add(PdrWarning.SESSION_INTERRUPTED)
            }

            else -> {}
        }
        publish()
    }

    fun pause() = onSensorFrameRestart()

    fun resumeWithNewTrace(): Boolean {
        if (phase != PdrPhase.PAUSED) return false
        if (!sensorStatus.rotationAvailable || sensorStatus.registrationFailed) return false
        val reference = orientation.meanHeadingAt(clock()) ?: return false
        referenceYaw = reference
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, clock())
        warnings.remove(PdrWarning.SESSION_INTERRUPTED)
        phase = PdrPhase.TRACKING
        publish()
        return true
    }

    fun recalibrate() {
        phase = PdrPhase.IDLE
        lengthPerStepM = null
        stepLengthManual = false
        referenceYaw = null
        calibStartNs = 0L
        calibFinishNs = Long.MAX_VALUE
        calibSteps = 0
        countedCalibSteps.clear()
        stepSource = StepSource.NONE
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, clock())
        warnings.remove(PdrWarning.CALIBRATION_FAILED_NO_STEPS)
        warnings.remove(PdrWarning.CALIBRATION_FAILED_IMPLAUSIBLE_LENGTH)
        warnings.remove(PdrWarning.CALIBRATION_FAILED_NO_HEADING)
        warnings.remove(PdrWarning.SESSION_INTERRUPTED)
        onSensorStatus(sensorStatus)
    }

    private fun failCalibration(warning: PdrWarning): Boolean {
        phase = PdrPhase.IDLE
        warnings.add(warning)
        publish()
        return false
    }

    private fun resetTraceInternal() {
        position = PointM(0.0, 0.0)
        distanceM = 0.0
        stepCount = 0
        points.clear()
        points.add(PointM(0.0, 0.0))
        milestoneTracker.reset()
        lastTrackedStepNs = Long.MIN_VALUE
    }

    private fun usable(source: StepSource): Boolean = when (source) {
        StepSource.HARDWARE ->
            sensorStatus.stepDetectorAvailable &&
                (!sensorStatus.permissionNeeded || sensorStatus.permissionGranted)

        StepSource.FALLBACK -> sensorStatus.fallbackCapable
        StepSource.NONE -> false
    }

    private fun publish() {
        val now = clock()
        val mean = orientation.meanHeadingAt(now)
        val reference = referenceYaw
        if (mean != null && reference != null) {
            displayedHeading = wrapAnglePi(mean - reference)
        }
        _state.value = PdrState(
            phase = phase,
            position = position,
            origin = points.first(),
            headingRadians = displayedHeading,
            headingValid = mean != null,
            tilted = orientation.lastTilted,
            lengthPerStepM = lengthPerStepM,
            stepLengthManual = stepLengthManual,
            distanceM = distanceM,
            stepCount = stepCount,
            calibrationStepCount = calibSteps,
            droppedStepCount = droppedSteps,
            rawPath = points.toList(),
            milestones = milestoneTracker.milestones,
            stepSource = stepSource,
            rotationSensorAvailable = sensorStatus.rotationAvailable,
            stepDetectorAvailable = sensorStatus.stepDetectorAvailable,
            fallbackCapable = sensorStatus.fallbackCapable,
            activityPermissionNeeded = sensorStatus.permissionNeeded,
            activityPermissionGranted = sensorStatus.permissionGranted,
            registrationFailed = sensorStatus.registrationFailed,
            warnings = warnings.toSet()
        )
    }

    companion object {
        const val MIN_STEP_LENGTH_M = 0.2
        const val MAX_STEP_LENGTH_M = 1.5
    }
}
