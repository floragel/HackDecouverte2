package com.hackdecouverte.pdr

import org.junit.Assert.assertEquals
import org.junit.Test

class StepReconcilerTest {
    private val ms = 1_000_000L

    /** Walk: steps every 550 ms from t = 1 s; the counter reports every [counterEvery] steps, [counterLagMs] late. */
    private fun run(steps: Int, missedByDetector: Set<Int>, detectorLagMs: Long = 0, counterEvery: Int = 1, counterLagMs: Long = 0): Int {
        val r = StepReconciler()
        data class Ev(val arrival: Long, val action: () -> Unit)
        val events = ArrayList<Ev>()
        events += Ev(0L) { r.onCounter(0L, 1000L) } // baseline at registration
        for (k in 1..steps) {
            val t = 1000 * ms + k * 550 * ms
            if (k !in missedByDetector) events += Ev(t + detectorLagMs * ms) { r.onDetector(t) }
            if (k % counterEvery == 0 || k == steps) events += Ev(t + counterLagMs * ms) { r.onCounter(t, 1000L + k) }
        }
        events.sortBy { it.arrival }
        var added = 0
        var now = 0L
        val end = 1000 * ms + (steps + 10) * 550 * ms
        var i = 0
        while (now <= end) {
            while (i < events.size && events[i].arrival <= now) events[i++].action()
            added += r.poll(now)
            now += 20 * ms
        }
        return added
    }

    @Test
    fun nothingIsAddedWhenBothAgree() = assertEquals(0, run(30, emptySet()))

    @Test
    fun theFirstStepsMissedByTheDetectorAreGivenBack() = assertEquals(4, run(30, setOf(1, 2, 3, 4)))

    @Test
    fun aLateBatchedCounterAddsNothing() = assertEquals(0, run(30, emptySet(), counterEvery = 10, counterLagMs = 2000))

    @Test
    fun aLateDetectorIsNotDoubleCounted() = assertEquals(0, run(30, emptySet(), detectorLagMs = 1500))

    @Test
    fun aSingleStepOfDisagreementIsTolerated() = assertEquals(0, run(30, setOf(12)))

    @Test
    fun aDetectorThatDoublesStepsGivesACountRatioBelowOne() {
        // Real pocket walk: detector 119, counter 95 over the same time.
        val r = StepReconciler()
        r.onCounter(0L, 0L)
        var counted = 0L
        for (k in 1..120) {
            val t = k * 600 * ms
            r.onDetector(t)
            if (k % 5 != 0) counted++ // the counter misses... no: the detector adds one extra every 5
            r.onCounter(t, counted)
            r.poll(t + 3000 * ms)
        }
        val ratio = r.countRatio()!!
        assertEquals(0.8, ratio, 0.05)
    }
}

