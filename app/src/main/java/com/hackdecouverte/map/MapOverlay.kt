package com.hackdecouverte.map

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Hand edits for one floor's precise map, written by the floor-plan editor
 * (`assets/maps/MB-<n>-overlay.json`). Polygons are lists of `[east, north]` metres.
 */
@Serializable
data class MapOverlay(
    val floorId: String,
    val doors: List<OverlayDoor> = emptyList(),
    val removeDoorNodeIds: List<String> = emptyList(),
    val extraWalls: List<List<List<Double>>> = emptyList(),
    val passableAreas: List<List<List<Double>>> = emptyList()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null when [text] is not a valid overlay (the map then falls back to graph doors). */
        fun parse(text: String): MapOverlay? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun encode(overlay: MapOverlay): String = json.encodeToString(serializer(), overlay)
    }
}

@Serializable
data class OverlayDoor(
    val east: Double,
    val north: Double,
    val widthM: Double = 0.9,
    val nodeId: String? = null
)
