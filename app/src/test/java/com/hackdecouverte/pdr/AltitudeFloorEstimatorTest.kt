package com.hackdecouverte.pdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class AltitudeFloorEstimatorTest {
    private val s = 1_000_000_000L
    private val street = 1013.0

    /** Pressure measured [h] metres above a street at [street] hPa (inverse barometric formula). */
    private fun pressureAt(h: Double) = street * (1.0 - h / 44_330.0).pow(5.255)

    @Test
    fun noReferenceAssumesGroundFloorFromTheEntrance() {
        val e = AltitudeFloorEstimator().estimate(10 * s, 1000.0)
        assertEquals(1, e.floorNumber)
        assertEquals(AltitudeFloorSource.ENTRANCE_ASSUMED, e.source)
        assertNull(e.heightAboveStreetM)
    }

    @Test
    fun heightAboveStreetMapsToFloors() {
        val est = AltitudeFloorEstimator()
        est.captureStreetReference(0, street)
        for (floor in 1..7) {
            val e = est.estimate(60 * s, pressureAt(est.floorElevationM(floor)))
            assertEquals("floor $floor", floor, e.floorNumber)
            assertEquals(AltitudeFloorSource.BAROMETER_STREET_REFERENCE, e.source)
            assertTrue(e.confident)
        }
    }

    @Test
    fun halfwayBetweenFloorsIsNotConfident() {
        val est = AltitudeFloorEstimator()
        est.captureStreetReference(0, street)
        val mid = (est.floorElevationM(3) + est.floorElevationM(4)) / 2
        assertFalse(est.estimate(s, pressureAt(mid)).confident)
    }

    @Test
    fun clampsToTheBuildingAndExpiresOldReferences() {
        val est = AltitudeFloorEstimator()
        est.captureStreetReference(0, street)
        assertEquals(7, est.estimate(s, pressureAt(80.0)).floorNumber)
        assertEquals(1, est.estimate(s, pressureAt(-6.0)).floorNumber)
        // 2 h later the weather may have moved the pressure by several floors: back to the assumption
        assertEquals(AltitudeFloorSource.ENTRANCE_ASSUMED, est.estimate(2 * 3600 * s, pressureAt(13.0)).source)
    }

    @Test
    fun ignoresImpossiblePressures() {
        val est = AltitudeFloorEstimator()
        est.captureStreetReference(0, Double.NaN)
        est.captureStreetReference(0, 50.0)
        assertFalse(est.hasStreetReference)
    }
}
