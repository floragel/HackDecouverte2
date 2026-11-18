package com.hackdecouverte.rec.train

import com.hackdecouverte.rec.RecEvent
import com.hackdecouverte.rec.WalkAnalysis
import com.hackdecouverte.tuning.Tuning
import java.io.File
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Plan F2: the acceptance table on the walks held out by the cross-validation (settings trained on the
 * others), plus the device measurements the phone wrote into the recordings (`perf`, `health` notes).
 * `./gradlew :app:testDebugUnitTest --tests '*AcceptanceTest*' -Daccept=true`. Fails on every unmet
 * criterion, naming it.
 */
class AcceptanceTest {
    private fun pocket(ds: Dataset): Boolean =
        ds.route.labels.any { it.label.equals("poche", true) } ||
            ds.recording.events.any { it is RecEvent.Note && it.name == "label" && it.detail.contains("poche", true) }

    @Test
    fun acceptance() {
        assumeTrue("set -Daccept=true", System.getProperty("accept") == "true")
        val datasets = Datasets.loadAll()
        assumeTrue("at least 3 traced walks are needed", datasets.size >= 3)
        val paramsFile = File("src/main/assets/tuning/params.json")
        val start = Tuning.load { if (it == Tuning.ASSET && paramsFile.exists()) paramsFile.inputStream() else null }
        val cv = Trainer.crossValidate(datasets, start)
        val failures = ArrayList<String>()
        fun check(ok: Boolean, what: String) { if (!ok) failures += what }

        for (h in cv.perWalk) {
            val ds = datasets.first { it.name == h.walk }
            val inPocket = pocket(ds)
            val known = h.runs.filter { it.mode == StartMode.KNOWN_START }
            val median = known.mapNotNull { it.trackErrMedianM }.average()
            val p90 = known.mapNotNull { it.trackErrP90M }.average()
            check(h.pdr.routeDtwM <= if (inPocket) 2.0 else 1.5, "${h.walk}: PDR route %.2f m".format(h.pdr.routeDtwM))
            h.pdr.closurePct?.let { check(it <= 5.0, "${h.walk}: loop closure %.1f %%".format(it)) }
            check(h.pdr.wallCrossingsPer100m <= 1.0, "${h.walk}: %.1f walls per 100 m".format(h.pdr.wallCrossingsPer100m))
            check(!median.isNaN() && median <= if (inPocket) 2.5 else 1.5, "${h.walk}: known-start median %.2f m".format(median))
            check(!p90.isNaN() && p90 <= if (inPocket) 5.0 else 3.0, "${h.walk}: known-start p90 %.2f m".format(p90))
            val cold = h.runs.filter { it.mode != StartMode.KNOWN_START }
            val foundSteps = cold.map { it.falseFoundShare }.average()
            check(foundSteps <= 0.05, "${h.walk}: wrong FOUND %.0f %%".format(foundSteps * 100))
            val ends = cold.filter { it.foundAfterM != null }.mapNotNull { it.endErrM }
            check(ends.all { it <= 5.0 }, "${h.walk}: end error once found ${ends.joinToString { "%.1f".format(it) }} m")
        }
        val supportKept = cv.perWalk.count { h -> h.runs.filter { it.mode == StartMode.KNOWN_START }.all { it.supportLostAtS == null } }
        check(supportKept * 3 >= 2 * cv.perWalk.size, "support kept on $supportKept / ${cv.perWalk.size} walks (≥ 2/3)")
        val foundAfter = cv.perWalk.flatMap { h -> h.runs.filter { it.mode == StartMode.COLD_GPS }.map { it.foundAfterM ?: Double.POSITIVE_INFINITY } }.sorted()
        val foundMedian = foundAfter.getOrNull(foundAfter.size / 2) ?: Double.POSITIVE_INFINITY
        check(foundMedian <= 40.0, "distance before a right FOUND with GPS: median %.0f m".format(foundMedian))

        // Device: what the phone measured (perf notes every 50 steps, health once a minute).
        val notes = datasets.flatMap { it.recording.events.filterIsInstance<RecEvent.Note>() }
        val perf = notes.filter { it.name == "perf" }.map { n -> n.detail.split(' ').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1].toDoubleOrNull() } }.toMap() }
        if (perf.isEmpty()) failures += "device: no perf note in the walks (record one walk with this version)"
        perf.mapNotNull { it["mapBuildMs"] }.filter { it > 0 }.maxOrNull()?.let { check(it <= 3000, "device: map ready in %.0f ms".format(it)) }
        perf.mapNotNull { it["stepMsP95"] }.maxOrNull()?.let { check(it <= 15.0, "device: step time p95 %.1f ms".format(it)) }
        val long = datasets.map { WalkAnalysis.health(it.recording) }.filter { it.minutes >= 25 }
        if (long.isEmpty()) failures += "device: no recording of 25 min or more (battery / heat, plan E3)"
        for (h in long) {
            h.batteryPerHour?.let { check(it <= 5.0, "device: battery %.1f %%/h".format(it)) }
            h.thermalMax?.let { check(it <= 1, "device: thermal status $it") }
        }
        if (failures.isNotEmpty()) fail("Acceptance not met:\n" + failures.joinToString("\n") { "- $it" })
    }
}
