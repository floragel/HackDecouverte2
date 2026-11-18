package com.hackdecouverte.rec.train

import com.hackdecouverte.tuning.Tuning
import java.io.File
import java.time.LocalDate
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The training run (plan C2/C3/F2): `./gradlew :app:testDebugUnitTest --tests '*TrainModel*' -Dtrain=true`.
 * Starts from the shipped `params.json`, trains on every walk of `recordings/` with a traced route, writes the
 * report to `docs/training/` and, unless the gate says REJECT, the new `params.json`.
 */
class TrainModel {
    @Test
    fun train() {
        assumeTrue("set -Dtrain=true", System.getProperty("train") == "true")
        val datasets = Datasets.loadAll()
        assumeTrue("no walk with a traced route in recordings/", datasets.isNotEmpty())
        val paramsFile = File("src/main/assets/tuning/params.json")
        val start = Tuning.load { if (it == Tuning.ASSET && paramsFile.exists()) paramsFile.inputStream() else null }
        val few = datasets.size < 3
        val t0 = System.nanoTime()
        val cv = if (few) {
            Trainer.crossValidate(datasets, start, Trainer.GENERATIONS_A, Trainer.GENERATIONS_B)
        } else {
            Trainer.crossValidate(datasets, start)
        }
        val decision = Trainer.gate(cv)
        val trained = when {
            decision.gate == Gate.REJECT -> start
            few -> cv.trainedOnAll!!
            else -> Trainer.trainBoth(datasets, start, Trainer.GENERATIONS_A, Trainer.GENERATIONS_B)
        }
        val final = if (decision.gate == Gate.REJECT) start else trained.copy(
            version = start.version + 1,
            provisional = decision.gate == Gate.PROVISIONAL,
            trainedOn = datasets.map { it.name }
        )
        if (decision.gate != Gate.REJECT) Trainer.writeParams(final, paramsFile)
        val dir = File("../docs/training")
        dir.mkdirs()
        val day = LocalDate.now().toString()
        var n = 1
        while (File(dir, "$day-$n-report.md").exists()) n++
        val report = File(dir, "$day-$n-report.md")
        Trainer.writeReport(cv, decision, final, report, start)
        report.appendText("\nDurée : %.0f min · %d marche(s)\n".format((System.nanoTime() - t0) / 6e10, datasets.size))
        println("TRAIN ${decision.gate} → ${report.path}")
    }
}
