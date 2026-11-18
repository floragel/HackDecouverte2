package com.hackdecouverte.rec

import com.hackdecouverte.backend.stepTimePercentiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Plan E3: battery, heat and step time from what the phone recorded. */
class HealthReportTest {
    private fun rec(vararg notes: Pair<Double, String>) = Recording(
        RecordingHeader(siteId = "STAN", floorId = "STAN-1", startedWallMs = 0L, startedNs = 0L, stepSource = "HARDWARE", stepLengthM = 0.7, declinationDeg = -14.2),
        notes.map { (min, d) -> RecEvent.Note((min * 60e9).toLong(), "health", d) }
    )

    @Test
    fun batteryDrainPerHourFromHealthNotes() {
        val h = WalkAnalysis.health(rec(0.0 to "battery=80 thermal=0", 15.0 to "battery=79 thermal=1", 30.0 to "battery=78 thermal=0"))
        assertEquals(4.0, h.batteryPerHour!!, 1e-9)
        assertEquals(1, h.thermalMax)
    }

    @Test
    fun unknownValuesAreIgnored() {
        val h = WalkAnalysis.health(rec(0.0 to "battery=-1 thermal=-1"))
        assertNull(h.batteryPerHour)
        assertNull(h.thermalMax)
    }

    @Test
    fun stepTimeMedianAndP95() {
        val (p50, p95) = stepTimePercentiles(List(100) { (it + 1).toDouble() })
        assertEquals(50.0, p50, 0.0)
        assertEquals(95.0, p95, 0.0)
        assertEquals(0.0 to 0.0, stepTimePercentiles(emptyList()))
    }
}
