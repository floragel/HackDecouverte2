package com.lync.app.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class LyncCodeTest {

    /** Renders a code into a grayscale frame like a camera would see it. */
    private fun frame(code: Long, w: Int, h: Int, cx: Float, cy: Float, r: Float, rotation: Double, noise: Int, seed: Int): IntArray {
        val bits = LyncCode.encode(code)
        val rnd = Random(seed)
        val px = IntArray(w * h)
        val c = cos(-rotation).toFloat(); val s = sin(-rotation).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx) / r; val dy = (y - cy) / r
            val nx = dx * c - dy * s; val ny = dx * s + dy * c
            val base = if (nx * nx + ny * ny <= 1.2f * 1.2f) {
                if (LyncCode.isInk(bits, nx, ny)) 40 else 230    // code on a white card
            } else 90                                              // phone / table around it
            px[y * w + x] = (base + rnd.nextInt(-noise, noise + 1)).coerceIn(0, 255)
        }
        return px
    }

    @Test fun encodeDecodeBits() {
        for (code in listOf(1L, 0xDEADBEEFL, 123456789L, 0xFFFFFFFFL)) {
            val (o, i) = LyncCode.encode(code)
            assertEquals(code, LyncCode.fromBits(o, i))
            // any rotation
            val shift = 7
            val ro = BooleanArray(32) { o[(it + shift) % 32] }; val ri = BooleanArray(32) { i[(it + shift) % 32] }
            assertEquals(code, LyncCode.fromBits(ro, ri))
        }
    }

    @Test fun decodesRenderedFrames() {
        val codes = listOf(0x1A2B3C4DL, 987654321L, 42L, 0xCAFEBABEL)
        var ok = 0
        codes.forEachIndexed { k, code ->
            val w = 640; val h = 480
            val px = frame(code, w, h, cx = 320f + k * 6, cy = 240f - k * 5, r = 170f + k * 8, rotation = 0.37 * k + 0.1, noise = 18, seed = k)
            val decoded = LyncCode.decode({ x, y -> px[y * w + x] }, w, h)
            if (decoded == code) ok++
        }
        assertEquals(codes.size, ok)
    }

    @Test fun decodesRandomCodesAnyRotation() {
        val rnd = Random(2026)
        var ok = 0
        repeat(12) { k ->
            val code = rnd.nextLong(1, 0xFFFFFFFFL)
            val w = 640; val h = 480
            val px = frame(code, w, h, cx = 300f + rnd.nextInt(40), cy = 220f + rnd.nextInt(40), r = 140f + rnd.nextInt(60),
                rotation = rnd.nextDouble(0.0, 6.28), noise = 25, seed = k)
            if (LyncCode.decode({ x, y -> px[y * w + x] }, w, h) == code) ok++
        }
        assertEquals(12, ok)
    }

    @Test fun rejectsBlankFrames() {
        val w = 320; val h = 240
        val px = IntArray(w * h) { 200 }
        assertNull(LyncCode.decode({ x, y -> px[y * w + x] }, w, h))
    }
}
