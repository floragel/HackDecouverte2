import Foundation

/// 1:1 port of `com.hackdecouverte.pdr.PdrTracker`.
/// Each accepted step: θ = meanHeading(step time) − reference, x += L·sin θ, y += L·cos θ.
/// Every correction is bounded per step (heading ≤ 2.5°, lateral ≤ 12 cm, GPS ≤ 8 cm) and never
/// rewrites past trace points.
struct CorridorConstraintResult { let projectedLocalPos: PointM; let alignedLocalTheta: Double? }

final class PdrTracker {
    static let minStepLengthM = 0.2
    static let maxStepLengthM = 1.5
    private static let shakeAccelThresholdMss = 11.5
    private static let shakeCooldownNs: Int64 = 450_000_000
    private static let maxSpinShakeRadS = 2.6
    private static let gaitContinuityNs: Int64 = 1_800_000_000
    private static let minGaitIntervalNs: Int64 = 280_000_000
    private static let maxGaitIntervalNs: Int64 = 1_300_000_000
    private static let straightSteps = 3
    private static let straightMaxTurnRate = 0.5
    private static let straightMaxSpreadRad = degToRad(8)
    private static let axisLearnSteps = 5
    private static let maxCorridorErrRad = degToRad(18)
    private static let maxAxisErrRad = degToRad(12)
    private static let headingGain = 0.35
    private static let maxHeadingStepRad = degToRad(2.5)
    private static let maxCompassDriftWindowRad = degToRad(22)
    private static let maxCompassDriftStepRad = degToRad(0.35)
    private static let maxGpsBearingStepRad = degToRad(2)
    private static let lateralGain = 0.25
    private static let maxLateralStepM = 0.12
    private static let cadenceWindow = 4
    private static let cadenceGain = 0.9
    private static let minDynamicFactor = 0.75
    private static let maxDynamicFactor = 1.30
    private static let maxFusionAccuracyM = 8.0
    private static let movingRecentlyNs: Int64 = 2_500_000_000
    private static let externalDeadbandM = 3.0
    private static let externalGain = 0.2
    private static let maxExternalPullM = 1.5
    private static let maxExternalStepM = 0.08
    private static let maxHeldSteps = 32
    /// Junction (landmark) correction, iOS addition: bounded like the other corrections.
    static let maxLandmarkStepM = 0.20
    static let maxLandmarkPullM = 6.0

    private let clock: () -> Int64
    private let orientation: OrientationProcessor
    private let calibrationDistanceM: Double
    private let milestoneTracker: MilestoneTracker
    private let antiShakeEnabled: Bool

    private(set) var state: PdrState
    /// Called after every state change (on the caller's thread).
    var onStateChange: ((PdrState) -> Void)?

    var corridorConstraint: ((PointM, Double) -> CorridorConstraintResult?)?
    var mapAxisBaseLocal: Double?
    var useTrueNorthReference = false

    private var verticalHoldActive = false
    private var verticalHoldSuppressed = false
    private var heldStepDeltas: [PointM] = []
    private var heldDistanceM = 0.0
    var heldStepCount: Int { heldStepDeltas.count }

    private var sensorStatus = SensorStatus()
    private var referenceYaw: Double?
    private var trueNorthOffsetRad: Double?
    private var pendingTrueNorthDriftRad = 0.0
    private var displayedHeading = 0.0
    private var position = PointM.zero
    private var distanceM = 0.0
    private var stepCount = 0
    private var droppedSteps = 0
    private var lengthPerStepM: Double?
    private var stepLengthManual = false
    private var stepSource: StepSource = .none
    private var phase: PdrPhase = .idle
    private var corridorAssistEnabled: Bool
    private var externalAccuracyM: Double?
    private var warnings: [PdrWarning] = [] // insertion-ordered set

    private var points: [PointM] = [.zero]
    private var calibStartNs: Int64 = 0
    private var calibFinishNs = Int64.max
    private var calibSteps = 0
    private var countedCalibSteps = Set<Int64>()
    private var calibStepHeadings: [Double] = []
    private var maxStepTimestampSeenNs = Int64.min
    private var sessionCutoffNs = Int64.min
    private var lastTrackedStepNs = Int64.min
    private var lastShakeNs = Int64.min
    private var pendingGaitStepNs = Int64.min
    private var lastAcceptedGaitStepNs = Int64.min
    private var lastAcceptedWallNs = Int64.min

