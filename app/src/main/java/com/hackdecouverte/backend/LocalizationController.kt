package com.hackdecouverte.backend

import com.hackdecouverte.loc.LocEstimate
import com.hackdecouverte.loc.LocParams
import com.hackdecouverte.loc.LocParticle
import com.hackdecouverte.loc.LocZone
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
    val strideSpread: Double = 0.0,
    val gpsFixesUsed: Int = 0,
    /** Effective sample size: how many hypotheses really count (low = weight concentrated). */
    val ess: Double = 0.0,
    /** False until the phone→walking angle is learned from the first straight line. */
    val offsetKnown: Boolean = true,
    /** The search started from the last zones instead of from scratch. */
    val warmStarted: Boolean = false
)

/** A system position (GPS / Wi-Fi / cell) in the plan frame, with its announced accuracy and clock (elapsedRealtimeNanos). */
data class SystemFix(val centerM: PointM, val accuracyM: Double, val timeNs: Long)

/** Where the last search ended (spec §6.4 "propagation depuis la dernière zone"): its zones and how long ago. */
data class WarmStart(val zones: List<LocZone>, val ageS: Double)

/**
 * Feeds the map localization filter with the PDR's displacements (pure Kotlin, no Android).
 * Maps are built once per floor by [mapProvider] and cached; [prepare] can run off the main thread.
 */
