package com.hackdecouverte.pdr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Second real walk (18/11, 19:37): the saved base cadence was 1.22 Hz (a slow start persisted from an
 * earlier session), so every step at 1.46 Hz was lengthened ×1.18: 156 m counted for a 109 m traced loop.
 * Any hold: a saved cadence outside normal walking is not a reference; the typical cadence is.
 */
class BaseCadenceTest {
    private val ms = 1_000_000L

    private fun started(cadenceHz: Double?, anyHold: Boolean = true): PdrTracker {
        val clock = longArrayOf(10_000_000_000L)
        val t = PdrTracker(clock = { clock[0] }, antiShakeEnabled = false).apply {
            anyHoldHeading = anyHold
            onSensorStatus(SensorStatus(rotationAvailable = true, stepDetectorAvailable = true, fallbackCapable = true, permissionNeeded = false, permissionGranted = true))
        }
        repeat(75) { t.onRotationVector(clock[0], Quat.phone(0.0, Quat.FLAT).rv()); clock[0] += 20 * ms }
        t.startWithManualStepLength(0.74, cadenceHz)
        return t
    }

    @Test
    fun anyHoldIgnoresASlowSavedCadence() =
        assertEquals(PdrTracker.TYPICAL_CADENCE_HZ, started(1.22).state.value.baseCadenceHz!!, 1e-9)

    @Test
    fun anyHoldKeepsAPlausibleSavedCadence() =
        assertEquals(1.95, started(1.95).state.value.baseCadenceHz!!, 1e-9)

    @Test
    fun flatHoldStillUsesTheCalibratedCadence() =
        assertEquals(1.22, started(1.22, anyHold = false).state.value.baseCadenceHz!!, 1e-9)
}
