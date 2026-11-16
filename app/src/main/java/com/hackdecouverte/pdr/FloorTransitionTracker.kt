package com.hackdecouverte.pdr

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

data class BarometerUpdate(
    val relativeAltitudeM: Double,
    val deltaFromFloorM: Double,
    val verticalSpeedMps: Double,
    val inVerticalTransit: Boolean,
    val floorStepDelta: Int
)

/**
 * Pure-Kotlin barometric floor-change & stair/elevator vertical-transit detector.
 *
 * Uses `Sensor.TYPE_PRESSURE` (hPa) to:
 * 1. Reject transient door-open/HVAC pressure spikes via a 3-sample median filter + 0.75 s EMA.
 * 2. Adapt baseline floor altitude very slowly (`τ = 45 s`) when walking flat so weather fronts
 *    do not trigger false floor switches.
 * 3. Detect active stair/elevator climb (`inVerticalTransit`) so horizontal PDR step vectors
 *    can be frozen instead of pushing the user through walls inside a switchback stairwell.
 * 4. Emit discrete `+1` / `-1` floor transitions as cumulative elevation crosses `floorHeightM`
 *    with hysteresis (`nearConnectorTriggerM` when near stairs/elevators, `fallbackTriggerM`
 *    otherwise on sustained vertical motion).
 */
