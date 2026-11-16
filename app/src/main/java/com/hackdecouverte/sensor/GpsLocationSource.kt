package com.hackdecouverte.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.hackdecouverte.map.GeoOrigin
import com.hackdecouverte.map.Wgs84Point
import com.hackdecouverte.pdr.PointM
import kotlin.math.abs

data class GpsFix(
    val wgs84: Wgs84Point,
    val eastNorthM: PointM,
    val accuracyM: Double,
    val verticalAccuracyM: Double?,
    val trueNorthBearingRad: Double?,
    val speedMps: Double,
    val magneticDeclinationDeg: Double,
    val provider: String,
    val satellitesUsed: Int,
    val galileoUsed: Int,
    val dualFrequencyUsed: Int,
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false

    private var satellitesUsedCount = 0
    private var galileoUsedCount = 0
    private var dualFreqUsedCount = 0
    private var lastHighPrecisionFixNs = Long.MIN_VALUE
    private var lastHighPrecisionAccM = Double.POSITIVE_INFINITY

    private val listener = LocationListener { location ->
        handleLocation(location)
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var galileo = 0
            var dualFreq = 0
            for (i in 0 until status.satelliteCount) {
                if (!status.usedInFix(i)) continue
                used++
                if (status.getConstellationType(i) == GnssStatus.CONSTELLATION_GALILEO) {
                    galileo++
                }
                if (status.hasCarrierFrequencyHz(i)) {
                    val mhz = status.getCarrierFrequencyHz(i) / 1e6f
                    // L5 / E5a (1176.45 MHz), E5b (1207.14 MHz), or Galileo E6 HAS band (1278.75 MHz)
                    if (abs(mhz - 1176.45f) < 25f || abs(mhz - 1207.14f) < 25f || abs(mhz - 1278.75f) < 25f) {
                        dualFreq++
                    }
                }
            }
            satellitesUsedCount = used
            galileoUsedCount = galileo
            dualFreqUsedCount = dualFreq
        }
    }

    fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun hasLocationPermission(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return hasFineLocationPermission() || coarse
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasLocationPermission()) return
        running = true

        val fine = hasFineLocationPermission()
        if (fine) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.registerGnssStatusCallback(appContext.mainExecutor, gnssStatusCallback)
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler)
                }
            }
        }

        val highAccProviders = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)
            ) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (fine && locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
        }
        val allProviders = buildList {
            addAll(highAccProviders)
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        var bestSeed: Location? = null
        for (p in allProviders) {
            runCatching {
                val last = locationManager.getLastKnownLocation(p)
                if (last != null && isBetterSeed(last, bestSeed)) {
                    bestSeed = last
                }
            }
        }
        bestSeed?.let(::handleLocation)

        for (p in highAccProviders) {
            runCatching {
                if (!locationManager.isProviderEnabled(p)) return@runCatching
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val req = LocationRequest.Builder(500L)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setMinUpdateIntervalMillis(250L)
                        .setMinUpdateDistanceMeters(0f)
                        .build()
                    locationManager.requestLocationUpdates(p, req, appContext.mainExecutor, listener)
                } else {
                    locationManager.requestLocationUpdates(p, 500L, 0f, listener, Looper.getMainLooper())
                }
            }
        }

        // Coarse fallback purely for geomagnetic declination if indoor GNSS has no sky view
        runCatching {
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER) &&
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5_000L,
                    2f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { locationManager.removeUpdates(listener) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssStatusCallback) }
    }

    private fun isBetterSeed(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        val dtMs = candidate.time - current.time
        if (dtMs > 30_000L) return true
        if (dtMs < -30_000L) return false
        val cAcc = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
        val curAcc = if (current.hasAccuracy()) current.accuracy else Float.MAX_VALUE
        return cAcc < curAcc
    }

    private fun handleLocation(loc: Location) {
        val lat = loc.latitude
        val lon = loc.longitude
        if (!lat.isFinite() || !lon.isFinite()) return

        val tsNs = loc.elapsedRealtimeNanos.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.POSITIVE_INFINITY
        val provider = loc.provider ?: "unknown"
        val isNetwork = provider == LocationManager.NETWORK_PROVIDER

        // Never let a coarse cell/Wi-Fi network fix clobber a fresh satellite/fused fix
        if (isNetwork && lastHighPrecisionFixNs != Long.MIN_VALUE &&
            tsNs - lastHighPrecisionFixNs <= 15_000_000_000L &&
            acc > lastHighPrecisionAccM + 2.0
        ) {
            return
        }
        if (!isNetwork && acc.isFinite()) {
            lastHighPrecisionFixNs = tsNs
            lastHighPrecisionAccM = acc
        }

        val geomag = GeomagneticField(
            lat.toFloat(),
            lon.toFloat(),
            if (loc.hasAltitude()) loc.altitude.toFloat() else 50f,
            loc.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        val declDeg = geomag.declination.toDouble()
        val eastNorth = origin.toEastNorthM(lat, lon)
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val vertAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy()) {
            loc.verticalAccuracyMeters.toDouble()
        } else {
            null
        }

        val bearingOk = loc.hasBearing() && speed >= 0.9 && acc <= 15.0 &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !loc.hasBearingAccuracy() ||
                loc.bearingAccuracyDegrees <= 28f)
        val bearingRad = if (bearingOk) Math.toRadians(loc.bearing.toDouble()) else null

        onFix(
            GpsFix(
                wgs84 = Wgs84Point(lat, lon),
                eastNorthM = eastNorth,
                accuracyM = acc,
                verticalAccuracyM = vertAcc,
                trueNorthBearingRad = bearingRad,
                speedMps = speed,
                magneticDeclinationDeg = declDeg,
                provider = provider,
                satellitesUsed = satellitesUsedCount,
                galileoUsed = galileoUsedCount,
                dualFrequencyUsed = dualFreqUsedCount,
                elapsedRealtimeNs = tsNs
            )
        )
    }
}
