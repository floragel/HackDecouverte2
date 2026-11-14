package com.lync.app.code

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Lync code — our own scannable code (not a QR code).
 *
 *   ┌─ finder ring (solid)            r 0.90 … 1.00
 *   ├─ outer data ring, 32 segments   r ≈ 0.76   -> the 32-bit profile code
 *   ├─ inner data ring, 32 segments   r ≈ 0.58   -> 16-bit CRC + 16-bit check
 *   └─ Lync mark in the middle        r < 0.40   (ignored by the decoder)
 *
 * A "1" bit is part of a rounded arc (consecutive 1s merge into one arc), a "0" bit is a tiny dot.
 * Decoding is rotation-free: we try the 32 possible rotations and keep the one whose CRC matches.
 *
 * Pure Kotlin, no Android dependency -> unit-tested, and ported 1:1 to Swift.
 */
object LyncCode {
    const val SEGMENTS = 32
    const val FINDER_INNER = 0.90f
    const val RING_OUTER = 0.76f
    const val RING_INNER = 0.58f
    const val STROKE = 0.12f      // arc thickness (fraction of R)
    const val DOT = 0.025f        // radius of a "0" dot (kept small so it never reads as a 1)
    const val MARK = 0.40f

    private const val SEG_ANGLE = 2 * PI / SEGMENTS

    /** Angle (radians, clockwise from 12 o'clock in screen coordinates) of segment i's center. */
    fun segmentAngle(i: Int): Double = i * SEG_ANGLE

    // ---------------------------------------------------------------- encoding

