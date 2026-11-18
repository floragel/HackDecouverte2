package com.hackdecouverte.tuning

import com.hackdecouverte.loc.LocParams
import com.hackdecouverte.pdr.CarryMonitor
import com.hackdecouverte.pdr.PdrTracker
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every setting the training can move (plan "produit fini", phase C). Defaults = the hand-set values,
 * so an app without `assets/tuning/params.json` behaves exactly as before. Pure Kotlin.
 */
@Serializable
data class TuningParams(
    val version: Int = 1,
    /** Trained on too few walks to be validated (gate PROVISIONAL). */
    val provisional: Boolean = false,
    val trainedOn: List<String> = emptyList(),
    val pdr: PdrTuning = PdrTuning(),
    val carry: CarryTuning = CarryTuning(),
    val filter: FilterTuning = FilterTuning()
)

@Serializable
data class PdrTuning(
    val compassPullTauS: Double = 8.0,
    val compassPullMaxErrDeg: Double = 60.0,
    val walkAxisCorrection: Boolean = true,
    val walkAxisTau: Double = 4.0,
    val walkAxisMinRatio: Double = 2.0,
    val strideWindowFactor: Double = 1.0,
    val typicalCadenceHz: Double = 1.8
)

@Serializable
data class CarryTuning(
    val startDeg: Double = 25.0,
    val commitDeg: Double = 35.0,
    val settledDeg: Double = 12.0,
    val swingOnDeg: Double = 9.0,
    val swingOffDeg: Double = 6.0
)

@Serializable
data class FilterTuning(
    val maxParticles: Int = 2000,
    val minParticles: Int = 300,
    val headingNoiseDeg: Double = 3.0,
    val bodyCarryNoiseScale: Double = 2.0,
    val lengthNoise: Double = 0.05,
    val offsetDriftDeg: Double = 0.5,
    val scaleDrift: Double = 0.005,
    val wallFactor: Double = 0.02,
    val beta: Double = 0.2,
    val recoveryFraction: Double = 0.02
)

/** Filter settings from the tuning; [base] keeps what the tuning does not own (stride prior, history). */
fun TuningParams.locParams(base: LocParams = LocParams()): LocParams = base.copy(
    maxParticles = filter.maxParticles,
    minParticles = filter.minParticles,
    headingNoiseRad = Math.toRadians(filter.headingNoiseDeg),
    bodyCarryNoiseScale = filter.bodyCarryNoiseScale,
    lengthNoise = filter.lengthNoise,
    offsetDriftRad = Math.toRadians(filter.offsetDriftDeg),
    scaleDrift = filter.scaleDrift,
    wallFactor = filter.wallFactor,
    beta = filter.beta,
    recoveryFraction = filter.recoveryFraction
)

/** Sets the tracker's public knobs. */
fun PdrTracker.applyTuning(t: PdrTuning) {
    compassPullTauS = t.compassPullTauS
    compassPullMaxErrDeg = t.compassPullMaxErrDeg
    walkAxisCorrectionEnabled = t.walkAxisCorrection
    walkAxisTau = t.walkAxisTau
    walkAxisMinRatio = t.walkAxisMinRatio
    strideWindowFactor = t.strideWindowFactor
    typicalCadenceHz = t.typicalCadenceHz
}

fun CarryMonitor.Companion.from(t: CarryTuning): CarryMonitor =
    CarryMonitor(startDeg = t.startDeg, commitDeg = t.commitDeg, settledDeg = t.settledDeg, swingOnDeg = t.swingOnDeg, swingOffDeg = t.swingOffDeg)

/** Reads `assets/tuning/params.json`; missing or invalid → defaults (never throws). */
object Tuning {
    const val ASSET = "tuning/params.json"
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun parse(text: String): TuningParams = json.decodeFromString(TuningParams.serializer(), text)

    fun load(open: (String) -> InputStream?): TuningParams =
        runCatching { open(ASSET)?.bufferedReader()?.use { parse(it.readText()) } }.getOrNull() ?: TuningParams()

    fun write(t: TuningParams): String = json.encodeToString(TuningParams.serializer(), t)
}
