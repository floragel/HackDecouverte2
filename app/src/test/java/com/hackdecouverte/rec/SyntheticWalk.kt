package com.hackdecouverte.rec

import com.hackdecouverte.map.GeoOrigin
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.rotationVectorFor
import com.hackdecouverte.pdr.wrapAnglePi
import com.hackdecouverte.sensor.SensorRouter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds the recording a phone would make on a walk along [legs] (start point, then a true-North heading
 * and step count per leg): game rotation vector at 50 Hz with an arbitrary zero, compass rotation vector
 * at 16 Hz (magnetic, so the router has to add the declination), hardware steps every 550 ms, a GPS fix
 * before the start and a ground-truth marker at the start and at the end of every leg.
 */
internal object SyntheticWalk {
    const val T0 = 50_000_000_000L
    private const val MS = 1_000_000L

    data class Leg(val headingDeg: Double, val steps: Int)

    fun record(
        origin: GeoOrigin,
        siteId: String,
        floorId: String,
        start: PointM,
        legs: List<Leg>,
        stepM: Double = 0.7,
        gpsCenter: PointM? = null,
        gpsAccuracyM: Double = 12.0,
        declinationDeg: Double = -14.2,
        gameZeroDeg: Double = -68.0
    ): Recording {
        val header = RecordingHeader(
            siteId = siteId, floorId = floorId, startedWallMs = 0L, startedNs = T0, device = "synthetic",
            stepSource = "HARDWARE", stepLengthM = stepM, declinationDeg = declinationDeg
        )
        val ev = ArrayList<RecEvent>()
        gpsCenter?.let {
            val w = origin.toWgs84(it)
            ev += RecEvent.Fix(T0 - 1000 * MS, w.latitude, w.longitude, gpsAccuracyM, "fused", null, null, null, 0.0, declinationDeg, 0)
        }
        var t = T0
        var pos = start
        var heading = Math.toRadians(legs.first().headingDeg)
        var markerId = 1

        fun sensorsUntil(tEnd: Long, headingAt: (Long) -> Double) {
            while (t < tEnd) {
                val h = headingAt(t)
                ev += RecEvent.Sample(SensorRouter.TYPE_GAME_ROTATION_VECTOR, t, 3, true, 1500, rotationVectorFor(wrapAnglePi(h - Math.toRadians(gameZeroDeg))))
                if ((t / MS) % 60 == 0L) {
                    ev += RecEvent.Sample(SensorRouter.TYPE_ROTATION_VECTOR, t, 3, true, 1500, rotationVectorFor(wrapAnglePi(h - Math.toRadians(declinationDeg))))
                }
                t += 20 * MS
            }
        }

        // Stand still 1.5 s so the heading settles, mark the start.
        sensorsUntil(T0 + 1500 * MS) { heading }
        ev += RecEvent.Marker(t, markerId++, pos.x, pos.y, "start")
        for ((i, leg) in legs.withIndex()) {
            val target = Math.toRadians(leg.headingDeg)
            if (i > 0) {
                // Turn on the spot over 1 s.
                val from = heading
                val delta = wrapAnglePi(target - from)
                val t1 = t
                sensorsUntil(t + 1000 * MS) { now -> from + delta * ((now - t1).toDouble() / (1000 * MS)).coerceIn(0.0, 1.0) }
                sensorsUntil(t + 400 * MS) { target }
            }
            heading = target
            repeat(leg.steps) {
                sensorsUntil(t + 550 * MS) { heading }
                ev += RecEvent.Sample(SensorRouter.TYPE_STEP_DETECTOR, t, 3, true, 2000, floatArrayOf(1f))
                pos = PointM(pos.x + stepM * sin(heading), pos.y + stepM * cos(heading))
            }
            sensorsUntil(t + 300 * MS) { heading }
            ev += RecEvent.Marker(t, markerId++, pos.x, pos.y, "leg ${i + 1}")
        }
        return Recording(header, ev)
    }

    /** College corridor (bearing 315°, south-east end (13, −13), north-west end (−14, 14)): almost the whole length, then back. A shorter straight walk stays ambiguous along the corridor, as it should. */
    fun stanislasNorthWestAndBack(origin: GeoOrigin): Recording = record(
        origin, "STAN", "STAN-1",
        start = PointM(11.5, -11.5),
        legs = listOf(Leg(-45.0, 48), Leg(135.0, 14)),
        gpsCenter = PointM(3.0, -1.0)
    )

    /** A synthetic walk with its exact truth (start, legs, step length). */
    data class Walk(val recording: Recording, val start: PointM, val legs: List<Leg>, val stepM: Double) {
        /** Start then the end of every leg. */
        fun routePoints(): List<PointM> {
            var p = start
            val out = arrayListOf(p)
            for (l in legs) {
                val h = Math.toRadians(l.headingDeg)
                p = PointM(p.x + stepM * l.steps * sin(h), p.y + stepM * l.steps * cos(h))
                out += p
            }
            return out
        }

        /** Where the walker is right after each step (time of the step event). */
        fun truePositions(): List<Pair<Long, PointM>> {
            val stepTimes = recording.events.filterIsInstance<RecEvent.Sample>().filter { it.type == SensorRouter.TYPE_STEP_DETECTOR }.map { it.tNs }
            val out = ArrayList<Pair<Long, PointM>>()
            var p = start
            var k = 0
            for (l in legs) {
                val h = Math.toRadians(l.headingDeg)
                repeat(l.steps) {
                    p = PointM(p.x + stepM * sin(h), p.y + stepM * cos(h))
                    out += stepTimes[k++] to p
                }
            }
            return out
        }
    }

    fun walk(origin: GeoOrigin, siteId: String, floorId: String, start: PointM, legs: List<Leg>, stepM: Double = 0.7, gpsCenter: PointM? = null): Walk =
        Walk(record(origin, siteId, floorId, start, legs, stepM, gpsCenter), start, legs, stepM)

    fun stanislasBackAndForthWalk(origin: GeoOrigin): Walk = walk(
        origin, "STAN", "STAN-1", PointM(10.6, -10.6),
        listOf(Leg(-45.0, 44), Leg(135.0, 41), Leg(-45.0, 41), Leg(135.0, 41)), gpsCenter = PointM(3.0, -1.0)
    )

    /** Starts inside the room at the north-west end (as the first real walk), out through its door, then down the corridor. */
    fun stanislasFromRoom(origin: GeoOrigin): Walk = walk(
        origin, "STAN", "STAN-1", PointM(-16.5, 7.9), listOf(Leg(41.0, 8), Leg(134.0, 44), Leg(314.0, 16))
    )

    /** About 2 min up and down the college corridor (four legs), markers at both ends: for the analysis. */
    fun stanislasBackAndForth(origin: GeoOrigin): Recording = record(
        origin, "STAN", "STAN-1",
        start = PointM(10.6, -10.6), // map rescaled ×0.925 on 18/11 (corridor measured 3.02 m wide)
        legs = listOf(Leg(-45.0, 44), Leg(135.0, 41), Leg(-45.0, 41), Leg(135.0, 41)),
        gpsCenter = PointM(3.0, -1.0)
    )

    fun angleDiffDeg(a: Double, b: Double) = abs(Math.toDegrees(wrapAnglePi(a - b)))
}
