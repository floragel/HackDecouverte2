package com.hackdecouverte.map

/** Result of the automatic map checks run before publishing a floor (spec §7). */
data class MapReport(
    val edgesCrossingWalls: List<Pair<String, String>>,
    val doorNodesBlocked: List<String>,
    val unreachableDoorNodes: List<String>,
    val facadeClosed: Boolean
) {
    val ok: Boolean
        get() = edgesCrossingWalls.isEmpty() && doorNodesBlocked.isEmpty() && unreachableDoorNodes.isEmpty() && facadeClosed

    fun summary(): String = buildString {
        append(if (ok) "OK" else "TO FIX")
        append(" — edges through walls: ${edgesCrossingWalls.size}")
        if (edgesCrossingWalls.isNotEmpty()) append(" ${edgesCrossingWalls.take(8).joinToString { "${it.first}-${it.second}" }}")
        append("; doors in a wall: ${doorNodesBlocked.size}")
        if (doorNodesBlocked.isNotEmpty()) append(" ${doorNodesBlocked.take(8)}")
        append("; doors unreachable: ${unreachableDoorNodes.size}")
        if (unreachableDoorNodes.isNotEmpty()) append(" ${unreachableDoorNodes.take(8)}")
        append("; facade closed: $facadeClosed")
    }
}

object MapValidator {
    fun validate(map: IndoorMap, plan: FloorPlan): MapReport {
        val g = map.grid
        val crossing = ArrayList<Pair<String, String>>()
        for (e in plan.edges) {
            val a = plan.node(e.fromId) ?: continue
            val b = plan.node(e.toId) ?: continue
            if (g.segmentBlocked(a.posM, b.posM)) crossing.add(e.fromId to e.toId)
        }
        val reached = BooleanArray(g.width * g.height)
        val queue = ArrayDeque<Int>()
        for (n in plan.nodes) {
            if (n.type != "corridor") continue
            val i = g.indexOf(n.posM)
            if (i >= 0 && !OccupancyGrid.isBlockedValue(g.cells[i]) && !reached[i]) { reached[i] = true; queue.add(i) }
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val r = i / g.width
            val c = i % g.width
            for (j in intArrayOf(if (r > 0) i - g.width else -1, if (r < g.height - 1) i + g.width else -1, if (c > 0) i - 1 else -1, if (c < g.width - 1) i + 1 else -1)) {
                if (j >= 0 && !reached[j] && !OccupancyGrid.isBlockedValue(g.cells[j])) { reached[j] = true; queue.add(j) }
            }
        }
        val blocked = ArrayList<String>()
        val unreachable = ArrayList<String>()
        for (n in plan.nodes) {
            if (n.type != "room") continue
            val i = g.indexOf(n.posM)
            if (i < 0 || OccupancyGrid.isBlockedValue(g.cells[i])) blocked.add(n.id)
            if (i < 0 || !reached[i]) unreachable.add(n.id)
        }
        var facadeClosed = true
        for (c in 0 until g.width) if (reached[c] || reached[(g.height - 1) * g.width + c]) facadeClosed = false
        for (r in 0 until g.height) if (reached[r * g.width] || reached[r * g.width + g.width - 1]) facadeClosed = false
        return MapReport(crossing, blocked, unreachable, facadeClosed)
    }
}
