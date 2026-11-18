package com.hackdecouverte.pdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorTransitionTrackerTest {

    @Test
    fun climbingOneFloorNearStairsEmitsPlusOneAndEntersVerticalTransit() {
        val tracker = FloorTransitionTracker(floorHeightM = 4.0)
        var tNs = 1_000_000_000L

        // Settle at floor 2 baseline (1013.25 hPa)
        for (i in 0..4) {
            tracker.onPressure(tNs, 1013.25, nearVerticalConnector = true)
            tNs += 200_000_000L
        }

        // Climb 4.2 m up stairs over ~10 seconds (~-0.50 hPa)
        var totalFloorSteps = 0
        var sawVerticalTransit = false
        val steps = 50
        for (k in 1..steps) {
            val frac = k.toDouble() / steps
            val pHpa = 1013.25 - 0.52 * frac
            val upd = tracker.onPressure(tNs, pHpa, nearVerticalConnector = true)
            assertNotNull(upd)
            if (upd!!.inVerticalTransit) sawVerticalTransit = true
            totalFloorSteps += upd.floorStepDelta
            tNs += 200_000_000L
        }

        assertTrue("Expected stair climb to trigger vertical transit", sawVerticalTransit)
        assertEquals(1, totalFloorSteps)

        // Settle flat on new landing for 2.5 seconds -> exits vertical transit
        for (k in 1..15) {
            tracker.onPressure(tNs, 1012.73, nearVerticalConnector = true)
            tNs += 200_000_000L
        }
        assertFalse(tracker.inVerticalTransit)
    }

    @Test
    fun elevatorRideUpThreeFloorsEmitsThreeSequentialFloorSteps() {
        val tracker = FloorTransitionTracker(floorHeightM = 4.0)
        var tNs = 1_000_000_000L
        tracker.onPressure(tNs, 1013.0, nearVerticalConnector = true)
        tNs += 200_000_000L

        // Ascend ~12.4 m (~-1.48 hPa) at ~1.5 m/s over 8.4 seconds (MB-1 -> MB-4)
        var cumulativeDelta = 0
        val samples = 42
        for (k in 1..samples) {
            val pHpa = 1013.0 - 1.50 * (k.toDouble() / samples)
            val upd = tracker.onPressure(tNs, pHpa, nearVerticalConnector = true)!!
            cumulativeDelta += upd.floorStepDelta
            tNs += 200_000_000L
        }

        assertEquals(3, cumulativeDelta)
    }

    @Test
    fun slowWeatherPressureDriftDoesNotFalseTriggerFloorChange() {
        val tracker = FloorTransitionTracker(floorHeightM = 4.0)
        var tNs = 1_000_000_000L

        // Simulate a 10-minute storm front dropping pressure by 1.2 hPa (~10 m equivalent!) very slowly
        // while walking flat away from stairs
        val totalSeconds = 600
        for (s in 0..totalSeconds) {
            val pHpa = 1015.0 - 1.2 * (s.toDouble() / totalSeconds)
            val upd = tracker.onPressure(tNs, pHpa, nearVerticalConnector = false)!!
            assertEquals("False floor trigger at second $s", 0, upd.floorStepDelta)
            assertFalse(upd.inVerticalTransit)
            tNs += 1_000_000_000L
        }
    }

    @Test
    fun singleSampleDoorSlamSpikeIsRejectedByMedianFilter() {
        val tracker = FloorTransitionTracker(floorHeightM = 4.0)
        var tNs = 1_000_000_000L
        repeat(5) {
            tracker.onPressure(tNs, 1012.0, nearVerticalConnector = true)
            tNs += 200_000_000L
        }
        // Sudden 1-sample transient spike (-0.8 hPa) then immediate return
        val spikeUpd = tracker.onPressure(tNs, 1011.2, nearVerticalConnector = true)!!
        tNs += 200_000_000L
        val afterUpd = tracker.onPressure(tNs, 1012.0, nearVerticalConnector = true)!!

        assertEquals(0, spikeUpd.floorStepDelta)
        assertEquals(0, afterUpd.floorStepDelta)
    }

    @Test
    fun horizontalStepDisplacementIsFrozenDuringStairTransitAndReanchorsKeepHeading() {
        var nowNs = 1_000_000_000L
        val tracker = PdrTracker(
            clock = { nowNs },
            antiShakeEnabled = false,
            corridorAssistInitial = false
        ).apply {
            useTrueNorthReference = true
        }
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                permissionNeeded = false,
                permissionGranted = true
            )
        )
        tracker.setStepSource(StepSource.HARDWARE)

        // Identity rotation vector => gameYaw = 0; True North = +90° (East)
        val identityRv = floatArrayOf(0f, 0f, 0f, 1f)
        tracker.onRotationVector(nowNs, identityRv, 4)
        tracker.onTrueNorthAzimuth(nowNs, Math.PI / 2.0, highConfidence = true)
        assertTrue(tracker.startWithManualStepLength(0.75))

        // Walk 2 steps East on floor 2 => x ≈ +1.50 m
        repeat(2) {
            nowNs += 500_000_000L
            tracker.onRotationVector(nowNs, identityRv, 4)
            tracker.onStep(nowNs, StepSource.HARDWARE)
        }
        assertEquals(1.50, tracker.state.value.position.x, 0.05)

        // Hold horizontal steps while climbing stairs (10 stair steps)
        tracker.beginVerticalHold()
        repeat(10) {
            nowNs += 500_000_000L
            tracker.onRotationVector(nowNs, identityRv, 4)
            tracker.onStep(nowNs, StepSource.HARDWARE)
        }
        // Position did NOT shoot 7.5 m through the stairwell wall!
        assertEquals(1.50, tracker.state.value.position.x, 0.05)
        assertEquals(12, tracker.state.value.stepCount)

        // Arrive on floor 3 landing -> reanchor local origin (drops held steps) keeping True-North heading
        tracker.reanchorFloorOriginKeepHeading()
        assertEquals(0.0, tracker.state.value.position.x, 1e-6)
        assertEquals(0.0, tracker.state.value.position.y, 1e-6)

        // Next step on floor 3 immediately continues East (+0.75 m) with zero angle recalibration!
        nowNs += 500_000_000L
        tracker.onRotationVector(nowNs, identityRv, 4)
        tracker.onStep(nowNs, StepSource.HARDWARE)
        assertEquals(0.75, tracker.state.value.position.x, 0.05)

        // False alarm: held steps are replayed, so walking is never lost
        tracker.beginVerticalHold()
        repeat(4) {
            nowNs += 500_000_000L
            tracker.onRotationVector(nowNs, identityRv, 4)
            tracker.onStep(nowNs, StepSource.HARDWARE)
        }
        assertEquals(0.75, tracker.state.value.position.x, 0.05)
        tracker.endVerticalHold(commit = true)
        assertEquals(3.75, tracker.state.value.position.x, 0.05)
    }

    @Test
    fun tallLobbyClimbLandsOnTheNextFloorWithoutAResidualMetre() {
        // MB-1 -> MB-2 is 5 m; the next floors are 4 m
        fun climb(spacingUpM: Double?): Double {
            val tracker = FloorTransitionTracker(floorHeightM = 4.0)
            tracker.setFloorSpacing(upM = spacingUpM, downM = null)
            var tNs = 1_000_000_000L
            repeat(5) {
                tracker.onPressure(tNs, 1013.0, nearVerticalConnector = true)
                tNs += 200_000_000L
            }
            var steps = 0
            val n = 50
            var last: BarometerUpdate? = null
            for (k in 1..n) {
                // 5 m ≈ 0.60 hPa at sea level
                last = tracker.onPressure(tNs, 1013.0 - 0.60 * k / n, nearVerticalConnector = true)
                steps += last!!.floorStepDelta
                tNs += 200_000_000L
            }
            repeat(3) {
                last = tracker.onPressure(tNs, 1012.40, nearVerticalConnector = true)
                tNs += 200_000_000L
            }
            assertEquals(1, steps)
            return last!!.deltaFromFloorM
        }
        assertTrue("with the real 5 m spacing the user is on the landing", kotlin.math.abs(climb(5.0)) < 0.5)
        assertTrue("with a flat 4 m the landing looks 1 m higher", climb(null) > 0.7)
    }
}
