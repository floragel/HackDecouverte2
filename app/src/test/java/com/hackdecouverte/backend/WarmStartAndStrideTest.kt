package com.hackdecouverte.backend

import com.hackdecouverte.loc.LocParams
import com.hackdecouverte.loc.LocZone
import com.hackdecouverte.loc.LocalizationFilter
import com.hackdecouverte.loc.SyntheticMapsAccess
import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WarmStartAndStrideTest {

    @Test
    fun disksGetTheirShareOfHypotheses() {
        val f = LocalizationFilter(SyntheticMapsAccess.lCorridor(), random = Random(2))
        f.initDisks(listOf(LocalizationFilter.Disk(PointM(0.0, 2.0), 1.5, 0.5), LocalizationFilter.Disk(PointM(20.0, 20.0), 1.5, 0.3)))
        val ps = f.sampleParticles(f.size())
        val a = ps.count { it.positionM.distanceTo(PointM(0.0, 2.0)) <= 1.6 } / ps.size.toDouble()
        val b = ps.count { it.positionM.distanceTo(PointM(20.0, 20.0)) <= 1.6 } / ps.size.toDouble()
        assertEquals(0.5, a, 0.05)
        assertEquals(0.3, b, 0.05)
    }

    @Test
    fun warmStartTrustsRecentZonesMoreAndForgetsOldOnes() {
        val zones = listOf(LocZone(PointM(0.0, 5.0), 1.0, 0.8, 0.0), LocZone(PointM(15.0, 20.0), 2.0, 0.2, 0.0))
        val fresh = LocalizationController.warmDisks(WarmStart(zones, 30.0))
        assertEquals(0.7, fresh.sumOf { it.share }, 1e-9)
        assertEquals(0.7 * 0.8, fresh[0].share, 1e-9)
        assertEquals(1.0 + LocalizationController.WARM_BASE_RADIUS_M + 0.5, fresh[0].radiusM, 1e-9)
        assertEquals(0.3, LocalizationController.warmDisks(WarmStart(zones, 1500.0)).sumOf { it.share }, 1e-9)
        assertTrue(LocalizationController.warmDisks(WarmStart(zones, 3600.0)).isEmpty())
    }

    @Test
    fun aWarmStartIsNotThrownAwayByTheFirstGpsFix() {
        val c = LocalizationController({ SyntheticMapsAccess.lCorridor() }, Random(5))
        c.start("L", fix = null, warm = WarmStart(listOf(LocZone(PointM(0.0, 5.0), 1.0, 1.0, 0.0)), 60.0))
        assertTrue(c.warmStarted)
        c.onSystemPosition(SystemFix(PointM(20.0, 20.0), 5.0, 1L)) // a GPS fix far away within the first steps
        val near = c.sampleParticles(2000).count { it.positionM.distanceTo(PointM(0.0, 5.0)) < 6.0 }
        assertTrue("$near hypotheses still near the last zone", near > 600)
    }

    @Test
    fun strideMemoryNarrowsThePriorAroundWhatWasLearned() {
        val none = StrideMemory()
        assertEquals(StrideMemory.DEFAULT_PRIOR, none.prior(0.74))
        val once = none.learn(pdrStepM = 0.74, scale = 0.92, newRun = true)
        assertEquals(0.74 * 0.92, once.learnedStepM!!, 1e-9)
        assertEquals(1, once.sessions)
        // Next session the PDR starts with the learned step: the prior is centred on 1, ±12 %.
        val (lo, hi) = once.prior(0.74 * 0.92)
        assertEquals(0.88, lo, 1e-9)
        assertEquals(1.12, hi, 1e-9)
        // Same session, PDR still on its old step: the prior is centred on the learned ratio.
        assertEquals(0.92, once.prior(0.74).let { (a, b) -> (a + b) / 2 }, 1e-9)
        val twice = once.learn(0.74 * 0.92, 1.02, newRun = true)
        assertEquals(2, twice.sessions)
        assertEquals(0.16, twice.prior(twice.learnedStepM).let { (a, b) -> b - a }, 1e-9)
        // Far from what was remembered (someone else walking): back to a first session.
        assertEquals(1, twice.learn(0.74, 1.3, newRun = true).sessions)
        // A refinement within the same search does not count as a new session.
        assertEquals(2, twice.learn(0.7, 1.0, newRun = false).sessions)
    }

    @Test
    fun aNarrowPriorReachesTheFilter() {
        val c = LocalizationController({ SyntheticMapsAccess.lCorridor() }, Random(1), LocParams(strideScaleMin = 0.95, strideScaleMax = 1.05))
        c.start("L")
        assertTrue(c.debug!!.strideSpread < 0.04)
        assertNull(null)
    }
}
