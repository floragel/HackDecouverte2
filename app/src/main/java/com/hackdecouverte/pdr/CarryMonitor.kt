package com.hackdecouverte.pdr

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** How the phone rides on the body (spec §6.6). */
enum class CarryMode {
    /** Still relative to the body: in hand while looking at it, chest pocket, bag held still. */
    STEADY,

    /** Rocking with every stride: trouser pocket, hand swinging, bag on the shoulder. */
    SWINGING
}

/**
 * Watches the direction of "up" in the phone's own frame (row 3 of the rotation matrix, i.e. gravity
 * as the phone sees it). Turning the body never changes it; changing how the phone is held always
 * does, unless the phone only spins about the vertical in the hand (undetectable here).
 *
 * - **Swinging:** "up" wobbles around its 1 s mean by more than [swingOnDeg] RMS → the phone rocks
 *   with the gait, and a step's heading must be averaged over a whole stride (spec §6.6).
 * - **Hold change:** the 1 s mean of "up" leaves the current hold by more than [startDeg] (the
 *   transition starts), then settles (two successive 1 s means within [settledDeg]) more than
 *   [commitDeg] away → [onHoldChanged] with the jump of the phone's own heading (twist about the
 *   vertical, stride-averaged before and after). The body is assumed not to have turned meanwhile,
 *   so the PDR can keep its heading continuous ("bridging"); the map filter still widens its
 *   phone→walking angles in case it did.
 * - **Reading pose:** steady, screen on, upright or flat in portrait — the phone's heading is then
 *   the walking direction give or take ~20°, which is what makes the compass a useful prior.
 *
 * Pure Kotlin; fed with the game rotation matrix at its own rate, evaluated every [EVAL_PERIOD_NS].
 */
