package com.hackdecouverte.loc

import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StartHeadingTest {

    @Test
    fun lCorridorConvergesWithUnknownOffset() {
        // Step length known here: with one turn and an unknown start it cannot be observed (see ZeeLearningTest)
        val f = LocalizationFilter(SyntheticMaps.lCorridor(), LocParams(strideScaleMin = 1.0, strideScaleMax = 1.0), Random(42))
        f.initUniform()
        assertFalse(f.offsetKnown)
        val pdrOffset = Math.toRadians(-73.0) // the PDR frame is not aligned with the plan
        repeat(29) { f.onStep(0.7, 0.0 + pdrOffset) }
        assertTrue(f.offsetKnown)
        repeat(30) { f.onStep(0.7, Math.PI / 2 + pdrOffset) }
        val main = f.estimate().zones.first()
        assertTrue("main zone at ${main.centerM}", main.centerM.distanceTo(PointM(21.0, 20.0)) < 2.0)
    }

    @Test
    fun noStraightLineFallsBackAfter20Steps() {
        val f = LocalizationFilter(SyntheticMaps.lCorridor(), random = Random(9))
        f.initUniform()
        repeat(19) { k -> f.onStep(0.7, if (k % 2 == 0) 0.4 else -0.4) }
        assertFalse(f.offsetKnown)
        f.onStep(0.7, 0.4)
        assertTrue(f.offsetKnown)
    }

    @Test
    fun explicitHypothesesHaveKnownOffset() {
        val f = LocalizationFilter(SyntheticMaps.lCorridor(), random = Random(1))
        f.initAt(listOf(PointM(0.0, 0.0)), listOf(0.0))
        assertTrue(f.offsetKnown)
    }
}
