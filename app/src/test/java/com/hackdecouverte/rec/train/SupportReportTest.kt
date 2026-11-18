package com.hackdecouverte.rec.train

import com.hackdecouverte.tuning.TuningParams
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Per-step support of the filter against the traced route (plan B1): where does it lose the walker? */
class SupportReportTest {

    @Test
    fun syntheticWalkNeverLosesSupport() {
        val rows = WalkEval.support(WalkEvalTest.syntheticDataset(), TuningParams(), StartMode.KNOWN_START, 1)
        assertTrue(rows.size > 100)
        val worst = rows.minOf { it.support }
        assertTrue("worst support $worst", worst >= 0.1) // never lost; ~0.3 at worst from the stride spread along the corridor
    }

    @Test
    fun writesReportsForRealWalks() {
        val all = Datasets.loadAll()
        assumeTrue(all.isNotEmpty())
        for (ds in all) {
            val rows = WalkEval.support(ds, TuningParams(), StartMode.KNOWN_START, 1)
            val text = buildString {
                appendLine("# ${ds.name} — support (weight within 3 m of the truth), known start, seed 1")
                appendLine("t_s   arc_m  support  zone_err_m  state        door_m  label")
                for (r in rows) appendLine("%5.1f %6.1f  %6.3f   %6s     %-12s %5s  %s".format(Locale.ROOT, r.tS, r.arcM, r.support,
                    r.zoneErrM?.let { "%.1f".format(Locale.ROOT, it) } ?: "—", r.state ?: "—", r.nearestDoorM?.let { "%.1f".format(Locale.ROOT, it) } ?: "—", r.label ?: ""))
                appendLine()
                appendLine("Support lost (< 0.01 for 5 steps):")
                var run = 0
                for ((i, r) in rows.withIndex()) {
                    run = if (r.support < 0.01) run + 1 else 0
                    if (run == 5) {
                        appendLine("  at %.1f s (arc %.1f m); 5 steps before:".format(Locale.ROOT, rows[i - 4].tS, rows[i - 4].arcM))
                        for (k in maxOf(0, i - 9)..(i - 4)) appendLine("    %.1f s support %.3f door %s label %s".format(Locale.ROOT, rows[k].tS, rows[k].support, rows[k].nearestDoorM?.let { "%.1f".format(Locale.ROOT, it) } ?: "—", rows[k].label ?: ""))
                    }
                }
            }
            File("build/replay/${ds.name}").mkdirs()
            File("build/replay/${ds.name}/support.txt").writeText(text)
        }
    }
}
