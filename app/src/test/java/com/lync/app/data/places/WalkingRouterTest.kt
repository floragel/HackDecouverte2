package com.lync.app.data.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkingRouterTest {
    // Trimmed OSRM foot response: Hall building -> along Bishop -> left on de Maisonneuve -> MB
    private val osrm = """
        {"code":"Ok","routes":[{"distance":200.0,"duration":140.0,
          "geometry":{"type":"LineString","coordinates":[[-73.5780,45.4972],[-73.5785,45.4962],[-73.5795,45.4957]]},
          "legs":[{"steps":[
            {"name":"Rue Bishop","distance":120,"maneuver":{"type":"depart","modifier":"left","location":[-73.5780,45.4972]}},
            {"name":"Boulevard De Maisonneuve","distance":80,"maneuver":{"type":"turn","modifier":"right","location":[-73.5785,45.4962]}},
            {"name":"","distance":0,"maneuver":{"type":"arrive","location":[-73.5795,45.4957]}}
          ]}]}]}
    """.trimIndent()

    @Test
    fun parsesGeometryAndManeuvers() {
        val r = WalkingRouter.parse(osrm)
        assertNotNull(r)
        r!!
        assertEquals(3, r.points.size)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.RIGHT, Maneuver.ARRIVE), r.steps.map { it.maneuver })
        assertEquals("Boulevard De Maisonneuve", r.steps[1].street)
        assertFalse(r.approximate)
        // walking pace never faster than 1.3 m/s
        assertTrue(r.durationS >= 200.0 / WalkingRouter.WALK_SPEED_MPS - 1e-6)
    }

    @Test
    fun progressGivesNextTurnAndRemainingDistance() {
        val r = WalkingRouter.parse(osrm)!!
        val me = GeoPoint(45.49695, -73.57813) // a quarter of the way down Bishop
        val p = WalkProgress.of(r, me)
        assertEquals(Maneuver.RIGHT, p.nextStep?.maneuver)
        assertTrue(p.offRouteM < 5.0)
        assertEquals(r.lengthM - p.alongM, p.remainingM, 1e-6)
        assertEquals(r.stepAlongM[1] - p.alongM, p.distanceToNextStepM, 1e-6)
        assertTrue(p.bearingDeg in 180.0..230.0) // heading south-west down Bishop
    }

    @Test
    fun offRouteIsMeasured() {
        val r = WalkingRouter.parse(osrm)!!
        val farAway = GeoPoint(45.4990, -73.5750)
        assertTrue(WalkProgress.of(r, farAway).offRouteM > 200.0)
    }

    @Test
    fun badResponsesFallBackToStraightLine() {
        assertEquals(null, WalkingRouter.parse("""{"code":"NoRoute"}"""))
        val a = GeoPoint(45.4972, -73.5780); val b = GeoPoint(45.4952, -73.5790)
        val line = WalkingRouter.straightLine(a, b)
        assertTrue(line.approximate)
        assertEquals(Geo.distanceM(a, b), line.lengthM, 1e-6)
    }
}
