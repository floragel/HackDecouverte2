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

    /** Pinned from the replay before TuningParams existed (A2 step 0). */
    @Test
    fun replayWithDefaultTuningIsUnchanged() {
        val site = IndoorSite.STANISLAS
        val r = WalkReplay(SyntheticWalk.stanislasNorthWestAndBack(site.origin), site.origin, WalkAnalysis.mapProvider(site), seed = 3, tuning = TuningParams()).run()
        assertEquals(60, r.steps.size)
        assertEquals(-4.370689849409958, r.steps.last().estimate!!.x, 1e-9)
        assertEquals(4.300096126843306, r.steps.last().estimate!!.y, 1e-9)
        assertEquals(0.8718040056618864, r.strideScale!!, 1e-9)
    }
}
