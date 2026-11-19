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
        File("build/replay/external-trajectories.txt").writeText(sb.toString())
    }
}
