package com.hackdecouverte.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.hackdecouverte.map.GeoOrigin
import com.hackdecouverte.map.Wgs84Point
import com.hackdecouverte.pdr.PointM

data class GpsFix(
    val wgs84: Wgs84Point,
    val eastNorthM: PointM,
    val accuracyM: Double,
    val trueNorthBearingRad: Double?,
    val speedMps: Double,
    val magneticDeclinationDeg: Double,
    val elapsedRealtimeNs: Long
)

class GpsLocationSource(
    context: Context,
    var origin: GeoOrigin = GeoOrigin.MB_ELEVATOR_CORE,
    private val onFix: (GpsFix) -> Unit
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

    private val listener = LocationListener { location ->
        handleLocation(location)
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasLocationPermission()) return
        running = true

        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)
            ) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        var freshest: Location? = null
        for (p in providers) {
            runCatching {
                val last = locationManager.getLastKnownLocation(p)
                if (last != null && (freshest == null || last.time > freshest!!.time)) {
                    freshest = last
                }
            }
        }
        freshest?.let(::handleLocation)

        for (p in providers) {
            runCatching {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(
                        p,
                        1_000L,
                        0.5f,
                        listener,
                        Looper.getMainLooper()
                    )
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { locationManager.removeUpdates(listener) }
    }

    private fun handleLocation(loc: Location) {
        val lat = loc.latitude
        val lon = loc.longitude
        if (!lat.isFinite() || !lon.isFinite()) return

        val geomag = GeomagneticField(
            lat.toFloat(),
            lon.toFloat(),
            if (loc.hasAltitude()) loc.altitude.toFloat() else 50f,
            loc.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        val declDeg = geomag.declination.toDouble()
        val eastNorth = origin.toEastNorthM(lat, lon)
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.POSITIVE_INFINITY
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0

        val bearingOk = loc.hasBearing() && speed >= 0.9 && acc <= 15.0 &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !loc.hasBearingAccuracy() ||
                loc.bearingAccuracyDegrees <= 28f)
        val bearingRad = if (bearingOk) Math.toRadians(loc.bearing.toDouble()) else null
        val tsNs = loc.elapsedRealtimeNanos.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()

        onFix(
            GpsFix(
                wgs84 = Wgs84Point(lat, lon),
                eastNorthM = eastNorth,
                accuracyM = acc,
                trueNorthBearingRad = bearingRad,
                speedMps = speed,
                magneticDeclinationDeg = declDeg,
                elapsedRealtimeNs = tsNs
            )
        )
    }
}
