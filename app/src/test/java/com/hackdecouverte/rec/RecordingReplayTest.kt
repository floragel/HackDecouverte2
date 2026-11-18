package com.hackdecouverte.rec

import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.map.MapTestSupport
import com.hackdecouverte.map.loadIndoorMap
import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Test

/**
 * Replays every walk recorded on a phone (`recordings/<walk>.txt.gz` at the repo root, or `-Dreplay=<file|dir>`)
 * and writes, per walk, `app/build/replay/<name>/`: report.txt (errors at each marker, per seed), steps.csv
 * and walk.svg. Also writes the synthetic corridor walk as a reference. Never fails on a recording:
 * it is a measuring tool, the assertions live in [WalkRecordingTest].
 *
 * `./gradlew :app:testDebugUnitTest --tests '*RecordingReplayTest*'`
 */
class RecordingReplayTest {

    private val seeds = 1..5

    private fun mapProvider(site: IndoorSite) = { floorId: String ->
        loadIndoorMap(FloorPlan.parse(MapTestSupport.open(site.floorPlanAsset(floorId))!!)) { MapTestSupport.open(it) }
    }

    private fun write(name: String, rec: Recording) {
        val site = IndoorSite.byId(rec.header.siteId)
        val runs = seeds.map { WalkReplay(rec, site.origin, mapProvider(site), seed = it).run() }
        val noGps = WalkReplay(rec, site.origin, mapProvider(site), seed = 1, useGps = false).run()
        val out = File("build/replay/$name").apply { mkdirs() }
        val report = ReplayOutput.report(name, rec, runs) +
            "\nwithout GPS (seed 1): found after ${noGps.firstFoundNs?.let { "%.0f s".format(java.util.Locale.ROOT, (it - rec.header.startedNs) / 1e9) } ?: "never"}, " +
            "end error ${noGps.markers.lastOrNull()?.onlineErrM?.let { "%.1f m".format(java.util.Locale.ROOT, it) } ?: "—"}\n"
        File(out, "report.txt").writeText(report)
        File(out, "steps.csv").writeText(ReplayOutput.stepsCsv(runs.first(), rec.header.startedNs))
        ReplayOutput.picture(rec, runs.first(), File(out, "walk.svg"))
        println(report)
    }

    private val sweep = System.getProperty("sweep") == "true"

    /** Walk with a traced route: PDR metrics and filter runs against it (A3), in truth-eval.txt. */
    private fun writeTruthEval(name: String, rec: Recording, route: com.hackdecouverte.rec.truth.TruthRoute) {
        val ds = com.hackdecouverte.rec.train.Datasets.of(name, rec, route, IndoorSite.byId(rec.header.siteId))
        val t = com.hackdecouverte.tuning.TuningParams()
        val pdr = com.hackdecouverte.rec.train.WalkEval.pdr(ds, t)
        val modes = listOf(com.hackdecouverte.rec.train.StartMode.KNOWN_START to 1..3, com.hackdecouverte.rec.train.StartMode.COLD_GPS to 1..3, com.hackdecouverte.rec.train.StartMode.COLD_NO_GPS to 1..2)
        val runs = com.hackdecouverte.rec.train.WalkEval.parallel(modes.flatMap { (m, seeds) -> seeds.map { s -> { com.hackdecouverte.rec.train.WalkEval.filter(ds, t, m, s) } } })
        val text = buildString {
            appendLine("# $name — against the traced route")
            appendLine("PDR: route %.2f m · walls %.1f /100 m · loop %s · counted/real %.2f".format(java.util.Locale.ROOT, pdr.routeDtwM, pdr.wallCrossingsPer100m, pdr.closurePct?.let { "%.1f %%".format(java.util.Locale.ROOT, it) } ?: "—", pdr.distanceRatio))
            for (r in runs) appendLine(r.toString())
        }
        File("build/replay/$name/truth-eval.txt").writeText(text)
        println(text)
    }

    @Test
    fun replayRecordedWalks() {
        write("synthetic-corridor", SyntheticWalk.stanislasNorthWestAndBack(IndoorSite.STANISLAS.origin))
        val long = SyntheticWalk.stanislasBackAndForth(IndoorSite.STANISLAS.origin)
        println(WalkAnalysis.write("synthetic-back-and-forth", long, File("build/replay/synthetic-back-and-forth"), sweep))
        val src = System.getProperty("replay")?.let(::File) ?: File("../recordings")
        val files = if (src.isDirectory) src.listFiles { f -> f.name.endsWith(".txt.gz") }.orEmpty().sorted() else listOfNotNull(src.takeIf { it.isFile })
        for (f in files) {
            val rec = runCatching { GZIPInputStream(f.inputStream()).bufferedReader().use(RecordingReader::read) }
                .onFailure { println("${f.name}: unreadable (${it.message})") }.getOrNull() ?: continue
            val name = f.name.removeSuffix(".txt.gz")
            write(name, rec)
            println(WalkAnalysis.write(name, rec, File("build/replay/$name"), sweep))
            com.hackdecouverte.rec.truth.TruthTrack.load(f)?.let { route -> writeTruthEval(name, rec, route) }
        }
    }
}
