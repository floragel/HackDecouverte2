import XCTest
@testable import Lync

// Port of the Android engine tests (com.hackdecouverte.pdr): PdrTrackerTest, OrientationProcessorTest,
// FloorTransitionTrackerTest, AltitudeFloorEstimatorTest, TrueNorthPdrTest, FallbackStepDetectorTest, PathGeometryTest.

private let MS: Int64 = 1_000_000

/// Android rotation vector (x, y, z, w) for a yaw (clockwise) and a tilt about the device X axis.
func rotationVectorFor(_ yawRad: Double, _ tiltAboutXRad: Double = 0) -> [Double] {
    let a = -yawRad
    let sa = sin(a / 2), ca = cos(a / 2), sb = sin(tiltAboutXRad / 2), cb = cos(tiltAboutXRad / 2)
    // Kotlin builds a FloatArray: keep the same float rounding
    return [Double(Float(ca * sb)), Double(Float(sa * sb)), Double(Float(sa * cb)), Double(Float(ca * cb))]
}

private final class FakeClock {
    var nowNs: Int64
    init(_ n: Int64 = 10_000_000_000) { nowNs = n }
    func advance(_ ns: Int64) { nowNs += ns }
}

private let fullStatus = SensorStatus(rotationAvailable: true, stepDetectorAvailable: true, fallbackCapable: true, permissionNeeded: false, permissionGranted: true)

final class PdrTrackerTests: XCTestCase {
    private func newTracker(_ clock: FakeClock, antiShake: Bool = false, assist: Bool = false) -> PdrTracker {
        let t = PdrTracker(clock: { clock.nowNs }, antiShakeEnabled: antiShake, corridorAssistInitial: assist)
        t.onSensorStatus(fullStatus)
        return t
    }

    private func feedHeading(_ tracker: PdrTracker, _ from: Int64, _ to: Int64, _ step: Int64 = 20 * MS, yaw: Double, tilt: Double = 0) {
        var t = from
        while t <= to { tracker.onRotationVector(t, rotationVectorFor(yaw, tilt)); t += step }
    }

    private func seedHeading(_ tracker: PdrTracker, _ clock: FakeClock, yaw: Double = 0) { feedHeading(tracker, clock.nowNs - 140 * MS, clock.nowNs, yaw: yaw) }

    @discardableResult
    private func startTracking(_ tracker: PdrTracker, _ clock: FakeClock, _ length: Double) -> PdrState {
        feedHeading(tracker, clock.nowNs - 140 * MS, clock.nowNs, yaw: 0)
        XCTAssertTrue(tracker.startWithManualStepLength(length))
        return tracker.state
    }

