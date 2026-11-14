package com.hackdecouverte.pdr

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(var nowNs: Long = 10_000_000_000L) {
    fun advance(ns: Long) {
        nowNs += ns
    }
}

private const val MS = 1_000_000L

class PdrTrackerTest {

    private fun newTracker(clock: FakeClock): PdrTracker {
        val tracker = PdrTracker(clock = { clock.nowNs })
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = false,
                permissionGranted = true
            )
        )
        return tracker
    }

    private fun feedHeading(
        tracker: PdrTracker,
        fromNs: Long,
        toNs: Long,
        stepNs: Long = 20 * MS,
        yaw: Double,
        tilt: Double = 0.0
    ) {
        var t = fromNs
        while (t <= toNs) {
            tracker.onRotationVector(t, rotationVectorFor(yaw, tilt))
            t += stepNs
        }
    }

    private fun seedHeading(tracker: PdrTracker, clock: FakeClock, yaw: Double = 0.0) {
        feedHeading(tracker, clock.nowNs - 140 * MS, clock.nowNs, 20 * MS, yaw)
    }

    private fun startTrackingWithLength(
        tracker: PdrTracker,
        clock: FakeClock,
        lengthM: Double
    ): PdrState {
        feedHeading(tracker, clock.nowNs - 140 * MS, clock.nowNs, 20 * MS, 0.0)
        assertTrue(tracker.startWithManualStepLength(lengthM))
        return tracker.state.value
    }

    @Test
    fun northStepsProduceExpectedPosition() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 0.5)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 2_000 * MS, 20 * MS, 0.0)
        repeat(10) { i ->
            tracker.onStep(t0 + (i + 1) * 100 * MS, StepSource.HARDWARE)
        }
        val s = tracker.state.value
        assertEquals(0.0, s.position.x, 1e-9)
        assertEquals(5.0, s.position.y, 1e-6)
        assertEquals(5.0, s.distanceM, 1e-6)
        assertEquals(10, s.stepCount)
        assertEquals(11, s.rawPath.size)
        assertEquals(1, s.milestones.size)
        assertEquals(0.0, s.milestones[0].position.x, 1e-9)
        assertEquals(5.0, s.milestones[0].position.y, 1e-6)
        assertEquals(5.0, s.milestones[0].distanceM, 1e-9)
    }

    @Test
    fun rightAngleTurnProducesExpectedSegment() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 1.0)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 450 * MS, 20 * MS, 0.0)
        feedHeading(tracker, t0 + 500 * MS, t0 + 900 * MS, 20 * MS, PI / 2)
        repeat(4) { i -> tracker.onStep(t0 + (i + 1) * 100 * MS, StepSource.HARDWARE) }
        repeat(4) { i -> tracker.onStep(t0 + (500 + (i + 1) * 100) * MS, StepSource.HARDWARE) }
        val s = tracker.state.value
        assertEquals(4.0, s.position.x, 1e-6)
        assertEquals(4.0, s.position.y, 1e-6)
        assertEquals(8.0, s.distanceM, 1e-6)
        val p = s.rawPath
        val ab = PointM(p[4].x - p[0].x, p[4].y - p[0].y)
        val bc = PointM(p[8].x - p[4].x, p[8].y - p[4].y)
        assertEquals(0.0, ab.x * bc.x + ab.y * bc.y, 1e-6)
    }

    @Test
    fun delayedStepBeforeTurnUsesItsOwnTimestampHeading() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 1.0)
        val t0 = clock.nowNs
        val tStep = t0 + 200 * MS
        feedHeading(tracker, t0, tStep, 20 * MS, 0.0)
        feedHeading(tracker, tStep + 300 * MS, tStep + 450 * MS, 20 * MS, PI / 2)
        tracker.onStep(tStep, StepSource.HARDWARE)
        val s = tracker.state.value
        assertEquals(0.0, s.position.x, 1e-9)
        assertEquals(1.0, s.position.y, 1e-6)
        tracker.onStep(tStep + 400 * MS, StepSource.HARDWARE)
        val s2 = tracker.state.value
        assertEquals(1.0, s2.position.x, 1e-6)
        assertEquals(1.0, s2.position.y, 1e-6)
    }

    @Test
    fun staleOrMissingOrientationDropsStep() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 1.0)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 100 * MS, 20 * MS, 0.0)
        tracker.onStep(t0 + 1_000 * MS, StepSource.HARDWARE)
        var s = tracker.state.value
        assertEquals(0, s.stepCount)
        assertEquals(1, s.droppedStepCount)
        assertEquals(0.0, s.distanceM, 1e-9)
        tracker.onStep(t0 + 3_000 * MS, StepSource.HARDWARE)
        s = tracker.state.value
        assertEquals(0, s.stepCount)
        assertEquals(2, s.droppedStepCount)
    }

    @Test
    fun rotationWithoutStepsDoesNotMovePosition() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 1.0)
        val t0 = clock.nowNs
        var yaw = 0.0
        var t = t0
        while (t <= t0 + 1_000 * MS) {
            tracker.onRotationVector(t, rotationVectorFor(yaw))
            yaw += 0.1
            t += 30 * MS
        }
        val s = tracker.state.value
        assertEquals(0.0, s.position.x, 1e-9)
        assertEquals(0.0, s.position.y, 1e-9)
        assertEquals(0.0, s.distanceM, 1e-9)
    }

    @Test
    fun duplicateAndOutOfOrderStepsAreIgnored() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 1.0)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 500 * MS, 20 * MS, 0.0)
        tracker.onStep(t0 + 100 * MS, StepSource.HARDWARE)
        tracker.onStep(t0 + 100 * MS, StepSource.HARDWARE)
        tracker.onStep(t0 + 50 * MS, StepSource.HARDWARE)
        tracker.onStep(t0 + 200 * MS, StepSource.HARDWARE)
        val s = tracker.state.value
        assertEquals(2, s.stepCount)
        assertEquals(2.0, s.distanceM, 1e-9)
        assertEquals(2, s.droppedStepCount)
    }

    @Test
    fun stepSourceIsLockedToSelection() {
        val clock = FakeClock()
        val tracker = PdrTracker(clock = { clock.nowNs })
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = true,
                permissionGranted = false
            )
        )
        assertEquals(StepSource.NONE, tracker.state.value.stepSource)
        assertTrue(tracker.setStepSource(StepSource.FALLBACK))
        startTrackingWithLength(tracker, clock, 1.0)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 500 * MS, 20 * MS, 0.0)
        tracker.onStep(t0 + 100 * MS, StepSource.HARDWARE)
        assertEquals(0, tracker.state.value.stepCount)
        tracker.onStep(t0 + 200 * MS, StepSource.FALLBACK)
        assertEquals(1, tracker.state.value.stepCount)
        assertFalse(tracker.setStepSource(StepSource.HARDWARE))
    }

    @Test
    fun sourceCannotChangeWhileCalibrating() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        seedHeading(tracker, clock)
        assertTrue(tracker.startCalibration())
        assertFalse(tracker.setStepSource(StepSource.FALLBACK))
        assertEquals(StepSource.HARDWARE, tracker.state.value.stepSource)
    }

    @Test
    fun missingRotationSensorBlocksStart() {
        val clock = FakeClock()
        val tracker = PdrTracker(clock = { clock.nowNs })
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = false,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = false,
                permissionGranted = true
            )
        )
        assertFalse(tracker.startCalibration())
        assertFalse(tracker.startWithManualStepLength(0.7))
        assertTrue(tracker.state.value.warnings.contains(PdrWarning.ROTATION_SENSOR_MISSING))
    }

    @Test
    fun calibrationCountsOnlyStepsInsideInterval() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        val tStart = clock.nowNs
        seedHeading(tracker, clock)
        assertTrue(tracker.startCalibration())
        tracker.onStep(tStart - 50 * MS, StepSource.HARDWARE)
        tracker.onStep(tStart + 100 * MS, StepSource.HARDWARE)
        tracker.onStep(tStart + 200 * MS, StepSource.HARDWARE)
        tracker.onStep(tStart + 200 * MS, StepSource.HARDWARE)
        assertEquals(2, tracker.state.value.calibrationStepCount)
        clock.nowNs = tStart + 1_000 * MS
        assertTrue(tracker.finishCalibration())
        val tFinish = tStart + 1_000 * MS
        tracker.onStep(tStart + 300 * MS, StepSource.HARDWARE)
        tracker.onStep(tStart + 400 * MS, StepSource.HARDWARE)
        tracker.onStep(tFinish + 10 * MS, StepSource.HARDWARE)
        assertEquals(4, tracker.state.value.calibrationStepCount)
        clock.nowNs = tFinish + 2_000 * MS
        feedHeading(tracker, clock.nowNs - 140 * MS, clock.nowNs, 20 * MS, 0.0)
        assertTrue(tracker.finalizeCalibration(clock.nowNs))
        val s = tracker.state.value
        assertEquals(PdrPhase.TRACKING, s.phase)
        assertNotNull(s.lengthPerStepM)
        assertEquals(5.0 / 4.0, s.lengthPerStepM!!, 1e-9)
        assertFalse(s.stepLengthManual)
        assertEquals(0, s.stepCount)
        assertEquals(0.0, s.distanceM, 1e-9)
    }

    @Test
    fun calibrationRejectsZeroSteps() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        seedHeading(tracker, clock)
        assertTrue(tracker.startCalibration())
        clock.advance(1_000 * MS)
        assertTrue(tracker.finishCalibration())
        clock.advance(2_000 * MS)
        feedHeading(tracker, clock.nowNs - 140 * MS, clock.nowNs, 20 * MS, 0.0)
        assertFalse(tracker.finalizeCalibration(clock.nowNs))
        val s = tracker.state.value
        assertEquals(PdrPhase.IDLE, s.phase)
        assertTrue(s.warnings.contains(PdrWarning.CALIBRATION_FAILED_NO_STEPS))
        assertNull(s.lengthPerStepM)
    }

    @Test
    fun calibrationRejectsImplausibleStepLength() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        val tStart = clock.nowNs
        seedHeading(tracker, clock)
        assertTrue(tracker.startCalibration())
        tracker.onStep(tStart + 100 * MS, StepSource.HARDWARE)
        clock.nowNs = tStart + 1_000 * MS
        assertTrue(tracker.finishCalibration())
        clock.advance(2_000 * MS)
        feedHeading(tracker, clock.nowNs - 140 * MS, clock.nowNs, 20 * MS, 0.0)
        assertFalse(tracker.finalizeCalibration(clock.nowNs))
        assertTrue(
            tracker.state.value.warnings
                .contains(PdrWarning.CALIBRATION_FAILED_IMPLAUSIBLE_LENGTH)
        )
        val tracker2 = newTracker(clock)
        val tStart2 = clock.nowNs
        seedHeading(tracker2, clock)
        assertTrue(tracker2.startCalibration())
        repeat(40) { i -> tracker2.onStep(tStart2 + (i + 1) * 20 * MS, StepSource.HARDWARE) }
        clock.nowNs = tStart2 + 1_000 * MS
        assertTrue(tracker2.finishCalibration())
        clock.advance(2_000 * MS)
        feedHeading(tracker2, clock.nowNs - 140 * MS, clock.nowNs, 20 * MS, 0.0)
        assertFalse(tracker2.finalizeCalibration(clock.nowNs))
        assertTrue(
            tracker2.state.value.warnings
                .contains(PdrWarning.CALIBRATION_FAILED_IMPLAUSIBLE_LENGTH)
        )
    }

    @Test
    fun calibrationFailsWithoutValidReferenceHeading() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        val tStart = clock.nowNs
        seedHeading(tracker, clock)
        assertTrue(tracker.startCalibration())
        repeat(6) { i -> tracker.onStep(tStart + (i + 1) * 100 * MS, StepSource.HARDWARE) }
        clock.nowNs = tStart + 1_000 * MS
        assertTrue(tracker.finishCalibration())
        clock.advance(2_000 * MS)
        assertFalse(tracker.finalizeCalibration(clock.nowNs))
        assertTrue(
            tracker.state.value.warnings.contains(PdrWarning.CALIBRATION_FAILED_NO_HEADING)
        )
    }

    @Test
    fun finishSetsNewOriginAndReferenceAndDropsPendingSteps() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        val tStart = clock.nowNs
        seedHeading(tracker, clock)
        assertTrue(tracker.startCalibration())
        repeat(5) { i -> tracker.onStep(tStart + (i + 1) * 100 * MS, StepSource.HARDWARE) }
        clock.nowNs = tStart + 1_000 * MS
        assertTrue(tracker.finishCalibration())
        clock.advance(2_000 * MS)
        val tNow = clock.nowNs
        feedHeading(tracker, tNow - 140 * MS, tNow + 2_000 * MS, 20 * MS, 0.3)
        assertTrue(tracker.finalizeCalibration(clock.nowNs))
        tracker.onStep(tStart + 600 * MS, StepSource.HARDWARE)
        var s = tracker.state.value
        assertEquals(0, s.stepCount)
        assertEquals(0.0, s.distanceM, 1e-9)
        tracker.onStep(tNow + 100 * MS, StepSource.HARDWARE)
        s = tracker.state.value
        assertEquals(1, s.stepCount)
        assertEquals(1.0, s.distanceM, 1e-9)
        assertEquals(0.0, s.position.x, 1e-6)
        assertEquals(1.0, s.position.y, 1e-6)
    }

    @Test
    fun resetTraceKeepsLengthAndRejectsQueuedSteps() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 0.8)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 500 * MS, 20 * MS, 0.0)
        repeat(3) { i -> tracker.onStep(t0 + (i + 1) * 100 * MS, StepSource.HARDWARE) }
        assertEquals(2.4, tracker.state.value.distanceM, 1e-6)
        clock.advance(200 * MS)
        assertTrue(tracker.resetTrace())
        var s = tracker.state.value
        assertEquals(0.0, s.distanceM, 1e-9)
        assertEquals(0, s.stepCount)
        assertEquals(1, s.rawPath.size)
        assertEquals(0.8, s.lengthPerStepM!!, 1e-9)
        val tOld = t0 + 150 * MS
        tracker.onStep(tOld, StepSource.HARDWARE)
        s = tracker.state.value
        assertEquals(0, s.stepCount)
        clock.advance(500 * MS)
        val tNew = clock.nowNs
        feedHeading(tracker, tNew - 140 * MS, tNew + 200 * MS, 20 * MS, 0.0)
        tracker.onStep(tNew + 100 * MS, StepSource.HARDWARE)
        s = tracker.state.value
        assertEquals(1, s.stepCount)
        assertEquals(0.8, s.distanceM, 1e-9)
    }

    @Test
    fun pauseCancelsCalibrationAndPausesTracking() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        seedHeading(tracker, clock)
        assertTrue(tracker.startCalibration())
        tracker.onStep(clock.nowNs + 100 * MS, StepSource.HARDWARE)
        tracker.pause()
        var s = tracker.state.value
        assertEquals(PdrPhase.IDLE, s.phase)
        assertTrue(s.warnings.contains(PdrWarning.SESSION_INTERRUPTED))
        clock.advance(500 * MS)
        startTrackingWithLength(tracker, clock, 0.7)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 500 * MS, 20 * MS, 0.0)
        tracker.onStep(t0 + 100 * MS, StepSource.HARDWARE)
        tracker.pause()
        s = tracker.state.value
        assertEquals(PdrPhase.PAUSED, s.phase)
        assertEquals(1, s.stepCount)
        assertEquals(2, s.rawPath.size)
    }

    @Test
    fun resumeWithNewTraceKeepsLengthAndRestartsOrigin() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 0.9)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 500 * MS, 20 * MS, 0.0)
        tracker.onStep(t0 + 100 * MS, StepSource.HARDWARE)
        tracker.pause()
        seedHeading(tracker, clock)
        assertTrue(tracker.resumeWithNewTrace())
        var s = tracker.state.value
        assertEquals(PdrPhase.TRACKING, s.phase)
        assertEquals(0.0, s.distanceM, 1e-9)
        assertEquals(0.9, s.lengthPerStepM!!, 1e-9)
        tracker.onStep(t0 + 50 * MS, StepSource.HARDWARE)
        s = tracker.state.value
        assertEquals(0, s.stepCount)
        clock.advance(1_000 * MS)
        val tNew = clock.nowNs
        feedHeading(tracker, tNew - 140 * MS, tNew + 500 * MS, 20 * MS, 0.0)
        tracker.onStep(tNew + 100 * MS, StepSource.HARDWARE)
        s = tracker.state.value
        assertEquals(1, s.stepCount)
        assertEquals(0.9, s.distanceM, 1e-9)
    }

    @Test
    fun recalibrateResetsAllState() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 0.9)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 200 * MS, 20 * MS, 0.0)
        tracker.onStep(t0 + 100 * MS, StepSource.HARDWARE)
        tracker.recalibrate()
        val s = tracker.state.value
        assertEquals(PdrPhase.IDLE, s.phase)
        assertNull(s.lengthPerStepM)
        assertEquals(0, s.stepCount)
        assertEquals(0.0, s.distanceM, 1e-9)
        assertEquals(StepSource.HARDWARE, s.stepSource)
    }

    @Test
    fun manualLengthValidation() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        feedHeading(tracker, clock.nowNs - 140 * MS, clock.nowNs, 20 * MS, 0.0)
        assertFalse(tracker.startWithManualStepLength(0.1))
        assertFalse(tracker.startWithManualStepLength(2.0))
        assertFalse(tracker.startWithManualStepLength(Double.NaN))
        assertTrue(tracker.startWithManualStepLength(0.75))
        assertTrue(tracker.state.value.stepLengthManual)
    }

    @Test
    fun missingHeadingRefusesStartAndPreservesState() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        assertFalse(tracker.state.value.headingValid)
        assertFalse(tracker.startCalibration())
        assertFalse(tracker.startWithManualStepLength(0.7))
        assertFalse(tracker.resetTrace())
        val s = tracker.state.value
        assertEquals(PdrPhase.IDLE, s.phase)
        assertNull(s.lengthPerStepM)
        assertEquals(0, s.stepCount)
        seedHeading(tracker, clock)
        assertTrue(tracker.startWithManualStepLength(0.7))
        assertEquals(PdrPhase.TRACKING, tracker.state.value.phase)
    }

    @Test
    fun pauseClearsFrameAndResumeNeedsFreshHeading() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 0.8)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 200 * MS, 20 * MS, 0.4)
        tracker.onStep(t0 + 100 * MS, StepSource.HARDWARE)
        tracker.pause()
        assertFalse(tracker.resumeWithNewTrace())
        assertEquals(PdrPhase.PAUSED, tracker.state.value.phase)
        assertEquals(1, tracker.state.value.stepCount)
        seedHeading(tracker, clock, 1.2)
        assertTrue(tracker.resumeWithNewTrace())
        clock.advance(500 * MS)
        val tNew = clock.nowNs
        feedHeading(tracker, tNew - 140 * MS, tNew + 200 * MS, 20 * MS, 1.2)
        tracker.onStep(tNew + 100 * MS, StepSource.HARDWARE)
        val s = tracker.state.value
        assertEquals(1, s.stepCount)
        assertEquals(0.0, s.position.x, 1e-6)
        assertEquals(0.8, s.position.y, 1e-6)
    }

    @Test
    fun invalidHeadingFreezesDisplayedConeAndDropsStep() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        startTrackingWithLength(tracker, clock, 1.0)
        val t0 = clock.nowNs
        feedHeading(tracker, t0, t0 + 200 * MS, 20 * MS, 0.5)
        tracker.onStep(t0 + 100 * MS, StepSource.HARDWARE)
        clock.nowNs = t0 + 300 * MS
        tracker.onRotationVector(t0 + 250 * MS, rotationVectorFor(0.5))
        val frozenHeading = tracker.state.value.headingRadians
        assertTrue(tracker.state.value.headingValid)
        assertEquals(0.5, frozenHeading, 1e-6)
        clock.nowNs = t0 + 400 * MS
        feedHeading(tracker, t0 + 320 * MS, t0 + 400 * MS, 20 * MS, 0.5, Math.toRadians(85.0))
        tracker.onStep(t0 + 350 * MS, StepSource.HARDWARE)
        val s = tracker.state.value
        assertFalse(s.headingValid)
        assertEquals(frozenHeading, s.headingRadians, 1e-9)
        assertEquals(1, s.stepCount)
        assertEquals(1, s.droppedStepCount)
        clock.nowNs = t0 + 460 * MS
        feedHeading(tracker, t0 + 420 * MS, t0 + 460 * MS, 20 * MS, 0.5)
        assertTrue(tracker.state.value.headingValid)
        tracker.onStep(t0 + 450 * MS, StepSource.HARDWARE)
        assertEquals(2, tracker.state.value.stepCount)
    }

    @Test
    fun registrationFailureBlocksStartAndSteps() {
        val clock = FakeClock()
        val tracker = newTracker(clock)
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = false,
                permissionGranted = true,
                registrationFailed = true
            )
        )
        seedHeading(tracker, clock)
        assertFalse(tracker.state.value.canStart)
        assertFalse(tracker.startCalibration())
        assertFalse(tracker.startWithManualStepLength(0.7))
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = false,
                permissionGranted = true,
                registrationFailed = false
            )
        )
        assertTrue(tracker.state.value.canStart)
        startTrackingWithLength(tracker, clock, 1.0)
        val t0 = clock.nowNs
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = false,
                permissionGranted = true,
                registrationFailed = true
            )
        )
        tracker.onStep(t0 + 100 * MS, StepSource.HARDWARE)
        val s = tracker.state.value
        assertEquals(0, s.stepCount)
        assertTrue(s.warnings.contains(PdrWarning.SENSOR_REGISTRATION_FAILED))
    }

    @Test
    fun bootTimeOffsetClockKeepsCalibrationAndStepsConsistent() {
        val clock = FakeClock(nowNs = 800_000_000_000L)
        val tracker = newTracker(clock)
        val tStart = clock.nowNs
        seedHeading(tracker, clock)
        assertTrue(tracker.startCalibration())
        repeat(5) { i -> tracker.onStep(tStart + (i + 1) * 100 * MS, StepSource.HARDWARE) }
        clock.nowNs = tStart + 1_000 * MS
        assertTrue(tracker.finishCalibration())
        clock.advance(2_000 * MS)
        val tNow = clock.nowNs
        feedHeading(tracker, tNow - 140 * MS, tNow + 500 * MS, 20 * MS, 0.0)
        assertTrue(tracker.finalizeCalibration(clock.nowNs))
        tracker.onStep(tNow + 100 * MS, StepSource.HARDWARE)
        val s = tracker.state.value
        assertEquals(1.0, s.lengthPerStepM!!, 1e-9)
        assertEquals(1, s.stepCount)
        assertEquals(0.0, s.position.x, 1e-6)
        assertEquals(1.0, s.position.y, 1e-6)
    }

    @Test
    fun permissionGrantAutoSelectsHardwareAndKeepsFallback() {
        val clock = FakeClock()
        val tracker = PdrTracker(clock = { clock.nowNs })
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = true,
                permissionGranted = false
            )
        )
        assertEquals(StepSource.NONE, tracker.state.value.stepSource)
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = true,
                permissionGranted = true
            )
        )
        assertEquals(StepSource.HARDWARE, tracker.state.value.stepSource)

        val tracker2 = PdrTracker(clock = { clock.nowNs })
        tracker2.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = true,
                permissionGranted = false
            )
        )
        assertTrue(tracker2.setStepSource(StepSource.FALLBACK))
        tracker2.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = true,
                permissionGranted = true
            )
        )
        assertEquals(StepSource.FALLBACK, tracker2.state.value.stepSource)
    }
}