class CarryMonitor(
    private val startDeg: Double = 25.0,
    private val commitDeg: Double = 35.0,
    private val settledDeg: Double = 12.0,
    private val swingOnDeg: Double = 9.0,
    private val swingOffDeg: Double = 6.0
) {
    private class Sample(val t: Long, val ux: Double, val uy: Double, val uz: Double, val twist: Double?)

    private val samples = ArrayDeque<Sample>()
    private var lastEvalNs = Long.MIN_VALUE
    private var ref: DoubleArray? = null
    private var transitionStartNs = 0L
    private var twistBefore: Double? = null

    var mode: CarryMode = CarryMode.STEADY
        private set
    var inTransition: Boolean = false
        private set
    var holdChanges: Int = 0
        private set

    /** RMS wobble of "up" around its 1 s mean, degrees. */
    var swingDeg: Double = 0.0
        private set

    /** 1 s mean of "up" in phone coordinates (x right, y top, z out of the screen), null before 1 s of data. */
    var up: DoubleArray? = null
        private set

    /** Screen on (set by the app; a phone whose screen is off is not being read). */
    var screenInteractive: Boolean = true

    /** (time, jump of the phone's heading in radians, clockwise) when a new hold has settled. */
    var onHoldChanged: ((tNs: Long, deltaTwistRad: Double) -> Unit)? = null

    /** Steady, screen on, portrait held flat or upright and not upside down: phone heading ≈ walking direction. */
    val readingPose: Boolean
        get() {
            val u = up ?: return false
            return mode == CarryMode.STEADY && screenInteractive && !inTransition &&
                kotlin.math.abs(u[0]) < 0.6 && u[1] > -0.3 && u[2] > -0.3
        }

    fun reset() {
        samples.clear()
        lastEvalNs = Long.MIN_VALUE
        ref = null
        up = null
        inTransition = false
        twistBefore = null
        mode = CarryMode.STEADY
        swingDeg = 0.0
    }

    /** [r]: row-major rotation matrix (device → world) of the game rotation vector at [tNs]. */
    fun onRotation(tNs: Long, r: DoubleArray) {
        if (r.size < 9) return
        if (samples.isNotEmpty() && tNs <= samples.last().t) return
        samples.addLast(Sample(tNs, r[6], r[7], r[8], twistYaw(r)))
        while (samples.first().t < tNs - BUFFER_NS) samples.removeFirst()
        if (lastEvalNs != Long.MIN_VALUE && tNs - lastEvalNs < EVAL_PERIOD_NS) return
        lastEvalNs = tNs
        evaluate(tNs)
    }

    private fun evaluate(t: Long) {
        if (samples.first().t > t - WINDOW_NS + EVAL_PERIOD_NS) return // need a full second
        val m1 = meanUp(t - WINDOW_NS, t) ?: return
        up = m1

        var s2 = 0.0
        var n = 0
        for (s in samples) {
            if (s.t < t - WINDOW_NS) continue
            val a = angleBetween(s.ux, s.uy, s.uz, m1)
            s2 += a * a
            n++
        }
        swingDeg = Math.toDegrees(sqrt(s2 / n))
        mode = when {
            swingDeg > swingOnDeg -> CarryMode.SWINGING
            swingDeg < swingOffDeg -> CarryMode.STEADY
            else -> mode
        }

        val r = ref
        if (r == null) {
            ref = m1
            return
        }
        val drift = Math.toDegrees(angleBetween(m1[0], m1[1], m1[2], r))
        if (!inTransition) {
            if (drift > startDeg) {
                inTransition = true
                transitionStartNs = t
                // The 1 s mean only drifts this far once the move is under way: the second before it is the old hold.
                twistBefore = meanTwist(t - 2 * WINDOW_NS, t - WINDOW_NS)
            } else if (drift < FOLLOW_DEG) {
                // Follow slow posture changes so they never add up to a false hold change.
                ref = normalize(doubleArrayOf(r[0] * 0.97 + m1[0] * 0.03, r[1] * 0.97 + m1[1] * 0.03, r[2] * 0.97 + m1[2] * 0.03))
            }
            return
        }
        val m2 = meanUp(t - 2 * WINDOW_NS, t - WINDOW_NS)
        val settled = m2 != null && Math.toDegrees(angleBetween(m1[0], m1[1], m1[2], m2)) < settledDeg
        val timedOut = t - transitionStartNs > MAX_TRANSITION_NS
        if ((settled || timedOut) && drift > commitDeg) {
            val after = meanTwist(t - WINDOW_NS, t)
            val before = twistBefore
            ref = m1
            inTransition = false
            holdChanges++
            onHoldChanged?.invoke(t, if (after != null && before != null) angleDelta(before, after) else 0.0)
        } else if (settled && drift < startDeg) {
            inTransition = false // a glance, then back to the same hold
        } else if (timedOut) {
            inTransition = false
            ref = m1
        }
    }

    private fun meanUp(from: Long, to: Long): DoubleArray? {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var n = 0
        for (s in samples) {
            if (s.t < from || s.t > to) continue
            x += s.ux; y += s.uy; z += s.uz; n++
        }
        if (n < MIN_SAMPLES) return null
        return normalize(doubleArrayOf(x, y, z))
    }

    private fun meanTwist(from: Long, to: Long): Double? {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (s in samples) {
            val tw = s.twist ?: continue
            if (s.t < from || s.t > to) continue
            sx += sin(tw); sy += cos(tw); n++
        }
        if (n < MIN_SAMPLES || hypot(sx, sy) < 1e-6) return null
        return atan2(sx, sy)
    }

    companion object {
        private const val WINDOW_NS = 1_000_000_000L
        private const val BUFFER_NS = 4_000_000_000L
        const val EVAL_PERIOD_NS = 100_000_000L
        private const val MAX_TRANSITION_NS = 8_000_000_000L
        private const val MIN_SAMPLES = 5
        private const val FOLLOW_DEG = 10.0

        /** Phone heading as twist about the vertical (same formula as [OrientationProcessor]); null when screen-down. */
        fun twistYaw(r: DoubleArray): Double? {
            val s = r[1] - r[3]
            val c = r[0] + r[4]
            return if (hypot(s, c) >= 0.2) atan2(s, c) else null
        }

        private fun normalize(v: DoubleArray): DoubleArray {
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return if (n < 1e-9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }

        private fun angleBetween(x: Double, y: Double, z: Double, b: DoubleArray): Double {
            val n = sqrt(x * x + y * y + z * z)
            if (n < 1e-9) return 0.0
            return acos(((x * b[0] + y * b[1] + z * b[2]) / n).coerceIn(-1.0, 1.0))
        }
    }
}
