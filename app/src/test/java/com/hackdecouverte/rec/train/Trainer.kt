package com.hackdecouverte.rec.train

import com.hackdecouverte.tuning.Tuning
import com.hackdecouverte.tuning.TuningParams
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** One trainable setting, moved by the optimizer in a normalized coordinate z ∈ [0, 1]. */
data class Knob(
    val name: String, val min: Double, val max: Double, val log: Boolean,
    val get: (TuningParams) -> Double, val set: (TuningParams, Double) -> TuningParams
) {
    fun value(z: Double): Double {
        val c = z.coerceIn(0.0, 1.0)
        return if (log) exp(ln(min) + c * (ln(max) - ln(min))) else min + c * (max - min)
    }

    fun z(t: TuningParams): Double {
        val v = get(t).coerceIn(min, max)
        return if (log) (ln(v) - ln(min)) / (ln(max) - ln(min)) else (v - min) / (max - min)
    }
}

object Knobs {
    private fun pdr(name: String, min: Double, max: Double, log: Boolean, get: (TuningParams) -> Double, set: (TuningParams, Double) -> TuningParams) =
        Knob("pdr.$name", min, max, log, get, set)

    private fun filter(name: String, min: Double, max: Double, log: Boolean, get: (TuningParams) -> Double, set: (TuningParams, Double) -> TuningParams) =
        Knob("filter.$name", min, max, log, get, set)

    /** PDR alone (plan C2, stage A). */
    val STAGE_A: List<Knob> = listOf(
        pdr("compassPullTauS", 2.0, 40.0, true, { it.pdr.compassPullTauS }) { t, v -> t.copy(pdr = t.pdr.copy(compassPullTauS = v)) },
        pdr("compassPullMaxErrDeg", 20.0, 120.0, false, { it.pdr.compassPullMaxErrDeg }) { t, v -> t.copy(pdr = t.pdr.copy(compassPullMaxErrDeg = v)) },
        pdr("walkAxisTau", 1.5, 15.0, true, { it.pdr.walkAxisTau }) { t, v -> t.copy(pdr = t.pdr.copy(walkAxisTau = v)) },
        pdr("walkAxisMinRatio", 1.2, 4.0, false, { it.pdr.walkAxisMinRatio }) { t, v -> t.copy(pdr = t.pdr.copy(walkAxisMinRatio = v)) },
        pdr("strideWindowFactor", 0.5, 2.0, true, { it.pdr.strideWindowFactor }) { t, v -> t.copy(pdr = t.pdr.copy(strideWindowFactor = v)) },
        Knob("carry.swingOnDeg", 5.0, 15.0, false, { it.carry.swingOnDeg }) { t, v -> t.copy(carry = t.carry.copy(swingOnDeg = v, swingOffDeg = 0.67 * v)) },
        pdr("pivotTurnRateRadS", 0.4, 3.0, false, { it.pdr.pivotTurnRateRadS }) { t, v -> t.copy(pdr = t.pdr.copy(pivotTurnRateRadS = v)) },
        pdr("magGateTolerance", 0.05, 0.5, true, { it.pdr.magGateTolerance }) { t, v -> t.copy(pdr = t.pdr.copy(magGateTolerance = v)) }
    )