    func testNorthStepsProduceExpectedPosition() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 0.5)
        let t0 = clock.nowNs
        feedHeading(tr, t0, t0 + 2_000 * MS, yaw: 0)
        for i in 0..<10 { tr.onStep(t0 + Int64(i + 1) * 100 * MS, source: .hardware) }
        let s = tr.state
        XCTAssertEqual(s.position.x, 0, accuracy: 1e-9)
        XCTAssertEqual(s.position.y, 5, accuracy: 1e-6)
        XCTAssertEqual(s.distanceM, 5, accuracy: 1e-6)
        XCTAssertEqual(s.stepCount, 10)
        XCTAssertEqual(s.rawPath.count, 11)
        XCTAssertEqual(s.milestones.count, 1)
        XCTAssertEqual(s.milestones[0].position.y, 5, accuracy: 1e-6)
        XCTAssertEqual(s.milestones[0].distanceM, 5, accuracy: 1e-9)
    }

    func testRightAngleTurnProducesExpectedSegment() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 1)
        let t0 = clock.nowNs
        feedHeading(tr, t0, t0 + 450 * MS, yaw: 0)
        feedHeading(tr, t0 + 500 * MS, t0 + 900 * MS, yaw: .pi / 2)
        for i in 0..<4 { tr.onStep(t0 + Int64(i + 1) * 100 * MS, source: .hardware) }
        for i in 0..<4 { tr.onStep(t0 + Int64(500 + (i + 1) * 100) * MS, source: .hardware) }
        let s = tr.state
        XCTAssertEqual(s.position.x, 4, accuracy: 1e-6)
        XCTAssertEqual(s.position.y, 4, accuracy: 1e-6)
        XCTAssertEqual(s.distanceM, 8, accuracy: 1e-6)
        let p = s.rawPath
        let ab = p[4] - p[0], bc = p[8] - p[4]
        XCTAssertEqual(ab.x * bc.x + ab.y * bc.y, 0, accuracy: 1e-6)
    }

    func testDelayedStepBeforeTurnUsesItsOwnTimestampHeading() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 1)
        let t0 = clock.nowNs, tStep = t0 + 200 * MS
        feedHeading(tr, t0, tStep, yaw: 0)
        feedHeading(tr, tStep + 300 * MS, tStep + 450 * MS, yaw: .pi / 2)
        tr.onStep(tStep, source: .hardware)
        XCTAssertEqual(tr.state.position.x, 0, accuracy: 1e-9)
        XCTAssertEqual(tr.state.position.y, 1, accuracy: 1e-6)
        tr.onStep(tStep + 400 * MS, source: .hardware)
        XCTAssertEqual(tr.state.position.x, 1, accuracy: 1e-6)
        XCTAssertEqual(tr.state.position.y, 1, accuracy: 1e-6)
    }

    func testStaleOrMissingOrientationDropsStep() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 1)
        let t0 = clock.nowNs
        feedHeading(tr, t0, t0 + 100 * MS, yaw: 0)
        tr.onStep(t0 + 1_000 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 0)
        XCTAssertEqual(tr.state.droppedStepCount, 1)
        tr.onStep(t0 + 3_000 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 0)
        XCTAssertEqual(tr.state.droppedStepCount, 2)
    }

    func testRotationWithoutStepsDoesNotMovePosition() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 1)
        var yaw = 0.0, t = clock.nowNs
        let end = t + 1_000 * MS
        while t <= end { tr.onRotationVector(t, rotationVectorFor(yaw)); yaw += 0.1; t += 30 * MS }
        XCTAssertEqual(tr.state.position, .zero)
        XCTAssertEqual(tr.state.distanceM, 0)
    }

    func testDuplicateAndOutOfOrderStepsAreIgnored() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 1)
        let t0 = clock.nowNs
        feedHeading(tr, t0, t0 + 500 * MS, yaw: 0)
        tr.onStep(t0 + 100 * MS, source: .hardware)
        tr.onStep(t0 + 100 * MS, source: .hardware)
        tr.onStep(t0 + 50 * MS, source: .hardware)
        tr.onStep(t0 + 200 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 2)
        XCTAssertEqual(tr.state.distanceM, 2, accuracy: 1e-9)
        XCTAssertEqual(tr.state.droppedStepCount, 2)
    }

    func testStepSourceIsLockedToSelection() {
        let clock = FakeClock()
        let tr = PdrTracker(clock: { clock.nowNs })
        tr.onSensorStatus(SensorStatus(rotationAvailable: true, stepDetectorAvailable: true, fallbackCapable: true, permissionNeeded: true, permissionGranted: false))
        XCTAssertEqual(tr.state.stepSource, .none)
        XCTAssertTrue(tr.setStepSource(.fallback))
        startTracking(tr, clock, 1)
        let t0 = clock.nowNs
        feedHeading(tr, t0, t0 + 500 * MS, yaw: 0)
        tr.onStep(t0 + 100 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 0)
        tr.onStep(t0 + 200 * MS, source: .fallback)
        XCTAssertEqual(tr.state.stepCount, 1)
        XCTAssertFalse(tr.setStepSource(.hardware))
    }

    func testMissingRotationSensorBlocksStart() {
        let clock = FakeClock()
        let tr = PdrTracker(clock: { clock.nowNs })
        tr.onSensorStatus(SensorStatus(rotationAvailable: false, stepDetectorAvailable: true, fallbackCapable: true, permissionNeeded: false, permissionGranted: true))
        XCTAssertFalse(tr.startCalibration())
        XCTAssertFalse(tr.startWithManualStepLength(0.7))
        XCTAssertTrue(tr.state.warnings.contains(.rotationSensorMissing))
    }

    func testCalibrationCountsOnlyStepsInsideInterval() {
        let clock = FakeClock(); let tr = newTracker(clock)
        let tStart = clock.nowNs
        seedHeading(tr, clock)
        XCTAssertTrue(tr.startCalibration())
        tr.onStep(tStart - 50 * MS, source: .hardware)
        tr.onStep(tStart + 100 * MS, source: .hardware)
        tr.onStep(tStart + 200 * MS, source: .hardware)
        tr.onStep(tStart + 200 * MS, source: .hardware)
        XCTAssertEqual(tr.state.calibrationStepCount, 2)
        clock.nowNs = tStart + 1_000 * MS
        XCTAssertTrue(tr.finishCalibration())
        let tFinish = tStart + 1_000 * MS
        tr.onStep(tStart + 300 * MS, source: .hardware)
        tr.onStep(tStart + 400 * MS, source: .hardware)
        tr.onStep(tFinish + 10 * MS, source: .hardware)
        XCTAssertEqual(tr.state.calibrationStepCount, 4)
        clock.nowNs = tFinish + 2_000 * MS
        seedHeading(tr, clock)
        XCTAssertTrue(tr.finalizeCalibration(clock.nowNs))
        let s = tr.state
        XCTAssertEqual(s.phase, .tracking)
        XCTAssertEqual(s.lengthPerStepM!, 5.0 / 4.0, accuracy: 1e-9)
        XCTAssertFalse(s.stepLengthManual)
        XCTAssertEqual(s.stepCount, 0)
    }

    func testCalibrationRejectsZeroAndImplausibleSteps() {
        let clock = FakeClock(); let tr = newTracker(clock)
        seedHeading(tr, clock)
        XCTAssertTrue(tr.startCalibration())
        clock.advance(1_000 * MS)
        XCTAssertTrue(tr.finishCalibration())
        clock.advance(2_000 * MS)
        seedHeading(tr, clock)
        XCTAssertFalse(tr.finalizeCalibration(clock.nowNs))
        XCTAssertEqual(tr.state.phase, .idle)
        XCTAssertTrue(tr.state.warnings.contains(.calibrationFailedNoSteps))

        let tr2 = newTracker(clock)
        let t2 = clock.nowNs
        seedHeading(tr2, clock)
        XCTAssertTrue(tr2.startCalibration())
        for i in 0..<40 { tr2.onStep(t2 + Int64(i + 1) * 20 * MS, source: .hardware) }
        clock.nowNs = t2 + 1_000 * MS
        XCTAssertTrue(tr2.finishCalibration())
        clock.advance(2_000 * MS)
        seedHeading(tr2, clock)
        XCTAssertFalse(tr2.finalizeCalibration(clock.nowNs))
        XCTAssertTrue(tr2.state.warnings.contains(.calibrationFailedImplausibleLength))
    }

    func testCalibrationFailsWithoutValidReferenceHeading() {
        let clock = FakeClock(); let tr = newTracker(clock)
        let tStart = clock.nowNs
        seedHeading(tr, clock)
        XCTAssertTrue(tr.startCalibration())
        for i in 0..<6 { tr.onStep(tStart + Int64(i + 1) * 100 * MS, source: .hardware) }
        clock.nowNs = tStart + 1_000 * MS
        XCTAssertTrue(tr.finishCalibration())
        clock.advance(2_000 * MS)
        XCTAssertFalse(tr.finalizeCalibration(clock.nowNs))
        XCTAssertTrue(tr.state.warnings.contains(.calibrationFailedNoHeading))
    }

    func testFinishSetsNewOriginAndReferenceAndDropsPendingSteps() {
        let clock = FakeClock(); let tr = newTracker(clock)
        let tStart = clock.nowNs
        seedHeading(tr, clock)
        XCTAssertTrue(tr.startCalibration())
        for i in 0..<5 { tr.onStep(tStart + Int64(i + 1) * 100 * MS, source: .hardware) }
        clock.nowNs = tStart + 1_000 * MS
        XCTAssertTrue(tr.finishCalibration())
        clock.advance(2_000 * MS)
        let tNow = clock.nowNs
        feedHeading(tr, tNow - 140 * MS, tNow + 2_000 * MS, yaw: 0.3)
        XCTAssertTrue(tr.finalizeCalibration(clock.nowNs))
        tr.onStep(tStart + 600 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 0)
        tr.onStep(tNow + 100 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 1)
        XCTAssertEqual(tr.state.position.x, 0, accuracy: 1e-6)
        XCTAssertEqual(tr.state.position.y, 1, accuracy: 1e-6)
    }

    func testResetTraceKeepsLengthAndRejectsQueuedSteps() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 0.8)
        let t0 = clock.nowNs
        feedHeading(tr, t0, t0 + 500 * MS, yaw: 0)
        for i in 0..<3 { tr.onStep(t0 + Int64(i + 1) * 100 * MS, source: .hardware) }
        XCTAssertEqual(tr.state.distanceM, 2.4, accuracy: 1e-6)
        clock.advance(200 * MS)
        XCTAssertTrue(tr.resetTrace())
        XCTAssertEqual(tr.state.stepCount, 0)
        XCTAssertEqual(tr.state.rawPath.count, 1)
        XCTAssertEqual(tr.state.lengthPerStepM!, 0.8, accuracy: 1e-9)
        tr.onStep(t0 + 150 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 0)
        clock.advance(500 * MS)
        let tNew = clock.nowNs
        feedHeading(tr, tNew - 140 * MS, tNew + 200 * MS, yaw: 0)
        tr.onStep(tNew + 100 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 1)
        XCTAssertEqual(tr.state.distanceM, 0.8, accuracy: 1e-9)
    }

    func testPauseResumeAndFreshHeading() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 0.8)
        let t0 = clock.nowNs
        feedHeading(tr, t0, t0 + 200 * MS, yaw: 0.4)
        tr.onStep(t0 + 100 * MS, source: .hardware)
        tr.pause()
        XCTAssertEqual(tr.state.phase, .paused)
        XCTAssertTrue(tr.state.warnings.contains(.sessionInterrupted))
        XCTAssertFalse(tr.resumeWithNewTrace()) // frame cleared: needs a fresh heading
        seedHeading(tr, clock, yaw: 1.2)
        XCTAssertTrue(tr.resumeWithNewTrace())
        clock.advance(500 * MS)
        let tNew = clock.nowNs
        feedHeading(tr, tNew - 140 * MS, tNew + 200 * MS, yaw: 1.2)
        tr.onStep(tNew + 100 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 1)
        XCTAssertEqual(tr.state.position.x, 0, accuracy: 1e-6)
        XCTAssertEqual(tr.state.position.y, 0.8, accuracy: 1e-6)
    }

    func testRecalibrateAndManualLengthValidation() {
        let clock = FakeClock(); let tr = newTracker(clock)
        seedHeading(tr, clock)
        XCTAssertFalse(tr.startWithManualStepLength(0.1))
        XCTAssertFalse(tr.startWithManualStepLength(2.0))
        XCTAssertFalse(tr.startWithManualStepLength(.nan))
        XCTAssertTrue(tr.startWithManualStepLength(0.75))
        XCTAssertTrue(tr.state.stepLengthManual)
        tr.recalibrate()
        XCTAssertEqual(tr.state.phase, .idle)
        XCTAssertNil(tr.state.lengthPerStepM)
        XCTAssertEqual(tr.state.stepSource, .hardware)
    }

    func testInvalidHeadingFreezesDisplayedConeAndDropsStep() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 1)
        let t0 = clock.nowNs
        feedHeading(tr, t0, t0 + 200 * MS, yaw: 0.5)
        tr.onStep(t0 + 100 * MS, source: .hardware)
        clock.nowNs = t0 + 300 * MS
        tr.onRotationVector(t0 + 250 * MS, rotationVectorFor(0.5))
        let frozen = tr.state.headingRadians
        XCTAssertEqual(frozen, 0.5, accuracy: 1e-6)
        clock.nowNs = t0 + 400 * MS
        feedHeading(tr, t0 + 320 * MS, t0 + 400 * MS, yaw: 0.5, tilt: degToRad(85))
        tr.onStep(t0 + 350 * MS, source: .hardware)
        XCTAssertFalse(tr.state.headingValid)
        XCTAssertEqual(tr.state.headingRadians, frozen, accuracy: 1e-9)
        XCTAssertEqual(tr.state.stepCount, 1)
        XCTAssertEqual(tr.state.droppedStepCount, 1)
    }

    func testRegistrationFailureBlocksStartAndSteps() {
        let clock = FakeClock(); let tr = newTracker(clock)
        var failed = fullStatus; failed.registrationFailed = true
        tr.onSensorStatus(failed)
        seedHeading(tr, clock)
        XCTAssertFalse(tr.state.canStart)
        tr.onSensorStatus(fullStatus)
        XCTAssertTrue(tr.state.canStart)
        startTracking(tr, clock, 1)
        tr.onSensorStatus(failed)
        tr.onStep(clock.nowNs + 100 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 0)
        XCTAssertTrue(tr.state.warnings.contains(.sensorRegistrationFailed))
    }

    func testAntiShakeBlocksIsolatedBumpAndCommitsTwoStepWalk() {
        let clock = FakeClock(); let tr = newTracker(clock, antiShake: true)
        startTracking(tr, clock, 0.75)
        let t0 = clock.nowNs
        feedHeading(tr, t0, t0 + 4_000 * MS, yaw: 0)
        tr.onStep(t0 + 200 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 0)
        tr.onStep(t0 + 2_200 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 0)
        tr.onStep(t0 + 2_750 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 2)
        XCTAssertEqual(tr.state.distanceM, 1.5, accuracy: 1e-6)
        tr.onStep(t0 + 3_300 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 3)
    }

    func testAntiShakeBlocksViolentShakeAcceleration() {
        let clock = FakeClock(); let tr = newTracker(clock, antiShake: true)
        startTracking(tr, clock, 0.75)
        let t0 = clock.nowNs
        feedHeading(tr, t0, t0 + 1_500 * MS, yaw: 0)
        tr.onLinearAccelerationMagnitude(t0 + 300 * MS, 16)
        tr.onStep(t0 + 400 * MS, source: .hardware)
        tr.onStep(t0 + 650 * MS, source: .hardware)
        XCTAssertEqual(tr.state.stepCount, 0)
    }

    private func uTurnEnd(assist: Bool) -> PointM {
        let clock = FakeClock(); let tr = newTracker(clock, assist: assist)
        startTracking(tr, clock, 1)
        var t = clock.nowNs
        for _ in 0..<10 { feedHeading(tr, t + 20 * MS, t + 500 * MS, yaw: 0); t += 500 * MS; tr.onStep(t, source: .hardware) }
        t += 300 * MS
        for _ in 0..<10 { feedHeading(tr, t + 20 * MS, t + 500 * MS, yaw: degToRad(171)); t += 500 * MS; tr.onStep(t, source: .hardware) }
        return tr.state.position
    }

    func testUTurnWithAxisDriftIsCorrectedGently() {
        let raw = uTurnEnd(assist: false), assisted = uTurnEnd(assist: true)
        XCTAssertGreaterThan(abs(raw.x), 1.4)
        XCTAssertLessThan(abs(assisted.x), 0.8)
        XCTAssertLessThan(abs(assisted.y), 0.4)
    }

    func testCorridorAssistOnlyCorrectsLaterallyAndNeverJumps() {
        let clock = FakeClock(); let tr = newTracker(clock, assist: true)
        tr.mapAxisBaseLocal = 0
        tr.corridorConstraint = { p, theta in abs(angleDelta(0, theta)) < degToRad(20) ? CorridorConstraintResult(projectedLocalPos: PointM(0, p.y), alignedLocalTheta: 0) : nil }
        startTracking(tr, clock, 1)
        var t = clock.nowNs
        for _ in 0..<20 { feedHeading(tr, t + 20 * MS, t + 500 * MS, yaw: degToRad(6)); t += 500 * MS; tr.onStep(t, source: .hardware) }
        let path = tr.state.rawPath
        for i in 1..<path.count {
            XCTAssertLessThan(path[i].distanceTo(path[i - 1]), 1.2)
            XCTAssertGreaterThan(path[i].y, path[i - 1].y + 0.8)
        }
        XCTAssertLessThan(abs(tr.state.position.x), 0.6)
        XCTAssertGreaterThan(tr.state.position.y, 19.5)
    }

    func testExternalFixIsBoundedAndIgnoredWhenInaccurate() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 1)
        tr.onExternalFix(.zero, accuracyM: 5)
        var t = clock.nowNs
        func walk(_ n: Int) { for _ in 0..<n { feedHeading(tr, t + 20 * MS, t + 500 * MS, yaw: 0); t += 500 * MS; clock.nowNs = t; tr.onStep(t, source: .hardware) } }
        walk(2)
        tr.onExternalFix(PointM(20, 2), accuracyM: 20)
        walk(2)
        XCTAssertEqual(tr.state.position.x, 0, accuracy: 1e-6)
        tr.onExternalFix(PointM(10, 4), accuracyM: 5)
        let before = tr.state.position
        walk(1)
        let after = tr.state.position
        XCTAssertGreaterThan(after.x, 0)
        XCTAssertLessThanOrEqual(after.x, 0.08 + 1e-9)
        XCTAssertLessThanOrEqual(after.distanceTo(before), 1.08 + 1e-9)
        walk(40)
        XCTAssertLessThanOrEqual(tr.state.position.x, 1.5 + 1e-6)
    }

    func testExternalFixNeverMovesStationaryPosition() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 1)
        tr.onExternalFix(.zero, accuracyM: 4)
        tr.onExternalFix(PointM(10, 10), accuracyM: 4)
        XCTAssertEqual(tr.state.position, .zero)
        XCTAssertEqual(tr.state.externalAccuracyM!, 4, accuracy: 1e-9)
    }

    func testDynamicStepLengthFollowsCadence() {
        let clock = FakeClock(); let tr = newTracker(clock)
        seedHeading(tr, clock)
        let tStart = clock.nowNs
        XCTAssertTrue(tr.startCalibration())
        for i in 0..<6 { tr.onStep(tStart + Int64(i + 1) * 500 * MS, source: .hardware) }
        clock.nowNs = tStart + 3_500 * MS
        XCTAssertTrue(tr.finishCalibration())
        clock.advance(2_000 * MS)
        feedHeading(tr, clock.nowNs - 600 * MS, clock.nowNs, yaw: 0)
        XCTAssertTrue(tr.finalizeCalibration(clock.nowNs))
        let base = 5.0 / 6.0
        XCTAssertEqual(tr.state.lengthPerStepM!, base, accuracy: 1e-9)
        XCTAssertEqual(tr.state.baseCadenceHz!, 2, accuracy: 1e-9)
        var t = clock.nowNs
        for _ in 0..<8 { feedHeading(tr, t + 20 * MS, t + 400 * MS, yaw: 0); t += 400 * MS; tr.onStep(t, source: .hardware) }
        XCTAssertEqual(tr.state.cadenceStepsPerMin!, 150, accuracy: 1e-6)
        XCTAssertEqual(tr.state.currentStepLengthM!, base * 1.225, accuracy: 1e-6)
        XCTAssertEqual(tr.state.distanceM, base * (4 + 4 * 1.225), accuracy: 1e-6)
    }

    func testDynamicStepLengthShrinksWhenSlowAndIsClamped() {
        let clock = FakeClock(); let tr = newTracker(clock)
        startTracking(tr, clock, 0.8)
        var t = clock.nowNs
        for _ in 0..<5 { feedHeading(tr, t + 20 * MS, t + 500 * MS, yaw: 0); t += 500 * MS; tr.onStep(t, source: .hardware) }
        XCTAssertEqual(tr.state.baseCadenceHz!, 2, accuracy: 1e-9)
        for _ in 0..<5 { feedHeading(tr, t + 20 * MS, t + 1_200 * MS, yaw: 0); t += 1_200 * MS; tr.onStep(t, source: .hardware) }
        XCTAssertEqual(tr.state.currentStepLengthM!, 0.8 * 0.75, accuracy: 1e-9)
    }

    func testTrueNorthAlignsWithoutCalibrationAndIgnoresMagnetSpikes() {
        var now: Int64 = 20_000_000_000
        let tr = PdrTracker(clock: { now })
        tr.useTrueNorthReference = true
        tr.onSensorStatus(fullStatus)
        let offset = degToRad(-68), east = Double.pi / 2
        var t = now - 300 * MS
        while t <= now { tr.onRotationVector(t, rotationVectorFor(wrapAnglePi(east - offset))); tr.onTrueNorthAzimuth(t, east); t += 20 * MS }
        XCTAssertTrue(tr.state.trueNorthAligned)
        XCTAssertEqual(tr.state.headingRadians, east, accuracy: degToRad(1))
        XCTAssertTrue(tr.startWithManualStepLength(0.75))
        for i in 0..<8 {
            let stepTs = now + Int64(i + 1) * 500 * MS
            var s = stepTs - 480 * MS
            while s <= stepTs {
                tr.onRotationVector(s, rotationVectorFor(wrapAnglePi(east - offset)))
                tr.onTrueNorthAzimuth(s, i == 4 ? wrapAnglePi(east + degToRad(85)) : east)
                s += 20 * MS
            }
            now = stepTs
            tr.onStep(stepTs, source: .hardware)
        }
        XCTAssertEqual(tr.state.position.x, 6, accuracy: 0.08)
        XCTAssertEqual(tr.state.position.y, 0, accuracy: 0.08)
        for i in 0..<4 {
            let stepTs = now + Int64(i + 1) * 500 * MS
            var s = stepTs - 480 * MS
            while s <= stepTs { tr.onRotationVector(s, rotationVectorFor(wrapAnglePi(0 - offset))); s += 20 * MS }
            tr.onTrueNorthBearingFromGps(stepTs, 0)
            now = stepTs
            tr.onStep(stepTs, source: .hardware)
        }
        XCTAssertLessThan(abs(tr.state.headingRadians), degToRad(2))
        XCTAssertGreaterThan(tr.state.position.y, 2.8)
    }
}

