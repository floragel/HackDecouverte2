package com.lync.app.code
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
class ScreenshotTest {
    @Test fun decodeScreenshot() {
        val path = System.getenv("LYNC_GRAY")
        Assume.assumeTrue(path != null)
        val s = System.getenv("LYNC_S")!!.toInt()
        val px = File(path!!).readBytes()
        val l = LyncCode.Luma { x, y -> px[y * s + x].toInt() and 0xFF }
        println("SCREENSHOT_CODE=" + LyncCode.decode(l, s, s) { println("DBG " + it) })
        val (o, i) = LyncCode.encode(3534583304L)
        println("EXP_O " + o.joinToString("") { if (it) "1" else "0" })
        println("EXP_I " + i.joinToString("") { if (it) "1" else "0" })
        val cx = 320.0; val cy = 368.0; val R = 254.5
        for ((name, rr) in listOf("O" to LyncCode.RING_OUTER, "I" to LyncCode.RING_INNER)) {
            val sb = StringBuilder()
            for (k in 0 until 32) { val a = LyncCode.segmentAngle(k); val x = (cx + R * rr * sin(a)).toInt(); val y = (cy - R * rr * cos(a)).toInt(); sb.append(if (l.at(x, y) < 158) '1' else '0') }
            println("GOT_$name $sb")
        }
    }
}
