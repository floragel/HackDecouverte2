package com.hackdecouverte.loc

import com.hackdecouverte.map.IndoorMap
import com.hackdecouverte.map.axialDiff
import com.hackdecouverte.pdr.PointM
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Particle filter on a floor's precise map (spec §6.3): each hypothesis carries a position, the
 * angle between the phone and the walking direction, and a step-length scale. Walls eliminate,
 * the direction map favours walking along corridors. Pure Kotlin, deterministic with a seeded [random].
 */
class LocalizationFilter(
    val map: IndoorMap,
    val params: LocParams = LocParams(),
    private val random: Random = Random.Default
) {
    private var east = DoubleArray(0)
    private var north = DoubleArray(0)
    private var offset = DoubleArray(0)
    private var scale = DoubleArray(0)
    private var weight = DoubleArray(0)
    /** Debug confidence per hypothesis: exponential average of its step factors (wall × alignment). */
    private var score = DoubleArray(0)
    /** Index, in the latest [history] snapshot, of the hypothesis each particle comes from (−1: injected, no past). */
    private var origin = IntArray(0)
    private var n = 0
    /** Positions after each step with a link to their parent (Zee's backward belief propagation), oldest first. */
    private val history = ArrayDeque<Snapshot>()

    private class Snapshot(val east: FloatArray, val north: FloatArray, val parent: IntArray)
    private var lastHeadingRad = 0.0
    private var badSteps = 0
    private var goodSteps = 0
    /** After a (re)start the filter claims nothing until [GOOD_STEPS_TO_TRUST] plausible steps in a row. */
    private var forcedSearching = false
    private val freeCells: IntArray by lazy { map.freeCellIndices() }

    /** False while the phone→walking angle is unknown: steps are buffered until the first straight line. */
    var offsetKnown: Boolean = true
        private set
    private val pendingSteps = ArrayList<Pair<Double, Double>>() // (length, relative heading)

    /**
     * True when the PDR headings are already True North (compass, spec §6.3 "Angle de départ"): the
     * phone angle is then drawn around 0 (±[COMPASS_PRIOR_RAD]) instead of every corridor direction,
     * except a [COMPASS_DISTRUST_SHARE] that ignores the compass (steel, magnets).
     */
    var compassAligned: Boolean = false

    /** × the heading noise of the next steps (a phone in a pocket or a swinging hand gives looser step headings). */
    var headingNoiseScale: Double = 1.0

    fun size(): Int = n

    /**
     * Positions drawn uniformly over the free cells, step-length scale spread over its prior (Zee:
     * the walls sort it out), phone angle **unknown**: it is set by the first straight line (spec §6.2 bis).
     */
    fun initUniform(count: Int = params.maxParticles) {
        spreadUniform(count)
        offsetKnown = false
        pendingSteps.clear()
    }

    /**
     * Cold start with a system (GPS / Wi-Fi) position (spec §6.4 case 3, the "GPS zone"): 90 % of the
     * hypotheses in the free space within [radiusM] of [center], 10 % anywhere on the floor in case
     * the fix is wrong. Phone angle unknown, step length spread, as in [initUniform]. A disk with no
     * free space (fix outside the plan) falls back to the whole floor.
     */
    fun initAround(center: PointM, radiusM: Double, count: Int = params.maxParticles) =
        initDisks(listOf(Disk(center, radiusM, 1 - OUTSIDE_DISK_SHARE)), count)

    /** One hypothesis's full state, to carry it to another floor's filter. */
    data class Hypothesis(val positionM: PointM, val offsetRad: Double, val scale: Double, val weight: Double)

    fun exportHypotheses(): List<Hypothesis> = List(n) { Hypothesis(PointM(east[it], north[it]), offset[it], scale[it], weight[it]) }

    /**
     * Continues a search on this (other) floor from [carried] hypotheses (spec §6.5: a floor change is
     * only possible next to stairs or a lift): drawn by weight, placed on a free cell within
     * [jitterM] of their position (else near it), keeping their phone angle and stride; [freshShare]
     * of new ones anywhere in case the barometer or the carried zone is wrong.
     */
    fun initFrom(carried: List<Hypothesis>, offsetKnown: Boolean, jitterM: Double = 1.0, freshShare: Double = 0.1, count: Int = params.maxParticles) {
        val total = carried.sumOf { it.weight }
        if (carried.isEmpty() || total <= 0) {
            initUniform(count)
            return
        }
        allocate(count)
        val cum = DoubleArray(carried.size)
        var acc = 0.0
        for ((k, h) in carried.withIndex()) { acc += h.weight / total; cum[k] = acc }
        for (i in 0 until count) {
            if (random.nextDouble() < freshShare) {
                val p = randomFreePoint()
                east[i] = p.x; north[i] = p.y
                offset[i] = random.nextDouble(0.0, 2 * PI); scale[i] = randomStrideScale()
            } else {
                val u = random.nextDouble()
                var k = 0
                while (k < cum.size - 1 && cum[k] < u) k++
                val h = carried[k]
                val p = if (!map.grid.isBlocked(h.positionM) && jitterM <= 0) h.positionM else randomFreePointNear(h.positionM, jitterM.coerceAtLeast(0.3))
                east[i] = p.x; north[i] = p.y
                offset[i] = h.offsetRad; scale[i] = h.scale
            }
            score[i] = 1.0
            weight[i] = 1.0 / count
        }
        this.offsetKnown = offsetKnown
        pendingSteps.clear()
        forcedSearching = true
        goodSteps = 0
        startHistory()
    }

    /** A search area: a disk and the share of the hypotheses it gets. */
    data class Disk(val center: PointM, val radiusM: Double, val share: Double)

    /**
     * Start from several areas at once (warm start: the last zones, plus the GPS disk): each disk gets
     * its [Disk.share] of the hypotheses in its free space, the rest go anywhere on the floor (as does
     * the share of a disk with no free space). Phone angle unknown, step length spread.
     */
    fun initDisks(disks: List<Disk>, count: Int = params.maxParticles) {
        val inside = disks.map { d ->
            val r2 = d.radiusM * d.radiusM
            freeCells.filter { c ->
                val p = map.grid.centerOf(c)
                (p.x - d.center.x) * (p.x - d.center.x) + (p.y - d.center.y) * (p.y - d.center.y) <= r2
            }
        }
        fun draw(): PointM {
            var u = random.nextDouble()
            for ((k, d) in disks.withIndex()) {
                if (u < d.share) {
                    val cells = inside[k]
                    return if (cells.isEmpty()) randomFreePoint() else jittered(cells[random.nextInt(cells.size)])
                }
                u -= d.share
            }
            return randomFreePoint()
        }
        spreadUniform(count, ::draw)
        offsetKnown = false
        pendingSteps.clear()
    }

    /**
     * System position as a tolerant measurement (spec §6.3): a widened gaussian plus a share kept for
     * outliers, so far hypotheses lose weight but are never cut. The caller rate-limits it (fixes
     * indoors are biased and correlated).
     */
    fun onSystemPosition(center: PointM, accuracyM: Double) {
        if (n == 0 || !center.x.isFinite() || !center.y.isFinite() || !(accuracyM > 0)) return
        val sigma = maxOf(accuracyM * SYSTEM_SIGMA_FACTOR, SYSTEM_MIN_SIGMA_M)
        val k = 1.0 / (2 * sigma * sigma)
        for (i in 0 until n) {
            val dx = east[i] - center.x; val dy = north[i] - center.y
            weight[i] *= (1 - SYSTEM_OUTLIER_SHARE) * exp(-(dx * dx + dy * dy) * k) + SYSTEM_OUTLIER_SHARE
        }
        normalize()
        if (effectiveSampleSize() < params.resampleEssRatio * n) resample()
    }

    private fun spreadUniform(count: Int, point: () -> PointM = ::randomFreePoint) {
        allocate(count)
        for (i in 0 until count) {
            val p = point()
            east[i] = p.x; north[i] = p.y
            offset[i] = random.nextDouble(0.0, 2 * PI)
            scale[i] = randomStrideScale()
            score[i] = 1.0
            weight[i] = 1.0 / count
        }
        forcedSearching = true
        goodSteps = 0
        startHistory()
    }

    /**
     * Explicit hypotheses (tests, shortcuts): one per point, with its phone angle. Step-length scale 1,
     * or spread over its prior when [spreadStride] (start known, step length not: Zee's own example).
     */
    fun initAt(points: List<PointM>, offsetsRad: List<Double>, spreadStride: Boolean = false) {
        require(points.size == offsetsRad.size && points.isNotEmpty())
        allocate(points.size)
        for (i in points.indices) {
            east[i] = points[i].x; north[i] = points[i].y
            offset[i] = offsetsRad[i]; scale[i] = if (spreadStride) randomStrideScale() else 1.0; score[i] = 1.0; weight[i] = 1.0 / points.size
        }
        forcedSearching = false
        offsetKnown = true
        pendingSteps.clear()
        startHistory()
    }

    /** One accepted PDR displacement: [lengthM] metres at relative heading [headingRad]. */
    fun onStep(lengthM: Double, headingRad: Double) {
        if (n == 0 || !(lengthM > 1e-6) || !headingRad.isFinite()) return
        if (offsetKnown) {
            advance(lengthM, headingRad)
            return
        }
        pendingSteps.add(lengthM to headingRad)
        val line = straightLineHeading()
        when {
            line != null -> assignOffsetsFromCorridors(line)
            pendingSteps.size >= MAX_STEPS_WITHOUT_LINE -> for (i in 0 until n) offset[i] = freeOffset()
            else -> return
        }
        offsetKnown = true
        val replay = pendingSteps.toList()
        pendingSteps.clear()
        for ((len, heading) in replay) advance(len, heading)
    }

    /** Circular mean of the last [LINE_STEPS] buffered headings if they all lie within [LINE_SPREAD_RAD] of it. */
    private fun straightLineHeading(): Double? {
        if (pendingSteps.size < LINE_STEPS) return null
        val last = pendingSteps.takeLast(LINE_STEPS).map { it.second }
        val mean = atan2(last.sumOf { sin(it) }, last.sumOf { cos(it) })
        return if (last.all { abs(wrapPi(it - mean)) <= LINE_SPREAD_RAD }) mean else null
    }

    /** The straight line is assumed to follow the local corridor: 2 candidate angles (4 where the axis is unclear). */
    private fun assignOffsetsFromCorridors(lineHeading: Double) {
        for (i in 0 until n) {
            val p = PointM(east[i], north[i])
            if (random.nextDouble() < FREE_OFFSET_SHARE) {
                offset[i] = freeOffset()
                continue
            }
            val candidates = if (map.directions.coherenceAt(p) >= 0.5) {
                val a = map.directions.axisAt(p)
                doubleArrayOf(a - lineHeading, a + PI - lineHeading)
            } else {
                DoubleArray(4) { k -> map.dominantAxisRad + k * PI / 2 - lineHeading }
            }
            offset[i] = if (compassAligned && random.nextDouble() >= COMPASS_DISTRUST_SHARE) {
                // The corridor direction the compass agrees with; none → this spot's corridor is not where the walker is.
                val near = candidates.filter { abs(wrapPi(it)) <= COMPASS_PRIOR_RAD }
                if (near.isNotEmpty()) near[random.nextInt(near.size)] else gaussian() * COMPASS_SIGMA_RAD
            } else {
                candidates[random.nextInt(candidates.size)]
            }
        }
    }

    /**
     * The phone changed hold (spec §6.6). The PDR kept its heading continuous assuming the body did
     * not turn meanwhile; in case it did, the angles widen: ±[HOLD_CHANGE_SIGMA_RAD] for most, a
     * half-turn for [HOLD_FLIP_SHARE], and for [HOLD_AXIS_SHARE] one of the building's four main
     * directions (a turn hidden by the change is usually a 90° corner). Positions stay where they are.
     */
    fun onHoldChanged() {
        if (n == 0 || !offsetKnown) return
        for (i in 0 until n) {
            val u = random.nextDouble()
            offset[i] = when {
                u < HOLD_AXIS_SHARE -> map.dominantAxisRad + random.nextInt(4) * PI / 2 - lastHeadingRad + gaussian() * Math.toRadians(5.0)
                u < HOLD_AXIS_SHARE + HOLD_FLIP_SHARE -> offset[i] + PI
                else -> offset[i] + gaussian() * HOLD_CHANGE_SIGMA_RAD
            }
        }
    }

    /** Phone angle with no corridor to go by: around 0 when the compass is trusted, else anything. */
    private fun freeOffset(): Double =
        if (compassAligned && random.nextDouble() >= COMPASS_DISTRUST_SHARE) gaussian() * COMPASS_SIGMA_RAD
        else random.nextDouble(0.0, 2 * PI)

    private fun advance(lengthM: Double, headingRad: Double) {
        if (suppressFoundSteps > 0) suppressFoundSteps--
        val main = zones().firstOrNull()
        val mainRadius = maxOf(main?.radiusM ?: 0.0, 1.0)
        var mainW = 0.0; var mainAlign = 0.0; var mainBlocked = 0.0
        lastHeadingRad = headingRad
        val g = map.grid
        val dirs = map.directions
        var likelihood = 0.0
        for (i in 0 until n) {
            val inMain = main != null && (east[i] - main.centerM.x).let { it * it } + (north[i] - main.centerM.y).let { it * it } <= mainRadius * mainRadius
            val h = headingRad + offset[i] + gaussian() * params.headingNoiseRad * headingNoiseScale
            val len = lengthM * scale[i] * (1 + gaussian() * params.lengthNoise)
            val from = PointM(east[i], north[i])
            val to = PointM(east[i] + len * sin(h), north[i] + len * cos(h))
            val blocked = g.segmentBlocked(from, to)
            var f = if (blocked) params.wallFactor else 1.0
            val c = dirs.coherenceAt(to)
            var align = 1.0
            if (c > 0) {
                val s = sin(axialDiff(h, dirs.axisAt(to)))
                align = 1 - params.beta * c * s * s
                f *= align
            }
            if (inMain) {
                mainW += weight[i]; mainAlign += weight[i] * align
                if (blocked) mainBlocked += weight[i]
            }
            east[i] = to.x; north[i] = to.y
            offset[i] += gaussian() * params.offsetDriftRad
            scale[i] = (scale[i] + gaussian() * params.scaleDrift).coerceIn(params.strideScaleMin, params.strideScaleMax)
            score[i] = SCORE_KEEP * score[i] + (1 - SCORE_KEEP) * f
            likelihood += weight[i] * f
            weight[i] *= f
        }
        if (!(likelihood > 0) || !likelihood.isFinite()) {
            spreadUniform(n) // keeps the learned phone angles of nobody: random ones
            for (i in 0 until n) offset[i] = random.nextDouble(0.0, 2 * PI)
            return
        }
        for (i in 0 until n) weight[i] /= likelihood
        if (mainW > 0) checkAlarm(mainAlign / mainW, mainBlocked / mainW)
        // Most of the mass walked through walls: the map or the estimate is wrong — re-seed around it.
        badSteps = if (likelihood < BAD_STEP_LIKELIHOOD) badSteps + 1 else 0
        goodSteps = if (likelihood >= GOOD_STEP_LIKELIHOOD) goodSteps + 1 else 0
        if (badSteps > 0) forcedSearching = true // the estimate just walked through walls: stop claiming it
        if (badSteps >= BAD_STEPS_BEFORE_RECOVERY) {
            reseedAround(0.5)
            badSteps = 0
            goodSteps = 0
            forcedSearching = true
        } else if (goodSteps >= GOOD_STEPS_TO_TRUST) {
            forcedSearching = false
        }
        if (effectiveSampleSize() < params.resampleEssRatio * n) resample()
        recordStep()
    }

    /** Weighted mean step-length scale (× the PDR step). */
    fun strideScaleEstimate(): Double {
        var s = 0.0; var w = 0.0
        for (i in 0 until n) { s += weight[i] * scale[i]; w += weight[i] }
        return if (w > 0) s / w else 1.0
    }

    /** Weighted standard deviation of the step-length scale: how sure the filter is about the step. */
    fun strideScaleSpread(): Double {
        val m = strideScaleEstimate()
        var v = 0.0; var w = 0.0
        for (i in 0 until n) { v += weight[i] * (scale[i] - m) * (scale[i] - m); w += weight[i] }
        return if (w > 0) sqrt(v / w) else 0.0
    }

    /**
     * Backward belief propagation (Zee §7): once a zone is found, follow the hypotheses inside it back
     * through their parents. Hypotheses whose line died out drop away, so each past step keeps only
     * what is consistent with the whole walk. Returns the weighted centre of those survivors per
     * step, oldest first (the start, then one point per step), or null while still searching.
     */
    fun tracePath(): List<PointM>? {
        if (history.isEmpty() || n == 0) return null
        val est = estimate()
        if (est.state == LocState.SEARCHING) return null
        val zone = est.zones.first()
        val r2 = maxOf(zone.radiusM, 1.0).let { it * it }
        val last = history.last()
        var mass = DoubleArray(last.east.size)
        var any = false
        for (i in 0 until n) {
            val dx = east[i] - zone.centerM.x; val dy = north[i] - zone.centerM.y
            // origin = this hypothesis' index in the last snapshot (same place unless injected: −1)
            if (origin[i] >= 0 && dx * dx + dy * dy <= r2) { mass[origin[i]] += weight[i]; any = true }
        }
        if (!any) return null
        val path = ArrayList<PointM>(history.size)
        for (k in history.indices.reversed()) {
            val snap = history[k]
            var sx = 0.0; var sy = 0.0; var sw = 0.0
            for (i in mass.indices) if (mass[i] > 0) { sx += mass[i] * snap.east[i]; sy += mass[i] * snap.north[i]; sw += mass[i] }
            if (sw <= 0) break
            path.add(PointM(sx / sw, sy / sw))
            if (k == 0) break
            val prev = DoubleArray(history[k - 1].east.size)
            for (i in mass.indices) if (mass[i] > 0 && snap.parent[i] >= 0) prev[snap.parent[i]] += mass[i]
            mass = prev
        }
        path.reverse()
        return path
    }

    /** Number of recorded positions (steps + start), at most [LocParams.historySteps] + 1. */
    fun historySize(): Int = history.size

    private fun startHistory() {
        history.clear()
        for (i in 0 until n) origin[i] = -1
        recordStep()
    }

    /** Store where every particle is now and which earlier hypothesis it came from. */
    private fun recordStep() {
        val e = FloatArray(n); val no = FloatArray(n); val parent = IntArray(n)
        for (i in 0 until n) { e[i] = east[i].toFloat(); no[i] = north[i].toFloat(); parent[i] = origin[i]; origin[i] = i }
        history.addLast(Snapshot(e, no, parent))
        while (history.size > params.historySteps + 1) history.removeFirst()
    }

    fun effectiveSampleSize(): Double {
        var s = 0.0
        for (i in 0 until n) s += weight[i] * weight[i]
        return if (s > 0) 1.0 / s else 0.0
    }

    /** Up to [max] hypotheses with weight, confidence score and assumed heading, evenly picked (debug map). */
    fun sampleParticles(max: Int): List<LocParticle> {
        if (n == 0 || max <= 0) return emptyList()
        val stride = maxOf(1, n / max)
        return (0 until n step stride).take(max).map {
            LocParticle(PointM(east[it], north[it]), weight[it], score[it], wrapPi(lastHeadingRad + offset[it]))
        }
    }

    /** Up to [max] particle positions, evenly picked (for display). */
    fun samplePositions(max: Int): List<PointM> {
        if (n == 0 || max <= 0) return emptyList()
        val stride = maxOf(1, n / max)
        return (0 until n step stride).take(max).map { PointM(east[it], north[it]) }
    }

    /** Number of times the main zone was caught walking across corridors or into walls (diagnostic). */
    var alarms: Int = 0
        private set
    private var suppressFoundSteps = 0
    private val recentAlign = ArrayDeque<Double>()
    private val recentBlocked = ArrayDeque<Double>()

    /**
     * Zones (main first) and state (spec §5, §6.3): FOUND when the main zone holds > 90 % of the
     * weight within 3 m, APPROXIMATE when > 60 % within 8 m, otherwise SEARCHING.
     */
    fun estimate(): LocEstimate {
        val zones = zones()
        val main = zones.firstOrNull() ?: return LocEstimate(emptyList(), LocState.SEARCHING)
        val state = when {
            forcedSearching -> LocState.SEARCHING
            main.weight > 0.9 && main.radiusM < 3.0 && suppressFoundSteps == 0 -> LocState.FOUND
            main.weight > 0.6 && main.radiusM <= 8.0 -> LocState.APPROXIMATE
            else -> LocState.SEARCHING
        }
        return LocEstimate(zones, state)
    }

    private fun zones(): List<LocZone> {
        if (n == 0) return emptyList()
        val headings = DoubleArray(n) { lastHeadingRad + offset[it] }
        return ZoneClustering.zones(east, north, headings, weight, n)
    }

    /**
     * Alarm signal (spec §6.3, user's confidence idea): over the last [ALARM_STEPS] steps the main
     * zone kept walking across a marked corridor or into walls → re-seed 10 % around it and do not
     * claim FOUND on the next step.
     */
    private fun checkAlarm(meanAlign: Double, blockedShare: Double) {
        recentAlign.addLast(meanAlign); recentBlocked.addLast(blockedShare)
        while (recentAlign.size > ALARM_STEPS) { recentAlign.removeFirst(); recentBlocked.removeFirst() }
        if (recentAlign.size < ALARM_STEPS) return
        if (recentAlign.average() < 1 - params.beta / 2 || recentBlocked.average() > 0.5) {
            alarms++
            recentAlign.clear(); recentBlocked.clear()
            reseedAround(0.1)
            suppressFoundSteps = 2 // still active right after this step; cleared at the start of the next-but-one
        }
    }

    // ---- internals

    private fun allocate(count: Int) {
        if (east.size < count) {
            east = DoubleArray(count); north = DoubleArray(count); offset = DoubleArray(count)
            scale = DoubleArray(count); weight = DoubleArray(count); origin = IntArray(count); score = DoubleArray(count)
        }
        n = count
    }

    private fun randomStrideScale(): Double =
        if (params.strideScaleMax > params.strideScaleMin) random.nextDouble(params.strideScaleMin, params.strideScaleMax) else params.strideScaleMin

    private fun randomFreePoint(): PointM = jittered(freeCells[random.nextInt(freeCells.size)])

    /** Random point inside free cell [cell]. */
    private fun jittered(cell: Int): PointM {
        val c = map.grid.centerOf(cell)
        val r = map.grid.resM
        return PointM(c.x + (random.nextDouble() - 0.5) * r, c.y + (random.nextDouble() - 0.5) * r)
    }

    /** Free point within [radius] of [center], or anywhere free if none is found quickly. */
    private fun randomFreePointNear(center: PointM, radius: Double): PointM {
        repeat(60) {
            val p = PointM(center.x + random.nextDouble(-radius, radius), center.y + random.nextDouble(-radius, radius))
            if (p.distanceTo(center) <= radius && !map.grid.isBlocked(p)) return p
        }
        return randomFreePoint()
    }

    private fun mainCenter(): PointM = estimate().zones.firstOrNull()?.centerM ?: randomFreePoint()

    /** Replace a [fraction] of the particles by fresh ones around the main zone (recovery). */
    private fun reseedAround(fraction: Double) {
        val center = mainCenter()
        val count = (n * fraction).roundToInt().coerceAtLeast(1)
        for (k in 0 until count) {
            val i = random.nextInt(n)
            val donor = random.nextInt(n)
            val p = randomFreePointNear(center, params.recoveryRadiusM)
            east[i] = p.x; north[i] = p.y
            offset[i] = offset[donor]; scale[i] = scale[donor]
            score[i] = INJECTED_SCORE
            weight[i] = 1.0 / n
            origin[i] = -1
        }
        normalize()
    }

    private fun normalize() {
        var s = 0.0
        for (i in 0 until n) s += weight[i]
        if (s > 0) for (i in 0 until n) weight[i] /= s
    }

    /** Systematic resampling to the target size, then a small recovery injection. */
    private fun resample() {
        val target = if (estimate().state == LocState.FOUND) params.minParticles else params.maxParticles
        val ne = DoubleArray(target); val nn = DoubleArray(target); val no = DoubleArray(target); val ns = DoubleArray(target); val nor = IntArray(target); val nsc = DoubleArray(target)
        val step = 1.0 / target
        var u = random.nextDouble() * step
        var cum = weight[0]
        var j = 0
        for (k in 0 until target) {
            while (u > cum && j < n - 1) { j++; cum += weight[j] }
            ne[k] = east[j]; nn[k] = north[j]; no[k] = offset[j]; ns[k] = scale[j]; nor[k] = origin[j]; nsc[k] = score[j]
            u += step
        }
        east = ne; north = nn; offset = no; scale = ns; origin = nor; score = nsc
        weight = DoubleArray(target) { 1.0 / target }
        n = target
        if (params.recoveryFraction > 0) reseedAround(params.recoveryFraction)
    }

    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }

    companion object {
        const val MAX_HISTORY_STEPS = 300
        const val BAD_STEP_LIKELIHOOD = 0.05
        const val GOOD_STEP_LIKELIHOOD = 0.5
        const val BAD_STEPS_BEFORE_RECOVERY = 3
        const val GOOD_STEPS_TO_TRUST = 10
        const val LINE_STEPS = 5
        val LINE_SPREAD_RAD = Math.toRadians(8.0)
        const val MAX_STEPS_WITHOUT_LINE = 20
        const val FREE_OFFSET_SHARE = 0.1
        /** The compass is 15° off in 90 % of indoor places, up to 30° (spec §6.3); a corridor axis within this is "the compass's". */
        val COMPASS_PRIOR_RAD = Math.toRadians(40.0)
        val COMPASS_SIGMA_RAD = Math.toRadians(15.0)
        /** Hypotheses that ignore the compass anyway (a steel door or a magnet can turn it by 90°). */
        const val COMPASS_DISTRUST_SHARE = 0.1
        /** Widening of the phone angles at a hold change (the bridge assumes the body did not turn). */
        val HOLD_CHANGE_SIGMA_RAD = Math.toRadians(20.0)
        const val HOLD_FLIP_SHARE = 0.05
        const val HOLD_AXIS_SHARE = 0.16
        const val OUTSIDE_DISK_SHARE = 0.1
        /** Score memory: ~5 steps. */
        const val SCORE_KEEP = 0.8
        /** Score of a hypothesis injected without a past (yellow on the debug map). */
        const val INJECTED_SCORE = 0.5
        const val SYSTEM_SIGMA_FACTOR = 1.5
        const val SYSTEM_MIN_SIGMA_M = 10.0
        const val SYSTEM_OUTLIER_SHARE = 0.2
        const val ALARM_STEPS = 5

        fun wrapPi(a: Double): Double {
            var r = a % (2 * PI)
            if (r <= -PI) r += 2 * PI
            if (r > PI) r -= 2 * PI
            return r
        }
    }
}
