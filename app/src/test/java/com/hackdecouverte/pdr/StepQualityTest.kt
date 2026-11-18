package com.hackdecouverte.pdr

import com.hackdecouverte.sensor.SensorRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan B3: steps taken while turning on the spot, or that the step counter does not confirm, are doubtful. */
class StepQualityTest {
    private val ms = 1_000_000L

    private fun tracker(clock: LongArray) = PdrTracker(clock = { clock[0] }, antiShakeEnabled = false).apply {
        anyHoldHeading = true
        onSensorStatus(SensorStatus(rotationAvailable = true, stepDetectorAvailable = true, fallbackCapable = true, permissionNeeded = false, permissionGranted = true))
    }

    @Test
    fun fastTurnOnTheSpotLowersStepQuality() {
        val clock = longArrayOf(10_000_000_000L)
        val t = tracker(clock)
        fun rot(h: Double) = t.onRotationVector(clock[0], Quat.phone(h, Quat.FLAT).rv())
        repeat(75) { rot(0.0); clock[0] += 20 * ms }
        assertTrue(t.startWithManualStepLength(0.7))
        val qualities = ArrayList<Double>()
        // 10 straight steps
        repeat(10) {
            repeat(27) { rot(0.0); clock[0] += 20 * ms }
            t.onStep(clock[0], StepSource.HARDWARE); qualities += t.state.value.stepQuality
        }
        assertTrue(qualities.all { it == 1.0 })
        // 180° in 1.5 s with 3 detector steps
        val t0 = clock[0]
        val pivot = ArrayList<Double>()
        while (clock[0] < t0 + 1_500_000_000L) {
            rot(Math.PI * (clock[0] - t0) / 1.5e9); clock[0] += 20 * ms
            if ((clock[0] - t0) % (500 * ms) == 0L) { t.onStep(clock[0], StepSource.HARDWARE); pivot += t.state.value.stepQuality }
        }
        assertEquals(3, pivot.size)
        assertTrue("pivot step qualities $pivot", pivot.all { it <= 0.5 })
    }

    @Test
    fun stepsTheCounterDoesNotConfirmAreDoubtful() {
        val clock = longArrayOf(10_000_000_000L)
        val t = tracker(clock)
        val router = SensorRouter(t, { StepSource.HARDWARE })
        val rv = Quat.phone(0.0, Quat.FLAT).rv()
        fun tick() { router.onSample(SensorRouter.TYPE_GAME_ROTATION_VECTOR, clock[0], rv, 3); clock[0] += 20 * ms }
        router.onSample(SensorRouter.TYPE_STEP_COUNTER, clock[0], floatArrayOf(1000f), 3)
        repeat(75) { tick() }
        assertTrue(t.startWithManualStepLength(0.7))
        var counter = 1000f
        var last = 1.0
        for (k in 1..30) { // detector: a step every 0.5 s; counter: +1 every 5 steps (shuffling, pivoting)
            repeat(25) { tick() }
            router.onSample(SensorRouter.TYPE_STEP_DETECTOR, clock[0], floatArrayOf(1f), 3)
            if (k % 5 == 0) { counter += 1f; router.onSample(SensorRouter.TYPE_STEP_COUNTER, clock[0], floatArrayOf(counter), 3) }
            last = t.state.value.stepQuality
        }
        assertTrue("last quality $last", last <= 0.5)
    }
}