final class OrientationProcessorTests: XCTestCase {
    func testCircularMeanAroundPi() {
        let p = OrientationProcessor()
        p.onRotationVector(100_000_000, rotationVectorFor(degToRad(179)))
        p.onRotationVector(120_000_000, rotationVectorFor(degToRad(-179)))
        XCTAssertLessThan(abs(angleDelta(p.meanHeadingAt(120_000_000)!, .pi)), 0.02)
    }

    func testAngleDeltaUsesShortestWrap() {
        let d = degToRad(179)
        XCTAssertEqual(angleDelta(d, -d), degToRad(2), accuracy: 1e-9)
        XCTAssertEqual(angleDelta(-d, d), degToRad(-2), accuracy: 1e-9)
    }

    func testEmptyAndCancellingWindowsAreInvalid() {
        XCTAssertNil(OrientationProcessor().meanHeadingAt(1_000_000))
        let p = OrientationProcessor()
        p.onRotationVector(100_000_000, rotationVectorFor(0))
        p.onRotationVector(110_000_000, rotationVectorFor(.pi))
        XCTAssertNil(p.meanHeadingAt(110_000_000))
    }

    func testFlatAndTiltedProjections() {
        let p = OrientationProcessor()
        p.onRotationVector(100_000_000, rotationVectorFor(0.3))
        XCTAssertEqual(p.meanHeadingAt(100_000_000)!, 0.3, accuracy: 1e-6)
        XCTAssertFalse(p.lastTilted)
        let q = OrientationProcessor()
        q.onRotationVector(100_000_000, rotationVectorFor(0, degToRad(45)))
        XCTAssertEqual(q.meanHeadingAt(100_000_000)!, 0, accuracy: 1e-6)
    }

