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

    /** How the phone is carried (spec §6.6): heading window, shake filter and hold-change bridging. */
    val carry = CarryMonitor().also { it.onHoldChanged = { t, d -> onHoldChanged(t, d) } }

    /**
     * Any way of holding the phone (map localization mode, where the filter learns the phone→walking
     * angle): heading = the phone's twist about the vertical for any attitude but screen-down, a
     * swinging phone's heading is averaged over a stride and its gait is not mistaken for shaking, and
     * hold changes are bridged so the heading stays continuous. Off: phone held flat, as before.
     */
    var anyHoldHeading: Boolean = false
        set(value) {
            field = value
            orientation.anyHold = value
        }

    /** Tuning switches (replay experiments): slow compass pull of the heading, hold-change bridging. */
    var compassPullEnabled: Boolean = true
    /** Pivot shrink (stride-averaged heading only): from this turn rate, down to [pivotMinScale] over [pivotFullRateSpanRadS]. */
    var pivotTurnRateRadS: Double = 99.0
    var pivotFullRateSpanRadS: Double = 1.0
    var pivotMinScale: Double = 0.2
    var holdBridgingEnabled: Boolean = true

    /** Screen on/off (set by the app): a phone with its screen off is not in a reading pose. */
    var screenInteractive: Boolean
        get() = carry.screenInteractive
        set(value) {
            carry.screenInteractive = value
        }

    /** Sum of the phone-heading jumps of all bridged hold changes since the last reference. */
    private var holdOffsetRad = 0.0
    private var bridgedHoldChanges = 0
    /** The heading is the walking direction ±~20° (started in a reading pose, bridged since). */
    private var headingAligned = true
    private var lastTheta: Double? = null

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
    private var landmarkPull = PointM(0.0, 0.0)

    /** Remaining landmark (junction) correction still to be replayed, in metres. */
    val pendingLandmarkPullM: Double
        get() = landmarkPull.norm()

    fun onRotationVector(timestampNs: Long, values: FloatArray, size: Int = values.size) {
        orientation.onRotationVector(timestampNs, values, size)
        rotationMatrixFromVector(values, size)?.let {
            carry.onRotation(timestampNs, it)
            lastRotation = it
        }
        publish()
    }

    private var lastRotation: DoubleArray? = null
    /** World-frame horizontal linear acceleration (east, north in the game frame), last few seconds. */
    private val horizontalAcc = ArrayDeque<Triple<Long, Double, Double>>()

    /**
     * Linear acceleration in the phone frame (m/s²): rotated to the world with the latest game
     * rotation and kept for the walking axis (see [walkAxisOffset]).
     */
    fun onLinearAcceleration(timestampNs: Long, ax: Double, ay: Double, az: Double) {
        val r = lastRotation ?: return
        horizontalAcc.addLast(Triple(timestampNs, r[0] * ax + r[1] * ay + r[2] * az, r[3] * ax + r[4] * ay + r[5] * az))
        while (horizontalAcc.isNotEmpty() && horizontalAcc.first().first < timestampNs - 4_000_000_000L) horizontalAcc.removeFirst()
    }

    /**
     * A phone in a pocket shifts a little at every stride and its heading (twist about the vertical)
     * moves with it, while the walking direction does not. The walking axis is the main axis of the
     * horizontal acceleration over a stride (body frame, mod 180°); the offset phone heading − walking
     * axis is followed slowly (≈ [walkAxisTau] strides) and its drift since the hold settled is removed
     * from the heading. Pure body-carry mode only (stride-averaged heading). **Off by default:** on the
     * first real loop (2 min in a pocket) it only moved the gap from 15.7 % to 15.0 % of the distance (an
     * apparent 4 % in a prototype came from 180° flips of the unwrapped axis); to be judged on more loops.
     */
    var walkAxisCorrectionEnabled: Boolean = false
    var walkAxisTau: Double = 4.0
    var walkAxisMinRatio: Double = 2.0
    private var axisOffX = 0.0
    private var axisOffY = 0.0
    private var axisOffset: Double? = null
    private var axisOffsetRef: Double? = null

    private fun resetWalkAxis() {
        axisOffset = null
        axisOffsetRef = null
    }

    /** Main axis (clockwise from north, mod 180°) of the horizontal acceleration in [from, to] and its eigenvalue ratio. */
    private fun walkAxis(from: Long, to: Long): Pair<Double, Double>? {
        var n = 0
        var mx = 0.0
        var my = 0.0
        for ((t, x, y) in horizontalAcc) if (t in from..to) { mx += x; my += y; n++ }
        if (n < 15) return null
        mx /= n; my /= n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for ((t, x, y) in horizontalAcc) if (t in from..to) {
            sxx += (x - mx) * (x - mx); syy += (y - my) * (y - my); sxy += (x - mx) * (y - my)
        }
        val d = kotlin.math.sqrt(((sxx - syy) / 2) * ((sxx - syy) / 2) + sxy * sxy)
        val l1 = (sxx + syy) / 2 + d
        val l2 = (sxx + syy) / 2 - d
        return (PI / 2 - 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)) to l1 / maxOf(l2, 1e-9)
    }

    /** Drift of the phone→walking offset since it was first measured in this hold (0 until then). */
    private fun walkAxisOffset(timestampNs: Long, phoneHeading: Double, strideNs: Long): Double {
        if (!walkAxisCorrectionEnabled || !swinging) return 0.0
        val (axis, ratio) = walkAxis(timestampNs - strideNs, timestampNs) ?: return currentAxisDrift()
        if (ratio < walkAxisMinRatio) return currentAxisDrift()
        val d2 = wrapAnglePi(2 * (phoneHeading - axis))
        val prev = axisOffset
        if (prev == null) {
            axisOffX = kotlin.math.cos(d2); axisOffY = kotlin.math.sin(d2)
        } else {
            val k = 1.0 / walkAxisTau
            axisOffX = (1 - k) * axisOffX + k * kotlin.math.cos(d2)
            axisOffY = (1 - k) * axisOffY + k * kotlin.math.sin(d2)
        }
        var half = kotlin.math.atan2(axisOffY, axisOffX) / 2
        // The offset is only known mod 180°: stay on the branch nearest to the previous value.
        if (prev != null) {
            while (half - prev > PI / 2) half -= PI
            while (prev - half > PI / 2) half += PI
        }
        axisOffset = half
        if (axisOffsetRef == null) axisOffsetRef = half
        return currentAxisDrift()
    }

    private fun currentAxisDrift(): Double {
        val o = axisOffset ?: return 0.0
        val r = axisOffsetRef ?: return 0.0
        return o - r
    }

    private var recoveredSteps = 0

    /**
     * Steps the detector missed but the step counter counted ([StepReconciler]): added one by one
     * along the current walking heading with the current step length. Never during a stair hold.
     */
    fun onRecoveredSteps(count: Int) {
        if (phase != PdrPhase.TRACKING || count <= 0 || verticalHoldActive) return
        val theta = lastTheta ?: run {
            val ref = referenceYaw ?: return
            val mean = orientation.meanHeadingAt(clock(), if (swinging) strideWindowNs() else 150_000_000L) ?: return
            wrapAnglePi(mean - ref - holdOffsetRad)
        }
        val length = (lengthPerStepM ?: return) * dynamicStepFactor()
        repeat(count) {
            val previous = position
            val previousDistance = distanceM
            position += PointM(length * sin(theta), length * cos(theta))
            distanceM += length
            stepCount++
            points.add(position)
            milestoneTracker.onSegment(previous, previousDistance, position, distanceM)
        }
        recoveredSteps += count
        publish()
    }

    /** A new hold has settled: keep the heading continuous (the body is assumed not to have turned meanwhile). */
    private fun onHoldChanged(@Suppress("UNUSED_PARAMETER") tNs: Long, deltaTwistRad: Double) {
        if (!anyHoldHeading || !holdBridgingEnabled) return
        alignmentPending = false // the pose at the start is gone: whatever was decided stays
        holdOffsetRad = wrapAnglePi(holdOffsetRad + deltaTwistRad)
        bridgedHoldChanges++
        resetWalkAxis() // the new hold has its own phone→walking offset
        recentStepYaws.clear() // the raw phone heading jumped: straightness starts over
    }

    /**
     * Any hold, and the phone is not held steady in front of the eyes (pocket, bag, swinging hand,
     * screen off): stride-averaged heading, no shake filter. A real walk showed the swing measure
     * hovering around its threshold in a pocket; the reading pose is the reliable dividing line.
     */
    private val swinging: Boolean
        get() = anyHoldHeading && (carry.mode == CarryMode.SWINGING || !carry.readingPose)

    /** Step-length multiplier (tuning; 1 = none). The detector/counter disagreement turned out to be pivots, not doubled steps. */
    var stepCountScale: Double = 1.0

    /** One stride (2 steps) from the recent cadence, for averaging a rocking phone's heading. */
    private fun strideWindowNs(): Long {
        if (recentIntervalsNs.isEmpty()) return DEFAULT_STRIDE_NS
        val sorted = recentIntervalsNs.sorted()
        return (2 * sorted[sorted.size / 2]).coerceIn(MIN_STRIDE_NS, MAX_STRIDE_NS)
    }

    private fun headingWindowNs(turnRate: Double): Long = when {
        swinging -> strideWindowNs()
        turnRate > 0.8 -> 60_000_000L
        else -> 150_000_000L
    }

    private fun turnRateAt(timestampNs: Long): Double =
        if (swinging) orientation.strideTurnRateRadPerSecAt(timestampNs, strideWindowNs())
        else orientation.turnRateRadPerSecAt(timestampNs)

    /** Reading pose not known yet (the carry monitor needs 1 s of data): decided at the first evaluation. */
    private var alignmentPending = false

    /** A new reference heading: hold bridging starts over from the current hold. */
    private fun onNewReference() {
        resetWalkAxis()
        holdOffsetRad = 0.0
        lastTheta = null
        alignmentPending = anyHoldHeading && carry.up == null
        headingAligned = !anyHoldHeading || carry.readingPose
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

    /**
     * A recognised landmark (e.g. the corridor junction where the user just turned) says the user
     * is really at [localTarget]. The gap is replayed over the next steps (≤ 20 cm per step,
     * ≤ 6 m in all), so the dot never jumps and past trace points are never rewritten.
     */
    fun onLandmark(localTarget: PointM) {
        if (phase != PdrPhase.TRACKING || !localTarget.x.isFinite() || !localTarget.y.isFinite()) return
        landmarkPull = clampNorm(localTarget - position, MAX_LANDMARK_PULL_M)
        publish()
    }

    fun onLinearAccelerationMagnitude(timestampNs: Long, magnitudeMss: Double) {
        if (swinging) return // a pocket or a swinging hand jolts at every heel strike: that is walking, not shaking
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
        // Same instant for both phone headings; a rocking phone moves too fast for an 80 ms average.
        val gameYaw = orientation.meanHeadingAt(timestampNs, if (swinging) 25_000_000L else 80_000_000L)
            ?: orientation.lastValidYaw
            ?: return
        val sampleOffset = wrapAnglePi(trueAzimuthRad - gameYaw)
        val prev = trueNorthOffsetRad
        if (prev == null) {
            trueNorthOffsetRad = sampleOffset
        } else if (phase != PdrPhase.TRACKING) {
            trueNorthOffsetRad = wrapAnglePi(prev + angleDelta(prev, sampleOffset) * 0.25)
        } else if (useTrueNorthReference && highConfidence && turnRateAt(timestampNs) < 0.35) {
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
        // The GPS course is the walking direction: only comparable with the phone heading when that is it.
        if (anyHoldHeading && (!headingAligned || swinging)) return
        val gameYaw = orientation.meanHeadingAt(timestampNs, 250_000_000L)
            ?: orientation.lastValidYaw
            ?: return
        val gpsOffset = wrapAnglePi(gpsTrueNorthRad - (gameYaw - holdOffsetRad))
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
                    // A phone in a pocket or a swinging hand jolts and spins at every step: that is gait, not shaking.
                    val shaken = !swinging && lastShakeNs != Long.MIN_VALUE &&
                        abs(timestampNs - lastShakeNs) <= SHAKE_COOLDOWN_NS
                    val turnRateNow = if (swinging) 0.0 else orientation.turnRateRadPerSecAt(timestampNs)
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
        val turnRate = turnRateAt(timestampNs)
        val windowNs = headingWindowNs(turnRate)
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
        // While the phone is being moved to a new hold its heading means nothing: keep the last one.
        val frozen = lastTheta?.takeIf { anyHoldHeading && carry.inTransition }
        val axisDrift = if (frozen == null) walkAxisOffset(timestampNs, mean, strideWindowNs()) else 0.0
        var theta = frozen ?: wrapAnglePi(mean - reference - holdOffsetRad - axisDrift)

        if (useTrueNorthReference && compassPullEnabled && straight && pendingTrueNorthDriftRad != 0.0) {
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
        lastTheta = theta

        val dynamicLength = length * dynamicStepFactor() * stepCountScale
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
        val turnScale = when {
            // Pivoting on the spot (to dodge someone, to turn back) fires the step detector while the body
            // barely moves: with a stride-averaged heading, steps under a fast turn shrink (tunable).
            swinging && turnRate > pivotTurnRateRadS ->
                (1.0 - (turnRate - pivotTurnRateRadS) / pivotFullRateSpanRadS).coerceIn(pivotMinScale, 1.0)
            corridorAssistEnabled && turnRate > 1.2 -> (1.0 - (turnRate - 1.2) / 2.0).coerceIn(0.2, 1.0)
            else -> 1.0
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
        if (landmarkPull.norm() > 1e-6) {
            val apply = clampNorm(landmarkPull, MAX_LANDMARK_STEP_M)
            nextPos += apply
            landmarkPull -= apply
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
        onNewReference()
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
        // Any-hold (map localization, no calibration walk): the first steps of a walk are slow and the
        // detector doubles some in a pocket, so the base cadence must not be learned from them — a
        // typical walking cadence is the reference instead (the map learns the stride on top).
        baseCadenceHz = cadenceHz?.takeIf { it.isFinite() && it in 0.6..3.6 } ?: if (anyHoldHeading) TYPICAL_CADENCE_HZ else null
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
        onNewReference()
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
        onNewReference()
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
        landmarkPull = PointM(0.0, 0.0)
        publish()
        return true
    }

    fun onSensorFrameRestart() {
        orientation.clear()
        carry.reset()
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
        onNewReference()
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
        landmarkPull = PointM(0.0, 0.0)
    }

    private fun usable(source: StepSource): Boolean = when (source) {
        StepSource.HARDWARE ->
            sensorStatus.stepDetectorAvailable &&
                (!sensorStatus.permissionNeeded || sensorStatus.permissionGranted)

        StepSource.FALLBACK -> sensorStatus.fallbackCapable
        StepSource.NONE -> false
    }

    private fun publish() {
        if (alignmentPending && carry.up != null) {
            alignmentPending = false
            headingAligned = carry.readingPose
        }
        val now = clock()
        val mean = if (swinging) orientation.meanHeadingAt(now, strideWindowNs()) else orientation.meanHeadingAt(now)
        val reference = referenceYaw
        val tnOffset = trueNorthOffsetRad
        if (mean != null) {
            if (reference != null) {
                displayedHeading = wrapAnglePi(mean - reference - holdOffsetRad)
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
            carryMode = carry.mode,
            inHoldTransition = anyHoldHeading && carry.inTransition,
            holdChanges = bridgedHoldChanges,
            headingAligned = headingAligned,
            readingPose = carry.readingPose,
            recoveredStepCount = recoveredSteps,
            strideAveraged = swinging,
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
        private const val DEFAULT_STRIDE_NS = 1_100_000_000L
        /** About 110 steps/min: an average adult walking cadence. */
        const val TYPICAL_CADENCE_HZ = 1.8
        private const val MIN_STRIDE_NS = 800_000_000L
        private const val MAX_STRIDE_NS = 1_600_000_000L

        /** Junction (landmark) correction: bounded like the other corrections. */
        const val MAX_LANDMARK_STEP_M = 0.20
        const val MAX_LANDMARK_PULL_M = 6.0
    }
}
