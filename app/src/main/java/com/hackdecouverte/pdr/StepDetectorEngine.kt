package com.hackdecouverte.pdr

import kotlin.math.sqrt

class FallbackStepDetector(
    private val warmupNs: Long = 500_000_000L,
    private val minIntervalNs: Long = 330_000_000L,
    private val thresholdMss: Double = 1.0,
    private val rearmMss: Double = 0.3,
    private val smoothTauS: Double = 0.04,
    private val gravityTauS: Double = 0.5,
    /**
     * Gait-adaptive threshold (ported from iOS, opt-in): the threshold is half the smoothed
     * vertical-acceleration peak of the last 2 s (clamped 0.55…1.6 m/s², rearm at 30 %), so soft
     * steps (slow walk, phone held gently) are caught and the heel-strike bounce of strong steps
     * isn't counted twice. Below a 0.8 m/s² peak nothing counts (hand tremor, standing still).
     * `false` keeps the fixed [thresholdMss] / [rearmMss] behaviour.
     */
    val adaptive: Boolean = false
) {
    var onStep: ((timestampNs: Long) -> Unit)? = null

    /** Per-100 ms bucket maxima of the smoothed signal over the last 2 s (adaptive mode only). */
    private val bucketStartNs = ArrayDeque<Long>()
    private val bucketPeak = ArrayDeque<Double>()

    private var active = false
    private var startNs = 0L
    private var lastSeenNs = Long.MIN_VALUE
    private var lastGravityNs = Long.MIN_VALUE
    private var lastSmoothNs = Long.MIN_VALUE
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var gravityReady = false
    private var smoothed = 0.0
    private var smoothReady = false
    private var armed = true
    private var lastStepNs = Long.MIN_VALUE

    fun start(nowNs: Long) {
        active = true
        startNs = nowNs
        lastSeenNs = Long.MIN_VALUE
        lastGravityNs = Long.MIN_VALUE
        lastSmoothNs = Long.MIN_VALUE
        gravityReady = false
        smoothReady = false
        smoothed = 0.0
        armed = true
        lastStepNs = Long.MIN_VALUE
        bucketStartNs.clear()
        bucketPeak.clear()
    }

    /** Current (threshold, rearm) pair; null = adaptive gate closed (too little motion to be walking). */
    private fun thresholds(): Pair<Double, Double>? {
        if (!adaptive) return thresholdMss to rearmMss
        val peak = bucketPeak.maxOrNull() ?: 0.0
        if (peak < ADAPTIVE_MIN_PEAK_MSS) return null
        val th = (0.5 * peak).coerceIn(ADAPTIVE_MIN_THRESHOLD_MSS, ADAPTIVE_MAX_THRESHOLD_MSS)
        return th to th * ADAPTIVE_REARM_RATIO
    }

    private fun trackPeak(timestampNs: Long, value: Double) {
        val start = timestampNs - timestampNs % BUCKET_NS
        if (bucketStartNs.isNotEmpty() && bucketStartNs.last() == start) {
            bucketPeak[bucketPeak.lastIndex] = maxOf(bucketPeak.last(), value)
        } else {
            bucketStartNs.addLast(start)
            bucketPeak.addLast(value)
        }
        while (bucketStartNs.isNotEmpty() && timestampNs - bucketStartNs.first() > PEAK_WINDOW_NS) {
            bucketStartNs.removeFirst()
            bucketPeak.removeFirst()
        }
    }

    fun stop() {
        active = false
    }

    fun onLinearAcceleration(
        timestampNs: Long,
        ax: Double, ay: Double, az: Double,
        ux: Double, uy: Double, uz: Double
    ) {
        if (!active || timestampNs <= lastSeenNs) return
        lastSeenNs = timestampNs
        val n = sqrt(ux * ux + uy * uy + uz * uz)
        if (n < 1e-6 || !n.isFinite()) return
        feed(timestampNs, (ax * ux + ay * uy + az * uz) / n)
    }

    fun onAccelerometer(timestampNs: Long, ax: Double, ay: Double, az: Double) {
        if (!active || timestampNs <= lastSeenNs) return
        lastSeenNs = timestampNs
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite()) return
        if (!gravityReady) {
            gx = ax; gy = ay; gz = az
            gravityReady = true
            lastGravityNs = timestampNs
            return
        }
        val dt = (timestampNs - lastGravityNs) / 1e9
        if (dt <= 0.0) return
        lastGravityNs = timestampNs
        val alpha = dt / (gravityTauS + dt)
        gx += alpha * (ax - gx)
        gy += alpha * (ay - gy)
        gz += alpha * (az - gz)
        val n = sqrt(gx * gx + gy * gy + gz * gz)
        if (n < 1e-6) return
        feed(timestampNs, ((ax - gx) * gx + (ay - gy) * gy + (az - gz) * gz) / n)
    }

    private fun feed(timestampNs: Long, vertical: Double) {
        if (!vertical.isFinite()) return
        if (!smoothReady) {
            smoothed = vertical
            smoothReady = true
            lastSmoothNs = timestampNs
            return
        }
        val dt = (timestampNs - lastSmoothNs) / 1e9
        if (dt <= 0.0) return
        lastSmoothNs = timestampNs
        val previous = smoothed
        smoothed += (dt / (smoothTauS + dt)) * (vertical - smoothed)
        if (adaptive) trackPeak(timestampNs, smoothed)
        if (timestampNs < startNs + warmupNs) return
        val (threshold, rearm) = thresholds() ?: run {
            armed = true
            return
        }
        val refractoryOk = lastStepNs == Long.MIN_VALUE ||
            timestampNs - lastStepNs >= minIntervalNs
        val risingCrossing = previous < threshold && smoothed >= threshold
        if (armed && risingCrossing && refractoryOk) {
            armed = false
            lastStepNs = timestampNs
            onStep?.invoke(timestampNs)
        } else if (!armed && smoothed < rearm) {
            armed = true
        }
    }

    companion object {
        const val ADAPTIVE_MIN_PEAK_MSS = 0.8
        private const val ADAPTIVE_MIN_THRESHOLD_MSS = 0.55
        private const val ADAPTIVE_MAX_THRESHOLD_MSS = 1.6
        private const val ADAPTIVE_REARM_RATIO = 0.3
        private const val BUCKET_NS = 100_000_000L
        private const val PEAK_WINDOW_NS = 2_000_000_000L
    }
}
