package com.hackdecouverte.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.hackdecouverte.rec.RecEvent
import com.hackdecouverte.rec.RecordingHeader
import com.hackdecouverte.rec.RecordingWriter
import com.hackdecouverte.rec.SensorInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** What the record button shows. */
data class RecorderStatus(
    val recording: Boolean = false,
    val startedNs: Long = 0L,
    val markers: Int = 0,
    /** Where the walker said they were, in plan metres (drawn on the map while recording). */
    val markerPoints: List<com.hackdecouverte.pdr.PointM> = emptyList(),
    val lines: Long = 0L,
    val wifiScans: Int = 0,
    val rttOk: Int = 0,
    val file: File? = null,
    /** The last finished recording (to share). */
    val lastFile: File? = null,
    val error: String? = null
)

/**
 * Records a walk for replay on a computer (format: [RecordingWriter]). The PDR's own sensor stream and
 * GPS fixes come in through the taps ([onPipelineSample], [onFix]) so the replay sees exactly what the
 * phone used. While recording it also logs, for research only: raw accelerometer / gyroscope /
 * magnetometer (calibrated and not) / light / step counter, GNSS satellites (C/N0, azimuth, elevation),
 * Wi-Fi scans and, where phone and access points support it, Wi-Fi RTT (802.11mc) ranges.
 * Files go to `Android/data/<app>/files/recordings/` (`adb pull`, or share from the app).
 */