    private var recentStepYaws: [Double] = []
    private var straightRunThetas: [Double] = []
    private var learnedAxisBase: Double?
    private var recentIntervalsNs: [Int64] = []
    private var baseCadenceHz: Double?
    private var currentStepLengthM: Double?
    private var currentCadenceHz: Double?
    private var externalOrigin: PointM?
    private var externalPull = PointM.zero
    private var landmarkPull = PointM.zero
    var pendingLandmarkPullM: Double { landmarkPull.norm() }

    init(clock: @escaping () -> Int64, orientation: OrientationProcessor = OrientationProcessor(), calibrationDistanceM: Double = 5,
         milestoneIntervalM: Double = 5, antiShakeEnabled: Bool = false, corridorAssistInitial: Bool = false) {
        self.clock = clock
        self.orientation = orientation
        self.calibrationDistanceM = calibrationDistanceM
        milestoneTracker = MilestoneTracker(intervalM: milestoneIntervalM)
        self.antiShakeEnabled = antiShakeEnabled
        corridorAssistEnabled = corridorAssistInitial
        state = PdrState(corridorAssistEnabled: corridorAssistInitial)
    }

    private func addWarning(_ w: PdrWarning) { if !warnings.contains(w) { warnings.append(w) } }
    private func removeWarning(_ w: PdrWarning) { warnings.removeAll { $0 == w } }

    // MARK: sensors

    func onRotationVector(_ timestampNs: Int64, _ values: [Double]) {
        orientation.onRotationVector(timestampNs, values)
        publish()
    }

    func onRotationMatrix(_ timestampNs: Int64, _ r: [Double]) {
        orientation.onRotationMatrix(timestampNs, r)
        publish()
    }

    func onSensorStatus(_ status: SensorStatus) {
        sensorStatus = status
        for w in [PdrWarning.rotationSensorMissing, .stepDetectorMissing, .fallbackSensorMissing, .activityPermissionDenied, .sensorRegistrationFailed] { removeWarning(w) }
        if !status.rotationAvailable { addWarning(.rotationSensorMissing) }
        if !status.stepDetectorAvailable { addWarning(.stepDetectorMissing) }
        if !status.fallbackCapable { addWarning(.fallbackSensorMissing) }
        if status.stepDetectorAvailable && status.permissionNeeded && !status.permissionGranted { addWarning(.activityPermissionDenied) }
        if status.registrationFailed { addWarning(.sensorRegistrationFailed) }
        if stepSource == .none {
            if usable(.hardware) { stepSource = .hardware }
        } else if !usable(stepSource) {
            stepSource = .none
        }
        publish()
    }

    /// A recognised landmark (e.g. the corridor junction where the user just turned) says the user is
    /// really at `localTarget`. The gap is replayed over the next steps (≤ 20 cm per step, ≤ 6 m in all),
    /// so the dot never jumps and past trace points are never rewritten.
    func onLandmark(_ localTarget: PointM) {
        guard phase == .tracking, localTarget.isFinite else { return }
        landmarkPull = clampNorm(localTarget - position, Self.maxLandmarkPullM)
        publish()
    }

    func onLinearAccelerationMagnitude(_ timestampNs: Int64, _ magnitudeMss: Double) {
        if magnitudeMss.isFinite && magnitudeMss >= Self.shakeAccelThresholdMss {
            lastShakeNs = max(lastShakeNs, timestampNs)
            pendingGaitStepNs = .min
        }
    }

    func setCorridorAssistEnabled(_ enabled: Bool) { corridorAssistEnabled = enabled; publish() }

