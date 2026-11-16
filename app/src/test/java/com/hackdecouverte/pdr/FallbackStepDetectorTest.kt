package com.hackdecouverte.pdr

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MS = 1_000_000L

class FallbackStepDetectorTest {

    private fun detectorWithSteps(): Pair<FallbackStepDetector, MutableList<Long>> {
        val detector = FallbackStepDetector()
        val steps = ArrayList<Long>()
        detector.onStep = { steps.add(it) }
        return detector to steps
    }

    private fun feedLinear(
        detector: FallbackStepDetector,
        fromNs: Long,
        toNs: Long,
        stepNs: Long = 10 * MS,
        value: (Double) -> Double
    ) {
        var t = fromNs
        while (t <= toNs) {
            val v = value(t / 1e9)
            detector.onLinearAcceleration(t, 0.0, 0.0, v, 0.0, 0.0, 1.0)
            t += stepNs
        }
    }

    @Test
    fun stationaryInputProducesNoSteps() {
        val (detector, steps) = detectorWithSteps()
        detector.start(0)
        feedLinear(detector, 0, 3_000 * MS) { 0.0 }
        assertTrue(steps.isEmpty())
    }

    @Test
    fun sinusoidalWalkProducesStepsRespectingWarmupAndRefractory() {
        val (detector, steps) = detectorWithSteps()
        detector.start(0)
        feedLinear(detector, 0, 5_000 * MS) { t -> 1.6 * sin(2.0 * PI * 1.8 * t) }
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.first() >= 500 * MS)
        for (i in 1 until steps.size) {
            assertTrue(steps[i] - steps[i - 1] >= 330 * MS)
        }
        assertTrue(steps.size in 5..9)
    }

    @Test
    fun refractoryBlocksCloseSecondPulseAndRearmAllowsLaterOne() {
        val (detector, steps) = detectorWithSteps()
        detector.start(0)
        feedLinear(detector, 0, 600 * MS) { 0.0 }
        feedLinear(detector, 610 * MS, 700 * MS) { 2.0 }
        feedLinear(detector, 710 * MS, 900 * MS) { 0.0 }
        feedLinear(detector, 910 * MS, 1_000 * MS) { 2.0 }
        feedLinear(detector, 1_010 * MS, 1_300 * MS) { 0.0 }
        feedLinear(detector, 1_310 * MS, 1_400 * MS) { 2.0 }
        feedLinear(detector, 1_410 * MS, 1_600 * MS) { 0.0 }
        assertEquals(2, steps.size)
        assertTrue(steps[1] - steps[0] >= 330 * MS)
    }

    @Test
    fun accelerometerModeConvergesGravityAndStaysQuietWhenStationary() {
        val (detector, steps) = detectorWithSteps()
        detector.start(0)
        var t = 0L
        while (t <= 3_000 * MS) {
            detector.onAccelerometer(t, 0.0, 0.0, 9.81)
            t += 20 * MS
        }
        assertTrue(steps.isEmpty())
    }

    @Test
    fun outOfOrderAndDuplicateTimestampsAreIgnored() {
        val (detector, steps) = detectorWithSteps()
        detector.start(0)
        detector.onLinearAcceleration(1_000 * MS, 0.0, 0.0, 0.5, 0.0, 0.0, 1.0)
        detector.onLinearAcceleration(1_000 * MS, 0.0, 0.0, 3.0, 0.0, 0.0, 1.0)
        detector.onLinearAcceleration(500 * MS, 0.0, 0.0, 3.0, 0.0, 0.0, 1.0)
        detector.onLinearAcceleration(1_100 * MS, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        assertTrue(steps.isEmpty())
    }

    @Test
    fun stoppedDetectorIgnoresInput() {
        val (detector, steps) = detectorWithSteps()
        detector.start(0)
        detector.stop()
        feedLinear(detector, 0, 3_000 * MS) { 2.0 }
        assertTrue(steps.isEmpty())
    }
}
