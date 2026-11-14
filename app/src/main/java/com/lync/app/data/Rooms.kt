package com.lync.app.data

data class Room(
    val name: String,         // e.g. "1-101", "2-204", "3-305"
    val buildingCode: String, // "MB" (John Molson School of Business)
    val buildingName: String, // "John Molson (JMSB)"
    val floor: Int,           // 1 to 7
    val roomNumber: String    // e.g. "101", "204"
)

/**
 * Imagined room names strictly in the John Molson (JMSB) building for floors 1 to 7
 * in the `floor-3digitRoomNumber` format.
 */
object Rooms {
    val sampleRooms = listOf(
        // Floor 1
        Room("1-101", "MB", "John Molson (JMSB)", 1, "101"),
        Room("1-105", "MB", "John Molson (JMSB)", 1, "105"),
        Room("1-110", "MB", "John Molson (JMSB)", 1, "110"),
        Room("1-125", "MB", "John Molson (JMSB)", 1, "125"),

        // Floor 2
        Room("2-201", "MB", "John Molson (JMSB)", 2, "201"),
        Room("2-204", "MB", "John Molson (JMSB)", 2, "204"),
        Room("2-210", "MB", "John Molson (JMSB)", 2, "210"),
        Room("2-230", "MB", "John Molson (JMSB)", 2, "230"),

        // Floor 3
        Room("3-301", "MB", "John Molson (JMSB)", 3, "301"),
        Room("3-305", "MB", "John Molson (JMSB)", 3, "305"),
        Room("3-315", "MB", "John Molson (JMSB)", 3, "315"),
        Room("3-340", "MB", "John Molson (JMSB)", 3, "340"),

        // Floor 4
        Room("4-401", "MB", "John Molson (JMSB)", 4, "401"),
        Room("4-405", "MB", "John Molson (JMSB)", 4, "405"),
        Room("4-415", "MB", "John Molson (JMSB)", 4, "415"),
        Room("4-430", "MB", "John Molson (JMSB)", 4, "430"),

        // Floor 5
        Room("5-501", "MB", "John Molson (JMSB)", 5, "501"),
        Room("5-510", "MB", "John Molson (JMSB)", 5, "510"),
        Room("5-520", "MB", "John Molson (JMSB)", 5, "520"),
        Room("5-545", "MB", "John Molson (JMSB)", 5, "545"),

        // Floor 6
        Room("6-602", "MB", "John Molson (JMSB)", 6, "602"),
        Room("6-615", "MB", "John Molson (JMSB)", 6, "615"),
        Room("6-630", "MB", "John Molson (JMSB)", 6, "630"),

        // Floor 7
        Room("7-705", "MB", "John Molson (JMSB)", 7, "705"),
        Room("7-715", "MB", "John Molson (JMSB)", 7, "715"),
        Room("7-740", "MB", "John Molson (JMSB)", 7, "740")
    )

    fun search(query: String): List<Room> {
        val q = query.trim()
        if (q.isEmpty()) return sampleRooms
        return sampleRooms.filter { room ->
            room.name.contains(q, ignoreCase = true) ||
            room.buildingCode.contains(q, ignoreCase = true) ||
            room.buildingName.contains(q, ignoreCase = true)
        }
    }
}