class LocalizationController(
    private val mapProvider: (floorId: String) -> IndoorMap?,
    private val random: Random = Random.Default,
    /** Filter settings for the next search (the stride memory narrows the stride prior; the replay sweeps them). */
    var params: LocParams = LocParams()
) {
    private val maps = HashMap<String, IndoorMap?>()
    @Volatile private var filter: LocalizationFilter? = null
    private var lastPosition: PointM? = null
    private var lastStepCount = -1
    private var lastHoldChanges = 0

    @Volatile var floorId: String? = null
        private set

    /** Latest estimate, refreshed after every accepted displacement (null when not running). */
    @Volatile var estimate: LocEstimate? = null
        private set
    @Volatile private var particles: List<LocParticle> = emptyList()
    @Volatile private var trace: List<PointM> = emptyList()

    /**
     * Builds (or fetches from cache) the map of [floorId]; heavy, safe to call off the main thread.
     * A map that fails to build, or has nowhere to stand, counts as missing (never throws).
     */
    fun prepare(floorId: String): Boolean = synchronized(maps) {
        maps.getOrPut(floorId) {
            val built = runCatching { mapProvider(floorId) }
            built.exceptionOrNull()?.let { lastError = "$floorId: ${it::class.simpleName}: ${it.message}" }
            val map = built.getOrNull()
            if (built.isSuccess && map == null) lastError = "$floorId: no map files"
            if (map != null && map.freeCellIndices().isEmpty()) lastError = "$floorId: map has no free space"
            map?.takeIf { it.freeCellIndices().isNotEmpty() }
        }
    } != null

    /** Why the last map could not be built (shown on the debug screen), null if none failed. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Starts a fresh search: in the GPS disk around [fix] when it is usable (spec §6.4 case 3),
     * otherwise over the whole floor. False if the floor has no usable map.
     */
    fun start(floorId: String, fix: SystemFix? = null, warm: WarmStart? = null): Boolean {
        if (!prepare(floorId)) return false
        val map = synchronized(maps) { maps[floorId] }!!
        val f = LocalizationFilter(map, params, random)
        filter = f
        this.floorId = floorId
        lastPosition = null
        lastStepCount = -1
        stepsSinceStart = 0
        gpsFixesUsed = 0
        systemCircle = null
        startedInDisk = false
        lastFixUsedNs = null
        stepsAtLastFix = 0
        lastHoldChanges = 0
        val usable = fix?.takeIf(::isUsable)
        val warmDisks = warm?.let(::warmDisks).orEmpty()
        warmStarted = warmDisks.isNotEmpty()
        when {
            warmDisks.isNotEmpty() -> {
                // Last zones first; the rest of the hypotheses go to the GPS disk (if any) and the whole floor.
                val rest = 1 - warmDisks.sumOf { it.share }
                val gps = usable?.let { listOf(LocalizationFilter.Disk(it.centerM, diskRadius(it), rest * (1 - LocalizationFilter.OUTSIDE_DISK_SHARE))) }.orEmpty()
                f.initDisks(warmDisks + gps)
                startedInDisk = true // a first GPS fix must not throw the warm start away
                usable?.let(::markUsed)
            }
            usable != null -> startInDisk(f, usable)
            else -> f.initUniform()
        }
        refresh(f)
        return true
    }

    /** The search started from the last zones ([WarmStart]). */
    @Volatile var warmStarted = false
        private set

    private fun diskRadius(fix: SystemFix) = maxOf(DISK_ACCURACY_FACTOR * fix.accuracyM, DISK_MIN_RADIUS_M)

    /** Circle of the last system position used: centre and search-disk radius (for display). */
    @Volatile var systemCircle: Pair<PointM, Double>? = null
        private set
    @Volatile private var gpsFixesUsed = 0
    private var startedInDisk = false

    /** Filter steps since the search (re)started; the traced path's last point is this step. */
    var stepsSinceStart = 0
        private set
    private var lastFixUsedNs: Long? = null
    private var stepsAtLastFix = 0

    /**
     * A new system position. Too imprecise ones are ignored. If the search started without one and
     * has barely moved, it restarts in the disk; otherwise the fix only weighs the hypotheses, at most
     * every [FIX_MIN_INTERVAL_NS] and [FIX_MIN_STEPS] steps (indoor fixes are biased and correlated).
     */
    fun onSystemPosition(fix: SystemFix) {
        val f = filter ?: return
        if (!isUsable(fix)) return
        if (!startedInDisk && stepsSinceStart < RESTART_IN_DISK_MAX_STEPS) {
            startInDisk(f, fix)
            refresh(f)
            return
        }
        val last = lastFixUsedNs
        if (last != null && (fix.timeNs - last < FIX_MIN_INTERVAL_NS || stepsSinceStart - stepsAtLastFix < FIX_MIN_STEPS)) return
        f.onSystemPosition(fix.centerM, fix.accuracyM)
        markUsed(fix)
        refresh(f)
    }

    private fun isUsable(fix: SystemFix) =
        fix.accuracyM > 0 && fix.accuracyM <= MAX_FIX_ACCURACY_M && fix.centerM.x.isFinite() && fix.centerM.y.isFinite()

    private fun startInDisk(f: LocalizationFilter, fix: SystemFix) {
        val radius = diskRadius(fix)
        f.initAround(fix.centerM, radius)
        startedInDisk = true
        stepsSinceStart = 0
        systemCircle = fix.centerM to radius
        markUsed(fix)
    }

    private fun markUsed(fix: SystemFix) {
        gpsFixesUsed++
        lastFixUsedNs = fix.timeNs
        stepsAtLastFix = stepsSinceStart
        systemCircle = fix.centerM to maxOf(DISK_ACCURACY_FACTOR * fix.accuracyM, DISK_MIN_RADIUS_M)
    }

    fun stop() {
        filter = null
        floorId = null
        systemCircle = null
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
        // The compass says where the phone points; that is where the user walks only in a reading pose.
        if (!f.offsetKnown) f.compassAligned = s.trueNorthAligned && s.headingAligned
        if (s.holdChanges != lastHoldChanges) {
            if (s.holdChanges > lastHoldChanges) f.onHoldChanged()
            lastHoldChanges = s.holdChanges
        }
        // A new PDR trace (resume after a pause): its heading frame may differ, as after a hold change.
        if (prevSteps > 0 && s.stepCount < prevSteps) f.onHoldChanged()
        if (prev == null || s.stepCount < prevSteps) return
        val dx = s.position.x - prev.x
        val dy = s.position.y - prev.y
        val d = hypot(dx, dy)
        if (d < 1e-6 || d > MAX_DISPLACEMENT_M) return
        val t0 = System.nanoTime()
        f.onStep(d, atan2(dx, dy))
        stepsSinceStart++
        val ms = (System.nanoTime() - t0) / 1e6
        stepMs = if (stepMs == 0.0) ms else stepMs * 0.8 + ms * 0.2
        refresh(f)
    }

    @Volatile private var stepMs = 0.0

    val debug: LocDebug?
        get() = filter?.let { LocDebug(it.size(), stepMs, it.alarms, it.strideScaleEstimate(), it.strideScaleSpread(), gpsFixesUsed, it.effectiveSampleSize(), it.offsetKnown, warmStarted) }

    fun samplePositions(max: Int): List<PointM> = sampleParticles(max).map { it.positionM }

    /** Hypotheses with weight, confidence score and heading, for the debug map. */
    fun sampleParticles(max: Int): List<LocParticle> = if (max >= particles.size) particles else particles.take(max)

    /** The precise map the filter runs on (walls, doors, direction map), null when stopped. */
    fun currentMap(): IndoorMap? = filter?.map

    /** The walk traced back from the found zone (Zee's backward belief propagation), oldest first; empty while searching. */
    fun tracedPath(): List<PointM> = trace

    private fun refresh(f: LocalizationFilter) {
        estimate = f.estimate()
        particles = f.sampleParticles(MAX_PARTICLES_SHOWN_SOURCE)
        trace = f.tracePath().orEmpty()
    }

    companion object {
        /**
         * Hypotheses for a warm start: the last zones, widened by [WARM_BASE_RADIUS_M] plus
         * [WARM_GROWTH_M_PER_MIN] per minute away (capped at 15 min), sharing [warmShare] of the
         * hypotheses by their weight. Too old (> 30 min) → none.
         */
        fun warmDisks(w: WarmStart): List<LocalizationFilter.Disk> {
            val share = warmShare(w.ageS)
            val total = w.zones.take(3).sumOf { it.weight }
            if (share <= 0.0 || total <= 0.0) return emptyList()
            val grow = WARM_BASE_RADIUS_M + WARM_GROWTH_M_PER_MIN * (w.ageS / 60).coerceIn(0.0, 15.0)
            return w.zones.take(3).map { z -> LocalizationFilter.Disk(z.centerM, z.radiusM + grow, share * z.weight / total) }
        }

        /** Share of the hypotheses placed on the last zones: the longer away, the less it says. */
        fun warmShare(ageS: Double): Double = when {
            ageS < 0 -> 0.0
            ageS <= 120 -> 0.7
            ageS <= 600 -> 0.5
            ageS <= 1800 -> 0.3
            else -> 0.0
        }

        const val WARM_BASE_RADIUS_M = 2.0
        const val WARM_GROWTH_M_PER_MIN = 1.0

        /**
         * Localization mode starts the PDR by itself, but not before the compass has given True North
         * (else the trace stays in the phone's arbitrary frame), unless it never comes within [COMPASS_WAIT_NS].
         */
        fun shouldAutoStartPdr(s: PdrState, waitedNs: Long): Boolean =
            s.canStart && (s.trueNorthAligned || waitedNs >= COMPASS_WAIT_NS)

        const val COMPASS_WAIT_NS = 3_000_000_000L
        const val MAX_DISPLACEMENT_M = 3.0
        /** Search disk = max(2 × accuracy, 30 m) around the fix (spec §6.4 case 3). */
        const val DISK_ACCURACY_FACTOR = 2.0
        const val DISK_MIN_RADIUS_M = 30.0
        /** Fixes worse than this (cell towers) are ignored. */
        const val MAX_FIX_ACCURACY_M = 80.0
        const val FIX_MIN_INTERVAL_NS = 15_000_000_000L
        const val FIX_MIN_STEPS = 5
        /** A first fix arriving within this many steps of a whole-floor start restarts the search in its disk. */
        const val RESTART_IN_DISK_MAX_STEPS = 10
        private const val MAX_PARTICLES_SHOWN_SOURCE = 2000
    }
}
