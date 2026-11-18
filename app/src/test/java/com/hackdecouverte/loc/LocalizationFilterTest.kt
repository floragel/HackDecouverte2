package com.hackdecouverte.loc

import com.hackdecouverte.map.loadIndoorMap
import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.random.Random

class LocalizationFilterTest {

    @Test
    fun walkingIntoWallLosesWeightToCorridorFollowers() {
        val f = LocalizationFilter(SyntheticMaps.straightCorridor(), random = Random(1))
        f.initAt(listOf(PointM(0.0, 0.0), PointM(0.0, 0.0)), listOf(0.0, PI / 2)) // north along the corridor / east into the wall
        repeat(3) { f.onStep(0.7, 0.0) }
        val main = f.estimate().zones.first()
        assertTrue("main zone at ${main.centerM}", main.centerM.distanceTo(PointM(0.0, 2.1)) < 0.5)
    }

    @Test
    fun lCorridorConvergesFromUniformStartWithKnownOffset() {
        val map = SyntheticMaps.lCorridor()
        val random = Random(42)
        val f = LocalizationFilter(map, random = random)
        val pts = SyntheticMaps.uniformPoints(map, 2000, random)
        f.initAt(pts, List(pts.size) { 0.0 })
        repeat(29) { f.onStep(0.7, 0.0) }
        repeat(30) { f.onStep(0.7, PI / 2) }
        val est = f.estimate()
        val main = est.zones.first()
        assertTrue("main zone at ${main.centerM}", main.centerM.distanceTo(PointM(21.0, 20.0)) < 2.0)
        assertTrue(est.zones.all { it.centerM.x.isFinite() && it.centerM.y.isFinite() && it.weight.isFinite() })
    }

    @Test
    fun allParticlesBlockedTriggersRecoveryNotEmptySet() {
        val map = SyntheticMaps.sealedRoom()
        val random = Random(7)
        val f = LocalizationFilter(map, random = random)
        val pts = List(500) { PointM(random.nextDouble(-1.0, 1.0), random.nextDouble(4.0, 5.5)) }
        f.initAt(pts, List(pts.size) { 0.0 })
        repeat(10) { f.onStep(0.7, 0.0) } // straight north through the room wall
        assertTrue(f.size() > 0)
        val est = f.estimate()
        assertTrue(est.zones.isNotEmpty())
        assertTrue(est.zones.all { it.weight.isFinite() && it.centerM.x.isFinite() })
        assertEquals(LocState.SEARCHING, est.state)
    }

    @Test
    fun standingStillOrTurningInPlaceDoesNotMoveOrCollapse() {
        val map = SyntheticMaps.lCorridor()
        val random = Random(3)
        val f = LocalizationFilter(map, random = random)
        val pts = SyntheticMaps.uniformPoints(map, 1000, random)
        f.initAt(pts, List(pts.size) { random.nextDouble(0.0, 2 * PI) })
        val before = f.samplePositions(f.size())
        val essBefore = f.effectiveSampleSize()
        f.onStep(0.0, 1.0)
        f.onStep(1e-9, 2.0)
        assertEquals(before, f.samplePositions(f.size()))
        assertEquals(essBefore, f.effectiveSampleSize(), 1e-9)
    }

    @Test
    fun stepWith2000ParticlesUnder20msOnJvm() {
        val map = loadIndoorMap(2) { javaClass.classLoader!!.getResourceAsStream(it) }!!
        val random = Random(5)
        val f = LocalizationFilter(map, random = random)
        val pts = SyntheticMaps.uniformPoints(map, 2000, random)
        f.initAt(pts, List(pts.size) { random.nextDouble(0.0, 2 * PI) })
        repeat(50) { f.onStep(0.7, random.nextDouble(0.0, 2 * PI)) } // warm-up
        val t0 = System.nanoTime()
        repeat(200) { f.onStep(0.7, random.nextDouble(0.0, 2 * PI)) }
        val ms = (System.nanoTime() - t0) / 1e6 / 200
        assertTrue("average step $ms ms", ms < 20.0)
    }
}
