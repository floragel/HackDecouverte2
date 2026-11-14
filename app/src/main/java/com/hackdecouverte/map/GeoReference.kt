package com.hackdecouverte.map

import com.hackdecouverte.pdr.PointM
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class Wgs84Point(
    val latitude: Double,
    val longitude: Double
) {
    fun isFinite(): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}

data class GeoOrigin(
    val latitude: Double,
    val longitude: Double
) {
    private val cosLat = cos(Math.toRadians(latitude))

    fun toEastNorthM(lat: Double, lon: Double): PointM {
        val east = Math.toRadians(lon - longitude) * EARTH_RADIUS_M * cosLat
        val north = Math.toRadians(lat - latitude) * EARTH_RADIUS_M
        return PointM(east, north)
    }

    fun toEastNorthM(point: Wgs84Point): PointM =
        toEastNorthM(point.latitude, point.longitude)

    fun toWgs84(eastNorthM: PointM): Wgs84Point {
        val lat = latitude + Math.toDegrees(eastNorthM.y / EARTH_RADIUS_M)
        val lon = longitude + Math.toDegrees(eastNorthM.x / (EARTH_RADIUS_M * cosLat))
        return Wgs84Point(lat, lon)
    }

    fun bboxAround(radiusM: Double): GeoBbox {
        val sw = toWgs84(PointM(-radiusM, -radiusM))
        val ne = toWgs84(PointM(radiusM, radiusM))
        return GeoBbox(
            minLon = sw.longitude,
            minLat = sw.latitude,
            maxLon = ne.longitude,
            maxLat = ne.latitude
        )
    }

    companion object {
        const val EARTH_RADIUS_M = 6_378_137.0

        /**
         * Centre of the 6 central elevator cars of Concordia University's
         * John Molson Building (MB, 1450 Rue Guy, Montréal) — matches (0, 0)
         * of all MB-floor-01..07.svg vector plans.
         */
        val MB_ELEVATOR_CORE = GeoOrigin(
            latitude = 45.4952549,
            longitude = -73.5790496
        )

        /** Magnetic declination in Montréal (~14.2° West of True North). */
        const val DEFAULT_MONTREAL_DECLINATION_DEG = -14.2

        fun trueNorthBearingRad(from: PointM, to: PointM): Double =
            atan2(to.x - from.x, to.y - from.y)

        fun unitFromTrueNorthRad(azimuthRad: Double): PointM =
            PointM(sin(azimuthRad), cos(azimuthRad))

        fun degToRad(deg: Double): Double = deg * PI / 180.0
    }
}

data class GeoBbox(
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double
)
