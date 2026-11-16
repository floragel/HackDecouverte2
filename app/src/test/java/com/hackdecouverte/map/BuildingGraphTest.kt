package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingGraphTest {

    private fun loadAllMbFloors(): List<FloorPlan> =
        (1..7).map { i ->
            val stream = javaClass.classLoader!!.getResourceAsStream("floors/floorplan-MB-$i.json")
            assertNotNull("floors/floorplan-MB-$i.json absent", stream)
            FloorPlan.parse(stream!!)
        }

    @Test
    fun loadsAllSevenRealMbFloorsAndRoutesAcrossFloorsWithStairsAndElevators() {
        val floors = loadAllMbFloors()
        assertEquals(7, floors.size)
        assertEquals(761, floors.sumOf { it.nodes.size })

        val graph = BuildingGraph(floors)
        assertTrue("Expected >= 250 deduplicated destinations", graph.destinations.size >= 250)

        // Same-floor route on MB-2: MB-2.255 -> MB-2.210 (which has 3 doors: picks closest door!)
        val mb2 = graph.floor("MB-2")!!
        val r255 = mb2.nodes.first { it.label == "MB-2.255" }
        val r210 = mb2.nodes.first { it.label == "MB-2.210" }
        val monoRoute = graph.findRouteFromNode(
            start = NodeKey("MB-2", r255.id),
            destination = NodeKey("MB-2", r210.id)
        )
        assertNotNull(monoRoute)
        assertFalse(monoRoute!!.isArrived)
        assertEquals("MB-2.210", monoRoute.destinationLabel)
        assertTrue(monoRoute.totalDistanceM in 15.0..75.0)
        assertTrue(monoRoute.pointsForFloor("MB-2").size >= 3)

        // Multi-floor route: Floor 1 main exit 1.199 -> Floor 6 room MB-6.445
        val exit199 = graph.floor("MB-1")!!.nodes.first { it.label == "Exit 1.199" }
        val room6 = graph.floor("MB-6")!!.nodes.first { it.type == "room" }

        // PMR accessible route MUST use central elevator in a single direct transfer MB-1 -> MB-6!
        val pmrRoute = graph.findRouteFromNode(
            start = NodeKey("MB-1", exit199.id),
            destination = NodeKey("MB-6", room6.id),
            accessibleOnly = true
        )
        assertNotNull(pmrRoute)
        val transfers = pmrRoute!!.steps.filterIsInstance<RouteStep.FloorTransfer>()
        assertEquals(1, transfers.size)
        assertTrue(transfers.single().viaElevator)
        assertEquals("MB-1", transfers.single().fromFloorId)
        assertEquals("MB-6", transfers.single().toFloorId)
    }

    @Test
    fun accessibleModeBlocksStairDestinationAndStepEdges() {
        val floors = loadAllMbFloors()
        val graph = BuildingGraph(floors)
        val mb2 = graph.floor("MB-2")!!
        val start = NodeKey("MB-2", mb2.nodes.first { it.label == "MB-2.255" }.id)
        val stairDest = NodeKey("MB-2", mb2.nodes.first { it.type == "stairs" }.id)
        assertNotNull(graph.findRouteFromNode(start, stairDest, accessibleOnly = false))
        assertNull(graph.findRouteFromNode(start, stairDest, accessibleOnly = true))
    }

    @Test
    fun liveMidCorridorPositionProjectsSmoothlyAndDetectsArrival() {
        val mb2 = loadAllMbFloors()[1]
        val graph = BuildingGraph(listOf(mb2))
        val a = mb2.node("MB-2-n012")!!
        val b = mb2.node("MB-2-n011")!!
        val heading = atan2(b.posM.x - a.posM.x, b.posM.y - a.posM.y)
        val midPos = PointM((a.posM.x + b.posM.x) / 2.0, (a.posM.y + b.posM.y) / 2.0)
        val destRoom = mb2.nodes.first { it.label == "MB-2.285" }

        val route = graph.findRouteFromPosition(
            floorId = "MB-2",
            positionM = midPos,
            destination = NodeKey("MB-2", destRoom.id),
            userMapHeadingRad = heading
        )
        assertNotNull(route)
        assertTrue(route!!.nextInstruction.isNotBlank())

        val arrived = graph.findRouteFromPosition(
            floorId = "MB-2",
            positionM = PointM(destRoom.posM.x + 0.4, destRoom.posM.y - 0.4),
            destination = NodeKey("MB-2", destRoom.id)
        )
        assertNotNull(arrived)
        assertTrue(arrived!!.isArrived)
    }

    @Test
    fun resolvesStairAndElevatorLandingsAcrossFloorsAndMergesMultiFlightStairSteps() {
        val graph = BuildingGraph(loadAllMbFloors())

        // 1. Grand stair 295 shifts ~12.8 m horizontally from MB-1 foot to MB-2 top:
        //    transitioning MB-1 -> MB-2 near MB-1.295 MUST snap to MB-2.295!
        val stair1295 = graph.floor("MB-1")!!.nodes.first { it.verticalGroupId == "stairs_295" }
        val stair2295 = graph.floor("MB-2")!!.nodes.first { it.verticalGroupId == "stairs_295" }
        val landing295 = graph.resolveFloorTransition(
            fromFloorId = "MB-1",
            targetFloorId = "MB-2",
            currentPosM = PointM(stair1295.posM.x + 1.2, stair1295.posM.y - 0.8),
            verticalSpeedMps = 0.35
        )
        assertNotNull(landing295)
        assertEquals("stairs_295", landing295!!.verticalGroupId)
        assertEquals(stair2295.id, landing295.arrivalNode?.id)
        assertEquals(stair2295.posM.x, landing295.arrivalPosM.x, 1e-4)
        assertEquals(stair2295.posM.y, landing295.arrivalPosM.y, 1e-4)

        // 2. Fast vertical velocity (>= 0.9 m/s) near elevator lobby selects elevator_central
        val elevMb5 = graph.floor("MB-5")!!.nodes.first { it.verticalGroupId == "elevator_central" }
        val elevLanding = graph.resolveFloorTransition(
            fromFloorId = "MB-2",
            targetFloorId = "MB-5",
            currentPosM = PointM(-3.0, -1.5), // near both stairs_394 and elevator_central
            verticalSpeedMps = 1.6
        )
        assertNotNull(elevLanding)
        assertTrue(elevLanding!!.viaElevator)
        assertEquals(elevMb5.id, elevLanding.arrivalNode?.id)

        // 3. Two-floor stair route (MB-2.285 -> room near stairs_294 on MB-4, cost 2*15=30m < elevator 26m+walk)
        val start285 = graph.floor("MB-2")!!.nodes.first { it.label == "MB-2.285" }
        val stair4294 = graph.floor("MB-4")!!.nodes.first { it.verticalGroupId == "stairs_294" }
        val nearStairRoom4 = graph.floor("MB-4")!!.nodes
            .filter { it.type == "room" }
            .minByOrNull { it.posM.distanceTo(stair4294.posM) }!!
        val stairRoute = graph.findRouteFromNode(
            start = NodeKey("MB-2", start285.id),
            destination = NodeKey("MB-4", nearStairRoom4.id),
            accessibleOnly = false
        )
        assertNotNull(stairRoute)
        assertEquals(1, stairRoute!!.transfers.size)
        assertEquals("MB-2", stairRoute.transfers.single().fromFloorId)
        assertEquals("MB-4", stairRoute.transfers.single().toFloorId)
    }

    @Test
    fun flexibleRoomSearchMatchesShorthandAndSpacedQueries() {
        assertTrue(BuildingGraph.matchesSearchQuery("M.2.255", "MB-2.255", "Classroom", "MB-2"))
        assertTrue(BuildingGraph.matchesSearchQuery("m2.255", "MB-2.255", "Classroom", "MB-2"))
        assertTrue(BuildingGraph.matchesSearchQuery("2.255", "MB-2.255", "Classroom", "MB-2"))
        assertTrue(BuildingGraph.matchesSearchQuery("mb 2 255", "MB-2.255", "Classroom", "MB-2"))
        assertFalse(BuildingGraph.matchesSearchQuery("M.4.206", "MB-2.255", "Classroom", "MB-2"))
    }
}
