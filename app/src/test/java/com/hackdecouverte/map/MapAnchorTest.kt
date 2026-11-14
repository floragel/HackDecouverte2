package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapAnchorTest {

    @Test
    fun facingPlanNorthMapsForwardToNegativeY() {
        val anchor = MapAnchor(PointM(0.0, 0.0), 0.0)
        val forward = anchor.toMap(PointM(0.0, 1.0))
        assertEquals(0.0, forward.x, 1e-9)
        assertEquals(-1.0, forward.y, 1e-9)
        val right = anchor.toMap(PointM(1.0, 0.0))
        assertEquals(1.0, right.x, 1e-9)
        assertEquals(0.0, right.y, 1e-9)
    }

    @Test
    fun facingPlanEastMapsForwardToPositiveX() {
        val anchor = MapAnchor(PointM(0.0, 0.0), PI / 2)
        val forward = anchor.toMap(PointM(0.0, 1.0))
        assertEquals(1.0, forward.x, 1e-9)
        assertEquals(0.0, forward.y, 1e-9)
        val right = anchor.toMap(PointM(1.0, 0.0))
        assertEquals(0.0, right.x, 1e-9)
        assertEquals(1.0, right.y, 1e-9)
    }

    @Test
    fun rotationAndTranslationOfKnownPoint() {
        val anchor = MapAnchor(PointM(10.0, 20.0), PI / 2)
        val mapped = anchor.toMap(PointM(2.0, 3.0))
        assertEquals(13.0, mapped.x, 1e-9)
        assertEquals(22.0, mapped.y, 1e-9)
    }

    @Test
    fun originKeepsLocalZeroOnNode() {
        val anchor = MapAnchor(PointM(43.036, 19.662), 1.1)
        val mapped = anchor.toMap(PointM(0.0, 0.0))
        assertEquals(43.036, mapped.x, 1e-9)
        assertEquals(19.662, mapped.y, 1e-9)
    }

    @Test
    fun mapHeadingWrapsIntoPiRange() {
        val anchor = MapAnchor(PointM(0.0, 0.0), 2.5)
        val heading = anchor.mapHeading(1.5)
        assertEquals(2.5 + 1.5 - 2.0 * PI, heading, 1e-9)
        assertTrue(heading <= PI && heading > -PI)
    }

    @Test
    fun mapHeadingIdentityPsi() {
        val anchor = MapAnchor(PointM(5.0, 5.0), 0.0)
        assertEquals(0.7, anchor.mapHeading(0.7), 1e-9)
    }
}
