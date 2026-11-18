package com.hackdecouverte.rec.truth

import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.rec.SyntheticWalk
import com.hackdecouverte.rec.WalkAnalysis
import com.hackdecouverte.rec.WalkReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TruthTrackTest {
    private val site = IndoorSite.STANISLAS

    @Test
    fun loadsV1AndV2Files() {
        val v1 = TruthTrack.parse("""{"floor":"STAN-1","path":[[0,0],[1,2]],"note":"x"}""")
        assertEquals(2, v1.path.size)
        assertTrue(v1.checkpoints.isEmpty())
        val v2 = TruthTrack.parse("""{"version":2,"floor":"STAN-1","path":[[0,0],[3,4]],"checkpoints":[{"i":1,"tS":12.5}],"labels":[{"i":1,"label":"porte"}]}""")
        assertEquals(Checkpoint(1, 12.5), v2.checkpoints.single())
        assertEquals("porte", v2.labels.single().label)
        assertEquals("STAN-1", v2.floorId)
    }

    /** Route = start + leg ends; steps from a PDR replay; compares with where the walker really was at each step. */
    private fun alignmentErrors(walk: SyntheticWalk.Walk, checkpoints: List<Checkpoint> = emptyList()): List<Double> {
        val rec = walk.recording
        val replay = WalkReplay(rec, site.origin, WalkAnalysis.mapProvider(site), seed = 1).run()
        val steps = replay.steps.map { it.tNs to it.pdr }
        val route = TruthRoute(rec.header.floorId, walk.routePoints(), checkpoints, emptyList())
        val track = TruthTrack.align(route, steps, rec.header.startedNs)
        val truth = walk.truePositions()
        return truth.mapNotNull { (t, p) -> TruthTrack.positionAt(track, t)?.distanceTo(p) }
    }

    @Test
    fun alignmentRecoversTheTruthOfASyntheticWalk() {
        val errs = alignmentErrors(SyntheticWalk.stanislasBackAndForthWalk(site.origin)).sorted()
        assertTrue("only ${errs.size} steps compared", errs.size > 100)
        assertTrue("median ${errs[errs.size / 2]}", errs[errs.size / 2] <= 0.7)
        assertTrue("max ${errs.last()}", errs.last() <= 2.0)
    }

    @Test
    fun checkpointsPinAnOutAndBackWalk() {
        val walk = SyntheticWalk.stanislasBackAndForthWalk(site.origin)
        val farEnd = walk.truePositions()[43] // end of the first leg (44 steps)
        val tFar = (farEnd.first - walk.recording.header.startedNs) / 1e9
        val route = TruthRoute(walk.recording.header.floorId, walk.routePoints(), listOf(Checkpoint(1, tFar)), emptyList())
        val replay = WalkReplay(walk.recording, site.origin, WalkAnalysis.mapProvider(site), seed = 1).run()
        val track = TruthTrack.align(route, replay.steps.map { it.tNs to it.pdr }, walk.recording.header.startedNs)
        val p = TruthTrack.positionAt(track, farEnd.first)!!
        assertTrue("far end aligned ${p.distanceTo(farEnd.second)} m away", p.distanceTo(farEnd.second) <= 1.0)
    }

    @Test
    fun positionAtIsNullOutsideTheTrack() {
        val track = listOf(TruthPoint(1_000_000_000L, PointM(0.0, 0.0), 0.0), TruthPoint(2_000_000_000L, PointM(1.0, 0.0), 1.0))
        assertEquals(null, TruthTrack.positionAt(track, 500_000_000L))
        assertEquals(0.5, TruthTrack.positionAt(track, 1_500_000_000L)!!.x, 1e-9)
    }
}
