package com.hackdecouverte.loc

import com.hackdecouverte.map.loadIndoorMap
import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The GPS / system position: a disk to start the search in (spec §6.4 case 3), then a tolerant weight (§6.3). */
class SystemPositionTest {

    private val mb2 by lazy { loadIndoorMap(2) { javaClass.classLoader!!.getResourceAsStream(it) }!! }

    @Test
    fun searchStartsMostlyInsideTheDiskWithSomeHypothesesElsewhere() {
        val center = mb2.grid.centerOf(mb2.freeCellIndices().let { it[it.size / 2] })
        val f = LocalizationFilter(mb2, random = Random(3))
        f.initAround(center, 15.0)
        assertFalse(f.offsetKnown) // phone angle still learned from the first straight line
        val pts = f.samplePositions(f.size())
        val inside = pts.count { it.distanceTo(center) <= 15.0 }.toDouble() / pts.size
        assertTrue("inside share $inside", inside in 0.85..0.95)
        assertTrue(pts.all { !mb2.grid.isBlocked(it) })
    }

    @Test
    fun diskWithNoFreeSpaceFallsBackToTheWholeFloor() {
        val f = LocalizationFilter(SyntheticMaps.lCorridor(), random = Random(5))
        f.initAround(PointM(500.0, 500.0), 20.0) // fix far outside the plan
        assertEquals(f.params.maxParticles, f.size())
        val pts = f.samplePositions(f.size())
        assertTrue(pts.any { it.y < 10 } && pts.any { it.x > 15 })
    }

    @Test
    fun systemPositionWeighsButNeverCuts() {
        val f = LocalizationFilter(SyntheticMaps.lCorridor(), random = Random(9))
        val a = PointM(0.0, 2.0); val b = PointM(22.0, 20.0) // 29 m apart
        f.initAt(List(500) { a } + List(500) { b }, List(1000) { 0.0 })
        f.onSystemPosition(PointM(1.0, 4.0), accuracyM = 8.0)
        val zones = f.estimate().zones
        assertTrue("main zone ${zones.first().centerM}", zones.first().centerM.distanceTo(a) < 1.0)
        val far = zones.firstOrNull { it.centerM.distanceTo(b) < 2.0 }
        assertNotNull("the far cloud must survive", far)
        assertTrue("far weight ${far!!.weight}", far.weight in 0.05..0.4)
    }

    @Test
    fun systemPositionKeepsTheTraceBack() {
        val map = SyntheticMaps.lCorridor()
        val random = Random(42)
        val f = LocalizationFilter(map, random = random)
        val pts = SyntheticMaps.uniformPoints(map, 2000, random)
        f.initAt(pts, List(pts.size) { 0.0 })
        repeat(29) { f.onStep(0.7, 0.0) }
        repeat(30) { f.onStep(0.7, Math.PI / 2) }
        repeat(5) { f.onSystemPosition(PointM(21.0, 20.0), 5.0) } // may resample without a step
        val path = f.tracePath()
        assertNotNull(path)
        assertTrue("start ${path!!.first()}", path.first().distanceTo(PointM(0.0, -0.3)) < 1.5)
    }
}