    /// True-North azimuth (0 = geographic North, +π/2 = East).
    func onTrueNorthAzimuth(_ timestampNs: Int64, _ trueAzimuthRad: Double, highConfidence: Bool = true) {
        guard trueAzimuthRad.isFinite,
              let gameYaw = orientation.meanHeadingAt(timestampNs, windowNs: 80_000_000) ?? orientation.lastValidYaw else { return }
        let sampleOffset = wrapAnglePi(trueAzimuthRad - gameYaw)
        if let prev = trueNorthOffsetRad {
            if phase != .tracking {
                trueNorthOffsetRad = wrapAnglePi(prev + angleDelta(prev, sampleOffset) * 0.25)
            } else if useTrueNorthReference && highConfidence && orientation.turnRateRadPerSecAt(timestampNs) < 0.35 {
                trueNorthOffsetRad = wrapAnglePi(prev + angleDelta(prev, sampleOffset) * 0.05)
                if let ref = referenceYaw {
                    let err = angleDelta(ref, wrapAnglePi(-sampleOffset))
                    if abs(err) <= Self.maxCompassDriftWindowRad {
                        pendingTrueNorthDriftRad = min(Self.maxCompassDriftStepRad, max(-Self.maxCompassDriftStepRad, err * 0.08))
                    }
                }
            }
        } else {
            trueNorthOffsetRad = sampleOffset
        }
        publish()
    }

    /// GPS ground-track bearing while moving (0 = North, +π/2 = East).
    func onTrueNorthBearingFromGps(_ timestampNs: Int64, _ gpsTrueNorthRad: Double) {
        guard gpsTrueNorthRad.isFinite,
              let gameYaw = orientation.meanHeadingAt(timestampNs, windowNs: 250_000_000) ?? orientation.lastValidYaw else { return }
        let gpsOffset = wrapAnglePi(gpsTrueNorthRad - gameYaw)
        if let prev = trueNorthOffsetRad { trueNorthOffsetRad = wrapAnglePi(prev + angleDelta(prev, gpsOffset) * 0.4) } else { trueNorthOffsetRad = gpsOffset }
        if phase == .tracking && useTrueNorthReference, let ref = referenceYaw {
            let err = angleDelta(ref, wrapAnglePi(-gpsOffset))
            pendingTrueNorthDriftRad = min(Self.maxGpsBearingStepRad, max(-Self.maxGpsBearingStepRad, err * 0.35))
        }
        publish()
    }

    func onExternalFix(_ localDelta: PointM?, accuracyM: Double) {
        externalAccuracyM = accuracyM.isFinite && accuracyM > 0 ? accuracyM : nil
        guard phase == .tracking, let localDelta, localDelta.isFinite, accuracyM.isFinite, accuracyM > 0, accuracyM <= Self.maxFusionAccuracyM else { publish(); return }
        guard let origin = externalOrigin else {
            externalOrigin = position - localDelta
            externalPull = .zero
            publish(); return
        }
        let movingRecently = lastAcceptedWallNs != .min && clock() - lastAcceptedWallNs <= Self.movingRecentlyNs
        if movingRecently {
            let err = origin + localDelta - position
            externalPull = err.norm() > max(Self.externalDeadbandM, accuracyM) ? clampNorm(err * Self.externalGain, Self.maxExternalPullM) : .zero
        }
        publish()
    }

    func onStep(_ timestampNs: Int64, source: StepSource) {
        guard source != .none, source == stepSource, !sensorStatus.registrationFailed else { return }
        switch phase {
        case .calibrating, .settling:
            if timestampNs >= calibStartNs && timestampNs <= calibFinishNs && countedCalibSteps.insert(timestampNs).inserted {
                calibSteps += 1
                if let h = orientation.meanHeadingAt(timestampNs) { calibStepHeadings.append(h) }
            }
            maxStepTimestampSeenNs = max(maxStepTimestampSeenNs, timestampNs)
            publish()
        case .tracking:
            maxStepTimestampSeenNs = max(maxStepTimestampSeenNs, timestampNs)
            if timestampNs <= sessionCutoffNs || timestampNs <= lastTrackedStepNs { droppedSteps += 1; publish(); return }
            let previousAccepted = lastAcceptedGaitStepNs
            var intervalNs: Int64? = previousAccepted == .min ? nil : timestampNs - previousAccepted
            if antiShakeEnabled {
                let shaken = lastShakeNs != .min && abs(timestampNs - lastShakeNs) <= Self.shakeCooldownNs
                if shaken || orientation.turnRateRadPerSecAt(timestampNs) > Self.maxSpinShakeRadS {
                    pendingGaitStepNs = .min; droppedSteps += 1; publish(); return
                }
                let fromStandstill = previousAccepted == .min || timestampNs - previousAccepted > Self.gaitContinuityNs
                if fromStandstill {
                    let gap = pendingGaitStepNs == .min ? Int64.max : timestampNs - pendingGaitStepNs
                    if !(Self.minGaitIntervalNs...Self.maxGaitIntervalNs).contains(gap) {
                        pendingGaitStepNs = timestampNs; publish(); return
                    }
                    let firstTs = pendingGaitStepNs
                    pendingGaitStepNs = .min
                    _ = advanceStepAt(firstTs, nil)
                    intervalNs = gap
                }
            }
            if advanceStepAt(timestampNs, intervalNs) {
                lastAcceptedGaitStepNs = timestampNs
                lastAcceptedWallNs = clock()
            }
            publish()
        default:
            maxStepTimestampSeenNs = max(maxStepTimestampSeenNs, timestampNs)
        }
    }

