package com.hackdecouverte.pdr

import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.rec.SyntheticWalk
import com.hackdecouverte.rec.train.WalkEval
import com.hackdecouverte.tuning.PdrTuning
import com.hackdecouverte.tuning.TuningParams
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan B6 / review focus 2: a long magnetic disturbance must not turn the heading through the compass pull.
 * Corridor assist and the walking-axis correction also hold a straight synthetic walk (~7° at most without
 * the gate): both are off here so the compass path is measured alone (rooms, turns: they do not help there).
 */
class MagneticGateTest {
    private val site = IndoorSite.STANISLAS

    /** Bearing (deg, true north) of the PDR displacement between steps [from] and [to]. */
    private fun bearing(rec: com.hackdecouverte.rec.Recording, tuning: TuningParams, from: Int, to: Int): Double {
        val steps = WalkEval.pdrRun(rec, tuning.copy(pdr = tuning.pdr.copy(walkAxisCorrection = false)), corridorAssist = false).steps
        val a = steps[from].second
        val b = steps[to].second
        return Math.toDegrees(atan2(b.x - a.x, b.y - a.y))
    }

    private fun steelWalk() = SyntheticWalk.record(
        site.origin, "STAN", "STAN-1", PointM(0.0, -10.0), listOf(SyntheticWalk.Leg(0.0, 40)),
        compassOffsetDeg = { tS -> if (tS in 4.0..21.0) 50.0 else 0.0 },
        fieldMicroTesla = { tS -> if (tS in 4.0..21.0) 80.0 else 52.0 }
    )

    @Test
    fun aLongMagneticDisturbanceDoesNotTurnTheHeading() {
        val rec = steelWalk()
        val gated = bearing(rec, TuningParams(), 28, 34)
        val ungated = bearing(rec, TuningParams(pdr = PdrTuning(magGateTolerance = 10.0)), 28, 34)
        assertTrue("gated bearing $gated", abs(gated) < 5.0)
        assertTrue("ungated bearing $ungated", abs(ungated) > 25.0)
    }

    @Test
    fun aNormalFieldKeepsTheCompassPull() {
        val rec = SyntheticWalk.record(
            site.origin, "STAN", "STAN-1", PointM(0.0, -10.0), listOf(SyntheticWalk.Leg(0.0, 70)),
            gyroDriftDegPerMin = 20.0, fieldMicroTesla = { 52.0 }
        )
        val end = bearing(rec, TuningParams(), 50, 59)
        assertTrue("bearing at the end $end", abs(end) < 6.0)
    }
}