    /** The particle filter, PDR frozen (stage B). */
    val STAGE_B: List<Knob> = listOf(
        filter("headingNoiseDeg", 1.0, 10.0, true, { it.filter.headingNoiseDeg }) { t, v -> t.copy(filter = t.filter.copy(headingNoiseDeg = v)) },
        filter("bodyCarryNoiseScale", 1.0, 4.0, false, { it.filter.bodyCarryNoiseScale }) { t, v -> t.copy(filter = t.filter.copy(bodyCarryNoiseScale = v)) },
        filter("lengthNoise", 0.02, 0.15, true, { it.filter.lengthNoise }) { t, v -> t.copy(filter = t.filter.copy(lengthNoise = v)) },
        filter("offsetDriftDeg", 0.1, 3.0, true, { it.filter.offsetDriftDeg }) { t, v -> t.copy(filter = t.filter.copy(offsetDriftDeg = v)) },
        filter("scaleDrift", 0.001, 0.03, true, { it.filter.scaleDrift }) { t, v -> t.copy(filter = t.filter.copy(scaleDrift = v)) },
        filter("wallFactor", 0.002, 0.2, true, { it.filter.wallFactor }) { t, v -> t.copy(filter = t.filter.copy(wallFactor = v)) },
        filter("doorWallFactor", 0.05, 1.0, true, { it.filter.doorWallFactor }) { t, v -> t.copy(filter = t.filter.copy(doorWallFactor = v)) },
        filter("doorSoftRadiusM", 0.2, 1.2, false, { it.filter.doorSoftRadiusM }) { t, v -> t.copy(filter = t.filter.copy(doorSoftRadiusM = v)) },
        filter("beta", 0.0, 0.6, false, { it.filter.beta }) { t, v -> t.copy(filter = t.filter.copy(beta = v)) },
        filter("recoveryFraction", 0.0, 0.1, false, { it.filter.recoveryFraction }) { t, v -> t.copy(filter = t.filter.copy(recoveryFraction = v)) },
        filter("lostRecoveryShare", 0.0, 0.6, false, { it.filter.lostRecoveryShare }) { t, v -> t.copy(filter = t.filter.copy(lostRecoveryShare = v)) },
        filter("foundRadiusM", 2.0, 4.0, false, { it.filter.foundRadiusM }) { t, v -> t.copy(filter = t.filter.copy(foundRadiusM = v)) },
        // Rounded: every width is a map build (cached per width).
        filter("graphDoorWidthM", 0.8, 1.6, false, { it.filter.graphDoorWidthM }) { t, v -> t.copy(filter = t.filter.copy(graphDoorWidthM = (v * 10).roundToInt() / 10.0)) }
    )
}

data class StageResult(val tuning: TuningParams, val loss: Double, val lossDefault: Double, val generations: Int)

data class HeldOut(val walk: String, val lossDefault: Double, val lossTrained: Double, val pdr: PdrMetrics, val runs: List<FilterRun>)

/** [trainedOnAll]: with fewer than 3 walks the evaluated settings were trained on every walk (reused as the final ones). */
data class CvReport(val perWalk: List<HeldOut>, val meanDefault: Double, val meanTrained: Double, val trainedOnAll: TuningParams? = null)

enum class Gate { ACCEPT, PROVISIONAL, REJECT }

data class GateDecision(val gate: Gate, val reasons: List<String>)

/** Plan C2: the settings trained like a small model on the walks with a traced route. */
object Trainer {
    const val LAMBDA_A = 10
    const val GENERATIONS_A = 30
    const val LAMBDA_B = 11
    const val GENERATIONS_B = 20
    const val CV_GENERATIONS_A = 15
    const val CV_GENERATIONS_B = 12

    private val KNOWN_SEEDS = listOf(1, 2, 3)
    private val COLD_GPS_SEEDS = listOf(1, 2, 3)
    private val COLD_NO_GPS_SEEDS = listOf(1, 2)

    fun lossA(datasets: List<Dataset>, t: TuningParams): Double = datasets.map { ds -> lossA(WalkEval.pdr(ds, t)) }.average()

    fun lossA(m: PdrMetrics): Double = m.routeDtwM + 0.5 * m.wallCrossingsPer100m + 0.05 * (m.closurePct ?: 0.0)

    /** Every filter run of one walk (8 runs), sequential: the training parallelizes over candidates. */
    fun filterRuns(ds: Dataset, t: TuningParams): List<FilterRun> =
        KNOWN_SEEDS.map { WalkEval.filter(ds, t, StartMode.KNOWN_START, it) } +
            COLD_GPS_SEEDS.map { WalkEval.filter(ds, t, StartMode.COLD_GPS, it) } +
            COLD_NO_GPS_SEEDS.map { WalkEval.filter(ds, t, StartMode.COLD_NO_GPS, it) }

    fun lossB(runs: List<FilterRun>): Double {
        val known = runs.filter { it.mode == StartMode.KNOWN_START }
        val cold = runs.filter { it.mode != StartMode.KNOWN_START }
        val tracking = known.map { minOf(it.trackErrMedianM ?: 15.0, 15.0) + 0.5 * minOf(it.trackErrP90M ?: 15.0, 15.0) }.average()
        val coldLoss = cold.map { minOf(it.foundAfterM ?: 150.0, 150.0) / 50 + 20 * it.falseFoundShare + minOf(it.endErrM ?: 15.0, 15.0) / 5 }.average()
        return (if (known.isEmpty()) 0.0 else tracking) + (if (cold.isEmpty()) 0.0 else coldLoss)
    }

