package com.hackdecouverte.backend

import com.hackdecouverte.loc.LocZone
import com.hackdecouverte.loc.SyntheticMapsAccess
import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Spec §6.5: a floor change only for the hypotheses next to stairs or a lift, which land at the other end. */
class FloorChangeTest {

    private val stairs = listOf(PointM(0.0, 0.0) to PointM(25.0, 20.0)) // departure on A → landing on B

    private fun controller() = LocalizationController({ SyntheticMapsAccess.lCorridor() }, Random(7))

    @Test
    fun hypothesesAtTheStairsLandAtTheOtherEnd() {
        val c = controller()
        c.start("A", warm = WarmStart(listOf(LocZone(PointM(0.0, 1.0), 1.0, 1.0, 0.0)), 30.0))
        assertTrue(c.massNear(listOf(PointM(0.0, 0.0)), 7.0) > 0.6) // the barometer may change the floor here
        assertTrue(c.prepare("B"))
        assertTrue(c.changeFloor("B", stairs))
        assertEquals("B", c.floorId)
        assertEquals(1, c.floorChanges)
        assertTrue("near the landing: ${c.massNear(listOf(PointM(25.0, 20.0)), 3.5)}", c.massNear(listOf(PointM(25.0, 20.0)), 3.5) > 0.8)
    }

    @Test
    fun nobodyNearStairsMeansAFreshSearchAroundEveryLanding() {
        val c = controller()
        c.start("A", warm = WarmStart(listOf(LocZone(PointM(20.0, 20.0), 1.0, 1.0, 0.0)), 30.0))
        c.prepare("B")
        // Only the 30 % spread over the whole floor can be near the stairs: too few to trust.
        assertTrue(c.changeFloor("B", stairs, nearM = 1.0))
        assertTrue(c.massNear(listOf(PointM(25.0, 20.0)), 3.5) > 0.6)
    }

    @Test
    fun noMapForTheTargetFloorChangesNothing() {
        val c = LocalizationController({ id -> if (id == "A") SyntheticMapsAccess.lCorridor() else null }, Random(1))
        c.start("A")
        assertFalse(c.changeFloor("B", stairs))
        assertEquals("A", c.floorId)
    }
}
