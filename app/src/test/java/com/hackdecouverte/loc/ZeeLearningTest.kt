package com.hackdecouverte.loc

import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.random.Random

/** The two Zee mechanisms (MobiCom 2012, §7): step length learned by the walls, backward belief propagation. */
class ZeeLearningTest {

    // ---- step length

    @Test
    fun uniformStartSpreadsTheStepLengthOverItsPrior() {
        val f = LocalizationFilter(SyntheticMaps.lCorridor(), random = Random(4))
        f.initUniform()
        val p = f.params
        assertEquals((p.strideScaleMin + p.strideScaleMax) / 2, f.strideScaleEstimate(), 0.03)
        assertEquals((p.strideScaleMax - p.strideScaleMin) / Math.sqrt(12.0), f.strideScaleSpread(), 0.02) // uniform
    }

    @Test
    fun knownStartLearnsATooShortStepFromTheTurn() {
        // Zee's own example: start known, step length not. The PDR says 0.7 m, real steps are 0.84 m (×1.2).
        val f = LocalizationFilter(SyntheticMaps.lCorridor(), random = Random(8))
        f.initAt(List(2000) { PointM(0.0, 0.0) }, List(2000) { 0.0 }, spreadStride = true)
        repeat(24) { f.onStep(0.7, 0.0) }       // 20.2 m north for real
        repeat(20) { f.onStep(0.7, PI / 2) }    // then east: only the right step lengths fit the corner
        assertEquals(1.2, f.strideScaleEstimate(), 0.06)
        val main = f.estimate().zones.first()
        assertTrue("main zone at ${main.centerM}", main.centerM.distanceTo(PointM(16.8, 20.2)) < 1.5)
    }

    @Test
    fun unknownStartAndUnknownStepConvergeInAZCorridor() {
        val f = LocalizationFilter(SyntheticMaps.zCorridor(), random = Random(21))
        f.initUniform()
        val pdrOffset = Math.toRadians(35.0) // PDR frame not aligned with the plan either
        repeat(21) { f.onStep(0.7, 0.0 + pdrOffset) }      // north, real 0.84 m steps
        repeat(30) { f.onStep(0.7, PI / 2 + pdrOffset) }   // east, 25.2 m between the two corners
        repeat(12) { f.onStep(0.7, 0.0 + pdrOffset) }      // north again, 10 m
        val main = f.estimate().zones.first()
        assertTrue("main zone at ${main.centerM}", main.centerM.distanceTo(PointM(25.0, 30.0)) < 2.0)
        assertEquals(1.2, f.strideScaleEstimate(), 0.08)
    }

    // ---- backward belief propagation

    @Test
    fun tracedPathRecoversTheWholeWalkOnceFound() {
        val map = SyntheticMaps.lCorridor()
        val random = Random(42)
        val f = LocalizationFilter(map, random = random)
        val pts = SyntheticMaps.uniformPoints(map, 2000, random)
        f.initAt(pts, List(pts.size) { 0.0 })
        repeat(10) { f.onStep(0.7, 0.0) }
        val midWalkRadius = f.estimate().zones.first().radiusM
        repeat(19) { f.onStep(0.7, 0.0) }       // 20.3 m north in all, from (0, -0.3)
        repeat(30) { f.onStep(0.7, PI / 2) }    // 21 m east
        val path = f.tracePath()
        assertNotNull(path)
        path!!
        assertEquals(60, path.size) // the start + one point per step
        assertTrue("forward estimate after 10 steps was already precise ($midWalkRadius m)", midWalkRadius > 4.0)
        assertTrue("start ${path[0]}", path[0].distanceTo(PointM(0.0, -0.3)) < 1.5)
        assertTrue("step 10 ${path[10]}", path[10].distanceTo(PointM(0.0, 6.7)) < 1.5)
        assertTrue("corner ${path[29]}", path[29].distanceTo(PointM(0.0, 20.0)) < 1.5)
        assertTrue("now ${path[59]}", path[59].distanceTo(PointM(21.0, 20.0)) < 1.5)
    }

    @Test
    fun noTraceWhileStillSearching() {
        val f = LocalizationFilter(SyntheticMaps.lCorridor(), random = Random(2))
        f.initUniform()
        repeat(3) { f.onStep(0.7, 0.0) }
        assertNull(f.tracePath())
    }

    @Test
    fun historyIsCappedOnLongWalks() {
        val f = LocalizationFilter(SyntheticMaps.straightCorridor(), random = Random(6))
        f.initAt(List(300) { PointM(0.0, -9.0) }, List(300) { 0.0 })
        repeat(LocalizationFilter.MAX_HISTORY_STEPS + 50) { k -> f.onStep(0.05, if ((k / 200) % 2 == 0) 0.0 else PI) }
        assertTrue(f.historySize() <= LocalizationFilter.MAX_HISTORY_STEPS + 1)
    }
}
