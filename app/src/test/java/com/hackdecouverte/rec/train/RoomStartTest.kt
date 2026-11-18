package com.hackdecouverte.rec.train

import com.hackdecouverte.loc.LocParams
import com.hackdecouverte.loc.LocState
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.rec.SyntheticWalk
import com.hackdecouverte.rec.WalkAnalysis
import com.hackdecouverte.rec.WalkReplay
import com.hackdecouverte.rec.truth.TruthRoute
import com.hackdecouverte.rec.truth.TruthTrack
import com.hackdecouverte.tuning.TuningParams
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review focus 3: the first real walk started inside a room. In a single straight corridor the far
 * wall only says the stride cannot be longer, so a first session stays APPROXIMATE (≈6 m cloud along
 * the corridor) — but on the walker, and never wrongly FOUND. With a remembered stride it is FOUND.
 */
class RoomStartTest {
    private val site = IndoorSite.STANISLAS
    private val walk = SyntheticWalk.stanislasFromRoom(site.origin)
    private val ds = Datasets.of("room-start", walk.recording, TruthRoute("STAN-1", walk.routePoints(), emptyList(), emptyList()), site)

    @Test
    fun coldStartInsideARoomIsFoundAfterLeavingIt() {
        val runs = (1..3).map { WalkEval.filter(ds, TuningParams(), StartMode.COLD_NO_GPS, it) }
        assertTrue("false FOUND in $runs", runs.all { it.falseFoundShare == 0.0 })
        assertTrue("end errors ${runs.map { it.endErrM }}", runs.all { (it.endErrM ?: 99.0) <= 3.0 })
    }

    @Test
    fun withARememberedStrideItIsFound() {
        val found = (1..3).count { seed ->
            val r = WalkReplay(walk.recording, site.origin, WalkAnalysis.mapProvider(site), seed, useGps = false,
                params = LocParams(strideScaleMin = 0.92, strideScaleMax = 1.08)).run()
            r.steps.any { st -> st.state == LocState.FOUND && TruthTrack.positionAt(ds.track, st.tNs)?.let { q -> st.estimate!!.distanceTo(q) <= 5.0 } == true }
        }
        assertTrue("correct FOUND in $found of 3", found >= 2)
    }
}
