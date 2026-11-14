package com.hackdecouverte.pdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun rotationVectorFor(yawRad: Double, tiltAboutXRad: Double = 0.0): FloatArray {
    val a = -yawRad
    val sa = sin(a / 2.0)
    val ca = cos(a / 2.0)
    val sb = sin(tiltAboutXRad / 2.0)
    val cb = cos(tiltAboutXRad / 2.0)
    return floatArrayOf(
        (ca * sb).toFloat(),
        (sa * sb).toFloat(),
        (sa * cb).toFloat(),
        (ca * cb).toFloat()
    )
}

class OrientationProcessorTest {

    @Test
    fun circularMeanOf179AndMinus179IsNearPi() {
        val p = OrientationProcessor()
        p.onRotationVector(100_000_000L, rotationVectorFor(Math.toRadians(179.0)))
        p.onRotationVector(120_000_000L, rotationVectorFor(Math.toRadians(-179.0)))
        val mean = p.meanHeadingAt(120_000_000L)
        assertTrue(mean != null)
        assertTrue(abs(angleDelta(mean!!, PI)) < 0.02)
    }

    @Test
    fun angleDeltaUsesShortestWrap() {
        val d = Math.toRadians(179.0)
        assertEquals(Math.toRadians(2.0), angleDelta(d, -d), 1e-9)
        assertEquals(Math.toRadians(-2.0), angleDelta(-d, d), 1e-9)
        assertEquals(0.0, angleDelta(0.3, 0.3), 1e-12)
    }

    @Test
    fun emptyWindowMeanIsInvalid() {
        assertNull(OrientationProcessor().meanHeadingAt(1_000_000L))
    }

    @Test
    fun cancellingSamplesMeanIsInvalid() {
        val p = OrientationProcessor()
        p.onRotationVector(100_000_000L, rotationVectorFor(0.0))
        p.onRotationVector(110_000_000L, rotationVectorFor(PI))
        assertNull(p.meanHeadingAt(110_000_000L))
    }

    @Test
    fun flatProjectionIsValid() {
        val p = OrientationProcessor()
        p.onRotationVector(100_000_000L, rotationVectorFor(0.3))
        val mean = p.meanHeadingAt(100_000_000L)
        assertTrue(mean != null)
        assertEquals(0.3, mean!!, 1e-6)
        assertFalse(p.lastTilted)
    }

    @Test
    fun tilted45ProjectionIsValid() {
        val p = OrientationProcessor()
        p.onRotationVector(100_000_000L, rotationVectorFor(0.0, Math.toRadians(45.0)))
        val mean = p.meanHeadingAt(100_000_000L)
        assertTrue(mean != null)
        assertEquals(0.0, mean!!, 1e-6)
    }

    @Test
    fun nearVerticalProjectionIsInvalidWithoutJump() {
        val p = OrientationProcessor()
        p.onRotationVector(100_000_000L, rotationVectorFor(0.4, Math.toRadians(40.0)))
        assertEquals(0.4, p.meanHeadingAt(100_000_000L)!!, 1e-6)
        p.onRotationVector(300_000_000L, rotationVectorFor(0.4, Math.toRadians(85.0)))
        assertTrue(p.lastTilted)
        p.onRotationVector(320_000_000L, rotationVectorFor(0.4, Math.toRadians(89.0)))
        assertNull(p.meanHeadingAt(320_000_000L))
        assertEquals(0.4, p.lastValidYaw!!, 1e-6)
        p.onRotationVector(500_000_000L, rotationVectorFor(0.4, Math.toRadians(40.0)))
        assertEquals(0.4, p.meanHeadingAt(500_000_000L)!!, 1e-6)
    }

    @Test
    fun staleSamplesOutsideWindowAreNotUsed() {
        val p = OrientationProcessor()
        var t = 0L
        while (t <= 100_000_000L) {
            p.onRotationVector(t, rotationVectorFor(0.0))
            t += 20_000_000L
        }
        assertNull(p.meanHeadingAt(500_000_000L))
    }

    @Test
    fun futureSamplesAreNotUsed() {
        val p = OrientationProcessor()
        var t = 900_000_000L
        while (t <= 1_000_000_000L) {
            p.onRotationVector(t, rotationVectorFor(PI / 2))
            t += 20_000_000L
        }
        assertNull(p.meanHeadingAt(500_000_000L))
    }

