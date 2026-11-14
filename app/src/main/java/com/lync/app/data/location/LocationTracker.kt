package com.lync.app.data.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lync.app.data.CampusRepository
import com.lync.app.data.model.LocationSharing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Single source of the user's live position.
 *
 *  - GPS runs while someone "holds" it: the sharing service (app in background or foreground)
 *    and/or the map screen (to draw your own dot, even in ghost mode).
 *  - Positions are pushed to the backend only when sharing is on, throttled
 *    (every 15 s or after moving 20 m) so friends see you move in real time without draining the battery.
 *
 * iOS: CLLocationManager with allowsBackgroundLocationUpdates + "While Using" permission,
 * same throttling.
 */
class LocationTracker(
    private val context: Context,
    private val repo: CampusRepository,
    private val scope: CoroutineScope,
) {
    private val client = LocationClient(context)
    private val _position = MutableStateFlow<Pair<Double, Double>?>(null)
    val position: StateFlow<Pair<Double, Double>?> = _position.asStateFlow()

    /** Bumped when the user grants location permission, so the sharing state is re-evaluated. */
    private val _permissionTick = MutableStateFlow(0)
    val permissionTick: StateFlow<Int> = _permissionTick.asStateFlow()

    private val holders = mutableSetOf<String>()
    private var job: Job? = null
    private var lastPush: Triple<Double, Double, Long>? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun onPermissionChanged() { _permissionTick.value++ }

    @Synchronized
    fun acquire(holder: String) {
        holders += holder
        if (job == null && hasPermission()) {
            job = scope.launch {
                client.updates(intervalMs = 10_000).collect { p ->
                    _position.value = p
                    maybePush(p)
                }
            }
        }
    }

    @Synchronized
    fun release(holder: String) {
        holders -= holder
        if (holders.isEmpty()) { job?.cancel(); job = null }
    }

    private suspend fun maybePush(p: Pair<Double, Double>) {
        if (repo.me.value?.locationSharing == LocationSharing.OFF || repo.me.value == null) return
        val now = System.currentTimeMillis()
        val last = lastPush
        val moved = last?.let { distanceM(it.first, it.second, p.first, p.second) } ?: Double.MAX_VALUE
        if (last == null || now - last.third >= 15_000 || moved >= 20.0) {
            lastPush = Triple(p.first, p.second, now)
            runCatching { repo.pushLocation(p.first, p.second) }
        }
    }

    /** Start / stop the background sharing service to match the user's setting. */
    fun sync(sharingOn: Boolean) {
        val intent = Intent(context, LocationSharingService::class.java)
        if (sharingOn && hasPermission()) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(intent)
            lastPush = null
        }
    }

    private fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * 111_320.0
        val dLng = (lng2 - lng1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLng * dLng)
    }
}
