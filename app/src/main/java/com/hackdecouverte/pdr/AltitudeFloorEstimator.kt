package com.hackdecouverte.pdr

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** How the absolute floor was obtained (shown to the user so the estimate is never presented as more than it is). */
enum class AltitudeFloorSource {
    /** Barometric height above a street-level pressure captured outdoors next to the building. */
    BAROMETER_STREET_REFERENCE,
    /** Barometric height relative to the pressure read the last time the floor was known (room chosen, floor confirmed, tracked climb). */
    BAROMETER_FLOOR_ANCHOR,
    /** No usable reference: the user entered from the street, so the ground floor is assumed. */
    ENTRANCE_ASSUMED
}

data class AltitudeFloorEstimate(
    val floorNumber: Int,
    /** Height above street level (metres), null when no reference was available. */
    val heightAboveStreetM: Double?,
    val source: AltitudeFloorSource,
    /** True when [probability] is at least [AltitudeFloorEstimator.CONFIDENT_PROBABILITY]. */
    val confident: Boolean,
    /** Probability of [floorNumber] among the building's floors (0 when assumed). */
    val probability: Double = 0.0,
    /** One-sigma uncertainty of [heightAboveStreetM] (metres), null when assumed. */
    val sigmaM: Double? = null,
    /** Age of the reference used (seconds), null when assumed. */
    val referenceAgeS: Double? = null
)

/**
 * A pressure read at a known height: the street outside, or a floor the user was known to be on.
 * Its uncertainty grows with age because the weather moves the pressure (≈ 0.5 hPa/h ≈ 4 m/h).
 */
data class PressureAnchor(
    val timestampNs: Long,
    val pressureHpa: Double,
    /** Height of the anchor above street level (m). */
    val elevationM: Double,
    /** One-sigma uncertainty of the anchor itself, before weather drift (m). */
    val sigmaM: Double,
    /** Older than this, the anchor is ignored. */
    val maxAgeNs: Long
) {
    fun ageNs(nowNs: Long): Long = nowNs - timestampNs

    fun usableAt(nowNs: Long): Boolean {
        val age = ageNs(nowNs)
        return pressureHpa.isFinite() && age >= -FUTURE_TOLERANCE_NS && age <= maxAgeNs
    }

    /** Variance (m²) of a height measured against this anchor at [nowNs]. */
    fun varianceAt(nowNs: Long): Double {
        val ageH = (ageNs(nowNs).coerceAtLeast(0L)) / 3.6e12
        val drift = AltitudeFloorEstimator.WEATHER_DRIFT_M_PER_H * ageH
        return sigmaM * sigmaM + AltitudeFloorEstimator.SENSOR_NOISE_M * AltitudeFloorEstimator.SENSOR_NOISE_M + drift * drift
    }

    private companion object {
        const val FUTURE_TOLERANCE_NS = 5_000_000_000L
    }
}

/**
 * Absolute floor from barometric altitude (pure Kotlin, JVM unit-tested).
 *
 * A phone barometer reads absolute pressure, which moves ±20 hPa with the weather (≈ ±170 m),
 * so absolute pressure alone cannot give a floor. Heights are therefore always measured
 * against an anchor, a pressure read at a known height on the same phone (its own offset
 * cancels):
 * - the street outside the building ([captureStreetReference]), or
 * - the floor the user was last known to be on ([anchorAtFloor]): room picked, floor confirmed,
 *   or a tracked climb that settled.
 *
 * `h = elevation(anchor) + 44330 · (1 − (p / p_anchor)^(1/5.255))` is compared with every floor
 * level as a Gaussian whose width grows with sensor noise, the anchor's own uncertainty and the
 * weather drift since the anchor ([WEATHER_DRIFT_M_PER_H]). The result is a probability per
 * floor: a barometer can be wrong (ventilation, weather, a stairwell door), so the estimate says
 * how sure it is instead of claiming a floor.
 *
 * Floor levels: floor [groundFloor] is at street level, the next one [groundFloorHeightM]
 * higher (taller lobby), then [floorHeightM] per floor.
 */
