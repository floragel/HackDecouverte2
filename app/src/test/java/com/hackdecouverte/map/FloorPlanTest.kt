package com.hackdecouverte.map

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorPlanTest {

    private fun loadPlan(): FloorPlan {
        val stream = javaClass.classLoader!!.getResourceAsStream("floorplan.json")
        assertNotNull("floorplan.json absent des resources de test", stream)
        return FloorPlan.parse(stream!!)
    }

    @Test
    fun parsesRealAssetCountsAndDimensions() {
        val plan = loadPlan()
        assertEquals("MB-2", plan.floorId)
        assertEquals(116.0, plan.widthM, 1e-9)
        assertEquals(87.0, plan.heightM, 1e-9)
        assertEquals(84, plan.nodes.size)
        assertEquals(89, plan.edges.size)
    }

    @Test
    fun nodePositionsAreScaledToMeters() {
        val plan = loadPlan()
        val n44 = plan.node("n44")
        assertNotNull(n44)
        assertEquals("MB-2.255", n44!!.label)
        assertEquals(0.371 * 116.0, n44.posM.x, 1e-9)
        assertEquals(0.226 * 87.0, n44.posM.y, 1e-9)
    }

    @Test
    fun neighborsResolveBothEdgeDirections() {
        val plan = loadPlan()
        assertEquals(listOf("n3"), plan.neighbors("n44").map { it.id })
        assertTrue(plan.neighbors("n3").any { it.id == "n44" })
        assertTrue(plan.neighbors("inconnu").isEmpty())
    }

    @Test
    fun nonAccessibleEdgeIsPreserved() {
        val plan = loadPlan()
        val edge = plan.edges.first { it.fromId == "n5" && it.toId == "n46" }
        assertFalse(edge.accessible)
    }

    @Test
    fun labeledNodesSortNonCorridorFirstThenByLabel() {
        val plan = loadPlan()
        val firstCorridor = plan.labeledNodes.indexOfFirst { it.type == "corridor" }
        val lastOther = plan.labeledNodes.indexOfLast { it.type != "corridor" }
        assertTrue(firstCorridor > lastOther)
        val corridorLabels = plan.labeledNodes.drop(firstCorridor).map { it.label }
        assertEquals(corridorLabels.sorted(), corridorLabels)
    }

    @Test
    fun facingPsiPointsFromCorridorToRoomNorth() {
        val plan = loadPlan()
        val n3 = plan.node("n3")!!
        val n44 = plan.node("n44")!!
        assertTrue(abs(n44.posM.x - n3.posM.x) < 1e-9)
        assertTrue(n44.posM.y < n3.posM.y)
        assertEquals(0.0, FloorPlan.facingPsi(n3, n44), 1e-9)
    }

    @Test
    fun malformedJsonFails() {
        try {
            FloorPlan.parse("{ pas du json valide")
            org.junit.Assert.fail("JSON malformé accepté")
        } catch (expected: Exception) {
        }
    }
}
