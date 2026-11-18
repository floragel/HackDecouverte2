package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.angleDelta
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2

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
        val viaElevator: Boolean,
        val verticalGroupId: String? = null,
        val departurePosM: PointM = PointM(0.0, 0.0),
        val arrivalPosM: PointM = PointM(0.0, 0.0)
    ) : RouteStep
    data class Arrive(val floorId: String, val label: String) : RouteStep
}

/** Language-neutral "get back on the route" hint (the UI words it in EN or FR). */
enum class RejoinHint { U_TURN, TURN_RIGHT, TURN_LEFT }

data class NavigationRoute(
    val destination: NodeKey,
    val destinationLabel: String,
    val accessibleOnly: Boolean,
    val totalDistanceM: Double,
    val floorSegments: List<RouteFloorSegment>,
    val steps: List<RouteStep>,
    val nextInstruction: String,
    val isArrived: Boolean,
    val rejoin: RejoinHint? = null
) {
    val transfers: List<RouteStep.FloorTransfer>
        get() = steps.filterIsInstance<RouteStep.FloorTransfer>()

    fun pointsForFloor(floorId: String): List<PointM> =
        floorSegments.filter { it.floorId == floorId }.flatMap { it.pointsM }
}

data class VerticalLandingResult(
    val targetFloorId: String,
    val arrivalNode: FloorNode?,
    val arrivalPosM: PointM,
    val verticalGroupId: String?,
    val viaElevator: Boolean
)

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
    private val floorIndexById: Map<String, Int> = floors.mapIndexed { idx, p -> p.floorId to idx }.toMap()
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

    fun floorNumber(floorId: String): Int =
        floorId.substringAfterLast('-', "").toIntOrNull()
            ?: ((floorIndexById[floorId] ?: 0) + 1)

    private fun linkVerticalGroups() {
        if (floors.size < 2) return
        val floorRank = floors.associate { it.floorId to floorNumber(it.floorId) }
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

    /**
     * Finds the nearest stairwell or elevator on [floorId] within [maxRadiusM] of [posM].
     */
    fun findNearestVerticalConnector(
        floorId: String,
        posM: PointM,
        maxRadiusM: Double = 10.0,
        accessibleOnly: Boolean = false
    ): FloorNode? {
        val plan = floorsById[floorId] ?: return null
        return plan.nodes
            .filter { node ->
                val isVert = !node.verticalGroupId.isNullOrBlank() ||
                    node.type == "stairs" ||
                    node.type == "elevator"
                isVert && (!accessibleOnly || node.type == "elevator")
            }
            .filter { it.posM.distanceTo(posM) <= maxRadiusM }
            .minByOrNull { it.posM.distanceTo(posM) }
    }

    /**
     * Every way from [fromFloorId] to [toFloorId]: (stairs / lift node on the first floor, the same
     * shaft's node on the second), matched by `vertical_group_id`.
     */
    fun verticalTransfers(fromFloorId: String, toFloorId: String): List<Pair<PointM, PointM>> {
        val from = floorsById[fromFloorId] ?: return emptyList()
        val to = floorsById[toFloorId] ?: return emptyList()
        val landings = to.nodes.filter { !it.verticalGroupId.isNullOrBlank() }.groupBy { it.verticalGroupId!! }
        return from.nodes.mapNotNull { n ->
            val group = n.verticalGroupId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val landing = landings[group]?.minByOrNull { it.posM.distanceTo(n.posM) } ?: return@mapNotNull null
            n.posM to landing.posM
        }
    }

    /** Stairs and lift nodes of [floorId]. */
    fun verticalConnectorPositions(floorId: String): List<PointM> =
        floorsById[floorId]?.nodes?.filter { !it.verticalGroupId.isNullOrBlank() || it.type == "stairs" || it.type == "elevator" }
            ?.map { it.posM }.orEmpty()

    /**
     * Adjacent floor (`+1` up or `-1` down) relative to [fromFloorId] in sorted building order.
     */
    fun adjacentFloorId(fromFloorId: String, floorDelta: Int): String? {
        val idx = floorIndexById[fromFloorId] ?: return null
        return floors.getOrNull(idx + floorDelta)?.floorId
    }

    /**
     * Resolves where the user lands on [targetFloorId] when moving from [fromFloorId] at [currentPosM],
     * accounting for stairwell/elevator shafts (`vertical_group_id`), elevator speed (`verticalSpeedMps`),
     * and any active navigation route transfer.
     */
    fun resolveFloorTransition(
        fromFloorId: String,
        targetFloorId: String,
        currentPosM: PointM,
        verticalSpeedMps: Double = 0.0,
        activeRoute: NavigationRoute? = null,
        explicitVerticalGroupId: String? = null
    ): VerticalLandingResult? {
        val fromPlan = floorsById[fromFloorId] ?: return null
        val targetPlan = floorsById[targetFloorId] ?: return null
        if (fromFloorId == targetFloorId) {
            return VerticalLandingResult(targetFloorId, null, currentPosM, null, false)
        }

        val targetVerticalByGroup = targetPlan.nodes
            .filter { !it.verticalGroupId.isNullOrBlank() }
            .groupBy { it.verticalGroupId!! }

        // 1. Explicit vertical group requested (e.g. tapping a specific stair/elevator transfer button)
        if (!explicitVerticalGroupId.isNullOrBlank()) {
            val arrival = targetVerticalByGroup[explicitVerticalGroupId]
                ?.minByOrNull { it.posM.distanceTo(currentPosM) }
            if (arrival != null) {
                return VerticalLandingResult(
                    targetFloorId = targetFloorId,
                    arrivalNode = arrival,
                    arrivalPosM = arrival.posM,
                    verticalGroupId = explicitVerticalGroupId,
                    viaElevator = arrival.type == "elevator"
                )
            }
        }

        // 2. If fast vertical velocity (> 0.9 m/s => elevator) and an elevator group exists nearby & on target
        if (abs(verticalSpeedMps) >= 0.9) {
            val nearElev = fromPlan.nodes
                .filter {
                    it.type == "elevator" &&
                        it.verticalGroupId in targetVerticalByGroup &&
                        it.posM.distanceTo(currentPosM) <= 18.0
                }
                .minByOrNull { it.posM.distanceTo(currentPosM) }
            if (nearElev != null) {
                val vg = nearElev.verticalGroupId!!
                val arrival = targetVerticalByGroup[vg]!!.minByOrNull { it.posM.distanceTo(nearElev.posM) }!!
                return VerticalLandingResult(targetFloorId, arrival, arrival.posM, vg, true)
            }
        }

        // 3. Active route transfer leaving fromFloorId in the same vertical direction when reasonably close
        val goingUp = floorNumber(targetFloorId) > floorNumber(fromFloorId)
        val routeTransfer = activeRoute?.transfers?.firstOrNull { t ->
            t.fromFloorId == fromFloorId &&
                (floorNumber(t.toFloorId) > floorNumber(fromFloorId)) == goingUp &&
                t.verticalGroupId != null &&
                t.verticalGroupId in targetVerticalByGroup &&
                t.departurePosM.distanceTo(currentPosM) <= 14.0
        }
        if (routeTransfer != null) {
            val vg = routeTransfer.verticalGroupId!!
            val arrival = targetVerticalByGroup[vg]!!.minByOrNull {
                it.posM.distanceTo(routeTransfer.departurePosM)
            }!!
            return VerticalLandingResult(
                targetFloorId = targetFloorId,
                arrivalNode = arrival,
                arrivalPosM = arrival.posM,
                verticalGroupId = vg,
                viaElevator = arrival.type == "elevator"
            )
        }

        // 4. Nearest vertical connector on fromFloorId that connects to targetFloorId (within 15 m)
        val nearDeparture = fromPlan.nodes
            .filter {
                !it.verticalGroupId.isNullOrBlank() &&
                    it.verticalGroupId in targetVerticalByGroup &&
                    it.posM.distanceTo(currentPosM) <= 15.0
            }
            .minByOrNull { it.posM.distanceTo(currentPosM) }
        if (nearDeparture != null) {
            val vg = nearDeparture.verticalGroupId!!
            val arrival = targetVerticalByGroup[vg]!!.minByOrNull {
                it.posM.distanceTo(nearDeparture.posM)
            }!!
            return VerticalLandingResult(
                targetFloorId = targetFloorId,
                arrivalNode = arrival,
                arrivalPosM = arrival.posM,
                verticalGroupId = vg,
                viaElevator = arrival.type == "elevator"
            )
        }

        // 5. Fallback: if activeRoute has ANY transfer arriving at targetFloorId (e.g. manual floor jump button)
        val directRouteArrival = activeRoute?.transfers?.firstOrNull {
            it.toFloorId == targetFloorId && it.verticalGroupId in targetVerticalByGroup
        }
        if (directRouteArrival != null) {
            val vg = directRouteArrival.verticalGroupId!!
            val arrival = targetVerticalByGroup[vg]!!.minByOrNull {
                it.posM.distanceTo(directRouteArrival.arrivalPosM)
            }!!
            return VerticalLandingResult(
                targetFloorId = targetFloorId,
                arrivalNode = arrival,
                arrivalPosM = arrival.posM,
                verticalGroupId = vg,
                viaElevator = arrival.type == "elevator"
            )
        }

        // 6. Shared True-North metric frame fallback: preserve (eastM, northM) projected onto target floor
        val projected = projectStartOntoPlan(targetPlan, currentPosM, accessibleOnly = false)?.projectedPointM
            ?: currentPosM
        return VerticalLandingResult(
            targetFloorId = targetFloorId,
            arrivalNode = null,
            arrivalPosM = projected,
            verticalGroupId = null,
            viaElevator = false
        )
    }

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
            isArrived = false,
            rejoin = rejoinHint(segments.firstOrNull()?.pointsM.orEmpty(), userMapHeadingRad)
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
                val newTransfer = RouteStep.FloorTransfer(
                    fromFloorId = prevFloor,
                    toFloorId = key.floorId,
                    viaLabel = via.label.ifBlank { via.name },
                    viaElevator = via.type == "elevator" && n.type == "elevator",
                    verticalGroupId = via.verticalGroupId ?: n.verticalGroupId,
                    departurePosM = via.posM,
                    arrivalPosM = n.posM
                )
                // Merge multi-flight stair transfers through the same stairwell (e.g. MB-2 -> MB-3 -> MB-4)
                val lastStep = rawSteps.lastOrNull()
                if (lastStep is RouteStep.FloorTransfer &&
                    lastStep.toFloorId == newTransfer.fromFloorId &&
                    lastStep.viaElevator == newTransfer.viaElevator &&
                    (lastStep.verticalGroupId == null || lastStep.verticalGroupId == newTransfer.verticalGroupId)
                ) {
                    rawSteps[rawSteps.lastIndex] = lastStep.copy(
                        toFloorId = newTransfer.toFloorId,
                        arrivalPosM = newTransfer.arrivalPosM
                    )
                } else {
                    rawSteps.add(newTransfer)
                }
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

    private fun rejoinHint(firstFloorPoints: List<PointM>, userMapHeadingRad: Double?): RejoinHint? {
        if (firstFloorPoints.size < 2 || userMapHeadingRad == null) return null
        val a = firstFloorPoints[0]
        val b = firstFloorPoints[1]
        if (a.distanceTo(b) < 0.8) return null
        val targetHeading = atan2(b.x - a.x, b.y - a.y)
        val diffDeg = Math.toDegrees(angleDelta(userMapHeadingRad, targetHeading))
        return when {
            abs(diffDeg) > 135.0 -> RejoinHint.U_TURN
            diffDeg > 45.0 -> RejoinHint.TURN_RIGHT
            diffDeg < -45.0 -> RejoinHint.TURN_LEFT
            else -> null
        }
    }

    private fun formatNextInstruction(
        firstFloorPoints: List<PointM>,
        steps: List<RouteStep>,
        userMapHeadingRad: Double?
    ): String {
        when (rejoinHint(firstFloorPoints, userMapHeadingRad)) {
            RejoinHint.U_TURN -> return "Faites demi-tour"
            RejoinHint.TURN_RIGHT -> return "Tournez à droite pour rejoindre l'itinéraire"
            RejoinHint.TURN_LEFT -> return "Tournez à gauche pour rejoindre l'itinéraire"
            null -> Unit
        }

        val first = steps.firstOrNull() ?: return "Suivez l'itinéraire"
        return when (first) {
            is RouteStep.Walk -> {
                val distTxt = "${first.distanceM.toInt().coerceAtLeast(1)} m"
                val next = steps.getOrNull(1)
                val suffix = when (next) {
                    is RouteStep.Turn -> if (next.left) " puis à gauche" else " puis à droite"
                    is RouteStep.FloorTransfer -> {
                        val goingUp = floorNumber(next.toFloorId) > floorNumber(next.fromFloorId)
                        if (next.viaElevator) {
                            " vers l'ascenseur (${next.toFloorId})"
                        } else if (goingUp) {
                            " vers l'escalier ↑ ${next.toFloorId}"
                        } else {
                            " vers l'escalier ↓ ${next.toFloorId}"
                        }
                    }
                    is RouteStep.Arrive -> " jusqu'à ${next.label}"
                    else -> ""
                }
                "Continuer tout droit sur $distTxt$suffix"
            }

            is RouteStep.Turn ->
                if (first.left) "Tourner à gauche (${first.intoLabel})"
                else "Tourner à droite (${first.intoLabel})"

            is RouteStep.FloorTransfer -> {
                val goingUp = floorNumber(first.toFloorId) > floorNumber(first.fromFloorId)
                when {
                    first.viaElevator -> "Prendre l'ascenseur (${first.viaLabel}) → ${first.toFloorId}"
                    goingUp -> "Monter par l'escalier ${first.viaLabel} → ${first.toFloorId}"
                    else -> "Descendre par l'escalier ${first.viaLabel} → ${first.toFloorId}"
                }
            }

            is RouteStep.Arrive -> "Arrivé à ${first.label}"
        }
    }

    companion object {
        const val ARRIVAL_RADIUS_M = 2.5

        private fun normalizeRoomToken(raw: String): String {
            var s = raw.lowercase()
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "")
                .replace(".", "")
            // Normalize shorthand "m2255" -> "mb2255" so "M.2.255" matches "MB-2.255"
            if (s.length >= 2 && s[0] == 'm' && s[1].isDigit()) {
                s = "mb" + s.substring(1)
            }
            return s
        }

        fun matchesSearchQuery(
            query: String,
            label: String,
            name: String,
            floorId: String = ""
        ): Boolean {
            val q = query.trim()
            if (q.isEmpty()) return true
            val combined = "$floorId $label $name"
            if (combined.contains(q, ignoreCase = true)) return true
            val normQ = normalizeRoomToken(q)
            if (normQ.isEmpty()) return true
            val normLabel = normalizeRoomToken(label)
            val normCombined = normalizeRoomToken(combined)
            return normLabel.contains(normQ) || normCombined.contains(normQ)
        }
    }
}
