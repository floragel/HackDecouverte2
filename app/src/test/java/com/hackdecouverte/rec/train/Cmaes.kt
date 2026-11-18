package com.hackdecouverte.rec.train

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * CMA-ES (Hansen, "The CMA Evolution Strategy: A Tutorial", 2016) over normalized coordinates in [0, 1]:
 * asked points outside the box are reflected back in, and the reflected points are what [tell] receives.
 * Pure Kotlin, deterministic for a given seed.
 */
class Cmaes(
    val dim: Int,
    x0: DoubleArray,
    sigma0: Double,
    val lambda: Int = 4 + (3 * ln(dim.toDouble())).toInt(),
    seed: Int = 1
) {
    private val n = dim
    private val mu = lambda / 2
    private val weights: DoubleArray
    private val mueff: Double
    private val cc: Double
    private val cs: Double
    private val c1: Double
    private val cmu: Double
    private val damps: Double
    private val chiN = sqrt(n.toDouble()) * (1 - 1.0 / (4 * n) + 1.0 / (21.0 * n * n))
    private val rng = Random(seed.toLong())

    private val mean = x0.copyOf()
    private var sigma = sigma0
    private val pc = DoubleArray(n)
    private val ps = DoubleArray(n)
    private val c = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
    private val b = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
    private val d = DoubleArray(n) { 1.0 }
    private var evalsSinceEigen = 0
    private val eigenEvery = max(1, lambda / (10 * n))

    var generation: Int = 0
        private set
    var bestX: DoubleArray = x0.copyOf()
        private set
    var bestLoss: Double = Double.POSITIVE_INFINITY
        private set

    init {
        require(x0.size == n && n >= 1 && lambda >= 2)
        val raw = DoubleArray(mu) { ln(mu + 0.5) - ln(it + 1.0) }
        val sum = raw.sum()
        weights = DoubleArray(mu) { raw[it] / sum }
        mueff = 1.0 / weights.sumOf { it * it }
        cc = (4 + mueff / n) / (n + 4 + 2 * mueff / n)
        cs = (mueff + 2) / (n + mueff + 5)
        c1 = 2 / ((n + 1.3).pow(2) + mueff)
        cmu = min(1 - c1, 2 * (mueff - 2 + 1 / mueff) / ((n + 2.0).pow(2) + mueff))
        damps = 1 + 2 * max(0.0, sqrt((mueff - 1) / (n + 1)) - 1) + cs
    }

    /** [lambda] candidates, every coordinate in [0, 1]. */
    fun ask(): List<DoubleArray> = List(lambda) {
        val z = DoubleArray(n) { rng.nextGaussian() }
        DoubleArray(n) { i ->
            var y = 0.0
            for (j in 0 until n) y += b[i][j] * d[j] * z[j]
            reflect(mean[i] + sigma * y)
        }
    }

    fun tell(xs: List<DoubleArray>, losses: List<Double>) {
        require(xs.size == losses.size && xs.size >= mu)
        val order = losses.indices.sortedBy { losses[it].takeIf { v -> v.isFinite() } ?: Double.MAX_VALUE }
        if (losses[order[0]] < bestLoss) {
            bestLoss = losses[order[0]]
            bestX = xs[order[0]].copyOf()
        }
        val old = mean.copyOf()
        for (i in 0 until n) mean[i] = (0 until mu).sumOf { k -> weights[k] * xs[order[k]][i] }
        val yw = DoubleArray(n) { (mean[it] - old[it]) / sigma }

        // C^{-1/2} · yw = B D⁻¹ Bᵀ yw
        val bty = DoubleArray(n) { j -> (0 until n).sumOf { i -> b[i][j] * yw[i] } / d[j] }
        val invSqrtY = DoubleArray(n) { i -> (0 until n).sumOf { j -> b[i][j] * bty[j] } }
        val csn = sqrt(cs * (2 - cs) * mueff)
        for (i in 0 until n) ps[i] = (1 - cs) * ps[i] + csn * invSqrtY[i]
        val psNorm = sqrt(ps.sumOf { it * it })
        val hsig = psNorm / sqrt(1 - (1 - cs).pow(2.0 * (generation + 1))) / chiN < 1.4 + 2.0 / (n + 1)
        val ccn = sqrt(cc * (2 - cc) * mueff)
        for (i in 0 until n) pc[i] = (1 - cc) * pc[i] + (if (hsig) ccn * yw[i] else 0.0)

        val ys = (0 until mu).map { k -> DoubleArray(n) { (xs[order[k]][it] - old[it]) / sigma } }
        val dh = if (hsig) 0.0 else cc * (2 - cc)
        for (i in 0 until n) for (j in 0..i) {
            var rankMu = 0.0
            for (k in 0 until mu) rankMu += weights[k] * ys[k][i] * ys[k][j]
            val v = (1 - c1 - cmu) * c[i][j] + c1 * (pc[i] * pc[j] + dh * c[i][j]) + cmu * rankMu
            c[i][j] = v
            c[j][i] = v
        }
        sigma *= exp((cs / damps) * (psNorm / chiN - 1))
        sigma = sigma.coerceIn(1e-12, 1.0)

        generation++
        evalsSinceEigen++
        if (evalsSinceEigen >= eigenEvery) {
            evalsSinceEigen = 0
            jacobiEigen()
        }
    }

    private fun reflect(v: Double): Double {
        var x = v
        repeat(8) {
            if (x > 1) x = 2 - x
            if (x < 0) x = -x
        }
        return x.coerceIn(0.0, 1.0)
    }

    /** C = B diag(d²) Bᵀ by cyclic Jacobi rotations. */
    private fun jacobiEigen() {
        val a = Array(n) { c[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        for (sweep in 0 until 60) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
            if (off < 1e-30) break
            for (p in 0 until n) for (q in p + 1 until n) {
                if (abs(a[p][q]) < 1e-300) continue
                val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val cos = 1 / sqrt(t * t + 1)
                val sin = t * cos
                for (k in 0 until n) {
                    val akp = a[k][p]
                    val akq = a[k][q]
                    a[k][p] = cos * akp - sin * akq
                    a[k][q] = sin * akp + cos * akq
                }
                for (k in 0 until n) {
                    val apk = a[p][k]
                    val aqk = a[q][k]
                    a[p][k] = cos * apk - sin * aqk
                    a[q][k] = sin * apk + cos * aqk
                }
                for (k in 0 until n) {
                    val vkp = v[k][p]
                    val vkq = v[k][q]
                    v[k][p] = cos * vkp - sin * vkq
                    v[k][q] = sin * vkp + cos * vkq
                }
            }
        }
        for (i in 0 until n) {
            d[i] = sqrt(max(a[i][i], 1e-20))
            for (j in 0 until n) b[i][j] = v[i][j]
        }
    }
}