    private func advanceStepAt(_ timestampNs: Int64, _ intervalNs: Int64?) -> Bool {
        let turnRate = orientation.turnRateRadPerSecAt(timestampNs)
        let windowNs: Int64 = turnRate > 0.8 ? 60_000_000 : 150_000_000
        let mean = orientation.meanHeadingAt(timestampNs, windowNs: windowNs) ?? orientation.meanHeadingAt(timestampNs)
        guard let length = lengthPerStepM, let mean, var reference = referenceYaw,
              timestampNs > sessionCutoffNs, timestampNs > lastTrackedStepNs else { droppedSteps += 1; return false }
        lastTrackedStepNs = timestampNs
        if let i = intervalNs { recordInterval(i) }
        let straight = updateStraightness(mean, turnRate)
        var theta = wrapAnglePi(mean - reference)

        if useTrueNorthReference && straight && pendingTrueNorthDriftRad != 0 {
            reference = wrapAnglePi(reference + pendingTrueNorthDriftRad)
            referenceYaw = reference
            theta = wrapAnglePi(theta - pendingTrueNorthDriftRad)
            pendingTrueNorthDriftRad = 0
        }
        if corridorAssistEnabled && straight {
            let correction = headingCorrection(theta)
            if correction != 0 {
                reference = wrapAnglePi(reference + correction)
                referenceYaw = reference
                theta = wrapAnglePi(theta - correction)
            }
        }
        learnAxisBase(theta, straight)

        let dynamicLength = length * dynamicStepFactor()
        currentStepLengthM = dynamicLength
        if verticalHoldActive {
            heldStepDeltas.append(PointM(dynamicLength * sin(theta), dynamicLength * cos(theta)))
            heldDistanceM += dynamicLength
            stepCount += 1
            if heldStepDeltas.count >= Self.maxHeldSteps {
                commitHeldSteps()
                verticalHoldActive = false
                verticalHoldSuppressed = true
            }
            return true
        }
        let turnScale = corridorAssistEnabled && turnRate > 1.2 ? min(1, max(0.2, 1 - (turnRate - 1.2) / 2)) : 1
        let effectiveLength = dynamicLength * turnScale
        let previous = position, previousDistance = distanceM
        var nextPos = position + PointM(effectiveLength * sin(theta), effectiveLength * cos(theta))
        if corridorAssistEnabled && straight, let c = corridorConstraint?(nextPos, theta) {
            nextPos = nextPos + clampNorm((c.projectedLocalPos - nextPos) * Self.lateralGain, Self.maxLateralStepM)
        }
        if externalPull.norm() > 1e-6 {
            let apply = clampNorm(externalPull, Self.maxExternalStepM)
            nextPos = nextPos + apply
            externalPull = externalPull - apply
        }
        if landmarkPull.norm() > 1e-6 {
            let apply = clampNorm(landmarkPull, Self.maxLandmarkStepM)
            nextPos = nextPos + apply
            landmarkPull = landmarkPull - apply
        }
        position = nextPos
        distanceM += effectiveLength
        stepCount += 1
        points.append(position)
        milestoneTracker.onSegment(previous, previousDistance, position, distanceM)
        return true
    }

    private func updateStraightness(_ yaw: Double, _ turnRate: Double) -> Bool {
        recentStepYaws.append(yaw)
        while recentStepYaws.count > Self.straightSteps { recentStepYaws.removeFirst() }
        guard turnRate <= Self.straightMaxTurnRate, recentStepYaws.count >= Self.straightSteps, let m = circularMean(recentStepYaws) else { return false }
        return recentStepYaws.allSatisfy { abs(angleDelta(m, $0)) <= Self.straightMaxSpreadRad }
    }

