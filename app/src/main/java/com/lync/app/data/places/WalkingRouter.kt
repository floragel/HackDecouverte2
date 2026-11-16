package com.lync.app.data.places

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

enum class Maneuver { DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ARRIVE }

data class WalkStep(val maneuver: Maneuver, val street: String, val distanceM: Double, val at: GeoPoint)

/**
 * A walking route. [approximate] = no routing service reachable, straight line to the door.
 * [stepAlongM] = distance along [points] of each step's maneuver (for "in 80 m, turn left").
 */
data class WalkRoute(
    val points: List<GeoPoint>,
    val distanceM: Double,
    val durationS: Double,
    val steps: List<WalkStep>,
    val approximate: Boolean,
) {
    val cumulativeM: DoubleArray = DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) acc[i] = acc[i - 1] + Geo.distanceM(points[i - 1], points[i])
    }
    val lengthM: Double get() = cumulativeM.lastOrNull() ?: 0.0
    val stepAlongM: List<Double> = run {
        var from = 0
        steps.map { st ->
            val (along, idx) = Geo.project(points, cumulativeM, st.at, from)
            from = idx
            along
        }
    }
}

/** Where the user is on the route right now. */
data class WalkProgress(
    val alongM: Double,
    val remainingM: Double,
    val offRouteM: Double,
    val nextStep: WalkStep?,
    val distanceToNextStepM: Double,
    /** Route direction at the user's position (degrees clockwise from north), for the follow camera. */
    val bearingDeg: Double,
) {
    companion object {
        fun of(route: WalkRoute, me: GeoPoint): WalkProgress {
            val (along, seg) = Geo.project(route.points, route.cumulativeM, me, 0)
            val onLine = Geo.pointAt(route.points, route.cumulativeM, along)
            val nextIdx = route.stepAlongM.indexOfFirst { it > along + 4.0 }
            val next = if (nextIdx >= 0) route.steps[nextIdx] else null
            val a = route.points.getOrNull(seg) ?: me
            val b = route.points.getOrNull(seg + 1) ?: a
            return WalkProgress(
                alongM = along,
                remainingM = (route.lengthM - along).coerceAtLeast(0.0),
                offRouteM = Geo.distanceM(me, onLine),
                nextStep = next,
                distanceToNextStepM = if (nextIdx >= 0) (route.stepAlongM[nextIdx] - along).coerceAtLeast(0.0) else 0.0,
                bearingDeg = Geo.bearingDeg(a, b),
            )
        }
    }
}

/** Small flat-earth helpers — plenty for a campus-sized walk. */
object Geo {
    private const val M_PER_DEG = 111_320.0

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat) * M_PER_DEG
        val dLng = (b.lng - a.lng) * M_PER_DEG * cos(Math.toRadians((a.lat + b.lat) / 2))
        return sqrt(dLat * dLat + dLng * dLng)
    }

    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
        val dy = b.lat - a.lat
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    /** Closest point of the polyline to [p], searched from segment [fromSeg]: (distance along, segment index). */
    fun project(points: List<GeoPoint>, cumulative: DoubleArray, p: GeoPoint, fromSeg: Int): Pair<Double, Int> {
        if (points.size < 2) return 0.0 to 0
        val k = cos(Math.toRadians(p.lat))
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestSeg = fromSeg.coerceIn(0, points.size - 2)
        for (i in fromSeg.coerceIn(0, points.size - 2) until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val ax = (a.lng - p.lng) * k; val ay = a.lat - p.lat
            val bx = (b.lng - p.lng) * k; val by = b.lat - p.lat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val cx = ax + t * dx; val cy = ay + t * dy
            val d = cx * cx + cy * cy
            if (d < best) {
                best = d; bestSeg = i
                bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            }
        }
        return bestAlong to bestSeg
    }

    fun pointAt(points: List<GeoPoint>, cumulative: DoubleArray, alongM: Double): GeoPoint {
        if (points.isEmpty()) return GeoPoint(0.0, 0.0)
        for (i in 1 until points.size) {
            if (cumulative[i] >= alongM) {
                val seg = cumulative[i] - cumulative[i - 1]
                val t = if (seg > 0) (alongM - cumulative[i - 1]) / seg else 0.0
                val a = points[i - 1]; val b = points[i]
                return GeoPoint(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))
            }
        }
        return points.last()
    }
}

