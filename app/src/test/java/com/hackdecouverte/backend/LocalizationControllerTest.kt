package com.hackdecouverte.backend

import com.hackdecouverte.loc.LocState
import com.hackdecouverte.loc.SyntheticMapsAccess
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PdrState
import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LocalizationControllerTest {

    private val lMap = SyntheticMapsAccess.lCorridor()
    private fun controller() = LocalizationController({ id -> if (id == "SYN") lMap else null }, Random(11))
    private fun tracking(x: Double, y: Double, steps: Int) = PdrState(phase = PdrPhase.TRACKING, position = PointM(x, y), stepCount = steps)

    @Test
    fun successiveDisplacementsMoveTheParticles() {
        val c = controller()
        assertTrue(c.start("SYN"))
        c.onPdrState(tracking(0.0, 0.0, 0))
        val before = c.samplePositions(50)
        c.onPdrState(tracking(0.0, 0.7, 1))
        c.onPdrState(tracking(0.0, 1.4, 2))
        repeat(4) { k -> c.onPdrState(tracking(0.0, 2.1 + 0.7 * k, 3 + k)) } // straight line: phone angle learned, steps replayed
        assertNotEquals(before, c.samplePositions(50))
        assertTrue(c.estimate != null)
    }

    @Test
    fun jumpOverThreeMetresDoesNotMoveParticles() {
        val c = controller()
        c.start("SYN")
        repeat(6) { k -> c.onPdrState(tracking(0.0, 0.7 * k, k)) }
        val before = c.samplePositions(2000)
        c.onPdrState(tracking(0.0, 9.0, 7)) // 4.8 m in one update: trace reset or merged burst
        assertEquals(before, c.samplePositions(2000))
        c.onPdrState(tracking(0.0, 9.0, 7)) // zero displacement
        assertEquals(before, c.samplePositions(2000))
    }

    @Test
    fun nonTrackingStatesAreIgnored() {
        val c = controller()
        c.start("SYN")
        val before = c.samplePositions(2000)
        c.onPdrState(PdrState(phase = PdrPhase.IDLE, position = PointM(0.0, 0.0)))
        c.onPdrState(PdrState(phase = PdrPhase.IDLE, position = PointM(0.0, 2.0)))
        assertEquals(before, c.samplePositions(2000))
        assertEquals(LocState.SEARCHING, c.estimate!!.state)
    }

    @Test
    fun unknownFloorDoesNotStart() {
        val c = controller()
        assertFalse(c.start("MB-99"))
        assertTrue(c.estimate == null)
    }
}
