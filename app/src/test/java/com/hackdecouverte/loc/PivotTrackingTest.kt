package com.hackdecouverte.loc

import com.hackdecouverte.pdr.PointM
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan B3: three spurious steps fired while pivoting must not make the filter lose a known walker. */
class PivotTrackingTest {

    private fun finalError(useQuality: Boolean, seed: Int): Double {
        val f = LocalizationFilter(SyntheticMaps.straightCorridor(), LocParams(strideScaleMin = 1.0, strideScaleMax = 1.0), Random(seed))
        f.initAt(List(1000) { PointM(0.0, 0.0) }, List(1000) { 0.0 })
        repeat(20) { f.onStep(0.7, 0.0) }                       // north to (0, 14)
        for (h in listOf(60.0, 120.0, 180.0)) {                  // pivot on the spot: 3 detector steps, heading sweeping
            f.stepQuality = if (useQuality) 0.2 else 1.0
            f.onStep(0.7, Math.toRadians(h))
        }
        f.stepQuality = 1.0
        repeat(15) { f.onStep(0.7, Math.PI) }                    // south: truth ends at (0, 3.5)
        return f.estimate().zones.first().centerM.distanceTo(PointM(0.0, 3.5))
    }

    @Test
    fun aPivotWithSpuriousStepsKeepsTheTrack() {
        val with = (1..3).map { finalError(true, it) }
        val without = (1..3).map { finalError(false, it) }
        assertTrue("with quality $with", with.all { it < 2.0 })
        assertTrue("with $with vs without $without", with.average() < without.average())
    }
}
