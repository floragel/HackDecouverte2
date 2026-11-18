package com.hackdecouverte.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lync.app.R

/**
 * Keeps indoor tracking alive with the screen off or the app in the background (spec §6.7, "trajet
 * actif"): a foreground service of type location with its notification and a partial wake lock, so
 * the sensors keep delivering in a pocket. It owns nothing: the engine (sensors, PDR, filter) lives in
 * the app's view model and simply keeps running; this service only stops Android from pausing it.
 * "Arrêter" in the notification calls [onStopRequested].
 */
class TrackingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Localisation intérieure active"
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(title),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            // No location permission, or started from the background: tracking simply pauses with the app.
            Log.w(TAG, "foreground service refused", e)
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hackdecouverte:indoor-tracking")
                .apply { acquire(MAX_WAKE_MS) }
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        running = false
        super.onDestroy()
    }

    private fun notification(title: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Localisation intérieure", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Suivi des pas pendant un trajet, écran éteint ou téléphone dans la poche"
                    setShowBadge(false)
                }
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(title)
            .setContentText("Le suivi continue écran éteint et téléphone dans la poche.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "indoor_tracking"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "com.hackdecouverte.tracking.STOP"
        private const val EXTRA_TITLE = "title"
        /** Safety net: the wake lock never outlives a forgotten session by more than this. */
        private const val MAX_WAKE_MS = 3 * 60 * 60 * 1000L

        /** Set by the engine that started the service; called by the notification's "Arrêter". */
        @Volatile
        var onStopRequested: (() -> Unit)? = null

        @Volatile
        var running = false
            private set

        fun start(context: Context, title: String) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).putExtra(EXTRA_TITLE, title))
            }.onFailure { Log.w(TAG, "could not start", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
            running = false
        }
    }
}
