package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.angleDelta
import com.hackdecouverte.pdr.circularMean
import com.hackdecouverte.pdr.wrapAnglePi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Corridor-junction landmark matching (pure Kotlin, JVM unit-tested; ported from the iOS engine).
 *
 * PDR drifts *along* corridors (a step length 10 % off = 2 m error after 20 m) and the corridor
 * assist only corrects sideways, within 2 m. A turn, though, happens at a real junction. When the
 * walking direction settles on a new heading ≥ 55° away from the previous one, the matcher looks
 * for a plan junction within 7 m that has an edge back along the old heading and an edge along the
 * new one. The user is then really on that new corridor: it returns the projection of the current
 * position onto it, which `PdrTracker.onLandmark` replays gently (≤ 20 cm per step).
 */
class JunctionMatcher {
    private data class Sample(val step: Int, val pos: PointM, val heading: Double)

    private val samples = ArrayList<Sample>()
    private var cooldownUntilStep = Int.MIN_VALUE

    var lastJunctionId: String? = null
        private set

    fun reset() {
        samples.clear()
        cooldownUntilStep = Int.MIN_VALUE
        lastJunctionId = null
    }

    /**
     * Call once per accepted step with the map position and walking heading (0 = plan north,
     * clockwise). Returns the map point the user should be pulled toward, or null.
     */
    fun onStep(step: Int, mapPos: PointM, mapHeading: Double, plan: FloorPlan): PointM? {
        val last = samples.lastOrNull()
        if (last?.step == step) return null
        if (last != null && step < last.step) reset() // new trace (resume / reset): old samples are meaningless
        samples.add(Sample(step, mapPos, mapHeading))
        while (samples.size > HISTORY) samples.removeAt(0)
        if (samples.size < MIN_SAMPLES || step < cooldownUntilStep) return null

        val n = samples.size
        val recent = samples.subList(n - 3, n).map { it.heading }
        val post = circularMean(recent) ?: return null
        if (!recent.all { abs(angleDelta(post, it)) <= POST_SPREAD_RAD }) return null
        val pre = circularMean(samples.subList(n - 9, n - 6).map { it.heading }) ?: return null
        if (abs(angleDelta(pre, post)) < MIN_TURN_RAD) return null

        // where the turn happened: first sample that left the old heading
        val turnSample = samples.subList(n - 8, n)
            .firstOrNull { abs(angleDelta(pre, it.heading)) > TURN_START_RAD } ?: samples[n - 4]
        val (junction, outDir) = bestJunction(plan, turnSample.pos, pre, post) ?: return null

        val ux = sin(outDir)
        val uy = cos(outDir)
        val t = max(0.0, (mapPos.x - junction.posM.x) * ux + (mapPos.y - junction.posM.y) * uy)
        val target = PointM(junction.posM.x + ux * t, junction.posM.y + uy * t)
        cooldownUntilStep = step + COOLDOWN_STEPS
        lastJunctionId = junction.id
        return if (target.distanceTo(mapPos) >= MIN_CORRECTION_M) target else null
    }

    /** Closest node with an edge back along [pre] (where we came from) and an edge along [post]. */
    fun bestJunction(plan: FloorPlan, near: PointM, pre: Double, post: Double): Pair<FloorNode, Double>? {
        var best: FloorNode? = null
        var bestOut = 0.0
        var bestDist = Double.MAX_VALUE
        val back = wrapAnglePi(pre + PI)
        for (node in plan.nodes) {
            val d = node.posM.distanceTo(near)
            if (d > MAX_JUNCTION_DISTANCE_M) continue
            val nbrs = plan.neighbors(node.id)
            if (nbrs.size < 2) continue
            val dirs = nbrs.mapNotNull { nb ->
                if (nb.posM.distanceTo(node.posM) < 0.5) null
                else atan2(nb.posM.x - node.posM.x, nb.posM.y - node.posM.y)
            }
            if (dirs.none { abs(angleDelta(back, it)) <= MATCH_TOLERANCE_RAD }) continue
            val out = dirs.minByOrNull { abs(angleDelta(post, it)) } ?: continue
            if (abs(angleDelta(post, out)) > MATCH_TOLERANCE_RAD) continue
            if (d < bestDist) {
                best = node
                bestOut = out
                bestDist = d
            }
        }
        return best?.let { it to bestOut }
    }

    companion object {
        val MIN_TURN_RAD = Math.toRadians(55.0)
        val MATCH_TOLERANCE_RAD = Math.toRadians(30.0)
        private val POST_SPREAD_RAD = Math.toRadians(15.0)
        private val TURN_START_RAD = Math.toRadians(25.0)
        const val MAX_JUNCTION_DISTANCE_M = 7.0
        const val MIN_CORRECTION_M = 0.6
        private const val COOLDOWN_STEPS = 10
        private const val HISTORY = 14
        private const val MIN_SAMPLES = 9
    }
}