class WalkRecorder(context: Context) {
    private val app = context.applicationContext
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val wifiManager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val rttManager: WifiRttManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
            app.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        } else null

    private val _status = MutableStateFlow(RecorderStatus(lastFile = recordings().firstOrNull()))
    val status: StateFlow<RecorderStatus> = _status

    @Volatile private var active = false
    private var queue = LinkedBlockingQueue<String>()
    private var writerThread: Thread? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var markerCount = 0
    @Volatile private var lines = 0L
    private var ftmResponders: List<ScanResult> = emptyList()

    val wifiRttSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && rttManager?.isAvailable == true

    fun dir(): File = File(app.getExternalFilesDir(null) ?: app.filesDir, "recordings").apply { mkdirs() }

    /** Finished recordings, newest first. */
    fun recordings(): List<File> = dir().listFiles { f -> f.name.endsWith(".txt.gz") }.orEmpty().sortedByDescending { it.lastModified() }

    /** Sensor description for the header (what the replay and the paper need to know about the phone). */
    fun describe(type: Int): SensorInfo? = sensorManager.getDefaultSensor(type)?.let {
        SensorInfo(it.type, it.name, it.vendor, it.resolution, it.maximumRange, it.minDelay, it.fifoMaxEventCount)
    }

    /** Starts writing; [header] already filled by the backend (site, floor, step length…). False if already recording or the file cannot be opened. */
    fun start(header: RecordingHeader, initialFixes: List<RecEvent.Fix>): Boolean {
        if (active) return false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(header.startedWallMs))
        val file = File(dir(), "${header.floorId}_$stamp.txt.gz")
        val writer = runCatching { GZIPOutputStream(file.outputStream(), 1 shl 16).bufferedWriter() }.getOrElse {
            _status.value = _status.value.copy(error = "fichier : ${it.message}")
            return false
        }
        val full = header.copy(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdk = Build.VERSION.SDK_INT,
            sensors = EXTRA_TYPES.plus(PIPELINE_TYPES).distinct().mapNotNull(::describe),
            wifiRttSupported = wifiRttSupported
        )
        queue = LinkedBlockingQueue()
        markerCount = 0
        lines = 0
        val rec = RecordingWriter(writer)
        rec.header(full)
        initialFixes.forEach { queue.put(RecordingWriter.line(it)) }
        writerThread = Thread({ drain(writer, file) }, "walk-writer").apply { start() }
        active = true
        _status.value = RecorderStatus(recording = true, startedNs = header.startedNs, file = file, lastFile = _status.value.lastFile)
        startResearchStreams()
        Log.d(TAG, "recording to $file")
        return true
    }

    /** Stops and closes the file (also called when the app goes to the background). */
    fun stop(): File? {
        if (!active) return null
        note("stop", "")
        active = false
        stopResearchStreams()
        queue.put(END)
        writerThread?.join(5_000)
        writerThread = null
        val file = _status.value.file
        _status.value = RecorderStatus(lastFile = file ?: _status.value.lastFile, lines = lines, markers = markerCount)
        return file
    }

    private fun drain(writer: java.io.Writer, file: File) {
        try {
            while (true) {
                val l = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (l === END) break
                writer.write(l); writer.write("\n")
                lines++
                if (lines % 500 == 0L) _status.value = _status.value.copy(lines = lines)
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed", e)
            _status.value = _status.value.copy(error = "écriture : ${e.message}")
        } finally {
            runCatching { writer.close() }
            Log.d(TAG, "recording closed: $file ($lines lines)")
        }
    }

    private fun put(e: RecEvent) {
        if (active) queue.put(RecordingWriter.line(e))
    }

    // ---- taps from the PDR pipeline (main thread) ----

    fun onPipelineSample(type: Int, timestampNs: Long, values: FloatArray, accuracy: Int) {
        if (!active) return
        val lagUs = (SystemClock.elapsedRealtimeNanos() - timestampNs) / 1000
        put(RecEvent.Sample(type, timestampNs, accuracy, true, lagUs, values.copyOf()))
    }

    fun onPipelineAccuracy(type: Int, accuracy: Int) = put(RecEvent.Accuracy(type, SystemClock.elapsedRealtimeNanos(), accuracy))

    fun onFix(fix: RecEvent.Fix) = put(fix)

    fun onLive(e: RecEvent.Live) = put(e)

    fun note(name: String, detail: String) = put(RecEvent.Note(SystemClock.elapsedRealtimeNanos(), name, detail))

    /** Ground truth: the walker says they are at ([eastM], [northM]) on the plan right now. Returns the marker number, or null when not recording. */
    fun addMarker(eastM: Double, northM: Double): Int? {
        if (!active) return null
        markerCount++
        put(RecEvent.Marker(SystemClock.elapsedRealtimeNanos(), markerCount, eastM, northM, "M$markerCount"))
        _status.value = _status.value.copy(markers = markerCount, markerPoints = _status.value.markerPoints + com.hackdecouverte.pdr.PointM(eastM, northM))
        return markerCount
    }

    // ---- research streams (own thread) ----

    private val extraListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!active) return
            val lagUs = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1000
            put(RecEvent.Sample(event.sensor.type, event.timestamp, event.accuracy, false, lagUs, event.values.copyOf()))
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val sats = (0 until status.satelliteCount).map { i ->
                RecEvent.Satellite(
                    status.getConstellationType(i), status.getSvid(i), status.getCn0DbHz(i), status.getAzimuthDegrees(i),
                    status.getElevationDegrees(i), status.usedInFix(i),
                    if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) / 1e6f else null
                )
            }
            put(RecEvent.Gnss(SystemClock.elapsedRealtimeNanos(), sats))
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = recordScan()
    }

    private val scanTick = object : Runnable {
        override fun run() {
            if (!active) return
            @Suppress("DEPRECATION")
            runCatching { wifiManager?.startScan() } // throttled by Android to 4 per 2 min in the foreground
            handler?.postDelayed(this, WIFI_SCAN_PERIOD_MS)
        }
    }

    private val rttTick = object : Runnable {
        override fun run() {
            if (!active) return
            rangeOnce()
            handler?.postDelayed(this, RTT_PERIOD_MS)
        }
    }

    @SuppressLint("MissingPermission") // fine location checked by hasFineLocation()
    private fun startResearchStreams() {
        val t = HandlerThread("walk-research").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        for (type in EXTRA_TYPES) {
            sensorManager.getDefaultSensor(type)?.let { sensorManager.registerListener(extraListener, it, EXTRA_PERIOD_US, h) }
        }
        if (hasFineLocation()) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.registerGnssStatusCallback({ r -> h.post(r) }, gnssCallback)
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.registerGnssStatusCallback(gnssCallback, h)
                }
            }
            runCatching {
                ContextCompat.registerReceiver(app, scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), null, h, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
            h.post { recordScan() } // whatever the last scan saw, before the first new one
            h.post(scanTick)
            if (rttManager != null) h.postDelayed(rttTick, RTT_PERIOD_MS)
        }
    }

    private fun stopResearchStreams() {
        sensorManager.unregisterListener(extraListener)
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        runCatching { app.unregisterReceiver(scanReceiver) }
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        ftmResponders = emptyList()
    }

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun recordScan() {
        if (!active || !hasFineLocation()) return
        val results = runCatching { wifiManager?.scanResults }.getOrNull().orEmpty()
        if (results.isEmpty()) return
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000
        put(RecEvent.WifiScan(SystemClock.elapsedRealtimeNanos(), results.map {
            RecEvent.AccessPoint(it.BSSID ?: "", it.level, it.frequency, it.is80211mcResponder, (nowUs - it.timestamp) / 1000)
        }))
        ftmResponders = results.filter { it.is80211mcResponder }.sortedByDescending { it.level }
        _status.value = _status.value.copy(wifiScans = _status.value.wifiScans + 1)
    }

    @SuppressLint("MissingPermission")
    private fun rangeOnce() {
        val rtt = rttManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !rtt.isAvailable || ftmResponders.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) return
        val request = runCatching {
            RangingRequest.Builder().apply { ftmResponders.take(RangingRequest.getMaxPeers()).forEach(::addAccessPoint) }.build()
        }.getOrNull() ?: return
        val h = handler ?: return
        runCatching {
            rtt.startRanging(request, { r -> h.post(r) }, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) = note("rtt_failure", code.toString())

                override fun onRangingResults(results: List<RangingResult>) {
                    val ok = results.count { it.status == RangingResult.STATUS_SUCCESS }
                    put(RecEvent.Rtt(SystemClock.elapsedRealtimeNanos(), results.map {
                        val success = it.status == RangingResult.STATUS_SUCCESS
                        RecEvent.Range(
                            it.macAddress?.toString() ?: "", it.status,
                            if (success) it.distanceMm else 0, if (success) it.distanceStdDevMm else 0, if (success) it.rssi else 0,
                            if (success) it.numAttemptedMeasurements else 0, if (success) it.numSuccessfulMeasurements else 0
                        )
                    }))
                    if (ok > 0) _status.value = _status.value.copy(rttOk = _status.value.rttOk + ok)
                }
            })
        }.onFailure { note("rtt_error", it.message ?: "") }
    }

    companion object {
        private const val TAG = "WalkRecorder"
        private const val END = "\u0000end"
        private const val EXTRA_PERIOD_US = 10_000 // 100 Hz
        private const val WIFI_SCAN_PERIOD_MS = 30_000L
        private const val RTT_PERIOD_MS = 1_000L

        /** Logged for research only (not used by the PDR). */
        val EXTRA_TYPES = listOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_LIGHT, Sensor.TYPE_STEP_COUNTER
        )

        /** What the PDR registers (described in the header). */
        val PIPELINE_TYPES = listOf(
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_PRESSURE
        )
    }
}