    private func headingCorrection(_ theta: Double) -> Double {
        let target: Double, maxErr: Double
        if let corridorTheta = corridorConstraint?(position, theta)?.alignedLocalTheta {
            target = corridorTheta; maxErr = Self.maxCorridorErrRad
        } else if let axis = axisTarget(theta) {
            target = axis; maxErr = Self.maxAxisErrRad
        } else { return 0 }
        let err = angleDelta(target, theta)
        if abs(err) > maxErr { return 0 }
        return min(Self.maxHeadingStepRad, max(-Self.maxHeadingStepRad, err * Self.headingGain))
    }

    private func axisTarget(_ theta: Double) -> Double? {
        guard let base = mapAxisBaseLocal ?? learnedAxisBase else { return nil }
        let k = (angleDelta(base, theta) / (.pi / 2)).rounded(.toNearestOrEven) // kotlin.math.round = half-even
        return wrapAnglePi(base + k * .pi / 2)
    }

    private func learnAxisBase(_ theta: Double, _ straight: Bool) {
        if mapAxisBaseLocal != nil || learnedAxisBase != nil { return }
        if !straight { straightRunThetas.removeAll(); return }
        straightRunThetas.append(theta)
        if straightRunThetas.count >= Self.axisLearnSteps {
            learnedAxisBase = circularMean(straightRunThetas)
            straightRunThetas.removeAll()
        }
    }

    private func recordInterval(_ intervalNs: Int64) {
        guard (Self.minGaitIntervalNs...Self.maxGaitIntervalNs).contains(intervalNs) else { return }
        recentIntervalsNs.append(intervalNs)
        while recentIntervalsNs.count > Self.cadenceWindow { recentIntervalsNs.removeFirst() }
        if recentIntervalsNs.count >= Self.cadenceWindow {
            let sorted = recentIntervalsNs.sorted()
            let median = Double(sorted[1] + sorted[2]) / 2
            currentCadenceHz = 1e9 / median
            if baseCadenceHz == nil { baseCadenceHz = currentCadenceHz }
        }
    }

    private func dynamicStepFactor() -> Double {
        guard let base = baseCadenceHz, let f = currentCadenceHz else { return 1 }
        return min(Self.maxDynamicFactor, max(Self.minDynamicFactor, 1 + Self.cadenceGain * (f / base - 1)))
    }

    private func clampNorm(_ v: PointM, _ maxNorm: Double) -> PointM {
        let n = v.norm()
        return n <= maxNorm || n < 1e-12 ? v : v * (maxNorm / n)
    }

    // MARK: lifecycle

    @discardableResult func setStepSource(_ source: StepSource) -> Bool {
        guard phase == .idle else { return false }
        if source != .none && !usable(source) { return false }
        stepSource = source
        publish()
        return true
    }

    @discardableResult func startCalibration() -> Bool {
        guard phase == .idle, sensorStatus.rotationAvailable, !sensorStatus.registrationFailed, usable(stepSource),
              orientation.meanHeadingAt(clock()) != nil else { return false }
        calibStartNs = clock(); calibFinishNs = .max; calibSteps = 0
        countedCalibSteps.removeAll(); calibStepHeadings.removeAll()
        for w in [PdrWarning.calibrationFailedNoSteps, .calibrationFailedImplausibleLength, .calibrationFailedNoHeading, .sessionInterrupted] { removeWarning(w) }
        phase = .calibrating
        publish()
        return true
    }

    @discardableResult func finishCalibration() -> Bool {
        guard phase == .calibrating else { return false }
        calibFinishNs = clock()
        phase = .settling
        publish()
        return true
    }

