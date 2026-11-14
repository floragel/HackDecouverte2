package com.hackdecouverte.map

import com.hackdecouverte.pdr.CorridorConstraintResult
import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.SensorStatus
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.pdr.angleDelta
import com.hackdecouverte.pdr.rotationVectorFor
import com.hackdecouverte.pdr.wrapAnglePi
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MS = 1_000_000L

class CorridorTurnRegressionTest {

    @Test
    fun rightTurnOnTrueNorthMb2CorridorFollowsNewCorridorWithoutJumpsOrReversal() {
        val plan = FloorPlan.parse(javaClass.classLoader!!.getResourceAsStream("floorplan.json")!!)
        // Real 90° clockwise turn on MB-2: MB-2-n160 -> MB-2-n014 (40.5°) -> MB-2-n150 (130.5°)
        val nStart = plan.node("MB-2-n160")!!
        val nCorner = plan.node("MB-2-n014")!!
        val nAfter = plan.node("MB-2-n150")!!
        val h1 = atan2(nCorner.posM.x - nStart.posM.x, nCorner.posM.y - nStart.posM.y)
        val h2 = atan2(nAfter.posM.x - nCorner.posM.x, nAfter.posM.y - nCorner.posM.y)

        // True-North mode: psi = 0.0 (North of plan = North of phone)
        val anchor = MapAnchor(nStart.posM, 0.0)
        var now = 50_000_000_000L
        val tracker = PdrTracker(clock = { now }, corridorAssistInitial = true).apply {
            useTrueNorthReference = true
            mapAxisBaseLocal = wrapAnglePi(plan.dominantCorridorAxisRad - anchor.psiRad)
        }
        tracker.corridorConstraint = { p, theta ->
            plan.matchCorridor(anchor.toMap(p), anchor.mapHeading(theta))?.let {
                CorridorConstraintResult(
                    anchor.fromMap(it.projectedPointM),
                    it.alignedHeadingRad?.let(anchor::localHeading)
                )
            }
        }
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = true,
                fallbackCapable = true,
                permissionNeeded = false,
                permissionGranted = true
            )
        )

        fun feed(from: Long, to: Long, trueNorthYaw: Double) {
            var t = from
            while (t <= to) {
                // Arbitrary gyro zero offset of +37° to prove zero manual angle calibration works!
                val gameYaw = wrapAnglePi(trueNorthYaw + Math.toRadians(37.0))
                tracker.onRotationVector(t, rotationVectorFor(gameYaw))
                tracker.onTrueNorthAzimuth(t, trueNorthYaw, highConfidence = true)
                t += 20 * MS
            }
        }

        feed(now - 600 * MS, now, h1)
        assertTrue(tracker.startWithManualStepLength(0.66))
        var t = now
        repeat(5) {
            feed(t + 20 * MS, t + 500 * MS, h1 + Math.toRadians(1.5))
            t += 500 * MS
            now = t
            tracker.onStep(t, StepSource.HARDWARE)
        }
        repeat(3) { i ->
            feed(t + 20 * MS, t + 250 * MS, h1 + Math.toRadians(30.0 * (i + 1)))
            t += 250 * MS
        }
        repeat(10) {
            feed(t + 20 * MS, t + 500 * MS, h2 + Math.toRadians(2.0))
            t += 500 * MS
            now = t
            tracker.onStep(t, StepSource.HARDWARE)
        }
        val mapPath = tracker.state.value.rawPath.map(anchor::toMap)
        for (i in 1 until mapPath.size) {
            assertTrue(mapPath[i].distanceTo(mapPath[i - 1]) < 1.0)
        }
        val mapHeading = anchor.mapHeading(tracker.state.value.headingRadians)
        assertTrue(abs(angleDelta(h2, mapHeading)) < Math.toRadians(8.0))
    }
}