    func testNearVerticalIsInvalidWithoutJump() {
        let p = OrientationProcessor()
        p.onRotationVector(100_000_000, rotationVectorFor(0.4, degToRad(40)))
        XCTAssertEqual(p.meanHeadingAt(100_000_000)!, 0.4, accuracy: 1e-6)
        p.onRotationVector(300_000_000, rotationVectorFor(0.4, degToRad(85)))
        XCTAssertTrue(p.lastTilted)
        p.onRotationVector(320_000_000, rotationVectorFor(0.4, degToRad(89)))
        XCTAssertNil(p.meanHeadingAt(320_000_000))
        XCTAssertEqual(p.lastValidYaw!, 0.4, accuracy: 1e-6)
        p.onRotationVector(500_000_000, rotationVectorFor(0.4, degToRad(40)))
        XCTAssertEqual(p.meanHeadingAt(500_000_000)!, 0.4, accuracy: 1e-6)
    }

    func testStaleFutureAndEvictedSamples() {
        let p = OrientationProcessor()
        var t: Int64 = 0
        while t <= 100_000_000 { p.onRotationVector(t, rotationVectorFor(0)); t += 20_000_000 }
        XCTAssertNil(p.meanHeadingAt(500_000_000))
        let f = OrientationProcessor()
        t = 900_000_000
        while t <= 1_000_000_000 { f.onRotationVector(t, rotationVectorFor(.pi / 2)); t += 20_000_000 }
        XCTAssertNil(f.meanHeadingAt(500_000_000))
        let e = OrientationProcessor()
        e.onRotationVector(0, rotationVectorFor(0))
        e.onRotationVector(3_000_000_000, rotationVectorFor(0.5))
        XCTAssertEqual(e.meanHeadingAt(3_000_000_000)!, 0.5, accuracy: 1e-6)
    }