    fun lossB(datasets: List<Dataset>, t: TuningParams): Double = datasets.map { lossB(filterRuns(it, t)) }.average()

    private fun apply(start: TuningParams, knobs: List<Knob>, z: DoubleArray): TuningParams =
        knobs.indices.fold(start) { t, i -> knobs[i].set(t, knobs[i].value(z[i])) }

    /**
     * CMA-ES over [knobs] from [start]; the objective is [loss] + [regularization] · Σ (z − z_default)², the
     * defaults being [TuningParams]' own. Candidates of a generation are evaluated in parallel.
     */
    fun train(
        datasets: List<Dataset>, start: TuningParams, knobs: List<Knob>, loss: (List<Dataset>, TuningParams) -> Double,
        generations: Int, regularization: Double = 0.05, lambda: Int = if (knobs.size <= 8) LAMBDA_A else LAMBDA_B, seed: Int = 1
    ): StageResult {
        val defaults = TuningParams()
        val zDefault = DoubleArray(knobs.size) { knobs[it].z(defaults) }
        val z0 = DoubleArray(knobs.size) { knobs[it].z(start) }
        val lossStart = loss(datasets, start)
        val objective = { z: DoubleArray ->
            loss(datasets, apply(start, knobs, z)) + regularization * z.indices.sumOf { (z[it] - zDefault[it]).let { d -> d * d } }
        }
        val opt = Cmaes(knobs.size, z0, 0.2, lambda, seed)
        var bestZ = z0
        var best = lossStart + regularization * z0.indices.sumOf { (z0[it] - zDefault[it]).let { d -> d * d } }
        repeat(generations) {
            val xs = opt.ask()
            val ls = WalkEval.parallel(xs.map { x -> { objective(x) } })
            opt.tell(xs, ls)
            if (opt.bestLoss < best) {
                best = opt.bestLoss
                bestZ = opt.bestX
            }
        }
        val tuned = apply(start, knobs, bestZ)
        return StageResult(tuned, loss(datasets, tuned), lossStart, generations)
    }

    /** Both stages: PDR first, then the filter with the PDR frozen. */
    fun trainBoth(datasets: List<Dataset>, start: TuningParams, generationsA: Int, generationsB: Int): TuningParams {
        val a = train(datasets, start, Knobs.STAGE_A, ::lossA, generationsA)
        return train(datasets, a.tuning, Knobs.STAGE_B, ::lossB, generationsB).tuning
    }

    private fun totalLoss(ds: Dataset, t: TuningParams): Triple<Double, PdrMetrics, List<FilterRun>> {
        val m = WalkEval.pdr(ds, t)
        val runs = WalkEval.parallel(listOf({ filterRuns(ds, t) })).first()
        return Triple(lossA(m) + lossB(runs), m, runs)
    }

    /** Leave-one-walk-out (≥ 3 walks); with fewer, the training walks are also the evaluation (PROVISIONAL at best). */
    fun crossValidate(
        datasets: List<Dataset>, start: TuningParams,
        generationsA: Int = CV_GENERATIONS_A, generationsB: Int = CV_GENERATIONS_B
    ): CvReport {
        if (datasets.size >= 3) {
            val rows = datasets.map { held -> heldOut(held, start, trainBoth(datasets - held, start, generationsA, generationsB)) }
            return CvReport(rows, rows.map { it.lossDefault }.average(), rows.map { it.lossTrained }.average())
        }
        val trained = trainBoth(datasets, start, generationsA, generationsB)
        val rows = datasets.map { heldOut(it, start, trained) }
        return CvReport(rows, rows.map { it.lossDefault }.average(), rows.map { it.lossTrained }.average(), trained)
    }

    private fun heldOut(ds: Dataset, start: TuningParams, trained: TuningParams): HeldOut {
        val before = totalLoss(ds, start)
        val after = totalLoss(ds, trained)
        return HeldOut(ds.name, before.first, after.first, after.second, after.third)
    }

