package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.angleDelta
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorPlanTest {

    private fun loadPlan(): FloorPlan {
        val stream = javaClass.classLoader!!.getResourceAsStream("floorplan.json")
        assertNotNull("floorplan.json absent des resources de test", stream)
        return FloorPlan.parse(stream!!)
    }

    @Test
    fun parsesRealMb2AssetCountsAndTrueNorthFrame() {
        val plan = loadPlan()
        assertEquals("MB-2", plan.floorId)
        assertEquals(94.0, plan.widthM, 1e-9)
        assertEquals(74.0, plan.heightM, 1e-9)
        assertEquals(97, plan.nodes.size)
        assertEquals(98, plan.edges.size)
        assertTrue(plan.isGeoReferenced)
        assertEquals("floor-plans/MB/MB-floor-02.svg", plan.svgAsset)

        val elev = plan.node("MB-2-n001")!!
        assertEquals("elevator", elev.type)
        assertEquals(-0.23, elev.posM.x, 0.05)
        assertEquals(0.19, elev.posM.y, 0.05)

        // Montreal street grid ~40.5° mod 90°
        assertEquals(Math.toRadians(40.5), abs(plan.dominantCorridorAxisRad), Math.toRadians(3.0))
    }

    @Test
    fun nodePositionsFollowTrueNorthCoordinateNote() {
        val plan = loadPlan()
        val room255 = plan.node("MB-2-n102")
        assertNotNull(room255)
        assertEquals("MB-2.255", room255!!.label)
        assertEquals(room255.x * 94.0 - 44.0, room255.posM.x, 1e-6)
        assertEquals(-(room255.y * 74.0 - 34.0), room255.posM.y, 1e-6)
    }

    @Test
    fun neighborsResolveBothEdgeDirections() {
        val plan = loadPlan()
        assertEquals(listOf("MB-2-n101"), plan.neighbors("MB-2-n102").map { it.id })
        assertTrue(plan.neighbors("MB-2-n101").any { it.id == "MB-2-n102" })
        assertTrue(plan.neighbors("inconnu").isEmpty())
    }

    @Test
    fun nonAccessibleEdgeIsPreserved() {
        val plan = loadPlan()
        val edge = plan.edges.first { it.fromId == "MB-2-n116" && it.toId == "MB-2-n117" }
        assertFalse(edge.accessible)
    }

    @Test
    fun labeledNodesExcludeBlankCorridorsAndSortNonCorridorFirst() {
        val plan = loadPlan()
        assertTrue(plan.labeledNodes.all { it.label.isNotBlank() || it.name.isNotBlank() })
        val firstCorridor = plan.labeledNodes.indexOfFirst { it.type == "corridor" }
        val lastOther = plan.labeledNodes.indexOfLast { it.type != "corridor" }
        if (firstCorridor >= 0) {
            assertTrue(firstCorridor > lastOther)
        }
    }

    @Test
    fun malformedJsonFails() {
        try {
            FloorPlan.parse("{ pas du json valide")
            org.junit.Assert.fail("JSON malformé accepté")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun matchCorridorProjectsLaterallyOntoAlignedCorridorInTrueNorthFrame() {
        val plan = loadPlan()
        val a = plan.node("MB-2-n012")!!
        val b = plan.node("MB-2-n011")!!
        val segHeading = atan2(b.posM.x - a.posM.x, b.posM.y - a.posM.y)
        val mid = PointM((a.posM.x + b.posM.x) * 0.5, (a.posM.y + b.posM.y) * 0.5)
        // Offset 0.9m perpendicular to the corridor
        val perp = PointM(cos(segHeading) * 0.9, -sin(segHeading) * 0.9)
        val slightlyOff = mid + perp

        val match = plan.matchCorridor(slightlyOff, segHeading + Math.toRadians(5.0))
        assertNotNull(match)
        assertEquals(mid.x, match!!.projectedPointM.x, 1e-4)
        assertEquals(mid.y, match.projectedPointM.y, 1e-4)
        assertTrue(abs(angleDelta(segHeading, match.alignedHeadingRad!!)) < 1e-5)

        // Perpendicular heading does not match that corridor
        val wrongHeading = segHeading + Math.PI / 2.0
        val noMatchFarRoom = plan.matchCorridor(PointM(42.0, -38.0), wrongHeading)
        assertNull(noMatchFarRoom)
    }
}
