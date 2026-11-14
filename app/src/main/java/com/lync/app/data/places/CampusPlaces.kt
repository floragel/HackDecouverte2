package com.lync.app.data.places

import com.lync.app.data.Buildings
import com.lync.app.data.model.Building

data class GeoPoint(val lat: Double, val lng: Double)

/**
 * Where the user wants to go, resolved from free text ("JMSB, MB-2.210", "H-920", "EV 3.102", "Hall").
 * [roomLabel] is normalised to the floor-plan format ("MB-2.210"); [floor] is the floor number when
 * it can be read from the room number.
 */
data class Place(
    val building: Building,
    val roomLabel: String?,
    val floor: Int?,
) {
    val point: GeoPoint get() = GeoPoint(building.lat, building.lng)
    /** Only the John Molson building has indoor plans + step tracking for now. */
    val hasIndoorMap: Boolean get() = building.code == CampusPlaces.INDOOR_BUILDING
    val title: String get() = roomLabel ?: building.name
}

/** Pure Kotlin (JVM unit-tested): no Android imports. */
object CampusPlaces {
    const val INDOOR_BUILDING = "MB"

    private val aliases = mapOf(
        "JMSB" to "MB", "JOHN MOLSON" to "MB", "MOLSON" to "MB",
        "HALL" to "H", "WEBSTER" to "LB", "LIBRARY" to "LB", "BIBLIOTHEQUE" to "LB",
        "ENGINEERING" to "EV", "VISUAL ARTS" to "EV", "GREY NUNS" to "GN", "SOEURS GRISES" to "GN",
        "FAUBOURG" to "FG", "VANIER" to "VL", "PERFORM" to "PC", "SCIENCE PAVILION" to "SP",
    )

    /** "MB-2.210", "MB 2.210", "MB2210", "H-920", "H 920", "EV-3.102", "MB S2.330". */
    private val roomRegex = Regex("""\b(MB|H|LB|EV|GM|FG|GN|VL|SP|PC|AD|CC|FC)\s*[-.\s]?\s*(?:(S?\d{1,2})\s*\.\s*)?(\d{2,4})\b""", RegexOption.IGNORE_CASE)
    /** Bare MB-style room "2.210" (the JMSB numbering, used in schedules without the building). */
    private val bareMbRoom = Regex("""(?<![\w.])([1-7])\.(\d{3})(?![\w.])""")

    fun resolve(text: String?): Place? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val upper = raw.uppercase()
        roomRegex.find(upper)?.let { m ->
            val code = m.groupValues[1].uppercase()
            val building = building(code) ?: return@let
            val floorPart = m.groupValues[2]
            val number = m.groupValues[3]
            return roomPlace(building, floorPart, number)
        }
        val code = aliases.entries.firstOrNull { upper.contains(it.key) }?.value
            ?: Buildings.concordia.firstOrNull { b -> Regex("""\b${b.code}\b""").containsMatchIn(upper) }?.code
        bareMbRoom.find(upper)?.let { m ->
            if (code == null || code == INDOOR_BUILDING) {
                return roomPlace(building(INDOOR_BUILDING)!!, m.groupValues[1], m.groupValues[2])
            }
        }
        return code?.let(::building)?.let { Place(it, null, null) }
            ?: Buildings.concordia.firstOrNull { upper.contains(it.name.uppercase()) }?.let { Place(it, null, null) }
    }

    private fun roomPlace(building: Building, floorPart: String, number: String): Place {
        return if (building.code == INDOOR_BUILDING) {
            // JMSB: "MB-2.210" (floor 2, room 210) — "MB2210" / "MB 2210" also accepted
            val (floor, room) = when {
                floorPart.isNotEmpty() -> floorPart to number
                number.length == 4 -> number.take(1) to number.drop(1)
                else -> (number.take(1)) to number
            }
            Place(building, "${building.code}-$floor.$room", floor.toIntOrNull())
        } else {
            // Hall-style numbering: H-920 is on floor 9, H-1020 on floor 10, EV-3.102 on floor 3
            val digits = floorPart + number
            val floor = when {
                floorPart.isNotEmpty() -> floorPart.toIntOrNull()
                number.length >= 3 -> number.dropLast(2).toIntOrNull()
                else -> null
            }
            val label = if (floorPart.isNotEmpty()) "${building.code}-$floorPart.$number" else "${building.code}-$digits"
            Place(building, label, floor)
        }
    }

    fun building(code: String): Building? = Buildings.concordia.firstOrNull { it.code.equals(code, ignoreCase = true) }

    /** Comparable key for room labels: "MB-2.210", "mb 2210", "M.2.210" -> "mb2210". */
    fun roomKey(label: String): String {
        var s = label.lowercase().filter { it.isLetterOrDigit() }
        if (s.length >= 2 && s[0] == 'm' && s[1].isDigit()) s = "mb" + s.substring(1)
        return s
    }
}
