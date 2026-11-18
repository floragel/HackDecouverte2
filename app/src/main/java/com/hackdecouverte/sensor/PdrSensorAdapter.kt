package com.hackdecouverte.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.hackdecouverte.pdr.FallbackStepDetector
import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.StepSource

class PdrSensorAdapter(
    context: Context,
    private val tracker: PdrTracker,
    private val sourceProvider: () -> StepSource,
    /** Gait-adaptive threshold on, as on iOS (only used when the accelerometer fallback is the step source). */
    private val fallback: FallbackStepDetector = FallbackStepDetector(adaptive = true),
    private val onPressureSample: (timestampNs: Long, pressureHpa: Double) -> Unit = { _, _ -> },
    private val onRegistrationResult: (Boolean) -> Unit = {}
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val compassRotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    val linearAcceleration: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val fallbackCapable: Boolean
        get() = linearAcceleration != null || accelerometer != null

    /** Same routing as the walk replay: see [SensorRouter]. */
    private val router = SensorRouter(tracker, sourceProvider, fallback, onPressureSample)

    val latestWorldAzimuthRad: Double?
        get() = router.latestWorldAzimuthRad

    var magneticDeclinationDeg: Double
        get() = router.magneticDeclinationDeg
        set(value) {
            router.magneticDeclinationDeg = value
        }

    /** Every sample the PDR receives, before it is used (the walk recorder taps here). Called on the main thread. */
    @Volatile
    var onRawSample: ((type: Int, timestampNs: Long, values: FloatArray, accuracy: Int) -> Unit)? = null

    @Volatile
    var onRawAccuracy: ((type: Int, accuracy: Int) -> Unit)? = null

    private var registered = false

    fun start() {
        if (registered) return
        registered = true
        router.reset()
        var failed = false
        val rot = rotationSensor
        if (rot != null) {
            failed = !sensorManager.registerListener(
                this, rot, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US
            ) || failed
        }
        compassRotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, 0)
        }
        pressureSensor?.let {
            sensorManager.registerListener(this, it, PRESSURE_SAMPLING_PERIOD_US, 0)
        }
        val activeSource = sourceProvider()
        when (activeSource) {
            StepSource.HARDWARE -> {
                val step = stepSensor
                if (step != null) {
                    failed = !sensorManager.registerListener(this, step, 0, 0) || failed
                }
                linearAcceleration?.let {
                    sensorManager.registerListener(
                        this, it, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US
                    )
                }
            }

            StepSource.FALLBACK -> {
                fallback.start(SystemClock.elapsedRealtimeNanos())
                val lin = linearAcceleration
                if (lin != null) {
                    failed = !sensorManager.registerListener(
                        this, lin, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US
                    ) || failed
                } else {
                    val acc = accelerometer
                    if (acc != null) {
                        failed = !sensorManager.registerListener(
                            this, acc, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US
                        ) || failed
                    }
                }
            }

            StepSource.NONE -> {}
        }
        onRegistrationResult(failed)
    }

    fun stop() {
        if (!registered) return
        registered = false
        sensorManager.unregisterListener(this)
        fallback.stop()
        router.reset()
    }

    fun refreshRegistrations() {
        if (!registered) return
        stop()
        start()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered) return
        onRawSample?.invoke(event.sensor.type, event.timestamp, event.values, event.accuracy)
        router.onSample(event.sensor.type, event.timestamp, event.values, event.accuracy)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        onRawAccuracy?.invoke(sensor.type, accuracy)
        router.onAccuracyChanged(sensor.type, accuracy)
    }

    companion object {
        private const val SAMPLING_PERIOD_US = 16_667
        private const val PRESSURE_SAMPLING_PERIOD_US = 100_000
        private const val MAX_REPORT_LATENCY_US = 0
    }
}