    fun gate(cv: CvReport): GateDecision {
        val reasons = ArrayList<String>()
        val gain = 1 - cv.meanTrained / cv.meanDefault
        if (cv.perWalk.size < 3) {
            reasons += "${cv.perWalk.size} marche(s) : pas de validation croisée possible (3 au moins)"
            return if (gain >= 0.10) {
                reasons += "perte d'entraînement −%.0f %% (≥ 10 %%)".format(gain * 100)
                GateDecision(Gate.PROVISIONAL, reasons)
            } else {
                reasons += "perte d'entraînement −%.0f %% (< 10 %%)".format(gain * 100)
                GateDecision(Gate.REJECT, reasons)
            }
        }
        var ok = true
        if (cv.meanTrained > 0.9 * cv.meanDefault) {
            ok = false
            reasons += "moyenne tenue à l'écart −%.0f %% (il faut ≥ 10 %%)".format(gain * 100)
        } else {
            reasons += "moyenne tenue à l'écart −%.0f %%".format(gain * 100)
        }
        for (h in cv.perWalk) if (h.lossTrained > 1.1 * h.lossDefault) {
            ok = false
            reasons += "${h.walk} pire de %.0f %% que par défaut".format((h.lossTrained / h.lossDefault - 1) * 100)
        }
        return GateDecision(if (ok) Gate.ACCEPT else Gate.REJECT, reasons)
    }

    fun writeParams(t: TuningParams, file: File = File("src/main/assets/tuning/params.json")) {
        file.parentFile?.mkdirs()
        file.writeText(Tuning.write(t) + "\n")
    }

    fun writeReport(cv: CvReport, decision: GateDecision, final: TuningParams, file: File, start: TuningParams = TuningParams()) {
        val sb = StringBuilder()
        sb.append("# Entraînement des réglages — v${final.version}${if (final.provisional) " (provisoire)" else ""}\n\n")
        sb.append("Marches : ${final.trainedOn.joinToString(", ").ifEmpty { "—" }}\n\n")
        sb.append("## Décision : ${decision.gate}\n\n")
        for (r in decision.reasons) sb.append("- ").append(r).append('\n')
        sb.append("\n## Par marche (tenue à l'écart si ≥ 3 marches)\n\n")
        sb.append("| Marche | Perte défaut | Perte entraînée | Trajet DTW (m) | Murs /100 m | Fermeture % | Suivi médiane (m) | Suivi p90 (m) | « Trouvé » après (m) | Faux « Trouvé » |\n")
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n")
        for (h in cv.perWalk) {
            val known = h.runs.filter { it.mode == StartMode.KNOWN_START }
            val cold = h.runs.filter { it.mode != StartMode.KNOWN_START }
            fun fmt(v: Double?) = v?.let { "%.1f".format(it) } ?: "—"
            sb.append("| ${h.walk} | %.2f | %.2f | %.2f | %.1f | %s | %s | %s | %s | %.0f %% |\n".format(
                h.lossDefault, h.lossTrained, h.pdr.routeDtwM, h.pdr.wallCrossingsPer100m, fmt(h.pdr.closurePct),
                fmt(known.mapNotNull { it.trackErrMedianM }.takeIf { it.isNotEmpty() }?.average()),
                fmt(known.mapNotNull { it.trackErrP90M }.takeIf { it.isNotEmpty() }?.average()),
                fmt(cold.mapNotNull { it.foundAfterM }.takeIf { it.isNotEmpty() }?.average()),
                (cold.map { it.falseFoundShare }.takeIf { it.isNotEmpty() }?.average() ?: 0.0) * 100
            ))
        }
        sb.append("\nMoyenne : %.2f → %.2f\n\n## Réglages modifiés\n\n".format(cv.meanDefault, cv.meanTrained))
        val changed = (Knobs.STAGE_A + Knobs.STAGE_B).filter { kotlin.math.abs(it.get(start) - it.get(final)) > 1e-9 }
        if (changed.isEmpty()) sb.append("Aucun.\n")
        for (k in changed) sb.append("- `${k.name}` : %.4g → %.4g\n".format(k.get(start), k.get(final)))
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }
}