    func testMalformedAndDuplicateSamples() {
        let p = OrientationProcessor()
        p.onRotationVector(0, [])
        p.onRotationVector(10_000_000, [.nan, 0, 0, 1])
        XCTAssertNil(p.meanHeadingAt(10_000_000))
        let d = OrientationProcessor()
        d.onRotationVector(100_000_000, rotationVectorFor(0))
        d.onRotationVector(100_000_000, rotationVectorFor(1))
        XCTAssertEqual(d.meanHeadingAt(100_000_000)!, 0, accuracy: 1e-6)
    }

    func testWorldVerticalAndStableHeadingAndTurnRate() {
        let up = worldVerticalFromRotationMatrix(rotationMatrixFromVector(rotationVectorFor(0))!)
        XCTAssertEqual(up.2, 1, accuracy: 1e-6)
        let p = OrientationProcessor()
        var t: Int64 = 100_000_000
        while t <= 450_000_000 { p.onRotationVector(t, rotationVectorFor(0, degToRad(40))); t += 20_000_000 }
        p.onRotationVector(520_000_000, rotationVectorFor(degToRad(14), degToRad(40)))
        XCTAssertEqual(p.stableHeadingAt(520_000_000)!, 0, accuracy: 1e-4)
        let r = OrientationProcessor()
        t = 0
        var yaw = 0.0
        while t <= 250_000_000 { r.onRotationVector(t, rotationVectorFor(yaw)); yaw += 0.05; t += 25_000_000 }
        XCTAssertEqual(r.turnRateRadPerSecAt(250_000_000), 2, accuracy: 0.05)
    }
}

