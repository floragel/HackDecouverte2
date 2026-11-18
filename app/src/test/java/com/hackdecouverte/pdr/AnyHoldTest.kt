package com.hackdecouverte.pdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit quaternion (w, x, y, z); left-multiplying by a world rotation turns the phone in the world. */
internal data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w
    )

    /** Android rotation vector layout: x, y, z, w. */
    fun rv() = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())

    /** The fraction [f] of this rotation (from the identity), for smooth hold changes. */
    fun pow(f: Double): Quat {
        val angle = 2 * kotlin.math.acos(w.coerceIn(-1.0, 1.0))
        val s = sin(angle / 2)
        return if (s < 1e-9) Quat(1.0, 0.0, 0.0, 0.0) else axisAngle(x / s, y / s, z / s, f * angle)
    }

    companion object {
        fun axisAngle(ax: Double, ay: Double, az: Double, angle: Double): Quat {
            val n = sqrt(ax * ax + ay * ay + az * az)
            val s = sin(angle / 2)
            return Quat(cos(angle / 2), ax / n * s, ay / n * s, az / n * s)
        }

        /** Body facing [headingRad] (clockwise from the reference), phone in [hold] relative to the body. */
        fun phone(headingRad: Double, hold: Quat): Quat = axisAngle(0.0, 0.0, 1.0, -headingRad) * hold

        /** The leg / arm swing: rotation about the body's lateral (right) axis, in the world. */
        fun swing(headingRad: Double, angle: Double): Quat = axisAngle(cos(headingRad), -sin(headingRad), 0.0, angle)

        val FLAT = Quat(1.0, 0.0, 0.0, 0.0)
        /** Upright in a trouser pocket: top down, screen facing right (against the thigh), back of the phone forward-left. */
        val POCKET = Quat(0.5, -0.5, 0.5, -0.5)
    }
}

class AnyHoldTest {

    private val ms = 1_000_000L

    private fun twistOf(q: Quat): Double = CarryMonitor.twistYaw(rotationMatrixFromVector(q.rv())!!)!!

    @Test
    fun turningTheBodyIsNotAHoldChange() {
        val m = CarryMonitor()
        var changes = 0
        m.onHoldChanged = { _, _ -> changes++ }
        var t = 0L
        repeat(500) { i ->
            val heading = if (i < 200) 0.0 else Math.toRadians(90.0 * ((i - 200).coerceAtMost(50) / 50.0))
            m.onRotation(t, rotationMatrixFromVector(Quat.phone(heading, Quat.FLAT).rv())!!)
            t += 20 * ms
        }
        assertEquals(0, changes)
        assertEquals(CarryMode.STEADY, m.mode)
        assertTrue(m.readingPose)
    }

    @Test
    fun aPhoneRockingInAPocketIsSwingingNotChangingHold() {
        val m = CarryMonitor()
        var changes = 0
        m.onHoldChanged = { _, _ -> changes++ }
        var t = 0L
        repeat(600) {
            val beta = Math.toRadians(25.0) * sin(2 * PI * 0.9 * t / 1e9)
            m.onRotation(t, rotationMatrixFromVector((Quat.swing(0.3, beta) * Quat.phone(0.3, Quat.POCKET)).rv())!!)
            t += 20 * ms
        }
        assertEquals(0, changes)
        assertEquals(CarryMode.SWINGING, m.mode)
        assertFalse(m.readingPose)
    }

    @Test
    fun handToPocketIsOneHoldChangeWithThePhoneHeadingJump() {
        val m = CarryMonitor()
        val jumps = ArrayList<Double>()
        m.onHoldChanged = { _, d -> jumps += d }
        var t = 0L
        val heading = 0.4
        repeat(150) { // 3 s reading
            m.onRotation(t, rotationMatrixFromVector(Quat.phone(heading, Quat.FLAT).rv())!!); t += 20 * ms
        }
        repeat(30) { k -> // 0.6 s: into the pocket (rough slerp by axis-angle steps is enough here)
            val f = (k + 1) / 30.0
            val q = Quat.phone(heading, Quat.POCKET.pow(f))
            m.onRotation(t, rotationMatrixFromVector(q.rv())!!); t += 20 * ms
        }
        repeat(250) { // 5 s walking with the phone rocking in the pocket
            val beta = Math.toRadians(20.0) * sin(2 * PI * 0.9 * t / 1e9)
            m.onRotation(t, rotationMatrixFromVector((Quat.swing(heading, beta) * Quat.phone(heading, Quat.POCKET)).rv())!!); t += 20 * ms
        }
        assertEquals(1, jumps.size)
        val expected = angleDelta(twistOf(Quat.phone(heading, Quat.FLAT)), twistOf(Quat.phone(heading, Quat.POCKET)))
        assertEquals(expected, jumps[0], Math.toRadians(8.0))
    }

