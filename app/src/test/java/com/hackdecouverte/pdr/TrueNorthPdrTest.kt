package com.hackdecouverte.pdr

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MS_NS = 1_000_000L

class TrueNorthPdrTest {

    @Test
    fun alignsPlanNorthWithPhoneTrueNorthWithoutManualCalibrationAndIgnoresIndoorMagnetSpikes() {
        var now = 20_000_000_000L
        val tracker = PdrTracker(clock = { now }).apply {
            useTrueNorthReference = true
        }
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = false,
                permissionGranted = true
            )
        )

        // Phone's arbitrary GAME_ROTATION_VECTOR zero is rotated by -68° relative to True North.
        // Phone physically points East (+90° True North).
        val arbitraryOffset = Math.toRadians(-68.0)
        val physicalTrueNorth = PI / 2.0

        var t = now - 300 * MS_NS
        while (t <= now) {
            val gameYaw = wrapAnglePi(physicalTrueNorth - arbitraryOffset)
            tracker.onRotationVector(t, rotationVectorFor(gameYaw))
            tracker.onTrueNorthAzimuth(t, physicalTrueNorth, highConfidence = true)
            t += 20 * MS_NS
        }

        assertTrue(tracker.state.value.trueNorthAligned)
        assertEquals(physicalTrueNorth, tracker.state.value.headingRadians, Math.toRadians(1.0))

        // Start WITHOUT calibrating any angle: walking East (+90°) must advance +x (East) and 0 y (North)!
        assertTrue(tracker.startWithManualStepLength(0.75))
        repeat(8) { i ->
            val stepTs = now + (i + 1) * 500 * MS_NS
            var s = stepTs - 480 * MS_NS
            while (s <= stepTs) {
                val gameYaw = wrapAnglePi(physicalTrueNorth - arbitraryOffset)
                tracker.onRotationVector(s, rotationVectorFor(gameYaw))
                // Simulate passing a steel elevator on step 4 (+85° magnetic spike!) -> must be ignored!
                val compassYaw = if (i == 4) wrapAnglePi(physicalTrueNorth + Math.toRadians(85.0)) else physicalTrueNorth
                tracker.onTrueNorthAzimuth(s, compassYaw, highConfidence = true)
                s += 20 * MS_NS
            }
            now = stepTs
            tracker.onStep(stepTs, StepSource.HARDWARE)
        }

        val state = tracker.state.value
        assertEquals(6.0, state.position.x, 0.08)
        assertEquals(0.0, state.position.y, 0.08)

        // Now simulate outdoor GPS Doppler bearing confirming True North (0°) when user turns North:
        val northTrue = 0.0
        repeat(4) { i ->
            val stepTs = now + (i + 1) * 500 * MS_NS
            var s = stepTs - 480 * MS_NS
            while (s <= stepTs) {
                tracker.onRotationVector(s, rotationVectorFor(wrapAnglePi(northTrue - arbitraryOffset)))
                s += 20 * MS_NS
            }
            tracker.onTrueNorthBearingFromGps(stepTs, northTrue)
            now = stepTs
            tracker.onStep(stepTs, StepSource.HARDWARE)
        }
        assertTrue(abs(tracker.state.value.headingRadians - northTrue) < Math.toRadians(2.0))
        assertTrue(tracker.state.value.position.y > 2.8)
    }
}