    @Test
    fun samplesOlderThanBufferAreEvicted() {
        val p = OrientationProcessor()
        p.onRotationVector(0L, rotationVectorFor(0.0))
        p.onRotationVector(3_000_000_000L, rotationVectorFor(0.5))
        val mean = p.meanHeadingAt(3_000_000_000L)
        assertTrue(mean != null)
        assertEquals(0.5, mean!!, 1e-6)
    }

    @Test
    fun malformedVectorsAreIgnored() {
        val p = OrientationProcessor()
        p.onRotationVector(0L, floatArrayOf())
        p.onRotationVector(10_000_000L, floatArrayOf(Float.NaN, 0f, 0f, 1f))
        assertNull(p.meanHeadingAt(10_000_000L))
    }

    @Test
    fun duplicateTimestampsAreIgnored() {
        val p = OrientationProcessor()
        p.onRotationVector(100_000_000L, rotationVectorFor(0.0))
        p.onRotationVector(100_000_000L, rotationVectorFor(1.0))
        assertEquals(0.0, p.meanHeadingAt(100_000_000L)!!, 1e-6)
    }

    @Test
    fun latestInvalidSampleNullifiesMeanAcrossWindow() {
        val p = OrientationProcessor()
        p.onRotationVector(100_000_000L, rotationVectorFor(0.4))
        p.onRotationVector(120_000_000L, rotationVectorFor(0.4, Math.toRadians(85.0)))
        assertNull(p.meanHeadingAt(120_000_000L))
    }

    @Test
    fun validPostureRecoveryReturnsFreshHeading() {
        val p = OrientationProcessor()
        p.onRotationVector(100_000_000L, rotationVectorFor(0.4))
        p.onRotationVector(300_000_000L, rotationVectorFor(0.4, Math.toRadians(85.0)))
        assertNull(p.meanHeadingAt(300_000_000L))
        p.onRotationVector(360_000_000L, rotationVectorFor(0.6))
        val mean = p.meanHeadingAt(360_000_000L)
        assertTrue(mean != null)
        assertEquals(0.6, mean!!, 1e-6)
    }

    @Test
    fun worldVerticalMatchesDeviceZWhenFlat() {
        val r = rotationMatrixFromVector(rotationVectorFor(0.0))!!
        val (ux, uy, uz) = worldVerticalFromRotationMatrix(r)
        assertEquals(0.0, ux, 1e-6)
        assertEquals(0.0, uy, 1e-6)
        assertEquals(1.0, uz, 1e-6)
    }

    @Test
    fun worldVerticalTiltKeepsPositiveZComponent() {
        val r = rotationMatrixFromVector(rotationVectorFor(0.0, Math.toRadians(40.0)))!!
        val (ux, uy, uz) = worldVerticalFromRotationMatrix(r)
        assertEquals(0.0, ux, 1e-6)
        assertTrue(uz > 0.5)
        val linZ = 1.3
        val vertical = 0.0 * ux + 0.0 * uy + linZ * uz
        assertTrue(vertical > 0.0)
    }

    @Test
    fun stableHeadingIgnoresThumbTapTwistInLast100Ms() {
        val p = OrientationProcessor()
        var t = 100_000_000L
        while (t <= 450_000_000L) {
            p.onRotationVector(t, rotationVectorFor(0.0, Math.toRadians(40.0)))
            t += 20_000_000L
        }
        p.onRotationVector(520_000_000L, rotationVectorFor(Math.toRadians(14.0), Math.toRadians(40.0)))
        val stable = p.stableHeadingAt(520_000_000L)
        assertTrue(stable != null)
        assertEquals(0.0, stable!!, 1e-4)
    }

    @Test
    fun turnRateMeasuresFastRotationAccurately() {
        val p = OrientationProcessor()
        var t = 0L
        var yaw = 0.0
        while (t <= 250_000_000L) {
            p.onRotationVector(t, rotationVectorFor(yaw))
            yaw += 0.05
            t += 25_000_000L
        }
        val rate = p.turnRateRadPerSecAt(250_000_000L)
        assertEquals(2.0, rate, 0.05)
    }
}