    /** Walks [steps] steps at [headingRad] (body), phone given by [phoneAt], with pocket-like jolts when [jolts]. */
    private class Walker(val clock: LongArray) {
        lateinit var tracker: PdrTracker
        var t: Long
            get() = clock[0]
            set(v) { clock[0] = v }
        fun run(durationNs: Long, stepEveryNs: Long?, jolts: Boolean, phoneAt: (Long) -> Quat) {
            val end = t + durationNs
            var nextStep = stepEveryNs?.let { t + it }
            while (t < end) {
                tracker.onRotationVector(t, phoneAt(t).rv())
                if (jolts) tracker.onLinearAccelerationMagnitude(t, 14.0) // a pocket at heel strike: way past the shake threshold
                if (nextStep != null && t >= nextStep) {
                    tracker.onStep(t, StepSource.HARDWARE)
                    nextStep += stepEveryNs!!
                }
                t += 20_000_000L
            }
        }
    }

    private fun anyHoldWalker(): Pair<Walker, PdrTracker> {
        val w = Walker(longArrayOf(10_000_000_000L))
        val tracker = PdrTracker(clock = { w.t }, antiShakeEnabled = true).apply {
            anyHoldHeading = true
            onSensorStatus(SensorStatus(rotationAvailable = true, stepDetectorAvailable = true, fallbackCapable = true, permissionNeeded = false, permissionGranted = true))
        }
        w.tracker = tracker
        return w to tracker
    }

    @Test
    fun walkingOnWithThePhoneMovedToThePocketKeepsTheHeadingAndEveryStep() {
        val (w, tracker) = anyHoldWalker()
        val north = 0.0
        w.run(1_500_000_000L, null, false) { Quat.phone(north, Quat.FLAT) }
        assertTrue(tracker.startWithManualStepLength(0.7))
        assertTrue(tracker.state.value.headingAligned)
        w.run(5_500_000_000L, 550_000_000L, false) { Quat.phone(north, Quat.FLAT) } // 10 steps in hand
        val transitionStart = w.t
        w.run(700_000_000L, 550_000_000L, false) { now -> // into the pocket while walking
            val f = ((now - transitionStart) / 700e6).coerceIn(0.0, 1.0)
            Quat.phone(north, Quat.POCKET.pow(f))
        }
        w.run(8_250_000_000L, 550_000_000L, true) { now ->
            Quat.swing(north, Math.toRadians(25.0) * sin(2 * PI * 0.9 * now / 1e9)) * Quat.phone(north, Quat.POCKET)
        }
        val s = tracker.state.value
        assertEquals(1, s.holdChanges)
        assertEquals(CarryMode.SWINGING, s.carryMode)
        assertTrue("steps ${s.stepCount}, dropped ${s.droppedStepCount}", s.stepCount >= 24)
        val bearing = Math.toDegrees(atan2(s.position.x, s.position.y))
        assertTrue("walked towards $bearing°", abs(bearing) < 12.0)
        assertTrue(s.headingAligned)
    }

    @Test
    fun aRealTurnWithThePhoneInThePocketIsStillATurn() {
        val (w, tracker) = anyHoldWalker()
        fun pocket(heading: Double) = { now: Long -> Quat.swing(heading, Math.toRadians(25.0) * sin(2 * PI * 0.9 * now / 1e9)) * Quat.phone(heading, Quat.POCKET) }
        w.run(2_000_000_000L, null, true, pocket(0.0))
        assertTrue(tracker.startWithManualStepLength(0.7))
        assertFalse(tracker.state.value.headingAligned) // started in a pocket: the compass says nothing about walking
        w.run(5_500_000_000L, 550_000_000L, true, pocket(0.0))
        val atTurn = tracker.state.value.position
        val t0 = w.t
        w.run(1_100_000_000L, 550_000_000L, true) { now -> pocket(PI / 2 * ((now - t0) / 1.1e9).coerceIn(0.0, 1.0))(now) }
        w.run(5_500_000_000L, 550_000_000L, true, pocket(PI / 2))
        val s = tracker.state.value
        assertEquals(0, s.holdChanges)
        val leg = s.position - atTurn
        val bearing = Math.toDegrees(atan2(leg.x, leg.y))
        assertTrue("second leg towards $bearing°", abs(bearing - 90.0) < 20.0)
    }

    @Test
    fun flatOnlyModeStillRefusesAnUprightPhone() {
        val p = OrientationProcessor()
        p.onRotationVector(1_000_000L, Quat.phone(0.0, Quat.POCKET).rv())
        assertTrue(p.lastTilted)
        p.anyHold = true
        p.onRotationVector(2_000_000L, Quat.phone(0.0, Quat.POCKET).rv())
        assertFalse(p.lastTilted)
    }
}
