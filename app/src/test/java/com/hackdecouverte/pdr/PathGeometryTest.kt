package com.hackdecouverte.pdr

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathGeometryTest {

    private fun assertPointEquals(expected: PointM, actual: PointM, eps: Double = 1e-9) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
    }

    @Test
    fun smoothedSegmentsPreserveEndpointsAndConnect() {
        val points = listOf(
            PointM(0.0, 0.0),
            PointM(1.0, 0.0),
            PointM(1.0, 1.0),
            PointM(2.0, 1.0)
        )
        val segments = PathGeometry.smoothedSegments(points)
        assertEquals(4, segments.size)
        assertPointEquals(points.first(), segments.first().start)
        assertPointEquals(points.last(), segments.last().end)
        for (i in 1 until segments.size) {
            assertPointEquals(segments[i - 1].end, segments[i].start)
        }
    }

    @Test
    fun singleAndTwoPointPaths() {
        assertTrue(PathGeometry.smoothedSegments(listOf(PointM(0.0, 0.0))).isEmpty())
        val two = PathGeometry.smoothedSegments(listOf(PointM(0.0, 0.0), PointM(3.0, 4.0)))
        assertEquals(1, two.size)
        assertPointEquals(PointM(1.5, 2.0), two[0].eval(0.5))
    }

    @Test
    fun quadJoinDerivativesAreContinuousAtMidpoints() {
        val points = listOf(
            PointM(0.0, 0.0),
            PointM(1.0, 0.2),
            PointM(2.0, 0.0),
            PointM(3.0, -0.4),
            PointM(4.0, 0.0)
        )
        val segments = PathGeometry.smoothedSegments(points)
        val quads = segments.filterIsInstance<PathSegment.Quad>()
        assertEquals(3, quads.size)
        for (i in 1 until quads.size) {
            val outgoing = quads[i - 1].derivative(1.0)
            val incoming = quads[i].derivative(0.0)
            assertEquals(outgoing.x, incoming.x, 1e-9)
            assertEquals(outgoing.y, incoming.y, 1e-9)
        }
        val firstJoinOut = segments[0].derivative(1.0)
        val firstJoinIn = segments[1].derivative(0.0)
        val cross = firstJoinOut.x * firstJoinIn.y - firstJoinOut.y * firstJoinIn.x
        assertTrue(abs(cross) < 1e-9)
    }

    @Test
    fun milestonesInterpolateAcrossLongSegment() {
        val points = listOf(PointM(0.0, 0.0), PointM(12.0, 0.0))
        val distances = listOf(0.0, 12.0)
        val milestones = MilestoneTracker.compute(points, distances, 5.0)
        assertEquals(2, milestones.size)
        assertPointEquals(PointM(5.0, 0.0), milestones[0].position)
        assertPointEquals(PointM(10.0, 0.0), milestones[1].position)
        assertEquals(5.0, milestones[0].distanceM, 1e-9)
        assertEquals(10.0, milestones[1].distanceM, 1e-9)
    }

    @Test
    fun milestonesOnExactBoundariesAreNotDuplicated() {
        val points = listOf(PointM(0.0, 0.0), PointM(5.0, 0.0), PointM(10.0, 0.0))
        val distances = listOf(0.0, 5.0, 10.0)
        val milestones = MilestoneTracker.compute(points, distances, 5.0)
        assertEquals(2, milestones.size)
        assertPointEquals(PointM(5.0, 0.0), milestones[0].position)
        assertPointEquals(PointM(10.0, 0.0), milestones[1].position)
    }

    @Test
    fun milestonesInsideSegmentAreInterpolated() {
        val points = listOf(PointM(0.0, 0.0), PointM(3.0, 0.0), PointM(9.0, 0.0))
        val distances = listOf(0.0, 3.0, 9.0)
        val milestones = MilestoneTracker.compute(points, distances, 5.0)
        assertEquals(1, milestones.size)
        assertPointEquals(PointM(5.0, 0.0), milestones[0].position)
    }

    @Test
    fun fiftySmallStepsReachFiveMeterMarkerWithoutExtraStep() {
        val tracker = MilestoneTracker(5.0)
        var distance = 0.0
        var position = PointM(0.0, 0.0)
        repeat(50) {
            val from = position
            val fromDistance = distance
            position += PointM(0.0, 0.1)
            distance += 0.1
            tracker.onSegment(from, fromDistance, position, distance)
        }
        val milestones = tracker.milestones
        assertEquals(1, milestones.size)
        assertEquals(5.0, milestones[0].distanceM, 1e-9)
        assertPointEquals(PointM(0.0, 5.0), milestones[0].position, 1e-6)
    }
}
