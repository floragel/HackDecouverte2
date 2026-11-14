package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.angleDelta
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

data class NodeKey(val floorId: String, val nodeId: String)

data class DestinationItem(
    val key: NodeKey,
    val label: String,
    val name: String,
    val type: String
)

data class RouteFloorSegment(
    val floorId: String,
    val pointsM: List<PointM>
)

sealed interface RouteStep {
    data class Walk(val floorId: String, val distanceM: Double, val label: String) : RouteStep
    data class Turn(val floorId: String, val left: Boolean, val intoLabel: String) : RouteStep
    data class FloorTransfer(
        val fromFloorId: String,
        val toFloorId: String,
        val viaLabel: String,
        val viaElevator: Boolean
    ) : RouteStep
    data class Arrive(val floorId: String, val label: String) : RouteStep
}

data class NavigationRoute(
    val destination: NodeKey,
    val destinationLabel: String,
    val accessibleOnly: Boolean,
    val totalDistanceM: Double,
    val floorSegments: List<RouteFloorSegment>,
    val steps: List<RouteStep>,
    val nextInstruction: String,
    val isArrived: Boolean
) {
    fun pointsForFloor(floorId: String): List<PointM> =
        floorSegments.filter { it.floorId == floorId }.flatMap { it.pointsM }
}

