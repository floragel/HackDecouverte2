package com.hackdecouverte.backend

import com.hackdecouverte.loc.LocEstimate
import com.hackdecouverte.loc.LocState
import com.hackdecouverte.loc.LocZone
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.pdr.PointM

/** Where the map localization puts the user: main zone, heading once FOUND. */
data class LocalizedPose(val floorId: String, val positionM: PointM, val headingRad: Double?, val radiusM: Double, val state: LocState)

/** Navigation from the localized position (plan E1, spec §6.4, §6.7). Pure Kotlin. */
object LocalizedNavigation {
    const val ARRIVAL_RADIUS_M = 2.5
    const val DOOR_HYSTERESIS_M = 3.0
    const val ROOM_HINT_RADIUS_M = 2.0
    const val ENTRANCE_HINT_RADIUS_M = 3.0
    const val ENTRANCE_MAX_DISTANCE_M = 25.0
    const val REROUTE_MIN_MOVE_M = 1.0
    const val REROUTE_MIN_STEPS = 2

    /** Main zone of [estimate]; heading only when FOUND; null without a zone. */
    fun pose(floorId: String, estimate: LocEstimate): LocalizedPose? {
        val z = estimate.zones.firstOrNull() ?: return null
        return LocalizedPose(floorId, z.centerM, if (estimate.state == LocState.FOUND) z.headingRad else null, z.radiusM, estimate.state)
    }

    /** Never while SEARCHING; on reaching APPROXIMATE or better, a floor change, or ≥ 2 steps and ≥ 1 m moved. */
    fun shouldReroute(last: LocalizedPose?, cur: LocalizedPose, stepsSince: Int): Boolean {
        if (cur.state == LocState.SEARCHING) return false
        if (last == null || last.state == LocState.SEARCHING) return true
        if (last.floorId != cur.floorId) return true
        if (last.state == LocState.APPROXIMATE && cur.state == LocState.FOUND) return true
        return stepsSince >= REROUTE_MIN_STEPS && last.positionM.distanceTo(cur.positionM) >= REROUTE_MIN_MOVE_M
    }

    /** FOUND and within [ARRIVAL_RADIUS_M] of one of the destination's doors. */
    fun arrived(pose: LocalizedPose, destinationDoorsM: List<PointM>): Boolean =
        pose.state == LocState.FOUND && destinationDoorsM.any { it.distanceTo(pose.positionM) <= ARRIVAL_RADIUS_M }

    /** « Je suis à … »: one zone per door node of that room, equal weights (a hint, not a certainty). */
    fun roomHintZones(plan: FloorPlan, roomLabel: String): List<LocZone> {
        val doors = plan.nodes.filter { it.type == "room" && it.label == roomLabel }
        return doors.map { LocZone(it.posM, ROOM_HINT_RADIUS_M, 1.0 / doors.size, 0.0) }
    }

    /** Coming in from the street: the exits within 25 m of the last outdoor fix, weight ∝ 1 / distance. */
    fun entranceHintZones(plan: FloorPlan, lastOutdoorFixM: PointM): List<LocZone> {
        val exits = plan.nodes.filter { it.type == "exit" }
            .map { it to it.posM.distanceTo(lastOutdoorFixM) }
            .filter { it.second <= ENTRANCE_MAX_DISTANCE_M }
        if (exits.isEmpty()) return emptyList()
        val raw = exits.map { 1.0 / maxOf(it.second, 1.0) }
        val sum = raw.sum()
        return exits.mapIndexed { i, (n, _) -> LocZone(n.posM, ENTRANCE_HINT_RADIUS_M, raw[i] / sum, 0.0) }
    }

    /** The closest door from [from], but the previous one is kept unless the new one is closer by more than 3 m. */
    fun stickyDestinationDoor(prevDoor: PointM?, candidates: List<PointM>, from: PointM): PointM {
        require(candidates.isNotEmpty())
        val best = candidates.minBy { it.distanceTo(from) }
        val prev = prevDoor?.let { p -> candidates.firstOrNull { it.distanceTo(p) < 0.01 } } ?: return best
        return if (prev.distanceTo(from) - best.distanceTo(from) > DOOR_HYSTERESIS_M) best else prev
    }
}
