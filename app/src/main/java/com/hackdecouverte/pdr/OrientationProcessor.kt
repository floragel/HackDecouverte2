package com.hackdecouverte.pdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

fun wrapAnglePi(angle: Double): Double {
    var r = angle % (2.0 * PI)
    if (r <= -PI) r += 2.0 * PI
    if (r > PI) r -= 2.0 * PI
    return r
}

fun angleDelta(from: Double, to: Double): Double = wrapAnglePi(to - from)

fun circularMean(angles: List<Double>): Double? {
    if (angles.isEmpty()) return null
    var s = 0.0
    var c = 0.0
    for (a in angles) {
        s += sin(a)
        c += cos(a)
    }
    if (hypot(s, c) / angles.size < 0.01) return null
    return atan2(s, c)
}

fun rotationMatrixFromVector(values: FloatArray, size: Int = values.size): DoubleArray? {
    if (size < 3 || values.size < 3) return null
    var x = values[0].toDouble()
    var y = values[1].toDouble()
    var z = values[2].toDouble()
    var w = if (size >= 4 && values.size >= 4) values[3].toDouble()
    else sqrt(max(0.0, 1.0 - x * x - y * y - z * z))
    if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !w.isFinite()) return null
    val n = sqrt(x * x + y * y + z * z + w * w)
    if (n < 1e-9) return null
    x /= n; y /= n; z /= n; w /= n
    val r = DoubleArray(9)
    r[0] = 1.0 - 2.0 * (y * y + z * z)
    r[1] = 2.0 * (x * y - z * w)
    r[2] = 2.0 * (x * z + y * w)
    r[3] = 2.0 * (x * y + z * w)
    r[4] = 1.0 - 2.0 * (x * x + z * z)
    r[5] = 2.0 * (y * z - x * w)
    r[6] = 2.0 * (x * z - y * w)
    r[7] = 2.0 * (y * z + x * w)
    r[8] = 1.0 - 2.0 * (x * x + y * y)
    return r
}

fun worldVerticalFromRotationMatrix(r: DoubleArray): Triple<Double, Double, Double> =
    Triple(r[6], r[7], r[8])

class OrientationProcessor(
    private val bufferNs: Long = 2_500_000_000L,
    private val meanWindowNs: Long = 150_000_000L,
    private val minHorizontalNorm: Double = 0.25
) {
    private data class YawSample(val timestampNs: Long, val yaw: Double, val valid: Boolean)

    private val samples = ArrayDeque<YawSample>()
    private var lastTimestampNs = Long.MIN_VALUE

    var lastValidYaw: Double? = null
        private set

    var lastTilted: Boolean = false
        private set

    fun onRotationVector(timestampNs: Long, values: FloatArray, size: Int = values.size) {
        val r = rotationMatrixFromVector(values, size) ?: return
        val east = r[1]
        val north = r[4]
        val norm = hypot(east, north)
        val valid = norm >= minHorizontalNorm && east.isFinite() && north.isFinite()
        if (timestampNs <= lastTimestampNs && samples.isNotEmpty()) return
        lastTimestampNs = timestampNs
        lastTilted = !valid
        val yaw = atan2(east, north)
        samples.addLast(YawSample(timestampNs, yaw, valid))
        if (valid) lastValidYaw = yaw
        val cutoff = timestampNs - bufferNs
        while (samples.isNotEmpty() && samples.first().timestampNs < cutoff) {
            samples.removeFirst()
        }
    }

    fun meanHeadingAt(timestampNs: Long): Double? {
        if (samples.isEmpty()) return null
        val newest = samples.lastOrNull { it.timestampNs <= timestampNs } ?: return null
        if (!newest.valid) return null
        val window = ArrayList<Double>()
        val from = timestampNs - meanWindowNs
        for (s in samples) {
            if (s.timestampNs in from..timestampNs && s.valid) window.add(s.yaw)
        }
        return circularMean(window)
    }

    fun clear() {
        samples.clear()
        lastTimestampNs = Long.MIN_VALUE
        lastValidYaw = null
        lastTilted = false
    }
}
