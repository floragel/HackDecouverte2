package com.hackdecouverte.tuning

import com.hackdecouverte.loc.LocParams
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.map.MapTestSupport
import com.hackdecouverte.rec.SyntheticWalk
import com.hackdecouverte.rec.WalkAnalysis
import com.hackdecouverte.rec.WalkReplay
import org.junit.Assert.assertEquals
import org.junit.Test

class TuningParamsTest {

    @Test
    fun defaultsEqualTheCurrentConstants() {
        val t = TuningParams()
        assertEquals(LocParams(), t.locParams())
        assertEquals(8.0, t.pdr.compassPullTauS, 0.0)
        assertEquals(25.0, t.carry.startDeg, 0.0)
    }

    @Test
    fun missingOrBrokenFileGivesDefaults() {
        assertEquals(TuningParams(), Tuning.load { null })
        assertEquals(TuningParams(), Tuning.load { "{not json".byteInputStream() })
    }

    @Test
    fun unknownKeysAndPartialFilesAreAccepted() {
        val t = Tuning.parse("""{"version":1,"pdr":{"compassPullTauS":5.0},"future":{"x":1}}""")
        assertEquals(5.0, t.pdr.compassPullTauS, 0.0)
        assertEquals(PdrTuning().walkAxisTau, t.pdr.walkAxisTau, 0.0)
    }

    @Test
    fun shippedParamsFileParses() {
        assertEquals(TuningParams(), Tuning.load { MapTestSupport.open(it) })
    }

    /**
     * Going through TuningParams changes nothing: same replay as with the plain filter defaults.
     * (A2 first pinned the pre-A2 numbers — 60 steps, end (−4.3707, 4.3001), stride ×0.8718 — and
     * passed; phase B changes the defaults on purpose, so the invariant is now this equality.)
     */
    @Test
    fun replayWithDefaultTuningIsUnchanged() {
        val site = IndoorSite.STANISLAS
        val rec = SyntheticWalk.stanislasNorthWestAndBack(site.origin)
        val viaTuning = WalkReplay(rec, site.origin, WalkAnalysis.mapProvider(site), seed = 3, tuning = TuningParams()).run()
        val plain = WalkReplay(rec, site.origin, WalkAnalysis.mapProvider(site), seed = 3, params = LocParams()).run()
        assertEquals(plain.steps.size, viaTuning.steps.size)
        assertEquals(plain.steps.last().estimate!!.x, viaTuning.steps.last().estimate!!.x, 1e-12)
        assertEquals(plain.steps.last().estimate!!.y, viaTuning.steps.last().estimate!!.y, 1e-12)
        assertEquals(plain.strideScale!!, viaTuning.strideScale!!, 1e-12)
    }
}
