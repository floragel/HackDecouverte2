package com.hackdecouverte.backend

import kotlin.math.abs

/**
 * Zee's progressive learning carried across sessions: once the map has pinned the walker down long
 * enough, the step length it settled on (PDR step × learned scale) is kept, the PDR starts with it
 * next time, and the next search's stride prior narrows around it (straight corridors then converge
 * much sooner: the stride is what they cannot observe). Pure Kotlin.
 */
data class StrideMemory(val learnedStepM: Double? = null, val sessions: Int = 0) {

    /** Stride-scale prior (min, max) for a search whose PDR runs with step [pdrStepM]. */
    fun prior(pdrStepM: Double?): Pair<Double, Double> {
        val learned = learnedStepM ?: return DEFAULT_PRIOR
        val half = when {
            sessions >= 2 -> 0.08
            sessions == 1 -> 0.12
            else -> return DEFAULT_PRIOR
        }
        val center = if (pdrStepM != null && pdrStepM > 0) learned / pdrStepM else 1.0
        return (center - half).coerceAtLeast(MIN_SCALE) to (center + half).coerceAtMost(MAX_SCALE)
    }

    /**
     * After a confident stretch (FOUND for [FOUND_STEPS_TO_LEARN] steps, scale spread ≤ [MAX_SPREAD]).
     * [newRun]: first learning of this search (counts as a session). A value more than 15 % away from
     * the remembered one only counts as a first session again (another person, shoes, or a wrong fix).
     */
    fun learn(pdrStepM: Double, scale: Double, newRun: Boolean): StrideMemory {
        val learned = (pdrStepM * scale).coerceIn(MIN_STEP_M, MAX_STEP_M)
        val prev = learnedStepM
        val consistent = prev == null || abs(learned / prev - 1) <= 0.15
        val n = when {
            !consistent -> 1
            newRun -> sessions + 1
            else -> maxOf(sessions, 1)
        }
        return StrideMemory(learned, n)
    }

    companion object {
        val DEFAULT_PRIOR = 0.75 to 1.30
        const val MIN_SCALE = 0.6
        const val MAX_SCALE = 1.5
        const val MIN_STEP_M = 0.45
        const val MAX_STEP_M = 1.05
        const val FOUND_STEPS_TO_LEARN = 40
        const val RELEARN_EVERY_STEPS = 150
        const val MAX_SPREAD = 0.06
    }
}
