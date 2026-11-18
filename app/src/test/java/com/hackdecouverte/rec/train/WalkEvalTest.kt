package com.hackdecouverte.rec.train

import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.rec.SyntheticWalk
import com.hackdecouverte.rec.truth.TruthRoute
import com.hackdecouverte.tuning.TuningParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkEvalTest {
    companion object {
        /** The synthetic corridor walk with its exact route, as a dataset. */
        internal fun syntheticDataset(walk: SyntheticWalk.Walk = SyntheticWalk.stanislasBackAndForthWalk(IndoorSite.STANISLAS.origin)): Dataset =
            Datasets.of("synthetic", walk.recording, TruthRoute("STAN-1", walk.routePoints(), emptyList(), emptyList()), IndoorSite.STANISLAS)
    }

    @Test
    fun perfectSyntheticPdrScoresNearZero() {
        val m = WalkEval.pdr(syntheticDataset(), TuningParams())
        assertTrue("route ${m.routeDtwM}", m.routeDtwM < 0.5)
        assertEquals(0.0, m.wallCrossingsPer100m, 0.0)
        assertTrue("ratio ${m.distanceRatio}", m.distanceRatio in 0.95..1.05)
    }

    @Test
    fun knownStartTracksTheSyntheticWalk() {
        val r = WalkEval.filter(syntheticDataset(), TuningParams(), StartMode.KNOWN_START, seed = 1)
        assertTrue("median ${r.trackErrMedianM}", r.trackErrMedianM!! < 1.5)
        assertNull(r.supportLostAtS)
    }

    @Test
    fun coldStartNeverClaimsAWrongPlace() =
        assertEquals(0.0, WalkEval.filter(syntheticDataset(), TuningParams(), StartMode.COLD_GPS, 1).falseFoundShare, 0.0)

    @Test
    fun parallelRunsGiveTheSameNumbersAsSequential() {
        val ds = syntheticDataset()
        val jobs = (1..4).map { seed -> { WalkEval.filter(ds, TuningParams(), StartMode.KNOWN_START, seed) } }
        assertEquals(jobs.map { it() }, WalkEval.parallel(jobs))
    }
}
