package com.hackdecouverte.rec.train

import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.rec.SyntheticWalk
import com.hackdecouverte.tuning.Tuning
import com.hackdecouverte.tuning.TuningParams
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan C2: the training moves what matters, leaves the rest, and the gate refuses what does not generalize. */
class TrainerTest {

    /**
     * Corridor assist hides a slow compass pull on a perfect synthetic corridor walk (lossA 0.11 at τ 2…40 s):
     * it is off here, the loss is lossA's formula otherwise (τ 40 s → 7.6, τ 8 s → 0.14).
     */
    @Test
    fun stageATrainingFixesADeliberatelyWrongSetting() {
        val ds = WalkEvalTest.syntheticDataset(SyntheticWalk.stanislasBackAndForthWalk(IndoorSite.STANISLAS.origin, gyroDriftDegPerMin = 15.0))
        val start = TuningParams().let { it.copy(pdr = it.pdr.copy(compassPullTauS = 40.0)) }
        val loss = { dss: List<Dataset>, t: TuningParams -> dss.map { Trainer.lossA(WalkEval.pdr(it, t, corridorAssist = false)) }.average() }
        val r = Trainer.train(listOf(ds), start, Knobs.STAGE_A, loss, generations = 8)
        assertTrue("loss ${r.lossDefault} → ${r.loss}", r.loss <= 0.7 * r.lossDefault)
        assertTrue("tau ${r.tuning.pdr.compassPullTauS}", r.tuning.pdr.compassPullTauS < 20.0)
    }

    @Test
    fun regularizationKeepsUselessKnobsAtTheirDefaults() {
        val useful = Knobs.STAGE_A[0]
        val useless = Knobs.STAGE_A[1]
        val target = 0.8
        val r = Trainer.train(emptyList(), TuningParams(), listOf(useful, useless), { _, t -> (useful.z(t) - target).let { it * it } * 10 }, generations = 40)
        assertEquals(useless.z(TuningParams()), useless.z(r.tuning), 0.05)
        assertEquals(target, useful.z(r.tuning), 0.05)
    }

    private fun held(name: String, before: Double, after: Double) =
        HeldOut(name, before, after, PdrMetrics(1.0, 0.0, null, 1.0), emptyList())

    @Test
    fun gateRefusesParamsWorseOnAHeldOutWalk() {
        val rows = listOf(held("a", 10.0, 6.0), held("b", 10.0, 6.0), held("c", 10.0, 12.0))
        val d = Trainer.gate(CvReport(rows, 10.0, 8.0))
        assertEquals(Gate.REJECT, d.gate)
        assertTrue(d.reasons.toString(), d.reasons.any { it.startsWith("c ") })
    }

    @Test
    fun gateAcceptsAClearGainOnEveryWalk() {
        val rows = listOf(held("a", 10.0, 8.0), held("b", 10.0, 8.5), held("c", 10.0, 10.5))
        assertEquals(Gate.ACCEPT, Trainer.gate(CvReport(rows, 10.0, 9.0)).gate)
    }

    @Test
    fun gateMarksFewWalksAsProvisional() {
        val rows = listOf(held("a", 10.0, 8.5), held("b", 10.0, 8.5))
        assertEquals(Gate.PROVISIONAL, Trainer.gate(CvReport(rows, 10.0, 8.5)).gate)
    }

    @Test
    fun writtenParamsLoadBackEqual() {
        val t = TuningParams(version = 3, provisional = true, trainedOn = listOf("w1")).let { it.copy(pdr = it.pdr.copy(compassPullTauS = 6.5)) }
        val f = File(Files.createTempDirectory("params").toFile(), "params.json")
        Trainer.writeParams(t, f)
        assertEquals(t, Tuning.parse(f.readText()))
    }
}
