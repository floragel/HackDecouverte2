package com.hackdecouverte.rec.train

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan C1: the optimizer the training runs on. */
class CmaesTest {

    private fun run(opt: Cmaes, generations: Int, f: (DoubleArray) -> Double): Cmaes {
        repeat(generations) {
            val xs = opt.ask()
            opt.tell(xs, xs.map(f))
        }
        return opt
    }

    @Test
    fun sphereConverges() {
        val opt = run(Cmaes(5, DoubleArray(5) { 0.5 }, 0.3), 150) { x -> x.sumOf { (it - 0.3) * (it - 0.3) } }
        assertTrue("best ${opt.bestLoss}", opt.bestLoss < 1e-6)
    }

    @Test
    fun rosenbrockConverges() {
        val opt = run(Cmaes(4, DoubleArray(4) { 0.5 }, 0.2), 600) { z ->
            val x = DoubleArray(4) { 4 * z[it] - 2 }
            (0 until 3).sumOf { i -> 100 * (x[i + 1] - x[i] * x[i]).let { it * it } + (1 - x[i]).let { it * it } }
        }
        assertTrue("best ${opt.bestLoss}", opt.bestLoss < 1e-3)
    }

    @Test
    fun sameSeedSameRun() {
        val f = { x: DoubleArray -> x.sumOf { (it - 0.7) * (it - 0.7) } + 0.1 * x[0] * x[1] }
        val a = run(Cmaes(3, DoubleArray(3) { 0.5 }, 0.3, seed = 1), 20, f)
        val b = run(Cmaes(3, DoubleArray(3) { 0.5 }, 0.3, seed = 1), 20, f)
        assertArrayEquals(a.bestX, b.bestX, 0.0)
        assertEquals(20, a.generation)
    }

    @Test
    fun boundsAreRespected() {
        val opt = Cmaes(2, DoubleArray(2) { 0.5 }, 0.3)
        repeat(80) {
            val xs = opt.ask()
            for (x in xs) for (v in x) assertTrue("asked $v", v in 0.0..1.0)
            opt.tell(xs, xs.map { x -> x.sumOf { (it - 1.2) * (it - 1.2) } })
        }
        for (v in opt.bestX) assertEquals(1.0, v, 0.02)
    }
}