final class BarometerTests: XCTestCase {
    func testClimbingOneFloorNearStairs() {
        let tr = FloorTransitionTracker(floorHeightM: 4)
        var t: Int64 = 1_000_000_000
        for _ in 0...4 { _ = tr.onPressure(t, 1013.25, nearVerticalConnector: true); t += 200_000_000 }
        var total = 0, sawTransit = false
        for k in 1...50 {
            let u = tr.onPressure(t, 1013.25 - 0.52 * Double(k) / 50, nearVerticalConnector: true)!
            if u.inVerticalTransit { sawTransit = true }
            total += u.floorStepDelta
            t += 200_000_000
        }
        XCTAssertTrue(sawTransit)
        XCTAssertEqual(total, 1)
        for _ in 1...15 { _ = tr.onPressure(t, 1012.73, nearVerticalConnector: true); t += 200_000_000 }
        XCTAssertFalse(tr.inVerticalTransit)
    }

    func testElevatorRideUpThreeFloors() {
        let tr = FloorTransitionTracker(floorHeightM: 4)
        var t: Int64 = 1_000_000_000
        _ = tr.onPressure(t, 1013, nearVerticalConnector: true)
        t += 200_000_000
        var cumulative = 0
        for k in 1...42 { cumulative += tr.onPressure(t, 1013 - 1.5 * Double(k) / 42, nearVerticalConnector: true)!.floorStepDelta; t += 200_000_000 }
        XCTAssertEqual(cumulative, 3)
    }

