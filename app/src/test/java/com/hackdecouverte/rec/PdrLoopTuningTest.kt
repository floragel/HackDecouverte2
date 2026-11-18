package com.hackdecouverte.rec

import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.SensorStatus
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.sensor.SensorRouter
import com.hackdecouverte.backend.LocalizationController
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Loop walks (start = end, told by the walker): replays the PDR alone with variants and prints the
 * closure gap / distance. `-Dloop=<file.txt.gz>` (default: every walk in recordings/ named in LOOP_WALKS).
 */
class PdrLoopTuningTest {

    data class Out(val end: PointM, val distanceM: Double, val steps: Int)

    fun runPdr(rec: Recording, untilS: Double? = null, configure: (PdrTracker) -> Unit = {}): Out {
        val h = rec.header
        var now = h.startedNs
        val tracker = PdrTracker(clock = { now }, antiShakeEnabled = true, corridorAssistInitial = true).apply {
            useTrueNorthReference = true
            anyHoldHeading = true
        }
        tracker.onSensorStatus(SensorStatus(rotationAvailable = true, stepDetectorAvailable = true, fallbackCapable = true, permissionNeeded = false, permissionGranted = true))
        tracker.setStepSource(StepSource.HARDWARE)
        configure(tracker)
        val router = SensorRouter(tracker, { tracker.state.value.stepSource })
        router.magneticDeclinationDeg = h.declinationDeg
        val end = untilS?.let { h.startedNs + (it * 1e9).toLong() }
        for (e in rec.events) {
            if (end != null && e.tNs > end) break
            when (e) {
                is RecEvent.Sample -> if (e.pipeline) {
                    now = maxOf(now, e.tNs + e.lagUs * 1000)
                    router.onSample(e.type, e.tNs, e.values, e.accuracy)
                    if (LocalizationController.shouldAutoStartPdr(tracker.state.value, now - h.startedNs)) tracker.startWithManualStepLength(h.stepLengthM, h.cadenceHz)
                }
                is RecEvent.Note -> if (e.name == "screen") tracker.screenInteractive = e.detail == "on"
                else -> {}
            }
        }
        val s = tracker.state.value
        return Out(s.position, s.distanceM, s.stepCount)
    }

    @Test
    fun loopClosure() {
        val f = System.getProperty("loop")?.let(::File) ?: File("../recordings/STAN-1_20261118-150346.txt.gz")
        assumeTrue(f.isFile)
        val rec = GZIPInputStream(f.inputStream()).bufferedReader().use(RecordingReader::read)
        val variants: List<Pair<String, (PdrTracker) -> Unit>> = listOf(
            "current" to {},
            "walk-axis correction" to { t -> t.walkAxisCorrectionEnabled = true },
            "walk-axis tau 3" to { t -> t.walkAxisCorrectionEnabled = true; t.walkAxisTau = 3.0 },
            "walk-axis tau 6" to { t -> t.walkAxisCorrectionEnabled = true; t.walkAxisTau = 6.0 },
            "pivot shrink from 0.6 rad/s" to { t -> t.pivotTurnRateRadS = 0.6 },
            "no compass pull" to { t -> t.compassPullEnabled = false },
            "no corridor-axis snap" to { t -> t.setCorridorAssistEnabled(false) },
            "neither" to { t -> t.compassPullEnabled = false; t.setCorridorAssistEnabled(false) },
            "no hold bridging" to { t -> t.holdBridgingEnabled = false },
            "neither + no bridging" to { t -> t.compassPullEnabled = false; t.setCorridorAssistEnabled(false); t.holdBridgingEnabled = false },
        )
        val sb = StringBuilder("loop ${f.name}\n")
        for (until in listOf(118.0, 124.0, 140.0)) {
            val o = runPdr(rec, untilS = until)
            sb.append("current, walk cut at %.0f s: gap %.1f m · %.1f m · %d steps\n".format(Locale.ROOT, until, o.end.norm(), o.distanceM, o.steps))
        }
        for ((name, cfg) in variants) {
            val o = runPdr(rec, configure = cfg)
            val gap = o.end.norm()
            sb.append("%-26s gap %5.1f m · %5.1f m walked · %3d steps · %4.1f %%\n".format(Locale.ROOT, name, gap, o.distanceM, o.steps, 100 * gap / o.distanceM))
        }
        println(sb)
        File("build/replay").mkdirs()
        File("build/replay/loop-tuning.txt").writeText(sb.toString())
    }
}
