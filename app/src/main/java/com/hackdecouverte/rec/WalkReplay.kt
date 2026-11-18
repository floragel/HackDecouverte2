package com.hackdecouverte.rec

import com.hackdecouverte.backend.LocalizationController
import com.hackdecouverte.backend.SystemFix
import com.hackdecouverte.loc.LocParams
import com.hackdecouverte.loc.LocParticle
import com.hackdecouverte.loc.LocState
import com.hackdecouverte.map.GeoOrigin
import com.hackdecouverte.map.IndoorMap
import com.hackdecouverte.pdr.PdrState
import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.SensorStatus
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.sensor.SensorRouter
import kotlin.random.Random

/** One accepted PDR step during a replay: raw PDR position (local, True-North frame) and the map estimate after it. */
data class ReplayStep(
    val tNs: Long,
    val step: Int,
    val pdr: PointM,
    val state: LocState?,
    val estimate: PointM?,
    val radiusM: Double?
)

/**
 * How far each estimate was from where the walker said they were ([marker]).
 * online = the filter at that moment; smoothed = the last walk traced back (Zee's backward
 * propagation, only while a zone is found); pdrFromFirst = raw PDR started on the first marker (pure drift); gps = nearest fix
 * within 5 s; live = what the phone itself showed.
 */
data class MarkerResult(
    val marker: RecEvent.Marker,
    val state: LocState?,
    val onlineErrM: Double?,
    val smoothedErrM: Double?,
    val pdrFromFirstErrM: Double?,
    val gpsErrM: Double?,
    val gpsAccuracyM: Double?,
    val liveErrM: Double?
)

data class ReplayResult(
    val steps: List<ReplayStep>,
    val markers: List<MarkerResult>,
    val trace: List<PointM>,
    val particles: List<LocParticle>,
    val firstFoundNs: Long?,
    val strideScale: Double?,
    val distanceM: Double,
    val gpsFixesUsed: Int,
    val gpsPoints: List<Pair<PointM, Double>>,
    val systemCircle: Pair<PointM, Double>?,
    val map: IndoorMap?
)

/**
 * Replays a recorded walk through the phone's own code: [SensorRouter] → [PdrTracker] →
 * [LocalizationController], wired as `IndoorNavigationBackend` does in localization mode
 * (PDR starts by itself, no corridor pull / junction / GPS nudge, GPS feeds the filter).
 * Keep the two in step when the backend's localization wiring changes.
 */
