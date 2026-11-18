package com.hackdecouverte.pdr

import com.hackdecouverte.map.FloorEdge
import com.hackdecouverte.map.FloorNode
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.JunctionMatcher
import com.hackdecouverte.map.MapAnchor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accuracy improvements ported from the iOS engine: gait-adaptive step detector and
 * corridor-junction landmark correction.
 */
class AccuracyImprovementsTest {
    private val ms = 1_000_000L

    private fun countSteps(adaptive: Boolean, seconds: Double = 6.0, signal: (Double) -> Double): Int {
        val d = FallbackStepDetector(adaptive = adaptive)
        var n = 0
        d.onStep = { n++ }
        d.start(0L)
        var t = 0L
        while (t / 1e9 <= seconds) {
            d.onLinearAcceleration(t, 0.0, 0.0, signal(t / 1e9), 0.0, 0.0, 1.0)
            t += 10 * ms
        }
        return n
    }

    /** Slow walk / phone held gently: 0.9 m/s² bounce at 1.6 Hz never reaches the fixed 1.0 threshold. */
    @Test
    fun adaptiveDetectorCatchesSoftSteps() {
        val soft: (Double) -> Double = { 0.9 * sin(2 * PI * 1.6 * it) }
        val truth = (1.6 * (6 - 0.5)).toInt() // after the 0.5 s warm-up
        assertEquals(0, countSteps(false, signal = soft))
        assertEquals(truth.toDouble(), countSteps(true, signal = soft).toDouble(), 1.0)
    }

    /** Strong steps with a heel-strike bounce (2nd harmonic): one count per step, not two. */
    @Test
    fun adaptiveDetectorIgnoresHeelBounce() {
        val strong: (Double) -> Double = { t -> 3.2 * sin(2 * PI * 1.8 * t) + 1.6 * sin(2 * PI * 3.6 * t + 1.2) }
        val truth = (1.8 * (6 - 0.5)).toInt()
        val adaptive = countSteps(true, signal = strong)
        val fixed = countSteps(false, signal = strong)
        assertEquals(truth.toDouble(), adaptive.toDouble(), 1.0)
        assertTrue(abs(adaptive - truth) <= abs(fixed - truth))
    }

    /** Hand tremor / standing still never counts. */
    @Test
    fun adaptiveDetectorStaysQuietOnTremor() {
        val rng = java.util.Random(42)
        assertEquals(0, countSteps(true) { 1.2 * (rng.nextDouble() - 0.5) })
        assertEquals(0, countSteps(true) { 0.0 })
    }

    // ---- junction landmark ----

    private fun node(id: String, x: Double, y: Double) = FloorNode(id = id, type = "corridor", posM = PointM(x, y))

    /** L-shaped corridor A(0,0) → B(0,20) → C(25,20). */
    private fun lPlan() = FloorPlan(
        floorId = "T-1",
        widthM = 40.0,
        heightM = 40.0,
        nodes = listOf(node("A", 0.0, 0.0), node("B", 0.0, 20.0), node("C", 25.0, 20.0)),
        edges = listOf(FloorEdge("A", "B"), FloorEdge("B", "C"))
    )

    private data class Walk(val end: PointM, val maxStepM: Double)

    /**
     * Step length 14 % too long: the dot overshoots the junction by ~3 m, out of reach of the
     * 2 m corridor assist. After the turn the junction landmark brings it back onto the new
     * corridor, without jumps.
     */
    private fun walkL(landmarks: Boolean): Walk {
        var now = 10_000_000_000L
        val tr = PdrTracker(clock = { now })
        tr.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = false,
                permissionGranted = true
            )
        )
        val plan = lPlan()
        val anchor = MapAnchor(PointM(0.0, 0.0), 0.0)
        val matcher = JunctionMatcher()
        fun feed(yaw: Double) {
            var t = now - 480 * ms
            while (t <= now) {
                tr.onRotationVector(t, rotationVectorFor(yaw))
                t += 20 * ms
            }
        }
        feed(0.0)
        assertTrue(tr.startWithManualStepLength(0.8)) // true stride 0.7 m
        fun step(yaw: Double) {
            now += 500 * ms
            feed(yaw)
            tr.onStep(now, StepSource.HARDWARE)
            val s = tr.state.value
            if (landmarks) {
                matcher.onStep(s.stepCount, anchor.toMap(s.position), anchor.mapHeading(s.headingRadians), plan)
                    ?.let { tr.onLandmark(anchor.fromMap(it)) }
            }
        }
        repeat(29) { step(0.0) }     // 20.3 m north for real, PDR says 23.2 m
        repeat(30) { step(PI / 2) }  // then east
        val path = tr.state.value.rawPath
        val maxStep = path.zipWithNext { a, b -> a.distanceTo(b) }.maxOrNull() ?: 0.0
        return Walk(tr.state.value.position, maxStep)
    }

    @Test
    fun junctionLandmarkFixesAlongTrackDrift() {
        val raw = walkL(landmarks = false)
        val fixed = walkL(landmarks = true)
        assertTrue("without: still 3 m off (${raw.end})", abs(raw.end.y - 20) > 3)
        assertTrue("with: back on the corridor (${fixed.end})", abs(fixed.end.y - 20) < 0.5)
        assertTrue("bounded, no jump (${fixed.maxStepM})", fixed.maxStepM <= 0.8 + PdrTracker.MAX_LANDMARK_STEP_M + 1e-6)
    }

    @Test
    fun junctionMatcherNeedsAMatchingJunction() {
        val plan = lPlan()
        val m = JunctionMatcher()
        // turning right (east) at B matches; turning left (west) has no corridor there
        assertNotNull(m.bestJunction(plan, PointM(0.0, 22.0), 0.0, PI / 2))
        assertNull(m.bestJunction(plan, PointM(0.0, 22.0), 0.0, -PI / 2))
        assertNull(m.bestJunction(plan, PointM(0.0, 35.0), 0.0, PI / 2)) // too far from the junction
    }

    /** Real MB-2 corner (n160 → n014 → n150): the matcher recognises it from noisy headings. */
    @Test
    fun junctionMatcherOnRealMbCorner() {
        val mb2 = FloorPlan.parse(javaClass.classLoader!!.getResourceAsStream("floorplan.json")!!)
        val a = mb2.node("MB-2-n160")!!
        val b = mb2.node("MB-2-n014")!!
        val c = mb2.node("MB-2-n150")!!
        val h1 = atan2(b.posM.x - a.posM.x, b.posM.y - a.posM.y)
        val h2 = atan2(c.posM.x - b.posM.x, c.posM.y - b.posM.y)
        val near = PointM(b.posM.x + sin(h1) * 2, b.posM.y + cos(h1) * 2)
        val found = JunctionMatcher().bestJunction(mb2, near, h1 + Math.toRadians(6.0), h2 - Math.toRadians(5.0))
        assertEquals("MB-2-n014", found?.first?.id)
    }
}