    fun crc16(code: Long): Int {
        var crc = 0xFFFF
        for (shift in intArrayOf(24, 16, 8, 0)) {
            crc = crc xor (((code shr shift) and 0xFF).toInt() shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun check16(code: Long): Int = (((code shr 16) xor code xor 0xA55A).toInt()) and 0xFFFF

    /** Outer ring bits, inner ring bits (each 32, MSB first, segment 0 at the top). */
    fun encode(code: Long): Pair<BooleanArray, BooleanArray> {
        require(code in 0..0xFFFFFFFFL)
        val outer = BooleanArray(SEGMENTS) { i -> (code shr (31 - i)) and 1L == 1L }
        val innerWord = (crc16(code).toLong() shl 16) or check16(code).toLong()
        val inner = BooleanArray(SEGMENTS) { i -> (innerWord shr (31 - i)) and 1L == 1L }
        return outer to inner
    }

    private fun toLong(bits: BooleanArray, shift: Int): Long {
        var v = 0L
        for (i in 0 until SEGMENTS) v = (v shl 1) or if (bits[(i + shift) % SEGMENTS]) 1L else 0L
        return v
    }

    /** Tries every rotation (and the mirrored reading) and returns the code if a CRC matches. */
    fun fromBits(outer: BooleanArray, inner: BooleanArray): Long? {
        for (mirrored in listOf(false, true)) {
            val o = if (mirrored) BooleanArray(SEGMENTS) { outer[(SEGMENTS - it) % SEGMENTS] } else outer
            val n = if (mirrored) BooleanArray(SEGMENTS) { inner[(SEGMENTS - it) % SEGMENTS] } else inner
            for (shift in 0 until SEGMENTS) {
                val code = toLong(o, shift)
                val word = toLong(n, shift)
                if ((word shr 16).toInt() == crc16(code) && (word and 0xFFFF).toInt() == check16(code)) return code
            }
        }
        return null
    }

    /**
     * Ink test in normalized coordinates (center 0,0, R = 1, y down).
     * Used by the bitmap renderer and the unit tests; the Compose renderer draws the same shapes.
     */
    fun isInk(code: Long, x: Float, y: Float): Boolean = isInk(encode(code), x, y)

    fun isInk(bits: Pair<BooleanArray, BooleanArray>, x: Float, y: Float): Boolean {
        val r = hypot(x, y)
        if (r in FINDER_INNER..1f) return true
        val angle = (atan2(x.toDouble(), -y.toDouble()) + 2 * PI) % (2 * PI) // clockwise from top
        for ((ringR, ring) in listOf(RING_OUTER to bits.first, RING_INNER to bits.second)) {
            if (abs(r - ringR) > STROKE) continue
            val seg = ((angle / SEG_ANGLE).roundToInt()) % SEGMENTS
            for (s in intArrayOf(seg, (seg + 1) % SEGMENTS, (seg + SEGMENTS - 1) % SEGMENTS)) {
                if (inkForSegment(ring, s, ringR, x, y)) return true
            }
        }
        return false
    }

    /** Geometry of one segment: arc runs with round caps for 1s, dot for 0s. */
    private fun inkForSegment(ring: BooleanArray, s: Int, ringR: Float, x: Float, y: Float): Boolean {
        val half = STROKE / 2
        val cx = (ringR * sin(segmentAngle(s))).toFloat()
        val cy = (-ringR * cos(segmentAngle(s))).toFloat()
        if (!ring[s]) return hypot(x - cx, y - cy) <= DOT
        // round cap / body around this segment's center
        if (hypot(x - cx, y - cy) <= half) return true
        // body towards the next segment if it is also a 1 (merged arc)
        val next = (s + 1) % SEGMENTS
        if (ring[next]) {
            val a0 = segmentAngle(s); val a1 = a0 + SEG_ANGLE
            val a = (atan2(x.toDouble(), -y.toDouble()) + 2 * PI) % (2 * PI)
            val within = if (a1 > 2 * PI) (a >= a0 || a <= a1 - 2 * PI) else (a in a0..a1)
            if (within && abs(hypot(x, y) - ringR) <= half) return true
        }
        return false
    }

    // ---------------------------------------------------------------- decoding

    /** Grayscale access to a camera frame (0 = black, 255 = white). */
    fun interface Luma { fun at(x: Int, y: Int): Int }

    /**
     * Finds a Lync code roughly centered in a [w]×[h] frame. Returns the 32-bit code or null.
     * Steps: Otsu threshold -> rays from the outside find the finder ring -> circle fit -> sample both rings.
     */
    fun decode(luma: Luma, w: Int, h: Int, debug: ((String) -> Unit)? = null): Long? {
        val threshold = otsu(luma, w, h)
        var cx = w / 2f
        var cy = h / 2f
        val maxR = 0.46f * minOf(w, h)
        var radius = 0f
        repeat(2) { pass ->
            val start = if (pass == 0) maxR else radius * 1.25f
            val pts = ArrayList<Pair<Float, Float>>()
            for (k in 0 until 48) {
                val a = k * 2 * PI / 48
                val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
                // walk inward: first reach the white card around the code, then the first dark pixel = finder ring
                var r = start
                var seenLight = 0
                while (r > maxR * 0.15f) {
                    val x = (cx + dx * r).toInt(); val y = (cy + dy * r).toInt()
                    if (x in 1 until w - 1 && y in 1 until h - 1) {
                        val dark = luma.at(x, y) < threshold
                        if (!dark) seenLight++
                        else if (seenLight >= 3) {
                            val x2 = (cx + dx * (r - 2)).toInt(); val y2 = (cy + dy * (r - 2)).toInt()
                            if (luma.at(x2, y2) < threshold) { pts += (cx + dx * r) to (cy + dy * r); break }
                        } else seenLight = 0
                    }
                    r -= 1f
                }
            }
            if (pts.size < 12) return null
            val fit = fitCircle(pts) ?: return null
            // drop outliers (things hit outside the code) and refit
            val kept = pts.filter { abs(hypot(it.first - fit.first, it.second - fit.second) - fit.third) < fit.third * 0.08f }
            if (kept.size < 12) return null
            val fit2 = fitCircle(kept) ?: return null
            cx = fit2.first; cy = fit2.second; radius = fit2.third
        }
        debug?.invoke("T=$threshold c=($cx,$cy) R=$radius")
        if (radius < 20f) return null

        fun dark(nx: Float, ny: Float): Boolean {
            val x = (cx + nx * radius).toInt(); val y = (cy + ny * radius).toInt()
            return x in 0 until w && y in 0 until h && luma.at(x, y) < threshold
        }

        // The camera image can be rotated by any angle: search the sub-segment phase in 1/8 steps.
        for (step in 0 until 8) {
            val rot = (step / 8.0 - 0.5) * SEG_ANGLE
            fun ring(ringR: Float) = BooleanArray(SEGMENTS) { i ->
                val spread = 0.035f / ringR
                var votes = 0
                for (d in floatArrayOf(-spread, 0f, spread)) {
                    val a = segmentAngle(i) + d + rot
                    for (rr in floatArrayOf(ringR - 0.02f, ringR + 0.02f)) {
                        if (dark((rr * sin(a)).toFloat(), (-rr * cos(a)).toFloat())) votes++
                    }
                }
                votes >= 5 // of 6: a dot only lights the 2 center samples, an arc lights all 6
            }
            val ro = ring(RING_OUTER); val ri = ring(RING_INNER)
            debug?.invoke("phase=$step O=" + ro.joinToString("") { if (it) "1" else "0" } + " I=" + ri.joinToString("") { if (it) "1" else "0" })
            fromBits(ro, ri)?.let { return it }
        }
        return null
    }

    private fun otsu(luma: Luma, w: Int, h: Int): Int {
        val hist = IntArray(256)
        val step = maxOf(2, minOf(w, h) / 160)
        var n = 0
        var y = h / 8
        while (y < h * 7 / 8) {
            var x = w / 8
            while (x < w * 7 / 8) { hist[luma.at(x, y).coerceIn(0, 255)]++; n++; x += step }
            y += step
        }
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0; var best = 0.0; var t = 128
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0) continue
            val wF = n - wB; if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = i }
        }
        return t
    }

    /** Kåsa least-squares circle fit -> (cx, cy, r). */
    private fun fitCircle(pts: List<Pair<Float, Float>>): Triple<Float, Float, Float>? {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        val n = pts.size.toDouble()
        for ((xf, yf) in pts) {
            val x = xf.toDouble(); val y = yf.toDouble(); val z = x * x + y * y
            sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; sxz += x * z; syz += y * z; sz += z
        }
        // Solve [sxx sxy sx; sxy syy sy; sx sy n] * [D E F] = -[sxz syz sz]
        val m = arrayOf(doubleArrayOf(sxx, sxy, sx, -sxz), doubleArrayOf(sxy, syy, sy, -syz), doubleArrayOf(sx, sy, n, -sz))
        for (c in 0 until 3) {
            val p = (c until 3).maxByOrNull { abs(m[it][c]) } ?: return null
            val tmp = m[c]; m[c] = m[p]; m[p] = tmp
            if (abs(m[c][c]) < 1e-9) return null
            for (r in 0 until 3) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (k in c until 4) m[r][k] -= f * m[c][k]
            }
        }
        val d = m[0][3] / m[0][0]; val e = m[1][3] / m[1][1]; val f = m[2][3] / m[2][2]
        val cx = -d / 2; val cy = -e / 2
        val r2 = cx * cx + cy * cy - f
        if (r2 <= 0) return null
        return Triple(cx.toFloat(), cy.toFloat(), sqrt(r2).toFloat())
    }
}