    @discardableResult func finalizeCalibration(_ nowNs: Int64) -> Bool {
        guard phase == .settling else { return false }
        let n = countedCalibSteps.count
        if n == 0 { return failCalibration(.calibrationFailedNoSteps) }
        let length = calibrationDistanceM / Double(n)
        if !(Self.minStepLengthM...Self.maxStepLengthM).contains(length) { return failCalibration(.calibrationFailedImplausibleLength) }
        guard let endReference = orientation.stableHeadingAt(nowNs) else { return failCalibration(.calibrationFailedNoHeading) }
        let trueNorthRef = useTrueNorthReference ? trueNorthOffsetRad.map { wrapAnglePi(-$0) } : nil
        let walkMean = calibStepHeadings.count >= 3 ? circularMean(calibStepHeadings) : nil
        if let t = trueNorthRef { referenceYaw = t }
        else if let w = walkMean, abs(angleDelta(w, endReference)) <= degToRad(25) { referenceYaw = w }
        else { referenceYaw = endReference }
        lengthPerStepM = length
        stepLengthManual = false
        startGaitModel(calibrationCadenceHz())
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, nowNs)
        phase = .tracking
        publish()
        return true
    }

    private func calibrationCadenceHz() -> Double? {
        let sorted = countedCalibSteps.sorted()
        let intervals = zip(sorted, sorted.dropFirst()).map { $1 - $0 }
            .filter { (Self.minGaitIntervalNs...Self.maxGaitIntervalNs).contains($0) }.sorted()
        if intervals.count < 3 { return nil }
        let mid = intervals.count / 2
        let median = intervals.count % 2 == 0 ? Double(intervals[mid - 1] + intervals[mid]) / 2 : Double(intervals[mid])
        return 1e9 / median
    }

    private func startGaitModel(_ cadenceHz: Double?) {
        baseCadenceHz = cadenceHz.flatMap { $0.isFinite && $0 >= 0.6 && $0 <= 3.6 ? $0 : nil }
        currentCadenceHz = nil
        currentStepLengthM = lengthPerStepM
        recentIntervalsNs.removeAll()
    }

    private func resolveStartReferenceYaw(_ nowNs: Int64) -> Double? {
        if orientation.meanHeadingAt(nowNs) == nil && orientation.stableHeadingAt(nowNs) == nil { return nil }
        if useTrueNorthReference, let t = trueNorthOffsetRad { return wrapAnglePi(-t) }
        return orientation.stableHeadingAt(nowNs)
    }

    @discardableResult func startWithManualStepLength(_ lengthM: Double, cadenceHz: Double? = nil) -> Bool {
        guard phase == .idle, lengthM.isFinite, (Self.minStepLengthM...Self.maxStepLengthM).contains(lengthM),
              sensorStatus.rotationAvailable, !sensorStatus.registrationFailed, usable(stepSource),
              let reference = resolveStartReferenceYaw(clock()) else { return false }
        referenceYaw = reference
        lengthPerStepM = lengthM
        stepLengthManual = true
        startGaitModel(cadenceHz)
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, clock())
        removeWarning(.sessionInterrupted)
        phase = .tracking
        publish()
        return true
    }

    @discardableResult func resetTrace() -> Bool {
        guard phase == .tracking || phase == .paused, sensorStatus.rotationAvailable, !sensorStatus.registrationFailed,
              let reference = resolveStartReferenceYaw(clock()) else { return false }
        referenceYaw = reference
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, clock())
        publish()
        return true
    }

    /// Buffers step displacements while the barometer suspects a stair climb.
    func beginVerticalHold() {
        if verticalHoldActive || verticalHoldSuppressed || phase != .tracking { return }
        verticalHoldActive = true
        heldStepDeltas.removeAll()
        heldDistanceM = 0
    }

    func endVerticalHold(commit: Bool) {
        if commit { commitHeldSteps() } else { clearHeldSteps() }
        verticalHoldActive = false
        verticalHoldSuppressed = false
        publish()
    }

    private func commitHeldSteps() {
        for d in heldStepDeltas {
            let previous = position, previousDistance = distanceM
            position = position + d
            distanceM += d.norm()
            points.append(position)
            milestoneTracker.onSegment(previous, previousDistance, position, distanceM)
        }
        clearHeldSteps()
    }

    private func clearHeldSteps() { heldStepDeltas.removeAll(); heldDistanceM = 0 }

    /// Re-anchors local (0,0) on a new floor landing, keeping heading, distance, steps and gait.
    @discardableResult func reanchorFloorOriginKeepHeading() -> Bool {
        clearHeldSteps()
        verticalHoldActive = false; verticalHoldSuppressed = false
        position = .zero
        points = [.zero]
        recentStepYaws.removeAll(); straightRunThetas.removeAll()
        externalOrigin = nil; externalPull = .zero; landmarkPull = .zero
        publish()
        return true
    }

    func onSensorFrameRestart() {
        orientation.clear()
        referenceYaw = nil; trueNorthOffsetRad = nil; pendingTrueNorthDriftRad = 0
        switch phase {
        case .calibrating, .settling: calibFinishNs = .max; phase = .idle; addWarning(.sessionInterrupted)
        case .tracking: phase = .paused; addWarning(.sessionInterrupted)
        default: break
        }
        publish()
    }

    func pause() { onSensorFrameRestart() }

    @discardableResult func resumeWithNewTrace() -> Bool {
        guard phase == .paused, sensorStatus.rotationAvailable, !sensorStatus.registrationFailed,
              let reference = resolveStartReferenceYaw(clock()) else { return false }
        referenceYaw = reference
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, clock())
        removeWarning(.sessionInterrupted)
        phase = .tracking
        publish()
        return true
    }

    func recalibrate() {
        phase = .idle
        lengthPerStepM = nil; stepLengthManual = false; referenceYaw = nil
        calibStartNs = 0; calibFinishNs = .max; calibSteps = 0
        countedCalibSteps.removeAll(); calibStepHeadings.removeAll()
        stepSource = .none
        startGaitModel(nil)
        resetTraceInternal()
        sessionCutoffNs = max(maxStepTimestampSeenNs, clock())
        for w in [PdrWarning.calibrationFailedNoSteps, .calibrationFailedImplausibleLength, .calibrationFailedNoHeading, .sessionInterrupted] { removeWarning(w) }
        onSensorStatus(sensorStatus)
    }

    private func failCalibration(_ w: PdrWarning) -> Bool { phase = .idle; addWarning(w); publish(); return false }

    private func resetTraceInternal() {
        clearHeldSteps()
        verticalHoldActive = false; verticalHoldSuppressed = false
        position = .zero; distanceM = 0; stepCount = 0
        points = [.zero]
        milestoneTracker.reset()
        lastTrackedStepNs = .min; pendingGaitStepNs = .min; lastAcceptedGaitStepNs = .min; lastAcceptedWallNs = .min
        recentStepYaws.removeAll(); straightRunThetas.removeAll()
        learnedAxisBase = nil
        externalOrigin = nil; externalPull = .zero; landmarkPull = .zero
    }

    private func usable(_ source: StepSource) -> Bool {
        switch source {
        case .hardware: return sensorStatus.stepDetectorAvailable && (!sensorStatus.permissionNeeded || sensorStatus.permissionGranted)
        case .fallback: return sensorStatus.fallbackCapable
        case .none: return false
        }
    }

    private func publish() {
        let now = clock()
        let mean = orientation.meanHeadingAt(now)
        if let mean {
            if let reference = referenceYaw { displayedHeading = wrapAnglePi(mean - reference) }
            else if useTrueNorthReference, let tn = trueNorthOffsetRad { displayedHeading = wrapAnglePi(mean + tn) }
        }
        state = PdrState(
            phase: phase, position: position, origin: points.first ?? .zero, headingRadians: displayedHeading,
            headingValid: mean != nil, tilted: orientation.lastTilted, lengthPerStepM: lengthPerStepM,
            currentStepLengthM: currentStepLengthM ?? lengthPerStepM, cadenceStepsPerMin: currentCadenceHz.map { $0 * 60 },
            baseCadenceHz: baseCadenceHz, stepLengthManual: stepLengthManual, distanceM: distanceM, stepCount: stepCount,
            calibrationStepCount: calibSteps, droppedStepCount: droppedSteps, rawPath: points, milestones: milestoneTracker.milestones,
            stepSource: stepSource, rotationSensorAvailable: sensorStatus.rotationAvailable,
            stepDetectorAvailable: sensorStatus.stepDetectorAvailable, fallbackCapable: sensorStatus.fallbackCapable,
            activityPermissionNeeded: sensorStatus.permissionNeeded, activityPermissionGranted: sensorStatus.permissionGranted,
            registrationFailed: sensorStatus.registrationFailed, corridorAssistEnabled: corridorAssistEnabled,
            trueNorthAligned: useTrueNorthReference && trueNorthOffsetRad != nil, externalAccuracyM: externalAccuracyM,
            warnings: Set(warnings))
        onStateChange?(state)
    }
}
