package com.hackdecouverte.backend

import android.content.Context
import com.hackdecouverte.map.GeoOrigin
import com.hackdecouverte.map.OsmSurroundings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads OpenStreetMap vector surroundings around a [GeoOrigin]:
 * 1. Instant offline load from bundled `assets/osm/mb_surroundings.json` (when near MB)
 *    or from local disk cache in `context.cacheDir/osm/`.
 * 2. Live refresh from `https://api.openstreetmap.org/api/0.6/map` when online or relocated.
 */
class OsmNetworkLoader(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "osm").apply { mkdirs() }

    suspend fun loadInitial(origin: GeoOrigin = GeoOrigin.MB_ELEVATOR_CORE): OsmSurroundings? =
        withContext(Dispatchers.Default) {
            val cached = readDiskCache(origin)
            if (cached != null) return@withContext cached
            runCatching {
                appContext.assets.open(BUNDLED_MB_ASSET).use { OsmSurroundings.parseJson(it) }
            }.getOrNull()
        }

    suspend fun fetchLiveSurroundings(
        origin: GeoOrigin,
        radiusM: Double = 280.0,
        focusBuildingRef: String = "MB"
    ): OsmSurroundings? = withContext(Dispatchers.IO) {
        val bbox = origin.bboxAround(radiusM)
        val urlStr = String.format(
            Locale.US,
            "https://api.openstreetmap.org/api/0.6/map?bbox=%.6f,%.6f,%.6f,%.6f",
            bbox.minLon,
            bbox.minLat,
            bbox.maxLon,
            bbox.maxLat
        )
        runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "HackDecouverte-IndoorNav/1.0")
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val parsed = OsmSurroundings.parseOsmXml(xml, origin, focusBuildingRef)
                if (parsed.buildings.isNotEmpty() || parsed.roads.isNotEmpty()) {
                    writeDiskCache(origin, parsed)
                    parsed
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun cacheFileFor(origin: GeoOrigin): File {
        val key = String.format(Locale.US, "osm_%.4f_%.4f.json", origin.latitude, origin.longitude)
        return File(cacheDir, key)
    }

    private fun readDiskCache(origin: GeoOrigin): OsmSurroundings? = runCatching {
        val file = cacheFileFor(origin)
        if (!file.exists()) return null
        OsmSurroundings.parseJson(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun writeDiskCache(origin: GeoOrigin, surroundings: OsmSurroundings) {
        runCatching {
            cacheFileFor(origin).writeText(surroundings.toJsonString(), Charsets.UTF_8)
        }
    }

    companion object {
        private const val BUNDLED_MB_ASSET = "osm/mb_surroundings.json"
    }
}
