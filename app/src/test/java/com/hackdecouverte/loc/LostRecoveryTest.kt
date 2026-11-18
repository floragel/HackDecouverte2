package com.hackdecouverte.loc

import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.tuning.TuningParams
import com.hackdecouverte.tuning.locParams
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan B5: recovery around the last confident zone. Measured on synthetic heading jumps: no gain
 * (< 0.5 m either way) and, walking into a wall, the "last confident" zone follows the first wrong
 * steps — so it is off by default and only the training may turn it on.
 */
class LostRecoveryTest {

    @Test
    fun recoveryIsOffByDefaultAndTunable() {
        assertEquals(0.0, LocParams().lostRecoveryShare, 0.0)
        assertEquals(0.0, TuningParams().locParams().lostRecoveryShare, 0.0)
        assertEquals(0.4, TuningParams(filter = com.hackdecouverte.tuning.FilterTuning(lostRecoveryShare = 0.4)).locParams().lostRecoveryShare, 0.0)
    }

    @Test
    fun offMeansTheFilterBehavesAsBefore() {
        fun run(share: Double?): List<PointM> {
            val p = if (share == null) LocParams(strideScaleMin = 1.0, strideScaleMax = 1.0) else LocParams(strideScaleMin = 1.0, strideScaleMax = 1.0, lostRecoveryShare = share)
            val f = LocalizationFilter(SyntheticMaps.lCorridor(), p, Random(7))
            f.initAt(List(500) { PointM(0.0, 0.0) }, List(500) { 0.0 })
            repeat(15) { f.onStep(0.7, 0.0) }
            repeat(6) { f.onStep(1.5, Math.PI / 2) }
            return f.sampleParticles(f.size()).map { it.positionM }
        }
        assertEquals(run(null), run(0.0))
    }
}
