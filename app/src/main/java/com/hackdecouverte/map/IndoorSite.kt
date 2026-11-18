package com.hackdecouverte.map

/**
 * A building the app can navigate in: where its floor files live and where it is on Earth.
 * Floor plan `floorplan-<floorId>.json` lives in [floorsDir]; its JSON names its own SVG (`svg_asset`);
 * the optional hand edits live in `maps/<floorId>-overlay.json`.
 */
data class IndoorSite(
    val id: String,
    val name: String,
    val floorsDir: String,
    val defaultFloorId: String,
    /** (0, 0) of the floor plans. */
    val origin: GeoOrigin,
    /** False while [origin] is only approximate: GPS then gives the heading, never a position. */
    val gpsPositionTrusted: Boolean,
    /** OpenStreetMap streets around the building (bundled for MB only). */
    val osmSurroundings: Boolean
) {
    fun floorPlanAsset(floorId: String): String = "$floorsDir/floorplan-$floorId.json"

    fun overlayAsset(floorId: String): String = "maps/$floorId-overlay.json"

    companion object {
        val MB = IndoorSite(
            id = "MB",
            name = "John Molson (Concordia)",
            floorsDir = "floors",
            defaultFloorId = "MB-2",
            origin = GeoOrigin.MB_ELEVATOR_CORE,
            gpsPositionTrusted = true,
            osmSurroundings = true
        )

        /**
         * Collège Stanislas (Outremont), McEachran wing. Drawn from the level 100 evacuation plan;
         * (0, 0) = middle of the main corridor, placed on the middle of the wing as picked on a map
         * (a few metres off at most, well inside the ≥ 30 m GPS search disk).
         */
        val STANISLAS = IndoorSite(
            id = "STAN",
            name = "Collège Stanislas",
            floorsDir = "buildings/STAN",
            defaultFloorId = "STAN-1",
            origin = GeoOrigin(latitude = 45.51805395604281, longitude = -73.61554355694975),
            gpsPositionTrusted = true,
            osmSurroundings = false
        )

        val ALL = listOf(STANISLAS, MB)

        /** Unknown or missing id → the college (the default building on this branch). */
        fun byId(id: String?): IndoorSite = ALL.firstOrNull { it.id == id } ?: STANISLAS
    }
}
