package com.hackdecouverte.rec

import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.pdr.PointM
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkAnalysisTest {
    private val site = IndoorSite.STANISLAS

    @Test
    fun theTracedWalkIsAGoodPseudoTruthAndSegmentsAreMeasuredAgainstIt() {
        val rec = SyntheticWalk.stanislasBackAndForth(site.origin)
        val full = WalkAnalysis.full(rec, site)
        val truth = full.tracedWalk
        assertTrue("pseudo truth has ${truth.size} points", truth.size > 100)
        // Markers are the real truth here. This walk turns 1–2 m short of the corridor ends, so nothing pins
        // the stride (it settles ~8 % short) and the traced walk slides ~2.5 m along the corridor: on a real
        // walk, touching the ends and going through doors is what makes the pseudo truth sharp.
        for (m in rec.markers.drop(1)) {
            val p = WalkAnalysis.truthAt(truth, m.tNs) ?: continue
            assertTrue("M${m.id}: ${p.distanceTo(PointM(m.eastM, m.northM))} m", p.distanceTo(PointM(m.eastM, m.northM)) < 3.5)
        }
        val segs = WalkAnalysis.segments(rec, site, truth, windowS = 60.0, strideS = 30.0, seeds = 1..2, withoutGps = false)
        assertTrue(segs.size >= 4)
        assertTrue("no segment measured", segs.any { it.approxSteps > 0 || it.measuredSteps > 0 })
    }

    @Test
    fun aWindowStartsColdAtItsOwnStart() {
        val rec = SyntheticWalk.stanislasBackAndForth(site.origin)
        val t0 = rec.header.startedNs + 40_000_000_000L
        val r = WalkReplay(rec, site.origin, WalkAnalysis.mapProvider(site), windowStartNs = t0, windowEndNs = t0 + 30_000_000_000L).run()
        assertTrue(r.steps.isNotEmpty())
        assertTrue(r.steps.all { it.tNs >= t0 && it.tNs <= t0 + 30_000_000_000L })
        assertTrue(r.steps.first().step <= 3) // a fresh PDR, not the whole walk's count
    }
}
