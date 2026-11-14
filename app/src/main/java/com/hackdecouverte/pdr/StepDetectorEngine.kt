package com.hackdecouverte.pdr

import kotlin.math.sqrt

class FallbackStepDetector(
    private val warmupNs: Long = 500_000_000L,
    private val minIntervalNs: Long = 330_000_000L,
    private val thresholdMss: Double = 1.0,
    private val rearmMss: Double = 0.3,
    private val smoothTauS: Double = 0.04,
    private val gravityTauS: Double = 0.5
) {
    var onStep: ((timestampNs: Long) -> Unit)? = null

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
        if (timestampNs < startNs + warmupNs) return
        val refractoryOk = lastStepNs == Long.MIN_VALUE ||
            timestampNs - lastStepNs >= minIntervalNs
        val risingCrossing = previous < thresholdMss && smoothed >= thresholdMss
        if (armed && risingCrossing && refractoryOk) {
            armed = false
            lastStepNs = timestampNs
            onStep?.invoke(timestampNs)
        } else if (!armed && smoothed < rearmMss) {
            armed = true
        }
    }
}
