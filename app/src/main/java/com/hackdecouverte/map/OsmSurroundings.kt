package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.Element

data class OsmBuilding(
    val id: String,
    val name: String,
    val ref: String,
    val isFocusBuilding: Boolean,
    val polygonM: List<PointM>,
    val labelCenterM: PointM
)

data class OsmRoad(
    val kind: String,
    val name: String,
    val widthM: Double,
    val polylineM: List<PointM>
)

data class OsmPark(
    val name: String,
    val polygonM: List<PointM>
)

data class OsmPoi(
    val kind: String,
    val name: String,
    val positionM: PointM
)

class OsmSurroundings(
    val origin: GeoOrigin,
    val parks: List<OsmPark>,
    val buildings: List<OsmBuilding>,
    val roads: List<OsmRoad>,
    val pois: List<OsmPoi>
) {
    @Serializable
    private data class OsmBuildingDto(
        val id: String = "",
        val name: String = "",
        val ref: String = "",
        @SerialName("is_focus") val isFocus: Boolean = false,
        val points: List<List<Double>> = emptyList()
    )

    @Serializable
    private data class OsmRoadDto(
        val kind: String = "",
        val name: String = "",
        @SerialName("width_m") val widthM: Double = 6.0,
        val points: List<List<Double>> = emptyList()
    )

    @Serializable
    private data class OsmParkDto(
        val name: String = "",
        val points: List<List<Double>> = emptyList()
    )

    @Serializable
    private data class OsmPoiDto(
        val kind: String = "",
        val name: String = "",
        val x: Double = 0.0,
        val y: Double = 0.0
    )

    @Serializable
    private data class OsmBundleDto(
        @SerialName("origin_lat") val originLat: Double,
        @SerialName("origin_lon") val originLon: Double,
        val parks: List<OsmParkDto> = emptyList(),
        val buildings: List<OsmBuildingDto> = emptyList(),
        val roads: List<OsmRoadDto> = emptyList(),
        val pois: List<OsmPoiDto> = emptyList()
    )

    fun toJsonString(): String {
        val dto = OsmBundleDto(
            originLat = origin.latitude,
            originLon = origin.longitude,
            parks = parks.map { OsmParkDto(it.name, it.polygonM.map { p -> listOf(p.x, p.y) }) },
            buildings = buildings.map {
                OsmBuildingDto(
                    id = it.id,
                    name = it.name,
                    ref = it.ref,
                    isFocus = it.isFocusBuilding,
                    points = it.polygonM.map { p -> listOf(p.x, p.y) }
                )
            },
            roads = roads.map {
                OsmRoadDto(
                    kind = it.kind,
                    name = it.name,
                    widthM = it.widthM,
                    points = it.polylineM.map { p -> listOf(p.x, p.y) }
                )
            },
            pois = pois.map { OsmPoiDto(it.kind, it.name, it.positionM.x, it.positionM.y) }
        )
        return jsonCodec.encodeToString(OsmBundleDto.serializer(), dto)
    }

    companion object {
        private val jsonCodec = Json { ignoreUnknownKeys = true }

        private val ROAD_WIDTHS_M = mapOf(
            "primary" to 11.0,
            "primary_link" to 8.0,
            "secondary" to 10.0,
            "secondary_link" to 7.5,
            "tertiary" to 8.0,
            "residential" to 7.0,
            "living_street" to 6.0,
            "unclassified" to 6.5,
            "pedestrian" to 5.5,
            "service" to 3.8,
            "footway" to 2.2,
            "cycleway" to 2.2
        )

        fun parseJson(stream: InputStream): OsmSurroundings =
            parseJson(stream.bufferedReader(Charsets.UTF_8).readText())

        fun parseJson(text: String): OsmSurroundings {
            val dto = jsonCodec.decodeFromString(OsmBundleDto.serializer(), text)
            val origin = GeoOrigin(dto.originLat, dto.originLon)
            val parks = dto.parks.mapNotNull { p ->
                val pts = p.points.mapNotNull { pair ->
                    if (pair.size >= 2) PointM(pair[0], pair[1]) else null
                }
                if (pts.size >= 3) OsmPark(p.name, pts) else null
            }
            val buildings = dto.buildings.mapNotNull { b ->
                val pts = b.points.mapNotNull { pair ->
                    if (pair.size >= 2) PointM(pair[0], pair[1]) else null
                }
                if (pts.size >= 3) {
                    OsmBuilding(
                        id = b.id,
                        name = b.name,
                        ref = b.ref,
                        isFocusBuilding = b.isFocus,
                        polygonM = pts,
                        labelCenterM = centroid(pts)
                    )
                } else {
                    null
                }
            }
            val roads = dto.roads.mapNotNull { r ->
                val pts = r.points.mapNotNull { pair ->
                    if (pair.size >= 2) PointM(pair[0], pair[1]) else null
                }
                if (pts.size >= 2) OsmRoad(r.kind, r.name, r.widthM, pts) else null
            }
            val pois = dto.pois.map { OsmPoi(it.kind, it.name, PointM(it.x, it.y)) }
            return OsmSurroundings(origin, parks, buildings, roads, pois)
        }

        /**
         * Parses raw OpenStreetMap `/api/0.6/map` XML (or Overpass XML) directly into
         * metric [OsmSurroundings] anchored at [origin].
         */
        fun parseOsmXml(
            xmlText: String,
            origin: GeoOrigin,
            focusBuildingRef: String = "MB"
        ): OsmSurroundings {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }
            val doc = dbf.newDocumentBuilder()
                .parse(ByteArrayInputStream(xmlText.toByteArray(Charsets.UTF_8)))
            val root = doc.documentElement

            val nodesMap = HashMap<String, PointM>()
            val pois = ArrayList<OsmPoi>()

            val nodeEls = root.getElementsByTagName("node")
            for (i in 0 until nodeEls.length) {
                val el = nodeEls.item(i) as? Element ?: continue
                val id = el.getAttribute("id")
                val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
                val pt = origin.toEastNorthM(lat, lon)
                nodesMap[id] = pt

                val tags = readTags(el)
                if (tags["railway"] == "subway_entrance" || tags["station"] == "subway") {
                    pois.add(OsmPoi("metro", tags["name"] ?: "Métro", pt))
                }
            }

            val parks = ArrayList<OsmPark>()
            val buildings = ArrayList<OsmBuilding>()
            val roads = ArrayList<OsmRoad>()

            val wayEls = root.getElementsByTagName("way")
            for (i in 0 until wayEls.length) {
                val el = wayEls.item(i) as? Element ?: continue
                val tags = readTags(el)
                if (tags["indoor"] != null) continue
                if (tags["tunnel"] == "yes" && tags["highway"] in setOf("footway", "steps", "corridor")) {
                    continue
                }
                val ndEls = el.getElementsByTagName("nd")
                val pts = ArrayList<PointM>(ndEls.length)
                for (j in 0 until ndEls.length) {
                    val ref = (ndEls.item(j) as? Element)?.getAttribute("ref") ?: continue
                    nodesMap[ref]?.let { pts.add(it) }
                }
                if (pts.size < 2) continue

                val buildingTag = tags["building"]
                val highwayTag = tags["highway"]
                if (buildingTag != null && pts.size >= 3) {
                    val poly = if (pts.size > 3 && pts.first() == pts.last()) {
                        pts.subList(0, pts.size - 1)
                    } else {
                        pts
                    }
                    val ref = tags["ref"].orEmpty()
                    val name = tags["name"].orEmpty()
                    val isFocus = (focusBuildingRef.isNotBlank() && ref == focusBuildingRef) ||
                        name.contains("Molson", ignoreCase = true)
                    buildings.add(
                        OsmBuilding(
                            id = el.getAttribute("id"),
                            name = name,
                            ref = ref,
                            isFocusBuilding = isFocus,
                            polygonM = poly,
                            labelCenterM = centroid(poly)
                        )
                    )
                } else if (highwayTag != null && highwayTag in ROAD_WIDTHS_M) {
                    if (highwayTag == "footway" && tags["footway"] in setOf("sidewalk", "crossing")) {
                        continue
                    }
                    roads.add(
                        OsmRoad(
                            kind = highwayTag,
                            name = tags["name"].orEmpty(),
                            widthM = ROAD_WIDTHS_M.getValue(highwayTag),
                            polylineM = pts
                        )
                    )
                } else if (pts.size >= 3 && (
                        tags["leisure"] in setOf("park", "garden", "pitch") ||
                            tags["landuse"] in setOf("grass", "recreation_ground")
                        )
                ) {
                    val poly = if (pts.size > 3 && pts.first() == pts.last()) {
                        pts.subList(0, pts.size - 1)
                    } else {
                        pts
                    }
                    parks.add(OsmPark(tags["name"].orEmpty(), poly))
                }
            }
            return OsmSurroundings(origin, parks, buildings, roads, pois)
        }

        private fun readTags(el: Element): Map<String, String> {
            val tags = el.getElementsByTagName("tag")
            if (tags.length == 0) return emptyMap()
            val out = HashMap<String, String>(tags.length)
            for (i in 0 until tags.length) {
                val t = tags.item(i) as? Element ?: continue
                val k = t.getAttribute("k")
                val v = t.getAttribute("v")
                if (k.isNotEmpty()) out[k] = v
            }
            return out
        }

        private fun centroid(pts: List<PointM>): PointM {
            var sx = 0.0
            var sy = 0.0
            for (p in pts) {
                sx += p.x
                sy += p.y
            }
            return PointM(sx / pts.size, sy / pts.size)
        }
    }
}