class BuildingGraph(
    val floors: List<FloorPlan>,
    private val stairTransferCostPerFloorM: Double = 15.0,
    private val elevatorBaseCostM: Double = 20.0,
    private val elevatorPerFloorCostM: Double = 3.0
) {
    private data class AdjEdge(
        val to: NodeKey,
        val costM: Double,
        val accessible: Boolean,
        val vertical: Boolean
    )

    private val floorsById: Map<String, FloorPlan> = floors.associateBy { it.floorId }
    private val adj = HashMap<NodeKey, MutableList<AdjEdge>>()

    val destinations: List<DestinationItem> = floors.flatMap { plan ->
        val seenRoomLabels = HashSet<String>()
        plan.nodes
            .filter {
                (it.type == "room" || it.type == "stairs" || it.type == "elevator" ||
                    it.type == "entrance" || it.type == "exit" || it.type == "tunnel") &&
                    (it.label.isNotBlank() || it.name.isNotBlank())
            }
            .sortedWith(compareBy({ it.type != "room" }, { it.label.ifBlank { it.name } }))
            .filter { node ->
                if (node.type == "room" && node.label.isNotBlank()) {
                    seenRoomLabels.add(node.label)
                } else {
                    true
                }
            }
            .map {
                DestinationItem(
                    key = NodeKey(plan.floorId, it.id),
                    label = it.label.ifBlank { it.name },
                    name = it.name,
                    type = it.type
                )
            }
    }

    init {
        for (plan in floors) {
            for (node in plan.nodes) {
                adj.getOrPut(NodeKey(plan.floorId, node.id)) { ArrayList() }
            }
            for (edge in plan.edges) {
                val a = plan.node(edge.fromId) ?: continue
                val b = plan.node(edge.toId) ?: continue
                val dist = a.posM.distanceTo(b.posM)
                val acc = edge.accessible && a.type != "stairs" && b.type != "stairs"
                val ka = NodeKey(plan.floorId, a.id)
                val kb = NodeKey(plan.floorId, b.id)
                adj.getOrPut(ka) { ArrayList() }.add(AdjEdge(kb, dist, acc, vertical = false))
                adj.getOrPut(kb) { ArrayList() }.add(AdjEdge(ka, dist, acc, vertical = false))
            }
        }
        linkVerticalGroups()
    }

    private fun floorOrdinal(floorId: String, fallbackIndex: Int): Int =
        floorId.substringAfterLast('-', "").toIntOrNull() ?: fallbackIndex

    private fun linkVerticalGroups() {
        if (floors.size < 2) return
        val floorRank = floors.mapIndexed { idx, p -> p.floorId to floorOrdinal(p.floorId, idx) }.toMap()
        val groups = HashMap<String, MutableList<Pair<FloorPlan, FloorNode>>>()
        for (plan in floors) {
            for (node in plan.nodes) {
                val g = node.verticalGroupId?.takeIf { it.isNotBlank() } ?: continue
                groups.getOrPut(g) { ArrayList() }.add(plan to node)
            }
        }
        for ((_, entries) in groups) {
            val byFloor = entries.groupBy({ it.first.floorId }, { it.second })
            val floorIds = floors.map { it.floorId }.filter { byFloor.containsKey(it) }
            val isElevatorGroup = entries.all { it.second.type == "elevator" }
            if (isElevatorGroup) {
                // Direct ride between any two floors: cost = 20 m + 3 m * |Δfloor|
                for (i in floorIds.indices) {
                    for (j in i + 1 until floorIds.size) {
                        val fA = floorIds[i]
                        val fB = floorIds[j]
                        val deltaFloors = abs((floorRank[fB] ?: j) - (floorRank[fA] ?: i)).coerceAtLeast(1)
                        val cost = elevatorBaseCostM + elevatorPerFloorCostM * deltaFloors
                        for (na in byFloor[fA].orEmpty()) {
                            val nb = byFloor[fB].orEmpty().minByOrNull { it.posM.distanceTo(na.posM) } ?: continue
                            val ka = NodeKey(fA, na.id)
                            val kb = NodeKey(fB, nb.id)
                            adj.getOrPut(ka) { ArrayList() }.add(AdjEdge(kb, cost, accessible = true, vertical = true))
                            adj.getOrPut(kb) { ArrayList() }.add(AdjEdge(ka, cost, accessible = true, vertical = true))
                        }
                    }
                }
            } else {
                // Stairwells connect adjacent floors in the group: 15 m per floor
                for (i in 0 until floorIds.size - 1) {
                    val fA = floorIds[i]
                    val fB = floorIds[i + 1]
                    val deltaFloors = abs((floorRank[fB] ?: (i + 1)) - (floorRank[fA] ?: i)).coerceAtLeast(1)
                    val cost = stairTransferCostPerFloorM * deltaFloors
                    for (na in byFloor[fA].orEmpty()) {
                        val nb = byFloor[fB].orEmpty().minByOrNull { it.posM.distanceTo(na.posM) } ?: continue
                        val elevator = na.type == "elevator" && nb.type == "elevator"
                        val ka = NodeKey(fA, na.id)
                        val kb = NodeKey(fB, nb.id)
                        adj.getOrPut(ka) { ArrayList() }.add(AdjEdge(kb, cost, elevator, vertical = true))
                        adj.getOrPut(kb) { ArrayList() }.add(AdjEdge(ka, cost, elevator, vertical = true))
                    }
                }
            }
        }
    }

    fun floor(floorId: String): FloorPlan? = floorsById[floorId]

    fun node(key: NodeKey): FloorNode? = floorsById[key.floorId]?.node(key.nodeId)

    fun findRouteFromNode(
        start: NodeKey,
        destination: NodeKey,
        accessibleOnly: Boolean = false,
        userMapHeadingRad: Double? = null
    ): NavigationRoute? {
        val startNode = node(start) ?: return null
        return findRouteFromPosition(
            floorId = start.floorId,
            positionM = startNode.posM,
            destination = destination,
            accessibleOnly = accessibleOnly,
            userMapHeadingRad = userMapHeadingRad
        )
    }

    fun findRouteFromPosition(
        floorId: String,
        positionM: PointM,
        destination: NodeKey,
        accessibleOnly: Boolean = false,
        userMapHeadingRad: Double? = null
    ): NavigationRoute? {
        val startPlan = floorsById[floorId] ?: return null
        val destPlan = floorsById[destination.floorId] ?: return null
        val primaryDestNode = node(destination) ?: return null
        // Multi-door rooms share the same room label on a floor: route to whichever doorway is closest!
        val destCandidates: Set<NodeKey> = if (primaryDestNode.type == "room" && primaryDestNode.label.isNotBlank()) {
            destPlan.nodes
                .filter { it.type == "room" && it.label == primaryDestNode.label }
                .map { NodeKey(destination.floorId, it.id) }
                .toSet()
        } else {
            setOf(destination)
        }

        if (floorId == destination.floorId) {
            val nearDoor = destCandidates.mapNotNull(::node)
                .minByOrNull { positionM.distanceTo(it.posM) }
            if (nearDoor != null && positionM.distanceTo(nearDoor.posM) <= ARRIVAL_RADIUS_M) {
                val lbl = nearDoor.label.ifBlank { nearDoor.name }
                return NavigationRoute(
                    destination = destination,
                    destinationLabel = lbl,
                    accessibleOnly = accessibleOnly,
                    totalDistanceM = positionM.distanceTo(nearDoor.posM),
                    floorSegments = listOf(RouteFloorSegment(floorId, listOf(positionM, nearDoor.posM))),
                    steps = listOf(RouteStep.Arrive(destination.floorId, lbl)),
                    nextInstruction = "Arrivé à $lbl",
                    isArrived = true
                )
            }
        }

        val entry = projectStartOntoPlan(startPlan, positionM, accessibleOnly) ?: return null
        val dist = HashMap<NodeKey, Double>()
        val prev = HashMap<NodeKey, NodeKey?>()
        val pq = PriorityQueue<Pair<NodeKey, Double>>(compareBy { it.second })

        for ((candidate, initCost) in entry.anchors) {
            if (initCost < (dist[candidate] ?: Double.POSITIVE_INFINITY)) {
                dist[candidate] = initCost
                prev[candidate] = null
                pq.add(candidate to initCost)
            }
        }

        var reachedDest: NodeKey? = null
        while (pq.isNotEmpty()) {
            val (u, d) = pq.poll() ?: break
            if (d > (dist[u] ?: Double.POSITIVE_INFINITY)) continue
            if (u in destCandidates) {
                reachedDest = u
                break
            }
            for (e in adj[u].orEmpty()) {
                if (accessibleOnly && !e.accessible) continue
                val nd = d + e.costM
                if (nd < (dist[e.to] ?: Double.POSITIVE_INFINITY)) {
                    dist[e.to] = nd
                    prev[e.to] = u
                    pq.add(e.to to nd)
                }
            }
        }

        val finalDestKey = reachedDest ?: return null
        val finalDestNode = node(finalDestKey) ?: primaryDestNode
        val totalCost = dist[finalDestKey] ?: return null
        val nodeChain = ArrayList<NodeKey>()
        var cur: NodeKey? = finalDestKey
        while (cur != null) {
            nodeChain.add(cur)
            cur = prev[cur]
        }
        nodeChain.reverse()

        return buildRouteResult(
            startFloorId = floorId,
            projectedStart = entry.projectedPointM,
            nodeChain = nodeChain,
            destination = destination,
            destNode = finalDestNode,
            accessibleOnly = accessibleOnly,
            totalCostM = totalCost,
            userMapHeadingRad = userMapHeadingRad
        )
    }

    private data class StartProjection(
        val projectedPointM: PointM,
        val anchors: List<Pair<NodeKey, Double>>
    )

    private fun projectStartOntoPlan(
        plan: FloorPlan,
        pos: PointM,
        accessibleOnly: Boolean
    ): StartProjection? {
        var bestProj: PointM? = null
        var bestDist = Double.POSITIVE_INFINITY
        var bestA: FloorNode? = null
        var bestB: FloorNode? = null

        for (edge in plan.edges) {
            val a = plan.node(edge.fromId) ?: continue
            val b = plan.node(edge.toId) ?: continue
            val acc = edge.accessible && a.type != "stairs" && b.type != "stairs"
            if (accessibleOnly && !acc) continue
            val ab = b.posM - a.posM
            val lenSq = ab.x * ab.x + ab.y * ab.y
            if (lenSq < 1e-6) continue
            val t = (((pos.x - a.posM.x) * ab.x + (pos.y - a.posM.y) * ab.y) / lenSq).coerceIn(0.0, 1.0)
            val proj = a.posM + ab * t
            val d = pos.distanceTo(proj)
            if (d < bestDist) {
                bestDist = d
                bestProj = proj
                bestA = a
                bestB = b
            }
        }

        if (bestProj != null && bestA != null && bestB != null) {
            val ka = NodeKey(plan.floorId, bestA.id)
            val kb = NodeKey(plan.floorId, bestB.id)
            return StartProjection(
                projectedPointM = bestProj,
                anchors = listOf(
                    ka to bestProj.distanceTo(bestA.posM),
                    kb to bestProj.distanceTo(bestB.posM)
                )
            )
        }

        val nearest = plan.nodes
            .filter { !accessibleOnly || it.type != "stairs" }
            .minByOrNull { it.posM.distanceTo(pos) } ?: return null
        return StartProjection(
            projectedPointM = nearest.posM,
            anchors = listOf(NodeKey(plan.floorId, nearest.id) to 0.0)
        )
    }

    private fun buildRouteResult(
        startFloorId: String,
        projectedStart: PointM,
        nodeChain: List<NodeKey>,
        destination: NodeKey,
        destNode: FloorNode,
        accessibleOnly: Boolean,
        totalCostM: Double,
        userMapHeadingRad: Double?
    ): NavigationRoute {
        val segments = ArrayList<RouteFloorSegment>()
        var currentFloor = startFloorId
        var currentPts = ArrayList<PointM>().apply { add(projectedStart) }

        for (key in nodeChain) {
            val n = node(key) ?: continue
            if (key.floorId != currentFloor) {
                if (currentPts.size >= 2) segments.add(RouteFloorSegment(currentFloor, currentPts))
                currentFloor = key.floorId
                currentPts = ArrayList<PointM>().apply { add(n.posM) }
            } else if (currentPts.last().distanceTo(n.posM) > 0.05) {
                currentPts.add(n.posM)
            }
        }
        if (currentPts.size >= 2) {
            segments.add(RouteFloorSegment(currentFloor, currentPts))
        } else if (segments.isEmpty()) {
            segments.add(RouteFloorSegment(startFloorId, listOf(projectedStart, destNode.posM)))
        }

        val steps = buildInstructions(startFloorId, projectedStart, nodeChain, destNode)
        val nextText = formatNextInstruction(
            segments.firstOrNull()?.pointsM.orEmpty(),
            steps,
            userMapHeadingRad
        )

        return NavigationRoute(
            destination = destination,
            destinationLabel = destNode.label.ifBlank { destNode.name },
            accessibleOnly = accessibleOnly,
            totalDistanceM = totalCostM,
            floorSegments = segments,
            steps = steps,
            nextInstruction = nextText,
            isArrived = false
        )
    }

    private fun buildInstructions(
        startFloorId: String,
        projectedStart: PointM,
        nodeChain: List<NodeKey>,
        destNode: FloorNode
    ): List<RouteStep> {
        data class Leg(val floorId: String, val from: PointM, val to: PointM, val label: String)

        val rawSteps = ArrayList<RouteStep>()
        var prevFloor = startFloorId
        var prevPt = projectedStart
        var prevNode: FloorNode? = null
        val currentFloorLegs = ArrayList<Leg>()

        fun flushFloorLegs() {
            if (currentFloorLegs.isEmpty()) return
            var accDist = currentFloorLegs[0].from.distanceTo(currentFloorLegs[0].to)
            var accHeading = atan2(
                currentFloorLegs[0].to.x - currentFloorLegs[0].from.x,
                currentFloorLegs[0].to.y - currentFloorLegs[0].from.y
            )
            var accLabel = currentFloorLegs[0].label
            val fId = currentFloorLegs[0].floorId

            for (i in 1 until currentFloorLegs.size) {
                val leg = currentFloorLegs[i]
                val d = leg.from.distanceTo(leg.to)
                if (d < 0.25) continue
                val h = atan2(leg.to.x - leg.from.x, leg.to.y - leg.from.y)
                val turn = angleDelta(accHeading, h)
                if (abs(turn) <= Math.toRadians(28.0)) {
                    accDist += d
                    if (accLabel.isBlank() && leg.label.isNotBlank()) accLabel = leg.label
                    accHeading = h
                } else {
                    if (accDist >= 0.5) rawSteps.add(RouteStep.Walk(fId, accDist, accLabel))
                    rawSteps.add(RouteStep.Turn(fId, left = turn < 0.0, intoLabel = leg.label))
                    accDist = d
                    accHeading = h
                    accLabel = leg.label
                }
            }
            if (accDist >= 0.5) rawSteps.add(RouteStep.Walk(fId, accDist, accLabel))
            currentFloorLegs.clear()
        }

        for (key in nodeChain) {
            val n = node(key) ?: continue
            if (key.floorId != prevFloor) {
                flushFloorLegs()
                val via = prevNode ?: n
                rawSteps.add(
                    RouteStep.FloorTransfer(
                        fromFloorId = prevFloor,
                        toFloorId = key.floorId,
                        viaLabel = via.label.ifBlank { via.name },
                        viaElevator = via.type == "elevator" && n.type == "elevator"
                    )
                )
                prevFloor = key.floorId
                prevPt = n.posM
                prevNode = n
            } else {
                if (prevPt.distanceTo(n.posM) >= 0.25) {
                    val segLabel = if (n.type == "corridor") cleanCorridorName(n.label) else n.label
                    currentFloorLegs.add(Leg(key.floorId, prevPt, n.posM, segLabel))
                    prevPt = n.posM
                }
                prevNode = n
            }
        }
        flushFloorLegs()
        rawSteps.add(RouteStep.Arrive(nodeChain.last().floorId, destNode.label.ifBlank { destNode.name }))
        return rawSteps
    }

    private fun cleanCorridorName(raw: String): String =
        raw.substringBefore(" - ").trim()

    private fun formatNextInstruction(
        firstFloorPoints: List<PointM>,
        steps: List<RouteStep>,
        userMapHeadingRad: Double?
    ): String {
        if (firstFloorPoints.size >= 2 && userMapHeadingRad != null) {
            val a = firstFloorPoints[0]
            val b = firstFloorPoints[1]
            if (a.distanceTo(b) >= 0.8) {
                val targetHeading = atan2(b.x - a.x, b.y - a.y)
                val diffDeg = Math.toDegrees(angleDelta(userMapHeadingRad, targetHeading))
                if (abs(diffDeg) > 135.0) {
                    return "Faites demi-tour"
                } else if (diffDeg > 45.0) {
                    return "Tournez à droite pour rejoindre l'itinéraire"
                } else if (diffDeg < -45.0) {
                    return "Tournez à gauche pour rejoindre l'itinéraire"
                }
            }
        }

        val first = steps.firstOrNull() ?: return "Suivez l'itinéraire"
        return when (first) {
            is RouteStep.Walk -> {
                val distTxt = "${first.distanceM.toInt().coerceAtLeast(1)} m"
                val next = steps.getOrNull(1)
                val suffix = when (next) {
                    is RouteStep.Turn -> if (next.left) " puis à gauche" else " puis à droite"
                    is RouteStep.FloorTransfer ->
                        if (next.viaElevator) " vers l'ascenseur (${next.toFloorId})"
                        else " vers l'escalier (${next.toFloorId})"
                    is RouteStep.Arrive -> " jusqu'à ${next.label}"
                    else -> ""
                }
                "Continuer tout droit sur $distTxt$suffix"
            }

            is RouteStep.Turn ->
                if (first.left) "Tourner à gauche (${first.intoLabel})"
                else "Tourner à droite (${first.intoLabel})"

            is RouteStep.FloorTransfer ->
                if (first.viaElevator) "Prendre l'ascenseur ${first.viaLabel} → ${first.toFloorId}"
                else "Prendre l'escalier ${first.viaLabel} → ${first.toFloorId}"

            is RouteStep.Arrive -> "Arrivé à ${first.label}"
        }
    }

    companion object {
        const val ARRIVAL_RADIUS_M = 2.5
    }
}