class FloorTransitionTracker(
    val floorHeightM: Double = DEFAULT_FLOOR_HEIGHT_M,
    private val nearConnectorTriggerM: Double = 2.9,
    private val fallbackTriggerM: Double = 3.5
) {
    private data class AltSample(val timestampNs: Long, val altitudeM: Double)

    private var referencePressureHpa: Double? = null
    private val medianWindow = ArrayDeque<Double>(3)
    private var smoothedAltitudeM: Double? = null
    private var floorBaselineAltitudeM: Double = 0.0
    private var lastTimestampNs: Long = Long.MIN_VALUE
    private val velocityHistory = ArrayDeque<AltSample>()

    var inVerticalTransit: Boolean = false
        private set
    private var transitVerticalSign: Int = 0
    private var flatSinceNs: Long = Long.MIN_VALUE

    val hasReading: Boolean
        get() = smoothedAltitudeM != null

    val currentDeltaFromFloorM: Double
        get() = (smoothedAltitudeM ?: 0.0) - floorBaselineAltitudeM

    fun reset() {
        referencePressureHpa = null
        medianWindow.clear()
        smoothedAltitudeM = null
        floorBaselineAltitudeM = 0.0
        lastTimestampNs = Long.MIN_VALUE
        velocityHistory.clear()
        inVerticalTransit = false
        transitVerticalSign = 0
        flatSinceNs = Long.MIN_VALUE
    }

    /**
     * Re-anchors the current barometric altitude as the baseline of the user's current floor
     * (called when the user manually selects a starting room or confirms a floor).
     */
    fun onManualFloorSet() {
        smoothedAltitudeM?.let { floorBaselineAltitudeM = it }
        inVerticalTransit = false
        transitVerticalSign = 0
        flatSinceNs = Long.MIN_VALUE
    }

    fun onPressure(
        timestampNs: Long,
        pressureHpa: Double,
        nearVerticalConnector: Boolean
    ): BarometerUpdate? {
        if (!pressureHpa.isFinite() || pressureHpa !in MIN_VALID_HPA..MAX_VALID_HPA) {
            return null
        }
        if (lastTimestampNs != Long.MIN_VALUE && timestampNs <= lastTimestampNs) {
            return null
        }

        val p0 = referencePressureHpa ?: pressureHpa.also { referencePressureHpa = it }
        val rawAltM = pressureToRelativeAltitudeM(pressureHpa, p0)

        medianWindow.addLast(rawAltM)
        if (medianWindow.size > 3) medianWindow.removeFirst()
        val medAltM = medianOfUpTo3(medianWindow)

        val prevSmooth = smoothedAltitudeM
        val dtSec = if (lastTimestampNs == Long.MIN_VALUE) {
            0.0
        } else {
            ((timestampNs - lastTimestampNs) / 1e9).coerceIn(0.0, 5.0)
        }
        lastTimestampNs = timestampNs

        val smooth = if (prevSmooth == null || dtSec <= 0.0 || dtSec >= 3.0) {
            if (prevSmooth == null) {
                floorBaselineAltitudeM = medAltM
            }
            medAltM
        } else {
            val alpha = 1.0 - exp(-dtSec / EMA_TAU_SEC)
            prevSmooth + alpha * (medAltM - prevSmooth)
        }
        smoothedAltitudeM = smooth

        velocityHistory.addLast(AltSample(timestampNs, smooth))
        while (velocityHistory.size > 2 &&
            timestampNs - velocityHistory.first().timestampNs > VELOCITY_WINDOW_NS
        ) {
            velocityHistory.removeFirst()
        }
        val vz = regressionSlopeMps(velocityHistory)

        var deltaZ = smooth - floorBaselineAltitudeM

        // Update vertical transit state (stair climbing / elevator ride)
        val enteringByConnector = nearVerticalConnector &&
            abs(deltaZ) >= ENTER_TRANSIT_NEAR_M &&
            abs(vz) >= ENTER_TRANSIT_VZ_MPS
        val enteringByStrongClimb = abs(deltaZ) >= ENTER_TRANSIT_FALLBACK_M &&
            abs(vz) >= ENTER_TRANSIT_STRONG_VZ_MPS

        if (!inVerticalTransit) {
            if (enteringByConnector || enteringByStrongClimb) {
                inVerticalTransit = true
                transitVerticalSign = if (deltaZ >= 0.0) 1 else -1
                flatSinceNs = Long.MIN_VALUE
            } else if (abs(vz) < AMBIENT_MAX_VZ_MPS && abs(deltaZ) < AMBIENT_MAX_DELTA_M && dtSec > 0.0) {
                // Slow weather/HVAC pressure drift compensation while walking on a flat floor
                val ambientAlpha = dtSec / (AMBIENT_TAU_SEC + dtSec)
                floorBaselineAltitudeM += ambientAlpha * (smooth - floorBaselineAltitudeM)
                deltaZ = smooth - floorBaselineAltitudeM
            }
        } else {
            if (abs(vz) < EXIT_TRANSIT_VZ_MPS) {
                if (flatSinceNs == Long.MIN_VALUE) {
                    flatSinceNs = timestampNs
                } else if (timestampNs - flatSinceNs >= EXIT_FLAT_HOLD_NS) {
                    inVerticalTransit = false
                    transitVerticalSign = 0
                    flatSinceNs = Long.MIN_VALUE
                    // Snap residual sub-floor offset slowly back once settled on the new landing
                    if (abs(deltaZ) < nearConnectorTriggerM * 0.65) {
                        floorBaselineAltitudeM = smooth
                        deltaZ = 0.0
                    }
                }
            } else {
                flatSinceNs = Long.MIN_VALUE
            }
        }

        val effectiveNear = nearVerticalConnector || inVerticalTransit
        val trigger = if (effectiveNear) nearConnectorTriggerM else fallbackTriggerM
        var floorStep = 0
        if (effectiveNear || abs(vz) >= ENTER_TRANSIT_VZ_MPS) {
            if (deltaZ >= trigger) {
                floorStep = 1
                floorBaselineAltitudeM += floorHeightM
                deltaZ = smooth - floorBaselineAltitudeM
                inVerticalTransit = true
                transitVerticalSign = 1
            } else if (deltaZ <= -trigger) {
                floorStep = -1
                floorBaselineAltitudeM -= floorHeightM
                deltaZ = smooth - floorBaselineAltitudeM
                inVerticalTransit = true
                transitVerticalSign = -1
            }
        }

        return BarometerUpdate(
            relativeAltitudeM = smooth,
            deltaFromFloorM = deltaZ,
            verticalSpeedMps = vz,
            inVerticalTransit = inVerticalTransit,
            floorStepDelta = floorStep
        )
    }

    /**
     * Called by the backend if a detected `floorStepDelta` could not be applied because the
     * building has no floor in that direction (e.g. already on MB-1 or MB-7), preventing
     * `floorBaselineAltitudeM` from winding up past building bounds.
     */
    fun revertRejectedFloorStep(rejectedDelta: Int) {
        floorBaselineAltitudeM -= rejectedDelta * floorHeightM
    }

    companion object {
        const val DEFAULT_FLOOR_HEIGHT_M = 4.0
        private const val MIN_VALID_HPA = 850.0
        private const val MAX_VALID_HPA = 1100.0
        private const val EMA_TAU_SEC = 0.45
        private const val VELOCITY_WINDOW_NS = 1_400_000_000L
        private const val AMBIENT_TAU_SEC = 45.0
        private const val AMBIENT_MAX_VZ_MPS = 0.10
        private const val AMBIENT_MAX_DELTA_M = 1.25
        private const val ENTER_TRANSIT_NEAR_M = 1.05
        private const val ENTER_TRANSIT_FALLBACK_M = 1.65
        private const val ENTER_TRANSIT_VZ_MPS = 0.16
        private const val ENTER_TRANSIT_STRONG_VZ_MPS = 0.24
        private const val EXIT_TRANSIT_VZ_MPS = 0.12
        private const val EXIT_FLAT_HOLD_NS = 800_000_000L

        /** Least-squares vertical speed: averages out hand-swing pressure ripple while walking. */
        private fun regressionSlopeMps(samples: ArrayDeque<AltSample>): Double {
            if (samples.size < 3) return 0.0
            val t0 = samples.first().timestampNs
            val spanSec = (samples.last().timestampNs - t0) / 1e9
            if (spanSec < 0.35) return 0.0
            var sumT = 0.0
            var sumZ = 0.0
            for (s in samples) {
                sumT += (s.timestampNs - t0) / 1e9
                sumZ += s.altitudeM
            }
            val meanT = sumT / samples.size
            val meanZ = sumZ / samples.size
            var num = 0.0
            var den = 0.0
            for (s in samples) {
                val dt = (s.timestampNs - t0) / 1e9 - meanT
                num += dt * (s.altitudeM - meanZ)
                den += dt * dt
            }
            return if (den > 1e-9) num / den else 0.0
        }

        fun pressureToRelativeAltitudeM(pressureHpa: Double, referenceHpa: Double): Double =
            44330.0 * (1.0 - (pressureHpa / referenceHpa).pow(0.190294957))

        private fun medianOfUpTo3(values: ArrayDeque<Double>): Double = when (values.size) {
            0 -> 0.0
            1 -> values.first()
            2 -> (values.first() + values.last()) * 0.5
            else -> {
                val a = values.elementAt(0)
                val b = values.elementAt(1)
                val c = values.elementAt(2)
                maxOf(minOf(a, b), minOf(maxOf(a, b), c))
            }
        }
    }
}