    func testSlowWeatherDriftDoesNotTriggerFloorChange() {
        for window in [FloorTransitionTracker.defaultVelocityWindowNs, 3_500_000_000] { // Android and iPhone settings
            let tr = FloorTransitionTracker(floorHeightM: 4, velocityWindowNs: window)
            var t: Int64 = 1_000_000_000
            for s in 0...600 {
                let u = tr.onPressure(t, 1015 - 1.2 * Double(s) / 600, nearVerticalConnector: false)!
                XCTAssertEqual(u.floorStepDelta, 0)
                XCTAssertFalse(u.inVerticalTransit)
                t += 1_000_000_000
            }
        }
    }

    func testDoorSlamSpikeIsRejected() {
        let tr = FloorTransitionTracker(floorHeightM: 4)
        var t: Int64 = 1_000_000_000
        for _ in 0..<5 { _ = tr.onPressure(t, 1012, nearVerticalConnector: true); t += 200_000_000 }
        XCTAssertEqual(tr.onPressure(t, 1011.2, nearVerticalConnector: true)!.floorStepDelta, 0)
        t += 200_000_000
        XCTAssertEqual(tr.onPressure(t, 1012, nearVerticalConnector: true)!.floorStepDelta, 0)
    }

    /// iPhone barometer: 1 sample per second, climbing one floor of stairs near a stairwell.
    func testIphoneOneHertzStairClimbStillEmitsOneFloor() {
        let tr = FloorTransitionTracker(floorHeightM: 4, velocityWindowNs: 3_500_000_000)
        var t: Int64 = 1_000_000_000
        for _ in 0..<5 { _ = tr.onPressure(t, 1013.25, nearVerticalConnector: true); t += 1_000_000_000 }
        var total = 0
        for k in 1...12 { total += tr.onPressure(t, 1013.25 - 0.52 * Double(k) / 12, nearVerticalConnector: true)!.floorStepDelta; t += 1_000_000_000 }
        for _ in 0..<6 { total += tr.onPressure(t, 1012.73, nearVerticalConnector: true)!.floorStepDelta; t += 1_000_000_000 }
        XCTAssertEqual(total, 1)
    }

