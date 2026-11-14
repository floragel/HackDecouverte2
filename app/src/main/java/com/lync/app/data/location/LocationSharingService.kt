package com.lync.app.data.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lync.app.LyncApp
import com.lync.app.MainActivity
import com.lync.app.R
import com.lync.app.data.model.LocationSharing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps sharing your position with friends while sharing is on — also when Lync is in the background.
 * A visible notification says so, with a one-tap "Ghost mode" action. Only started while the app is
 * open (allowed with the "while using the app" permission), stops in ghost mode or on sign-out.
 */
class LocationSharingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val container get() = (application as LyncApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_GHOST) {
            scope.launch {
                runCatching { container.repository.setLocationSharing(LocationSharing.OFF) }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (!container.locationTracker.hasPermission()) { stopSelf(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        container.locationTracker.acquire(HOLDER)
        return START_STICKY
    }

    override fun onDestroy() {
        container.locationTracker.release(HOLDER)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification() = run {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.loc_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.loc_channel_desc)
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val ghost = PendingIntent.getService(this, 1, Intent(this, LocationSharingService::class.java).setAction(ACTION_GHOST), PendingIntent.FLAG_IMMUTABLE)
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lync_mark)
            .setContentTitle(getString(R.string.loc_notif_title))
            .setContentText(getString(R.string.loc_notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.map_ghost), ghost)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "location_sharing"
        private const val NOTIFICATION_ID = 42
        private const val HOLDER = "service"
        const val ACTION_GHOST = "com.lync.app.GHOST"
    }
}
