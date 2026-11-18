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

    // ---------- Floor anchors: pressure read where the floor was known ----------

    private val h = 3600L * s

    @Test
    fun floorAnchorGivesTheFloorAfterAClimb() {
        val est = AltitudeFloorEstimator()
        est.anchorAtFloor(0, pressureAt(est.floorElevationM(2)), floor = 2)
        // Two floors up (4 m each above MB-2), one minute later
        val e = est.estimate(60 * s, pressureAt(est.floorElevationM(4)))
        assertEquals(4, e.floorNumber)
        assertEquals(AltitudeFloorSource.BAROMETER_FLOOR_ANCHOR, e.source)
        assertTrue(e.confident)
        assertTrue(e.probability > 0.95)
    }

    @Test
    fun theSharperAnchorWins() {
        val est = AltitudeFloorEstimator()
        est.captureStreetReference(0, street)
        est.anchorAtFloor(30 * 60 * s, pressureAt(est.floorElevationM(5)), floor = 5)
        val e = est.estimate(31 * 60 * s, pressureAt(est.floorElevationM(5)))
        assertEquals(AltitudeFloorSource.BAROMETER_FLOOR_ANCHOR, e.source)
        assertEquals(5, e.floorNumber)
    }

    @Test
    fun probabilitiesSumToOneAndUncertaintyGrowsWithTheWeather() {
        val est = AltitudeFloorEstimator()
        est.anchorAtFloor(0, pressureAt(est.floorElevationM(3)), floor = 3)
        val p = pressureAt(est.floorElevationM(3) + 1.2)
        val probs = est.floorProbabilities(60 * s, p)
        assertEquals(1.0, probs.values.sum(), 1e-9)
        val fresh = est.estimate(60 * s, p)
        val old = est.estimate(2 * h, p)
        assertEquals(3, fresh.floorNumber)
        assertTrue(fresh.confident)
        assertTrue(old.sigmaM!! > fresh.sigmaM!!)
        assertFalse("2 h of weather: a barometer cannot be sure any more", old.confident)
    }

    @Test
    fun aWeatherDriftIsReportedAsDoubtNotAsAFloorChange() {
        val est = AltitudeFloorEstimator()
        val p0 = pressureAt(est.floorElevationM(3))
        est.anchorAtFloor(0, p0, floor = 3)
        // One hour later the weather raised the pressure by 0.5 hPa (≈ 4 m lower), user still on floor 3
        val e = est.estimate(h, p0 + 0.5)
        assertFalse(e.confident)
        assertTrue(e.probability < 0.6)
    }

    @Test
    fun theLobbyIsTallerThanTheOtherFloors() {
        val est = AltitudeFloorEstimator(groundFloorHeightM = 5.0)
        assertEquals(5.0, est.floorSpacingM(1, 2), 1e-9)
        assertEquals(4.0, est.floorSpacingM(2, 3), 1e-9)
        assertEquals(-9.0, est.floorSpacingM(3, 1), 1e-9)
    }

    @Test
    fun restoredAnchorsWorkAndExpire() {
        val est = AltitudeFloorEstimator()
        est.restoreFloorAnchor(PressureAnchor(0, pressureAt(est.floorElevationM(6)), est.floorElevationM(6), 0.5, Long.MAX_VALUE))
        assertEquals(6, est.estimate(10 * 60 * s, pressureAt(est.floorElevationM(6))).floorNumber)
        assertEquals(AltitudeFloorSource.ENTRANCE_ASSUMED, est.estimate(5 * h, pressureAt(est.floorElevationM(6))).source)
    }

    @Test
    fun rejectsAnchorsOutsideTheBuilding() {
        val est = AltitudeFloorEstimator()
        est.anchorAtFloor(0, street, floor = 9)
        est.anchorAtFloor(0, Double.NaN, floor = 2)
        assertNull(est.floorAnchor)
    }
}
