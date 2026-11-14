package com.lync.app.data

import com.lync.app.data.model.Building
import com.lync.app.data.model.BuildingPresence
import com.lync.app.data.model.MapPin
import com.lync.app.data.model.Profile
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Main Concordia buildings (SGW downtown + Loyola). Coordinates = building center, radius in meters.
 * Shared with the Maps feature; later this comes from a `buildings` table per university.
 */
object Buildings {
    val concordia = listOf(
        Building("H", "Hall building", 45.49726, -73.57895, 45.0),
        Building("LB", "Webster Library", 45.49685, -73.57800, 45.0),
        Building("EV", "EV building", 45.49557, -73.57790, 50.0),
        Building("MB", "John Molson (JMSB)", 45.49535, -73.57905, 40.0),
        Building("GM", "Guy-De Maisonneuve", 45.49595, -73.57880, 25.0),
        Building("FG", "Faubourg", 45.49425, -73.57815, 35.0),
        Building("GN", "Grey Nuns", 45.49360, -73.57720, 60.0),
        Building("VL", "Vanier Library, Loyola", 45.45900, -73.63860, 40.0),
        Building("SP", "Science Pavilion, Loyola", 45.45780, -73.64140, 50.0),
        Building("PC", "PERFORM Centre, Loyola", 45.45710, -73.63740, 40.0),
        Building("AD", "Loyola Administration", 45.45820, -73.64000, 40.0),
    )

    private fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * 111_320.0
        val dLng = (lng2 - lng1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLng * dLng)
    }

    fun at(lat: Double, lng: Double): Building? =
        concordia.map { it to distanceM(lat, lng, it.lat, it.lng) }.filter { (b, d) -> d <= b.radiusM }.minByOrNull { it.second }?.first

    /** Groups visible friends by the building they're in. Buildings with me first, then most people. */
    fun presence(pins: List<MapPin>, me: Profile?, myPos: Pair<Double, Double>?): List<BuildingPresence> {
        val myBuilding = myPos?.let { at(it.first, it.second) }
        val byBuilding = pins.mapNotNull { p -> at(p.lat, p.lng)?.let { it to p.profile } }.groupBy({ it.first }, { it.second })
        val all = (byBuilding.keys + listOfNotNull(myBuilding)).distinct()
        return all.map { b -> BuildingPresence(b, byBuilding[b].orEmpty(), b == myBuilding) }
            .filter { it.people.isNotEmpty() }
            .sortedWith(compareByDescending<BuildingPresence> { it.includesMe }.thenByDescending { it.people.size })
    }
}
