package com.lync.app.data.places

import android.content.Context
import com.hackdecouverte.backend.IndoorNavigationBackend
import com.hackdecouverte.map.BuildingGraph
import com.lync.app.data.Buildings
import com.lync.app.data.model.Building
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One search result: a building, or a real JMSB room from the MB 1-7 floor graph. */
data class PlaceSuggestion(val query: String, val title: String, val subtitle: String, val building: Building, val floor: Int?, val isRoom: Boolean)

/**
 * Searchable places for the map: Concordia buildings + every labelled room of the MB floor plans
 * (the same graph the indoor navigation routes on, so any result can be navigated to the door).
 */
object CampusDirectory {
    @Volatile private var rooms: List<PlaceSuggestion>? = null

    suspend fun load(context: Context): List<PlaceSuggestion> = rooms ?: withContext(Dispatchers.Default) {
        val mb = CampusPlaces.building(CampusPlaces.INDOOR_BUILDING)!!
        val graph = BuildingGraph(IndoorNavigationBackend.loadBundledFloors(context.applicationContext))
        graph.destinations
            .filter { it.type == "room" && it.label.startsWith("MB-") }
            .distinctBy { it.label }
            .map { d ->
                val floor = d.key.floorId.substringAfterLast('-').toIntOrNull()
                PlaceSuggestion(d.label, d.label, d.name.substringBefore(" (").replaceFirstChar { it.uppercase() }, mb, floor, isRoom = true)
            }
            .sortedBy { it.title }
            .also { rooms = it }
    }

    fun search(query: String, rooms: List<PlaceSuggestion>, limit: Int = 30): List<PlaceSuggestion> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val buildings = Buildings.concordia
            .filter { it.code.equals(q, true) || it.name.contains(q, true) || (q.length >= 2 && it.code.startsWith(q, true)) }
            .map { PlaceSuggestion(it.code, it.name, it.code, it, null, isRoom = false) }
        val key = CampusPlaces.roomKey(q)
        val roomHits = rooms.filter {
            BuildingGraph.matchesSearchQuery(q, it.title, it.subtitle) || (key.length >= 2 && CampusPlaces.roomKey(it.title).contains(key))
        }
        // A typed room that is not in the MB graph (e.g. "H-920") still resolves to its building
        val typed = CampusPlaces.resolve(q)?.takeIf { p -> p.roomLabel != null && roomHits.none { CampusPlaces.roomKey(it.title) == CampusPlaces.roomKey(p.roomLabel!!) } && !p.hasIndoorMap }
            ?.let { listOf(PlaceSuggestion(q, it.roomLabel!!, it.building.name, it.building, it.floor, isRoom = true)) }.orEmpty()
        return (buildings + typed + roomHits).take(limit)
    }
}
