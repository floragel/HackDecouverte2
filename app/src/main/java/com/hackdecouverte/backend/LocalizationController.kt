package com.hackdecouverte.backend

import com.hackdecouverte.loc.LocEstimate
import com.hackdecouverte.loc.LocalizationFilter
import com.hackdecouverte.map.IndoorMap
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PdrState
import com.hackdecouverte.pdr.PointM
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

/** Developer figures shown next to the particles; [strideScale] ± [strideSpread] = learned step (× the PDR step). */
data class LocDebug(
    val particleCount: Int,
    val stepMs: Double,
    val alarms: Int,
    val strideScale: Double = 1.0,
    val strideSpread: Double = 0.0
)

/**
 * Feeds the map localization filter with the PDR's displacements (pure Kotlin, no Android).
 * Maps are built once per floor by [mapProvider] and cached; [prepare] can run off the main thread.
 */
class LocalizationController(
    private val mapProvider: (floorId: String) -> IndoorMap?,
    private val random: Random = Random.Default
) {
    private val maps = HashMap<String, IndoorMap?>()
    @Volatile private var filter: LocalizationFilter? = null
    private var lastPosition: PointM? = null
    private var lastStepCount = -1

    @Volatile var floorId: String? = null
        private set

    /** Latest estimate, refreshed after every accepted displacement (null when not running). */
    @Volatile var estimate: LocEstimate? = null
        private set
    @Volatile private var particles: List<PointM> = emptyList()
    @Volatile private var trace: List<PointM> = emptyList()

    /**
     * Builds (or fetches from cache) the map of [floorId]; heavy, safe to call off the main thread.
     * A map that fails to build, or has nowhere to stand, counts as missing (never throws).
     */
    fun prepare(floorId: String): Boolean = synchronized(maps) {
        maps.getOrPut(floorId) {
            runCatching { mapProvider(floorId) }.getOrNull()?.takeIf { it.freeCellIndices().isNotEmpty() }
        }
    } != null

    /** Starts a fresh search over the whole floor. False if the floor has no usable map. */
    fun start(floorId: String): Boolean {
        if (!prepare(floorId)) return false
        val map = synchronized(maps) { maps[floorId] }!!
        val f = LocalizationFilter(map, random = random)
        f.initUniform()
        filter = f
        this.floorId = floorId
        lastPosition = null
        lastStepCount = -1
        refresh(f)
        return true
    }

    fun stop() {
        filter = null
        floorId = null
        estimate = null
        particles = emptyList()
        trace = emptyList()
        lastPosition = null
    }

    /**
     * One PDR state. Displacement since the previous TRACKING state → one filter step. Ignored: other
     * phases, zero displacement; a displacement over [MAX_DISPLACEMENT_M] or a step count going back
     * (trace reset, merged burst) only resets the reference position.
     */
    fun onPdrState(s: PdrState) {
        val f = filter ?: return
        if (s.phase != PdrPhase.TRACKING) {
            lastPosition = null
            return
        }
        val prev = lastPosition
        val prevSteps = lastStepCount
        lastPosition = s.position
        lastStepCount = s.stepCount
        if (prev == null || s.stepCount < prevSteps) return
        val dx = s.position.x - prev.x
        val dy = s.position.y - prev.y
        val d = hypot(dx, dy)
        if (d < 1e-6 || d > MAX_DISPLACEMENT_M) return
        val t0 = System.nanoTime()
        f.onStep(d, atan2(dx, dy))
        val ms = (System.nanoTime() - t0) / 1e6
        stepMs = if (stepMs == 0.0) ms else stepMs * 0.8 + ms * 0.2
        refresh(f)
    }

    @Volatile private var stepMs = 0.0

    val debug: LocDebug?
        get() = filter?.let { LocDebug(it.size(), stepMs, it.alarms, it.strideScaleEstimate(), it.strideScaleSpread()) }

    fun samplePositions(max: Int): List<PointM> = if (max >= particles.size) particles else particles.take(max)

    /** The walk traced back from the found zone (Zee's backward belief propagation), oldest first; empty while searching. */
    fun tracedPath(): List<PointM> = trace

    private fun refresh(f: LocalizationFilter) {
        estimate = f.estimate()
        particles = f.samplePositions(MAX_PARTICLES_SHOWN_SOURCE)
        trace = f.tracePath().orEmpty()
    }

    companion object {
        const val MAX_DISPLACEMENT_M = 3.0
        private const val MAX_PARTICLES_SHOWN_SOURCE = 2000
    }
}