class WalkReplay(
    private val recording: Recording,
    private val origin: GeoOrigin,
    private val mapProvider: (floorId: String) -> IndoorMap?,
    private val seed: Int = 1,
    private val params: LocParams = LocParams(),
    /** False = replay as if there were no GPS at all (does the map alone find the walker?). */
    private val useGps: Boolean = true
) {
    fun run(): ReplayResult {
        val h = recording.header
        var now = h.startedNs
        val tracker = PdrTracker(clock = { now }, antiShakeEnabled = true, corridorAssistInitial = true).apply {
            useTrueNorthReference = true
        }
        val source = runCatching { StepSource.valueOf(h.stepSource) }.getOrDefault(StepSource.HARDWARE)
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = true,
                stepDetectorAvailable = source == StepSource.HARDWARE,
                fallbackCapable = true,
                permissionNeeded = false,
                permissionGranted = true
            )
        )
        tracker.setStepSource(source)
        val router = SensorRouter(tracker, { tracker.state.value.stepSource })
        router.magneticDeclinationDeg = h.declinationDeg
        if (source == StepSource.FALLBACK) router.fallback.start(h.startedNs)

        val controller = LocalizationController(mapProvider, Random(seed), params)

        val steps = ArrayList<ReplayStep>()
        val gpsPoints = ArrayList<Pair<PointM, Double>>()
        var latestFix: SystemFix? = null
        var started = false
        var lastState: PdrState? = null
        var firstFoundNs: Long? = null
        var epoch = 0
        var lastStepsSinceStart = 0
        // Last walk traced back (the filter only traces while it has found a zone; keep the latest one).
        var lastTrace: List<PointM> = emptyList()
        var lastTraceSteps = 0
        var lastTraceEpoch = -1

        class Pending(val marker: RecEvent.Marker, val epoch: Int, val stepIdx: Int, val state: LocState?, val online: PointM?, val pdr: PointM, val stepCount: Int)
        val pending = ArrayList<Pending>()

        fun afterEvent() {
            if (!started) {
                started = true
                val fix = latestFix?.takeIf { useGps && now - it.timeNs <= SYSTEM_FIX_MAX_AGE_NS }
                controller.start(h.floorId, fix)
            }
            if (LocalizationController.shouldAutoStartPdr(tracker.state.value, now - h.startedNs)) {
                tracker.startWithManualStepLength(h.stepLengthM, h.cadenceHz)
            }
            val s = tracker.state.value
            if (s === lastState) return
            val prevSteps = lastState?.stepCount
            lastState = s
            controller.onPdrState(s)
            if (controller.stepsSinceStart < lastStepsSinceStart) epoch++
            lastStepsSinceStart = controller.stepsSinceStart
            val tr = controller.tracedPath()
            if (tr.isNotEmpty()) {
                lastTrace = tr
                lastTraceSteps = controller.stepsSinceStart
                lastTraceEpoch = epoch
            }
            if (s.stepCount != prevSteps && s.stepCount > 0) {
                val est = controller.estimate
                val zone = est?.zones?.firstOrNull()
                steps += ReplayStep(now, s.stepCount, s.position, est?.state, zone?.centerM, zone?.radiusM)
                if (firstFoundNs == null && est?.state == LocState.FOUND) firstFoundNs = now
            }
        }

        for (e in recording.events) {
            when (e) {
                is RecEvent.Sample -> {
                    if (!e.pipeline) continue
                    now = maxOf(now, e.tNs + e.lagUs * 1000)
                    router.onSample(e.type, e.tNs, e.values, e.accuracy)
                    afterEvent()
                }
                is RecEvent.Accuracy -> router.onAccuracyChanged(e.type, e.accuracy)
                is RecEvent.Fix -> {
                    now = maxOf(now, e.tNs)
                    router.magneticDeclinationDeg = e.declinationDeg
                    e.bearingDeg?.let { tracker.onTrueNorthBearingFromGps(e.tNs, Math.toRadians(it)) }
                    val en = origin.toEastNorthM(e.lat, e.lon)
                    gpsPoints += en to e.accuracyM
                    if (useGps && en.norm() <= MAX_CAMPUS_RADIUS_M) {
                        val fix = SystemFix(en, e.accuracyM, e.tNs)
                        latestFix = fix
                        if (started) controller.onSystemPosition(fix)
                    }
                    if (started) afterEvent()
                }
                is RecEvent.Marker -> {
                    val zone = controller.estimate?.zones?.firstOrNull()
                    val s = tracker.state.value
                    pending += Pending(e, epoch, controller.stepsSinceStart, controller.estimate?.state, zone?.centerM, s.position, s.stepCount)
                }
                else -> {}
            }
        }

        val trace = lastTrace
        val finalSteps = lastTraceSteps
        val first = pending.firstOrNull()
        val markers = pending.map { p ->
            val m = PointM(p.marker.eastM, p.marker.northM)
            val smoothed = if (trace.isNotEmpty() && p.epoch == lastTraceEpoch && p.stepIdx <= finalSteps) {
                val idx = trace.size - 1 - (finalSteps - p.stepIdx)
                trace.getOrNull(idx)
            } else null
            val pdrFromFirst = first?.let { f ->
                PointM(f.marker.eastM + p.pdr.x - f.pdr.x, f.marker.northM + p.pdr.y - f.pdr.y)
            }
            val gps = recording.fixes.filter { kotlin.math.abs(it.tNs - p.marker.tNs) <= GPS_MATCH_NS }
                .minByOrNull { kotlin.math.abs(it.tNs - p.marker.tNs) }
            val live = recording.events.filterIsInstance<RecEvent.Live>().lastOrNull { it.tNs <= p.marker.tNs }
            MarkerResult(
                marker = p.marker,
                state = p.state,
                onlineErrM = p.online?.distanceTo(m),
                smoothedErrM = smoothed?.distanceTo(m),
                pdrFromFirstErrM = if (p === first) 0.0 else pdrFromFirst?.distanceTo(m),
                gpsErrM = gps?.let { origin.toEastNorthM(it.lat, it.lon).distanceTo(m) },
                gpsAccuracyM = gps?.accuracyM,
                liveErrM = live?.let { l -> if (l.eastM != null && l.northM != null) PointM(l.eastM, l.northM).distanceTo(m) else null }
            )
        }
        return ReplayResult(
            steps = steps,
            markers = markers,
            trace = trace,
            particles = controller.sampleParticles(2000),
            firstFoundNs = firstFoundNs,
            strideScale = controller.debug?.strideScale,
            distanceM = tracker.state.value.distanceM,
            gpsFixesUsed = controller.debug?.gpsFixesUsed ?: 0,
            gpsPoints = gpsPoints,
            systemCircle = controller.systemCircle,
            map = controller.currentMap()
        )
    }

    companion object {
        /** Same limits as `IndoorNavigationBackend`. */
        const val MAX_CAMPUS_RADIUS_M = 350.0
        const val SYSTEM_FIX_MAX_AGE_NS = 120_000_000_000L
        const val GPS_MATCH_NS = 5_000_000_000L
    }
}