class AltitudeFloorEstimator(
    val groundFloor: Int = 1,
    val topFloor: Int = 7,
    val groundFloorHeightM: Double = 5.0,
    val floorHeightM: Double = FloorTransitionTracker.DEFAULT_FLOOR_HEIGHT_M,
    private val referenceMaxAgeNs: Long = 45L * 60L * 1_000_000_000L,
    private val floorAnchorMaxAgeNs: Long = 4L * 3600L * 1_000_000_000L
) {
    private var street: PressureAnchor? = null

    /** Last floor the user was known to be on, with the pressure read there. */
    var floorAnchor: PressureAnchor? = null
        private set

    val hasStreetReference: Boolean
        get() = street != null

    /** Records the pressure measured outdoors at street level (EMA so a single gust does not set it). */
    fun captureStreetReference(timestampNs: Long, pressureHpa: Double) {
        if (!validPressure(pressureHpa)) return
        val prev = street
        val fresh = prev != null && timestampNs - prev.timestampNs in 0..REFERENCE_SMOOTH_WINDOW_NS
        val p = if (fresh) prev!!.pressureHpa + 0.3 * (pressureHpa - prev.pressureHpa) else pressureHpa
        street = PressureAnchor(timestampNs, p, 0.0, STREET_SIGMA_M, referenceMaxAgeNs)
    }

    fun clearReference() {
        street = null
    }

    /**
     * The user is on [floor] now (room picked, floor confirmed, climb settled): remembers this
     * pressure as that floor's level. [sigmaM] is how sure we are of the floor height itself.
     */
    fun anchorAtFloor(timestampNs: Long, pressureHpa: Double, floor: Int, sigmaM: Double = KNOWN_FLOOR_SIGMA_M) {
        if (!validPressure(pressureHpa) || floor !in groundFloor..topFloor) return
        floorAnchor = PressureAnchor(timestampNs, pressureHpa, floorElevationM(floor), sigmaM, floorAnchorMaxAgeNs)
    }

    /** Restores an anchor saved before the app was closed (timestamp already converted to this boot's clock). */
    fun restoreFloorAnchor(anchor: PressureAnchor) {
        if (validPressure(anchor.pressureHpa) && anchor.sigmaM.isFinite() && anchor.sigmaM > 0.0) {
            floorAnchor = anchor.copy(maxAgeNs = floorAnchorMaxAgeNs)
        }
    }

    fun clearFloorAnchor() {
        floorAnchor = null
    }

    /** Elevation of [floor] above street level. */
    fun floorElevationM(floor: Int): Double = when {
        floor <= groundFloor -> 0.0
        else -> groundFloorHeightM + (floor - groundFloor - 1) * floorHeightM
    }

    /** Height difference between two floors (positive going up), e.g. 5 m from the ground floor to the next. */
    fun floorSpacingM(fromFloor: Int, toFloor: Int): Double = floorElevationM(toFloor) - floorElevationM(fromFloor)

    /** The anchor giving the smallest uncertainty at [timestampNs], if any is usable. */
    fun bestAnchor(timestampNs: Long): PressureAnchor? =
        listOfNotNull(street, floorAnchor)
            .filter { it.usableAt(timestampNs) }
            .minByOrNull { it.varianceAt(timestampNs) }

    /** Probability of each floor (sums to 1), empty without a usable anchor or pressure. */
    fun floorProbabilities(timestampNs: Long, pressureHpa: Double?): Map<Int, Double> {
        if (pressureHpa == null || !validPressure(pressureHpa)) return emptyMap()
        val a = bestAnchor(timestampNs) ?: return emptyMap()
        val h = a.elevationM + heightAboveM(pressureHpa, a.pressureHpa)
        return probabilitiesFor(h, a.varianceAt(timestampNs))
    }

    fun estimate(timestampNs: Long, pressureHpa: Double?): AltitudeFloorEstimate {
        val a = if (pressureHpa != null && validPressure(pressureHpa)) bestAnchor(timestampNs) else null
        if (a == null) {
            return AltitudeFloorEstimate(groundFloor, null, AltitudeFloorSource.ENTRANCE_ASSUMED, confident = false)
        }
        val h = a.elevationM + heightAboveM(pressureHpa!!, a.pressureHpa)
        val variance = a.varianceAt(timestampNs)
        val probs = probabilitiesFor(h, variance)
        val (floor, p) = probs.maxByOrNull { it.value }?.toPair() ?: (groundFloor to 0.0)
        return AltitudeFloorEstimate(
            floorNumber = floor,
            heightAboveStreetM = h,
            source = if (a === street) AltitudeFloorSource.BAROMETER_STREET_REFERENCE else AltitudeFloorSource.BAROMETER_FLOOR_ANCHOR,
            confident = p >= CONFIDENT_PROBABILITY,
            probability = p,
            sigmaM = sqrt(variance),
            referenceAgeS = a.ageNs(timestampNs).coerceAtLeast(0L) / 1e9
        )
    }

    private fun probabilitiesFor(h: Double, variance: Double): Map<Int, Double> {
        val floors = groundFloor..topFloor
        // Heights far outside the building still pick the nearest end floor: no underflow to zero everywhere.
        val nearest = floors.minByOrNull { abs(floorElevationM(it) - h) } ?: groundFloor
        val ref = floorElevationM(nearest) - h
        val weights = floors.associateWith { f ->
            val d = floorElevationM(f) - h
            exp(-(d * d - ref * ref) / (2.0 * variance))
        }
        val sum = weights.values.sum()
        return weights.mapValues { it.value / sum }
    }

    companion object {
        private const val REFERENCE_SMOOTH_WINDOW_NS = 30L * 1_000_000_000L

        /** Probability above which the barometer floor is shown without a question mark. */
        const val CONFIDENT_PROBABILITY = 0.9

        /** Pressure noise of a phone barometer after the app's smoothing, as a height (m). */
        const val SENSOR_NOISE_M = 0.3

        /** Typical weather pressure tendency, ≈ 0.5 hPa/h, as a height (m per hour, one sigma). */
        const val WEATHER_DRIFT_M_PER_H = 4.0

        /** Street vs inside: the street is not exactly at ground-floor level, and the building's ventilation shifts the pressure. */
        const val STREET_SIGMA_M = 1.3

        /** A floor chosen by the user (room picked or floor confirmed). */
        const val KNOWN_FLOOR_SIGMA_M = 0.5

        /** A floor reached by a tracked climb the barometer agreed with. */
        const val TRACKED_FLOOR_SIGMA_M = 0.7

        /** A floor reached by a tracked climb the absolute estimate could not confirm. */
        const val UNCONFIRMED_FLOOR_SIGMA_M = 1.2

        private fun validPressure(p: Double) = p.isFinite() && p in 300.0..1_100.0

        /** International barometric formula: height of [pressureHpa] above the level where [referenceHpa] was read. */
        fun heightAboveM(pressureHpa: Double, referenceHpa: Double): Double =
            44_330.0 * (1.0 - (pressureHpa / referenceHpa).pow(1.0 / 5.255))
    }
}
