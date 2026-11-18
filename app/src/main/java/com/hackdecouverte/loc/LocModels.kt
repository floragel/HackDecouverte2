package com.hackdecouverte.loc

import com.hackdecouverte.pdr.PointM

/** Tuning of the map localization filter (spec §6.3, §6.2 bis). */
data class LocParams(
    val maxParticles: Int = 2000,
    val minParticles: Int = 300,
    val headingNoiseRad: Double = Math.toRadians(3.0),
    val lengthNoise: Double = 0.05,
    val offsetDriftRad: Double = Math.toRadians(0.5),
    val scaleDrift: Double = 0.005,
    /** Step-length scale prior (× the PDR step), drawn uniformly at a search start and then sorted out by the walls (Zee §7). */
    val strideScaleMin: Double = 0.75,
    val strideScaleMax: Double = 1.30,
    val wallFactor: Double = 0.02,
    val beta: Double = 0.2,
    val resampleEssRatio: Double = 0.5,
    val recoveryFraction: Double = 0.02,
    val recoveryRadiusM: Double = 15.0,
    /** Steps kept for the walk traced back (Zee's backward propagation); the computer replay keeps whole walks. */
    val historySteps: Int = 300
)

enum class LocState { SEARCHING, APPROXIMATE, FOUND }

/** A group of hypotheses: centre, radius holding ~95 % of them, share of the weight, walking heading. */
data class LocZone(val centerM: PointM, val radiusM: Double, val weight: Double, val headingRad: Double)

/** Zones sorted by weight (main zone first) and the overall state. */
data class LocEstimate(val zones: List<LocZone>, val state: LocState)

/**
 * One hypothesis for the debug map: [weight] (normalised over all), [score] = recent average of how
 * plausible its steps were (1 = followed the corridor, low = walked into walls or across corridors),
 * [headingRad] = walking direction it currently assumes.
 */
data class LocParticle(val positionM: PointM, val weight: Double, val score: Double, val headingRad: Double)
