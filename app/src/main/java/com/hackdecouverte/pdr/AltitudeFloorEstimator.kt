package com.hackdecouverte.pdr

import kotlin.math.abs
import kotlin.math.pow

/** How the absolute floor was obtained (shown to the user so the estimate is never presented as more than it is). */
enum class AltitudeFloorSource {
    /** Barometric height above a street-level pressure captured outdoors next to the building. */
    BAROMETER_STREET_REFERENCE,
    /** No usable reference: the user entered from the street, so the ground floor is assumed. */
    ENTRANCE_ASSUMED
}

data class AltitudeFloorEstimate(
    val floorNumber: Int,
    /** Height above the street reference (metres), null when no reference was available. */
    val heightAboveStreetM: Double?,
    val source: AltitudeFloorSource,
    /** True when the height sits close to a floor level (not half-way between two floors). */
    val confident: Boolean
)

/**
 * Absolute floor from altitude (pure Kotlin, JVM unit-tested).
 *
 * A phone barometer reads absolute pressure, which drifts ±20 hPa with the weather (≈ ±170 m),
 * so absolute pressure alone cannot give a floor. Instead, the pressure is sampled at street
 * level while the user is still outside next to the building ([captureStreetReference]), then
 * the height inside is `h = 44330 · (1 − (p / p₀)^(1/5.255))` above that street level and is
 * matched to the nearest floor level. The reference expires after [referenceMaxAgeNs]
 * (weather moves ~1 hPa/h ≈ 8 m/h).
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
    private val confidenceToleranceM: Double = 1.4
) {
    private var streetPressureHpa: Double? = null
    private var streetReferenceNs: Long = Long.MIN_VALUE

    val hasStreetReference: Boolean
        get() = streetPressureHpa != null

    /** Records the pressure measured outdoors at street level (EMA so a single gust does not set it). */
    fun captureStreetReference(timestampNs: Long, pressureHpa: Double) {
        if (!pressureHpa.isFinite() || pressureHpa !in 300.0..1_100.0) return
        val prev = streetPressureHpa
        val fresh = prev != null && timestampNs - streetReferenceNs in 0..REFERENCE_SMOOTH_WINDOW_NS
        streetPressureHpa = if (fresh) prev!! + 0.3 * (pressureHpa - prev) else pressureHpa
        streetReferenceNs = timestampNs
    }

    fun clearReference() {
        streetPressureHpa = null
        streetReferenceNs = Long.MIN_VALUE
    }

    /** Elevation of [floor] above street level. */
    fun floorElevationM(floor: Int): Double = when {
        floor <= groundFloor -> 0.0
        else -> groundFloorHeightM + (floor - groundFloor - 1) * floorHeightM
    }

    fun estimate(timestampNs: Long, pressureHpa: Double?): AltitudeFloorEstimate {
        val ref = streetPressureHpa
        val fresh = ref != null && timestampNs - streetReferenceNs in 0..referenceMaxAgeNs
        if (pressureHpa == null || !pressureHpa.isFinite() || !fresh) {
            return AltitudeFloorEstimate(groundFloor, null, AltitudeFloorSource.ENTRANCE_ASSUMED, confident = false)
        }
        val h = heightAboveM(pressureHpa, ref!!)
        val floor = (groundFloor..topFloor).minByOrNull { abs(floorElevationM(it) - h) } ?: groundFloor
        val confident = abs(floorElevationM(floor) - h) <= confidenceToleranceM
        return AltitudeFloorEstimate(floor, h, AltitudeFloorSource.BAROMETER_STREET_REFERENCE, confident)
    }

    companion object {
        private const val REFERENCE_SMOOTH_WINDOW_NS = 30L * 1_000_000_000L

        /** International barometric formula: height of [pressureHpa] above the level where [referenceHpa] was read. */
        fun heightAboveM(pressureHpa: Double, referenceHpa: Double): Double =
            44_330.0 * (1.0 - (pressureHpa / referenceHpa).pow(1.0 / 5.255))
    }
}
