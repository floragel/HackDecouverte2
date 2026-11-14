package com.hackdecouverte.pdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CorridorConstraintResult(
    val projectedLocalPos: PointM,
    val alignedLocalTheta: Double?
)

class PdrTracker(
    private val clock: () -> Long,
    private val orientation: OrientationProcessor = OrientationProcessor(),
    calibrationDistanceM: Double = 5.0,
    milestoneIntervalM: Double = 5.0,
    private val antiShakeEnabled: Boolean = false,
    corridorAssistInitial: Boolean = false
) {
    private val calibrationDistanceM = calibrationDistanceM
    private val milestoneTracker = MilestoneTracker(milestoneIntervalM)

    private val _state = MutableStateFlow(PdrState(corridorAssistEnabled = corridorAssistInitial))
    val state: StateFlow<PdrState> = _state

    var corridorConstraint: ((PointM, Double) -> CorridorConstraintResult?)? = null
    var mapAxisBaseLocal: Double? = null
    var useTrueNorthReference: Boolean = false

    private var verticalHoldActive = false
    private var verticalHoldSuppressed = false
    private val heldStepDeltas = ArrayList<PointM>()
    private var heldDistanceM = 0.0

    val heldStepCount: Int get() = heldStepDeltas.size

    private var sensorStatus = SensorStatus()
    private var referenceYaw: Double? = null
    private var trueNorthOffsetRad: Double? = null
    private var pendingTrueNorthDriftRad = 0.0
    private var displayedHeading = 0.0
    private var position = PointM(0.0, 0.0)
    private var distanceM = 0.0
    private var stepCount = 0
    private var droppedSteps = 0
    private var lengthPerStepM: Double? = null
    private var stepLengthManual = false
    private var stepSource = StepSource.NONE
    private var phase = PdrPhase.IDLE
    private var corridorAssistEnabled = corridorAssistInitial
    private var externalAccuracyM: Double? = null
    private val warnings = LinkedHashSet<PdrWarning>()

    private val points = ArrayList<PointM>().apply { add(PointM(0.0, 0.0)) }
    private var calibStartNs = 0L
    private var calibFinishNs = Long.MAX_VALUE
    private var calibSteps = 0
    private val countedCalibSteps = HashSet<Long>()
    private val calibStepHeadings = ArrayList<Double>()
    private var maxStepTimestampSeenNs = Long.MIN_VALUE
    private var sessionCutoffNs = Long.MIN_VALUE
    private var lastTrackedStepNs = Long.MIN_VALUE
    private var lastShakeNs = Long.MIN_VALUE
    private var pendingGaitStepNs = Long.MIN_VALUE
    private var lastAcceptedGaitStepNs = Long.MIN_VALUE
    private var lastAcceptedWallNs = Long.MIN_VALUE

    private val recentStepYaws = ArrayDeque<Double>()
    private val straightRunThetas = ArrayList<Double>()
    private var learnedAxisBase: Double? = null
    private val recentIntervalsNs = ArrayDeque<Long>()
    private var baseCadenceHz: Double? = null
    private var currentStepLengthM: Double? = null
    private var currentCadenceHz: Double? = null
    private var externalOrigin: PointM? = null
    private var externalPull = PointM(0.0, 0.0)

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

    fun onLinearAccelerationMagnitude(timestampNs: Long, magnitudeMss: Double) {
        if (magnitudeMss.isFinite() && magnitudeMss >= SHAKE_ACCEL_THRESHOLD_MSS) {
            lastShakeNs = max(lastShakeNs, timestampNs)
            pendingGaitStepNs = Long.MIN_VALUE
        }
    }

    fun setCorridorAssistEnabled(enabled: Boolean) {
        corridorAssistEnabled = enabled
        publish()
    }

    /**
     * Feeds a True-North azimuth (`0` = geographic North, `+π/2` = East) computed from
     * `TYPE_ROTATION_VECTOR` + GPS geomagnetic declination.
     */
    fun onTrueNorthAzimuth(
        timestampNs: Long,
        trueAzimuthRad: Double,
        highConfidence: Boolean = true
    ) {
        if (!trueAzimuthRad.isFinite()) return
        val gameYaw = orientation.meanHeadingAt(timestampNs, 80_000_000L)
            ?: orientation.lastValidYaw
            ?: return
        val sampleOffset = wrapAnglePi(trueAzimuthRad - gameYaw)
        val prev = trueNorthOffsetRad
        if (prev == null) {
            trueNorthOffsetRad = sampleOffset
        } else if (phase != PdrPhase.TRACKING) {
            trueNorthOffsetRad = wrapAnglePi(prev + angleDelta(prev, sampleOffset) * 0.25)
        } else if (useTrueNorthReference && highConfidence &&
            orientation.turnRateRadPerSecAt(timestampNs) < 0.35
        ) {
            trueNorthOffsetRad = wrapAnglePi(prev + angleDelta(prev, sampleOffset) * 0.05)
            val ref = referenceYaw
            if (ref != null) {
                val targetRef = wrapAnglePi(-sampleOffset)
                val err = angleDelta(ref, targetRef)
                if (abs(err) <= MAX_COMPASS_DRIFT_WINDOW_RAD) {
                    pendingTrueNorthDriftRad = (err * 0.08)
                        .coerceIn(-MAX_COMPASS_DRIFT_STEP_RAD, MAX_COMPASS_DRIFT_STEP_RAD)
                }
            }
        }
        publish()
    }

    /**
     * Feeds a direct GPS ground-track bearing (`0` = geographic North, `+π/2` = East)
     * when the user is moving with reliable satellite Doppler velocity.
     */
    fun onTrueNorthBearingFromGps(timestampNs: Long, gpsTrueNorthRad: Double) {
        if (!gpsTrueNorthRad.isFinite()) return
        val gameYaw = orientation.meanHeadingAt(timestampNs, 250_000_000L)
            ?: orientation.lastValidYaw
            ?: return
        val gpsOffset = wrapAnglePi(gpsTrueNorthRad - gameYaw)
        val prev = trueNorthOffsetRad
        trueNorthOffsetRad = if (prev == null) {
            gpsOffset
        } else {
            wrapAnglePi(prev + angleDelta(prev, gpsOffset) * 0.4)
        }
        if (phase == PdrPhase.TRACKING && useTrueNorthReference) {
            val ref = referenceYaw
            if (ref != null) {
                val err = angleDelta(ref, wrapAnglePi(-gpsOffset))
                pendingTrueNorthDriftRad = (err * 0.35)
                    .coerceIn(-MAX_GPS_BEARING_STEP_RAD, MAX_GPS_BEARING_STEP_RAD)
            }
        }
        publish()
    }

    fun onExternalFix(localDelta: PointM?, accuracyM: Double) {
        externalAccuracyM = if (accuracyM.isFinite() && accuracyM > 0.0) accuracyM else null
        if (phase != PdrPhase.TRACKING || localDelta == null || !localDelta.isFinite() ||
            !accuracyM.isFinite() || accuracyM <= 0.0 || accuracyM > MAX_FUSION_ACCURACY_M
        ) {
            publish()
            return
        }
        val origin = externalOrigin
        if (origin == null) {
            externalOrigin = position - localDelta
            externalPull = PointM(0.0, 0.0)
            publish()
            return
        }
        val movingRecently = lastAcceptedWallNs != Long.MIN_VALUE &&
            clock() - lastAcceptedWallNs <= MOVING_RECENTLY_NS
        if (movingRecently) {
            val err = origin + localDelta - position
            externalPull = if (err.norm() > max(EXTERNAL_DEADBAND_M, accuracyM)) {
                clampNorm(err * EXTERNAL_GAIN, MAX_EXTERNAL_PULL_M)
            } else {
                PointM(0.0, 0.0)
            }
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
                    orientation.meanHeadingAt(timestampNs)?.let { calibStepHeadings.add(it) }
                }
                maxStepTimestampSeenNs = max(maxStepTimestampSeenNs, timestampNs)
                publish()
            }

            PdrPhase.TRACKING -> {
                maxStepTimestampSeenNs = max(maxStepTimestampSeenNs, timestampNs)
                if (timestampNs <= sessionCutoffNs || timestampNs <= lastTrackedStepNs) {
                    droppedSteps++
                    publish()
                    return
                }
                val previousAccepted = lastAcceptedGaitStepNs
                var intervalNs = if (previousAccepted == Long.MIN_VALUE) {
                    null
                } else {
                    timestampNs - previousAccepted
                }
                if (antiShakeEnabled) {
                    val shaken = lastShakeNs != Long.MIN_VALUE &&
                        abs(timestampNs - lastShakeNs) <= SHAKE_COOLDOWN_NS
                    val turnRateNow = orientation.turnRateRadPerSecAt(timestampNs)
                    if (shaken || turnRateNow > MAX_SPIN_SHAKE_RAD_S) {
                        pendingGaitStepNs = Long.MIN_VALUE
                        droppedSteps++
                        publish()
                        return
                    }
                    val fromStandstill = previousAccepted == Long.MIN_VALUE ||
                        timestampNs - previousAccepted > GAIT_CONTINUITY_NS
                    if (fromStandstill) {
                        val gap = if (pendingGaitStepNs == Long.MIN_VALUE) {
                            Long.MAX_VALUE
                        } else {
                            timestampNs - pendingGaitStepNs
                        }
                        if (gap !in MIN_GAIT_INTERVAL_NS..MAX_GAIT_INTERVAL_NS) {
                            pendingGaitStepNs = timestampNs
                            publish()
                            return
                        }
                        val firstTs = pendingGaitStepNs
                        pendingGaitStepNs = Long.MIN_VALUE
                        advanceStepAt(firstTs, null)
                        intervalNs = gap
                    }
                }
                if (advanceStepAt(timestampNs, intervalNs)) {
                    lastAcceptedGaitStepNs = timestampNs
                    lastAcceptedWallNs = clock()
                }
                publish()
            }

            else -> {
                maxStepTimestampSeenNs = max(maxStepTimestampSeenNs, timestampNs)
            }
        }
    }

    private fun advanceStepAt(timestampNs: Long, intervalNs: Long?): Boolean {
        val length = lengthPerStepM
        val turnRate = orientation.turnRateRadPerSecAt(timestampNs)
        val windowNs = if (turnRate > 0.8) 60_000_000L else 150_000_000L
        val mean = orientation.meanHeadingAt(timestampNs, windowNs)
            ?: orientation.meanHeadingAt(timestampNs)
        var reference = referenceYaw
        if (length == null || mean == null || reference == null ||
            timestampNs <= sessionCutoffNs || timestampNs <= lastTrackedStepNs
        ) {
            droppedSteps++
            return false
        }
        lastTrackedStepNs = timestampNs
        intervalNs?.let(::recordInterval)
        val straight = updateStraightness(mean, turnRate)
        var theta = wrapAnglePi(mean - reference)

        if (useTrueNorthReference && straight && pendingTrueNorthDriftRad != 0.0) {
            reference = wrapAnglePi(reference + pendingTrueNorthDriftRad)
            referenceYaw = reference
            theta = wrapAnglePi(theta - pendingTrueNorthDriftRad)
            pendingTrueNorthDriftRad = 0.0
        }
        if (corridorAssistEnabled && straight) {
            val correction = headingCorrection(theta)
            if (correction != 0.0) {
                reference = wrapAnglePi(reference + correction)
                referenceYaw = reference
                theta = wrapAnglePi(theta - correction)
            }
        }
        learnAxisBase(theta, straight)

        val dynamicLength = length * dynamicStepFactor()
        currentStepLengthM = dynamicLength
        if (verticalHoldActive) {
            heldStepDeltas.add(PointM(dynamicLength * sin(theta), dynamicLength * cos(theta)))
            heldDistanceM += dynamicLength
            stepCount++
            if (heldStepDeltas.size >= MAX_HELD_STEPS) {
                commitHeldSteps()
                verticalHoldActive = false
                verticalHoldSuppressed = true
            }
            return true
        }
        val turnScale = if (corridorAssistEnabled && turnRate > 1.2) {
            (1.0 - (turnRate - 1.2) / 2.0).coerceIn(0.2, 1.0)
        } else {
            1.0
        }
        val effectiveLength = dynamicLength * turnScale
        val previous = position
        val previousDistance = distanceM
        var nextPos = position + PointM(effectiveLength * sin(theta), effectiveLength * cos(theta))

        if (corridorAssistEnabled && straight) {
            corridorConstraint?.invoke(nextPos, theta)?.let { c ->
                val lateral = c.projectedLocalPos - nextPos
                nextPos += clampNorm(lateral * LATERAL_GAIN, MAX_LATERAL_STEP_M)
            }
        }
        if (externalPull.norm() > 1e-6) {
            val apply = clampNorm(externalPull, MAX_EXTERNAL_STEP_M)
            nextPos += apply
            externalPull -= apply
        }

        position = nextPos
        distanceM += effectiveLength
        stepCount++
        points.add(position)
        milestoneTracker.onSegment(previous, previousDistance, position, distanceM)
        return true
    }

    private fun updateStraightness(yaw: Double, turnRate: Double): Boolean {
        recentStepYaws.addLast(yaw)
        while (recentStepYaws.size > STRAIGHT_STEPS) recentStepYaws.removeFirst()
        if (turnRate > STRAIGHT_MAX_TURN_RATE || recentStepYaws.size < STRAIGHT_STEPS) return false
        val m = circularMean(recentStepYaws.toList()) ?: return false
        return recentStepYaws.all { abs(angleDelta(m, it)) <= STRAIGHT_MAX_SPREAD_RAD }
    }

    private fun headingCorrection(theta: Double): Double {
        val corridorTheta = corridorConstraint?.invoke(position, theta)?.alignedLocalTheta
        val (target, maxErr) = if (corridorTheta != null) {
            corridorTheta to MAX_CORRIDOR_ERR_RAD
        } else {
            (axisTarget(theta) ?: return 0.0) to MAX_AXIS_ERR_RAD
        }
        val err = angleDelta(target, theta)
        if (abs(err) > maxErr) return 0.0
        return (err * HEADING_GAIN).coerceIn(-MAX_HEADING_STEP_RAD, MAX_HEADING_STEP_RAD)
    }

    private fun axisTarget(theta: Double): Double? {
        val base = mapAxisBaseLocal ?: learnedAxisBase ?: return null
        val k = round(angleDelta(base, theta) / (PI / 2.0))
        return wrapAnglePi(base + k * PI / 2.0)
    }

    private fun learnAxisBase(theta: Double, straight: Boolean) {
        if (mapAxisBaseLocal != null || learnedAxisBase != null) return
        if (!straight) {
            straightRunThetas.clear()
            return
        }
        straightRunThetas.add(theta)
        if (straightRunThetas.size >= AXIS_LEARN_STEPS) {
            learnedAxisBase = circularMean(straightRunThetas)
            straightRunThetas.clear()
        }
    }

    private fun recordInterval(intervalNs: Long) {
        if (intervalNs !in MIN_GAIT_INTERVAL_NS..MAX_GAIT_INTERVAL_NS) return
        recentIntervalsNs.addLast(intervalNs)
        while (recentIntervalsNs.size > CADENCE_WINDOW) recentIntervalsNs.removeFirst()
        if (recentIntervalsNs.size >= CADENCE_WINDOW) {
            val sorted = recentIntervalsNs.sorted()
            val median = (sorted[1] + sorted[2]) / 2.0
            currentCadenceHz = 1e9 / median
            if (baseCadenceHz == null) baseCadenceHz = currentCadenceHz
        }
    }

    private fun dynamicStepFactor(): Double {
        val base = baseCadenceHz ?: return 1.0
        val f = currentCadenceHz ?: return 1.0
        return (1.0 + CADENCE_GAIN * (f / base - 1.0)).coerceIn(MIN_DYNAMIC_FACTOR, MAX_DYNAMIC_FACTOR)
    }

    private fun clampNorm(v: PointM, maxNorm: Double): PointM {
        val n = v.norm()
        return if (n <= maxNorm || n < 1e-12) v else v * (maxNorm / n)
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
        calibStepHeadings.clear()
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
        val endReference = orientation.stableHeadingAt(nowNs)
            ?: return failCalibration(PdrWarning.CALIBRATION_FAILED_NO_HEADING)
        val trueNorthRef = if (useTrueNorthReference) trueNorthOffsetRad?.let { wrapAnglePi(-it) } else null
        val walkMean = if (calibStepHeadings.size >= 3) circularMean(calibStepHeadings) else null
        referenceYaw = when {
            trueNorthRef != null -> trueNorthRef
            walkMean != null && abs(angleDelta(walkMean, endReference)) <= Math.toRadians(25.0) -> walkMean
            else -> endReference
        }
        lengthPerStepM = length
        stepLengthManual = false
        startGaitModel(calibrationCadenceHz())
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, nowNs)
        phase = PdrPhase.TRACKING
        publish()
        return true
    }

    private fun calibrationCadenceHz(): Double? {
        val sorted = countedCalibSteps.sorted()
        val intervals = sorted.zipWithNext { a, b -> b - a }
            .filter { it in MIN_GAIT_INTERVAL_NS..MAX_GAIT_INTERVAL_NS }
            .sorted()
        if (intervals.size < 3) return null
        val mid = intervals.size / 2
        val median = if (intervals.size % 2 == 0) {
            (intervals[mid - 1] + intervals[mid]) / 2.0
        } else {
            intervals[mid].toDouble()
        }
        return 1e9 / median
    }

    private fun startGaitModel(cadenceHz: Double?) {
        baseCadenceHz = cadenceHz?.takeIf { it.isFinite() && it in 0.6..3.6 }
        currentCadenceHz = null
        currentStepLengthM = lengthPerStepM
        recentIntervalsNs.clear()
    }

    private fun resolveStartReferenceYaw(nowNs: Long): Double? {
        if (orientation.meanHeadingAt(nowNs) == null && orientation.stableHeadingAt(nowNs) == null) {
            return null
        }
        if (useTrueNorthReference) {
            trueNorthOffsetRad?.let { return wrapAnglePi(-it) }
        }
        return orientation.stableHeadingAt(nowNs)
    }

    fun startWithManualStepLength(lengthM: Double, cadenceHz: Double? = null): Boolean {
        if (phase != PdrPhase.IDLE) return false
        if (!lengthM.isFinite() || lengthM !in MIN_STEP_LENGTH_M..MAX_STEP_LENGTH_M) return false
        if (!sensorStatus.rotationAvailable || sensorStatus.registrationFailed) return false
        if (!usable(stepSource)) return false
        val reference = resolveStartReferenceYaw(clock()) ?: return false
        referenceYaw = reference
        lengthPerStepM = lengthM
        stepLengthManual = true
        startGaitModel(cadenceHz)
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
        val reference = resolveStartReferenceYaw(clock()) ?: return false
        referenceYaw = reference
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, clock())
        publish()
        return true
    }

    /**
     * Starts buffering step displacements while the barometer suspects a stair climb.
     * Buffered steps are replayed by [endVerticalHold] (`commit = true`) if no floor change
     * happens, or dropped by [reanchorFloorOriginKeepHeading] when the user lands on a new floor.
     */
    fun beginVerticalHold() {
        if (verticalHoldActive || verticalHoldSuppressed || phase != PdrPhase.TRACKING) return
        verticalHoldActive = true
        heldStepDeltas.clear()
        heldDistanceM = 0.0
    }

    fun endVerticalHold(commit: Boolean) {
        if (commit) commitHeldSteps() else clearHeldSteps()
        verticalHoldActive = false
        verticalHoldSuppressed = false
        publish()
    }

    private fun commitHeldSteps() {
        for (d in heldStepDeltas) {
            val previous = position
            val previousDistance = distanceM
            position += d
            distanceM += d.norm()
            points.add(position)
            milestoneTracker.onSegment(previous, previousDistance, position, distanceM)
        }
        clearHeldSteps()
    }

    private fun clearHeldSteps() {
        heldStepDeltas.clear()
        heldDistanceM = 0.0
    }

    /**
     * Re-anchors local `(0, 0)` on a new floor landing (stairs / elevator exit) while keeping
     * the locked heading reference, cumulative walked distance, step count, and gait model intact.
     */
    fun reanchorFloorOriginKeepHeading(): Boolean {
        clearHeldSteps()
        verticalHoldActive = false
        verticalHoldSuppressed = false
        position = PointM(0.0, 0.0)
        points.clear()
        points.add(PointM(0.0, 0.0))
        recentStepYaws.clear()
        straightRunThetas.clear()
        externalOrigin = null
        externalPull = PointM(0.0, 0.0)
        publish()
        return true
    }

    fun onSensorFrameRestart() {
        orientation.clear()
        referenceYaw = null
        trueNorthOffsetRad = null
        pendingTrueNorthDriftRad = 0.0
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
        val reference = resolveStartReferenceYaw(clock()) ?: return false
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
        calibStepHeadings.clear()
        stepSource = StepSource.NONE
        startGaitModel(null)
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
        clearHeldSteps()
        verticalHoldActive = false
        verticalHoldSuppressed = false
        position = PointM(0.0, 0.0)
        distanceM = 0.0
        stepCount = 0
        points.clear()
        points.add(PointM(0.0, 0.0))
        milestoneTracker.reset()
        lastTrackedStepNs = Long.MIN_VALUE
        pendingGaitStepNs = Long.MIN_VALUE
        lastAcceptedGaitStepNs = Long.MIN_VALUE
        lastAcceptedWallNs = Long.MIN_VALUE
        recentStepYaws.clear()
        straightRunThetas.clear()
        learnedAxisBase = null
        externalOrigin = null
        externalPull = PointM(0.0, 0.0)
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
        val tnOffset = trueNorthOffsetRad
        if (mean != null) {
            if (reference != null) {
                displayedHeading = wrapAnglePi(mean - reference)
            } else if (useTrueNorthReference && tnOffset != null) {
                displayedHeading = wrapAnglePi(mean + tnOffset)
            }
        }
        _state.value = PdrState(
            phase = phase,
            position = position,
            origin = points.first(),
            headingRadians = displayedHeading,
            headingValid = mean != null,
            tilted = orientation.lastTilted,
            lengthPerStepM = lengthPerStepM,
            currentStepLengthM = currentStepLengthM ?: lengthPerStepM,
            cadenceStepsPerMin = currentCadenceHz?.let { it * 60.0 },
            baseCadenceHz = baseCadenceHz,
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
            corridorAssistEnabled = corridorAssistEnabled,
            trueNorthAligned = useTrueNorthReference && tnOffset != null,
            externalAccuracyM = externalAccuracyM,
            warnings = warnings.toSet()
        )
    }

    companion object {
        const val MIN_STEP_LENGTH_M = 0.2
        const val MAX_STEP_LENGTH_M = 1.5
        private const val SHAKE_ACCEL_THRESHOLD_MSS = 11.5
        private const val SHAKE_COOLDOWN_NS = 450_000_000L
        private const val MAX_SPIN_SHAKE_RAD_S = 2.6
        private const val GAIT_CONTINUITY_NS = 1_800_000_000L
        private const val MIN_GAIT_INTERVAL_NS = 280_000_000L
        private const val MAX_GAIT_INTERVAL_NS = 1_300_000_000L
        private const val STRAIGHT_STEPS = 3
        private const val STRAIGHT_MAX_TURN_RATE = 0.5
        private val STRAIGHT_MAX_SPREAD_RAD = Math.toRadians(8.0)
        private const val AXIS_LEARN_STEPS = 5
        private val MAX_CORRIDOR_ERR_RAD = Math.toRadians(18.0)
        private val MAX_AXIS_ERR_RAD = Math.toRadians(12.0)
        private const val HEADING_GAIN = 0.35
        private val MAX_HEADING_STEP_RAD = Math.toRadians(2.5)
        private val MAX_COMPASS_DRIFT_WINDOW_RAD = Math.toRadians(22.0)
        private val MAX_COMPASS_DRIFT_STEP_RAD = Math.toRadians(0.35)
        private val MAX_GPS_BEARING_STEP_RAD = Math.toRadians(2.0)
        private const val LATERAL_GAIN = 0.25
        private const val MAX_LATERAL_STEP_M = 0.12
        private const val CADENCE_WINDOW = 4
        private const val CADENCE_GAIN = 0.9
        private const val MIN_DYNAMIC_FACTOR = 0.75
        private const val MAX_DYNAMIC_FACTOR = 1.30
        private const val MAX_FUSION_ACCURACY_M = 8.0
        private const val MOVING_RECENTLY_NS = 2_500_000_000L
        private const val EXTERNAL_DEADBAND_M = 3.0
        private const val EXTERNAL_GAIN = 0.2
        private const val MAX_EXTERNAL_PULL_M = 1.5
        private const val MAX_EXTERNAL_STEP_M = 0.08
        private const val MAX_HELD_STEPS = 32
    }
}