/**
 * Walking directions from the free OpenStreetMap routing service (OSRM, foot profile, no API key).
 * Offline / service down -> a straight line at walking pace, flagged [WalkRoute.approximate].
 */
object WalkingRouter {
    private const val ENDPOINT = "https://routing.openstreetmap.de/routed-foot/route/v1/foot"
    const val WALK_SPEED_MPS = 1.3
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun route(from: GeoPoint, to: GeoPoint): WalkRoute = withContext(Dispatchers.IO) {
        runCatching { fetch(from, to) }.getOrNull() ?: straightLine(from, to)
    }

    fun straightLine(from: GeoPoint, to: GeoPoint): WalkRoute {
        val d = Geo.distanceM(from, to)
        return WalkRoute(
            points = listOf(from, to), distanceM = d, durationS = d / WALK_SPEED_MPS,
            steps = listOf(WalkStep(Maneuver.DEPART, "", d, from), WalkStep(Maneuver.ARRIVE, "", 0.0, to)),
            approximate = true,
        )
    }

    private fun fetch(from: GeoPoint, to: GeoPoint): WalkRoute? {
        val url = URL("$ENDPOINT/${from.lng},${from.lat};${to.lng},${to.lat}?overview=full&geometries=geojson&steps=true")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 8_000
            setRequestProperty("User-Agent", "Lync-campus-app/0.1 (hackathon)")
        }
        return try {
            if (conn.responseCode != 200) null else parse(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    /** Parses an OSRM `/route` response (public for tests). */
    fun parse(body: String): WalkRoute? {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["code"]?.jsonPrimitive?.contentOrNull != "Ok") return null
        val route = root["routes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val coords = route["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return null
        val points = coords.map { c ->
            val a = c.jsonArray
            GeoPoint(a[1].jsonPrimitive.doubleOrNull ?: 0.0, a[0].jsonPrimitive.doubleOrNull ?: 0.0)
        }
        if (points.size < 2) return null
        val steps = route["legs"]?.jsonArray.orEmpty().flatMap { leg ->
            leg.jsonObject["steps"]?.jsonArray.orEmpty().map { it.jsonObject.toStep() }
        }
        val distance = route["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        return WalkRoute(
            points = points,
            distanceM = distance,
            // OSRM's foot speed is optimistic in town; keep a realistic student pace
            durationS = maxOf(route["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0, distance / WALK_SPEED_MPS),
            steps = steps,
            approximate = false,
        )
    }

    private fun JsonObject.toStep(): WalkStep {
        val m = this["maneuver"]?.jsonObject
        val type = m?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val modifier = m?.get("modifier")?.jsonPrimitive?.contentOrNull.orEmpty()
        val loc = m?.get("location")?.jsonArray
        val at = GeoPoint(loc?.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: 0.0, loc?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: 0.0)
        val maneuver = when {
            type == "depart" -> Maneuver.DEPART
            type == "arrive" -> Maneuver.ARRIVE
            else -> when (modifier) {
                "uturn" -> Maneuver.UTURN
                "sharp left" -> Maneuver.SHARP_LEFT
                "left" -> Maneuver.LEFT
                "slight left" -> Maneuver.SLIGHT_LEFT
                "sharp right" -> Maneuver.SHARP_RIGHT
                "right" -> Maneuver.RIGHT
                "slight right" -> Maneuver.SLIGHT_RIGHT
                else -> Maneuver.STRAIGHT
            }
        }
        return WalkStep(
            maneuver = maneuver,
            street = this["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            distanceM = this["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            at = at,
        )
    }
}
