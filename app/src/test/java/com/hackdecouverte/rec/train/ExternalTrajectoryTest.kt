package com.hackdecouverte.rec.train

import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.tuning.TuningParams
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Compares a trajectory computed outside the app (e.g. RoNIN, research only) with the PDR, on every
 * traced walk, with the same metrics: `-Dtraj=<dir>` holding `<walk>.<label>.csv` files (`t_ns,x,y`).
 * Writes build/replay/external-trajectories.txt.
 */
class ExternalTrajectoryTest {
    companion object {
        fun read(f: File): PdrRun {
            val rows = f.readLines().drop(1).filter { it.isNotBlank() }.map { l ->
                val p = l.split(',')
                p[0].toLong() to PointM(p[1].toDouble(), p[2].toDouble())
            }
            val dist = rows.zipWithNext().sumOf { (a, b) -> a.second.distanceTo(b.second) }
            // Origin at the first point, as the PDR (which starts at (0, 0)).
            val o = rows.first().second
            return PdrRun(rows.drop(1).map { (t, p) -> t to PointM(p.x - o.x, p.y - o.y) }, dist)
        }

        /** Position at a time, linear between rows; null outside the trajectory. */
        fun positionAt(run: PdrRun, tNs: Long): PointM? {
            val s = run.steps
            if (s.isEmpty() || tNs < s.first().first || tNs > s.last().first) return null
            val i = s.binarySearchBy(tNs) { it.first }.let { if (it >= 0) return s[it].second else -it - 1 }
            val (t0, a) = s[i - 1]; val (t1, b) = s[i]
            val k = (tNs - t0).toDouble() / (t1 - t0)
            return PointM(a.x + k * (b.x - a.x), a.y + k * (b.y - a.y))
        }
    }

    @Test
    fun readsATrajectoryFromTheOrigin() {
        val f = File.createTempFile("traj", ".csv").apply { writeText("t_ns,x,y\n1,2.0,3.0\n2,2.0,4.0\n3,5.0,8.0\n") }
        val r = read(f)
        assertEquals(listOf(PointM(0.0, 1.0), PointM(3.0, 5.0)), r.steps.map { it.second })
        assertEquals(6.0, r.distanceM, 1e-9)
    }

    @Test
    fun compare() {
        val dir = System.getProperty("traj")?.let(::File)
        assumeTrue("set -Dtraj=<dir>", dir != null && dir.isDirectory)
        val sb = StringBuilder("walk                     source        route DTW (m)  walls/100 m  loop %  distance / traced\n")
        for (ds in Datasets.loadAll()) {
            val rows = mutableListOf("PDR (defaults)" to WalkEval.pdr(ds, TuningParams()))
            dir!!.listFiles { f -> f.name.startsWith(ds.name + ".") && f.name.endsWith(".csv") }.orEmpty().sorted().forEach { f ->
                rows += f.name.removePrefix(ds.name + ".").removeSuffix(".csv") to WalkEval.trajectoryMetrics(ds, read(f))
            }
            for ((label, m) in rows) sb.append("%-24s %-13s %13.2f %12.1f %7s %18.2f\n".format(
                ds.name, label, m.routeDtwM, m.wallCrossingsPer100m, m.closurePct?.let { "%.1f".format(it) } ?: "—", m.distanceRatio))
        }
        // The same sources through the map filter (default settings): known start 3 seeds, cold 3 + 2.
        sb.append("\nThrough the map filter (defaults)\nwalk                     source        known med/p90 (m)  support lost  cold FOUND right  wrong FOUND  cold end err (m)\n")
        for (ds in Datasets.loadAll()) {
            val sources = mutableListOf<Triple<String, ((Long) -> PointM?)?, Boolean>>(Triple("PDR", null, false))
            dir!!.listFiles { f -> f.name.startsWith(ds.name + ".") && f.name.endsWith(".csv") }.orEmpty().sorted().forEach { f ->
                val run = read(f)
                sources += Triple(f.name.removePrefix(ds.name + ".").removeSuffix(".csv"), { t: Long -> positionAt(run, t) }, f.name.contains("+north"))
            }
            for ((label, ext, north) in sources) {
                val jobs = (1..3).map { s -> { WalkEval.filter(ds, TuningParams(), StartMode.KNOWN_START, s, ext, north) } } +
                    (1..3).map { s -> { WalkEval.filter(ds, TuningParams(), StartMode.COLD_GPS, s, ext, north) } } +
                    (1..2).map { s -> { WalkEval.filter(ds, TuningParams(), StartMode.COLD_NO_GPS, s, ext, north) } }
                val runs = WalkEval.parallel(jobs)
                val k = runs.filter { it.mode == StartMode.KNOWN_START }
                val c = runs.filter { it.mode != StartMode.KNOWN_START }
                fun f(v: Double?) = v?.let { "%.1f".format(it) } ?: "—"
                sb.append("%-24s %-13s %17s %13s %17s %12s %17s\n".format(
                    ds.name, label,
                    f(k.mapNotNull { it.trackErrMedianM }.takeIf { it.isNotEmpty() }?.average()) + " / " + f(k.mapNotNull { it.trackErrP90M }.takeIf { it.isNotEmpty() }?.average()),
                    "${k.count { it.supportLostAtS != null }}/3",
                    "${c.count { it.foundAfterM != null }}/5" + (c.mapNotNull { it.foundAfterM }.takeIf { it.isNotEmpty() }?.let { " (%.0f m)".format(it.average()) } ?: ""),
                    "%.0f %%".format(c.map { it.falseFoundShare }.average() * 100),
                    f(c.mapNotNull { it.endErrM }.average())))
            }
        }
        File("build/replay/external-trajectories.txt").writeText(sb.toString())
    }
}
