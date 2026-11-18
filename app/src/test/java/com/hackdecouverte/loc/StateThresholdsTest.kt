package com.hackdecouverte.loc

import com.hackdecouverte.pdr.PointM
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan B4: tunable state thresholds; a pocket's looser headings must not keep a right cloud in SEARCHING. */
class StateThresholdsTest {

    @Test
    fun thresholdsAreParameters() {
        val p = LocParams()
        assertTrue(p.foundWeight == 0.9 && p.foundRadiusM == 3.0 && p.approxWeight == 0.6 && p.approxRadiusM == 8.0)
        assertTrue(p.goodStepsToTrust == 10 && p.badStepLikelihood == 0.05)
    }

    @Test
    fun aCorrectCloudWithPocketNoiseReachesFound() {
        val rnd = Random(11)
        var found = -1
        val f = LocalizationFilter(SyntheticMaps.lCorridor(), LocParams(strideScaleMin = 1.0, strideScaleMax = 1.0), Random(3))
        f.initAt(List(1500) { PointM(0.0, 0.0) }, List(1500) { 0.0 })
        f.headingNoiseScale = 2.0
        for (k in 0 until 40) {
            val noise = Math.toRadians(8.0) * (rnd.nextDouble() * 2 - 1) * 1.7 // σ ≈ 8°
            f.onStep(0.7, (if (k < 28) 0.0 else Math.PI / 2) + noise)
            if (found < 0 && f.estimate().state == LocState.FOUND) found = k
        }
        assertTrue("first FOUND at step $found", found in 0..29)
    }

    @Test
    fun aSymmetricCorridorNeverClaimsFound() {
        val f = LocalizationFilter(SyntheticMaps.straightCorridor(), random = Random(4))
        f.initUniform()
        repeat(40) {
            f.onStep(0.7, 0.0)
            assertTrue(f.estimate().state != LocState.FOUND)
        }
    }
}