    func testHorizontalStepsFrozenDuringStairTransitAndReanchor() {
        var now: Int64 = 1_000_000_000
        let tr = PdrTracker(clock: { now })
        tr.useTrueNorthReference = true
        tr.onSensorStatus(SensorStatus(rotationAvailable: true, stepDetectorAvailable: true, permissionNeeded: false, permissionGranted: true))
        tr.setStepSource(.hardware)
        let identity: [Double] = [0, 0, 0, 1]
        tr.onRotationVector(now, identity)
        tr.onTrueNorthAzimuth(now, .pi / 2)
        XCTAssertTrue(tr.startWithManualStepLength(0.75))
        func step() { now += 500_000_000; tr.onRotationVector(now, identity); tr.onStep(now, source: .hardware) }
        step(); step()
        XCTAssertEqual(tr.state.position.x, 1.5, accuracy: 0.05)
        tr.beginVerticalHold()
        for _ in 0..<10 { step() }
        XCTAssertEqual(tr.state.position.x, 1.5, accuracy: 0.05)
        XCTAssertEqual(tr.state.stepCount, 12)
        tr.reanchorFloorOriginKeepHeading()
        XCTAssertEqual(tr.state.position, .zero)
        step()
        XCTAssertEqual(tr.state.position.x, 0.75, accuracy: 0.05)
        tr.beginVerticalHold()
        for _ in 0..<4 { step() }
        XCTAssertEqual(tr.state.position.x, 0.75, accuracy: 0.05)
        tr.endVerticalHold(commit: true)
        XCTAssertEqual(tr.state.position.x, 3.75, accuracy: 0.05)
    }

    private let street = 1013.0
    private func pressureAt(_ h: Double) -> Double { street * pow(1 - h / 44_330, 5.255) }

    func testAltitudeFloorEstimator() {
        let s: Int64 = 1_000_000_000
        let none = AltitudeFloorEstimator().estimate(10 * s, 1000)
        XCTAssertEqual(none.floorNumber, 1)
        XCTAssertEqual(none.source, .entranceAssumed)
        let est = AltitudeFloorEstimator()
        est.captureStreetReference(0, street)
        for floor in 1...7 {
            let e = est.estimate(60 * s, pressureAt(est.floorElevationM(floor)))
            XCTAssertEqual(e.floorNumber, floor)
            XCTAssertTrue(e.confident)
        }
        XCTAssertFalse(est.estimate(s, pressureAt((est.floorElevationM(3) + est.floorElevationM(4)) / 2)).confident)
        XCTAssertEqual(est.estimate(s, pressureAt(80)).floorNumber, 7)
        XCTAssertEqual(est.estimate(s, pressureAt(-6)).floorNumber, 1)
        XCTAssertEqual(est.estimate(2 * 3600 * s, pressureAt(13)).source, .entranceAssumed)
        let bad = AltitudeFloorEstimator()
        bad.captureStreetReference(0, .nan)
        bad.captureStreetReference(0, 50)
        XCTAssertFalse(bad.hasStreetReference)
    }
}

final class StepDetectorAndGeometryTests: XCTestCase {
    /// Walking ~2 Hz vertical bounce of ±2.5 m/s² for 4 s -> about 8 steps, none during the 0.5 s warm-up.
    func testFallbackDetectorCountsSinusoidalSteps() {
        let d = FallbackStepDetector()
        var steps: [Int64] = []
        d.onStep = { steps.append($0) }
        d.start(0)
        var t: Int64 = 0
        while t <= 4_000_000_000 {
            let v = 2.5 * sin(2 * .pi * 2 * Double(t) / 1e9)
            d.onLinearAcceleration(t, ax: 0, ay: 0, az: v, ux: 0, uy: 0, uz: 1)
            t += 10_000_000
        }
        XCTAssertTrue((6...8).contains(steps.count), "\(steps.count) steps")
        XCTAssertTrue(steps.allSatisfy { $0 >= 500_000_000 })
        for (a, b) in zip(steps, steps.dropFirst()) { XCTAssertGreaterThanOrEqual(b - a, 330_000_000) }
    }

    func testSmoothedSegmentsAndPathLength() {
        let pts = [PointM(0, 0), PointM(0, 2), PointM(2, 2)]
        let segs = PathGeometry.smoothedSegments(pts)
        XCTAssertEqual(segs.count, 3)
        XCTAssertEqual(segs.first!.start, pts[0])
        XCTAssertEqual(segs.last!.end, pts[2])
        XCTAssertEqual(PathGeometry.pathLength(pts), 4, accuracy: 1e-12)
        let m = MilestoneTracker.compute([.zero, PointM(0, 12)], [0, 12])
        XCTAssertEqual(m.map(\.distanceM), [5, 10])
    }
}
