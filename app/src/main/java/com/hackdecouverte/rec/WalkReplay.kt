package com.hackdecouverte.rec

import com.hackdecouverte.backend.LocalizationController
import com.hackdecouverte.backend.SystemFix
import com.hackdecouverte.backend.WarmStart
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
import com.hackdecouverte.pdr.CarryMonitor
import com.hackdecouverte.sensor.SensorRouter
import com.hackdecouverte.tuning.TuningParams
import com.hackdecouverte.tuning.applyTuning
import com.hackdecouverte.tuning.from
import com.hackdecouverte.tuning.locParams
import kotlin.random.Random

/** One accepted PDR step during a replay: raw PDR position (local, True-North frame) and the map estimate after it. */
data class ReplayStep(
    val tNs: Long,
    val step: Int,
    val pdr: PointM,
    val state: LocState?,
    val estimate: PointM?,
    val radiusM: Double?,
    /** Filter step index (since the search's last start) right after this PDR step, and that search's number. */
    val filterStep: Int = 0,
    val epoch: Int = 0
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
    val map: IndoorMap?,
    /** The last walk traced back, each point with its time (the analysis' pseudo ground truth). */
    val tracedWalk: List<Pair<Long, PointM>> = emptyList(),
    /** Carry mode over time (t, mode, moving to a new hold) and the times hold changes were committed. */
    val carryTimeline: List<Triple<Long, com.hackdecouverte.pdr.CarryMode, Boolean>> = emptyList(),
    val holdChangeTimes: List<Long> = emptyList(),
    val droppedSteps: Int = 0,
    val recoveredSteps: Int = 0
)

/**
 * Replays a recorded walk through the phone's own code (one floor: barometric floor changes are not
 * replayed yet): [SensorRouter] → [PdrTracker] →
 * [LocalizationController], wired as `IndoorNavigationBackend` does in localization mode
 * (PDR starts by itself, no corridor pull / junction / GPS nudge, GPS feeds the filter).
 * Keep the two in step when the backend's localization wiring changes.
 */
class WalkReplay(
    private val recording: Recording,
    private val origin: GeoOrigin,
    private val mapProvider: (floorId: String) -> IndoorMap?,
    private val seed: Int = 1,
    /** Trained settings (assets/tuning/params.json); [params] defaults to its filter part. */
    private val tuning: TuningParams = TuningParams(),
    private val params: LocParams = tuning.locParams(),
    /** False = replay as if there were no GPS at all (does the map alone find the walker?). */
    private val useGps: Boolean = true,
    /**
     * Replay only this part of the walk, from a cold start at [windowStartNs] (the 3 s before only warm
     * the sensors up: orientation buffer, compass); markers outside are ignored. Null = the whole walk.
     */
    private val windowStartNs: Long? = null,
    private val windowEndNs: Long? = null,
    /** Start the search from these zones (e.g. the known start of a traced walk) instead of cold. */
    private val startHint: WarmStart? = null,
    /** Called after every PDR step with the time and the controller (support measurements). */
    private val stepObserver: ((tNs: Long, controller: LocalizationController) -> Unit)? = null
) {
    fun run(): ReplayResult {
        val h = recording.header
        var now = h.startedNs
        val tracker = PdrTracker(clock = { now }, antiShakeEnabled = true, corridorAssistInitial = true, carry = CarryMonitor.from(tuning.carry)).apply {
            useTrueNorthReference = true
            anyHoldHeading = true // localization mode: any way of holding the phone
            applyTuning(tuning.pdr)
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

        val prior = params.copy(
            strideScaleMin = h.strideScaleMin ?: params.strideScaleMin,
            strideScaleMax = h.strideScaleMax ?: params.strideScaleMax
        )
        val controller = LocalizationController(mapProvider, Random(seed), prior)

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
        val carry = ArrayList<Triple<Long, com.hackdecouverte.pdr.CarryMode, Boolean>>()
        val holdTimes = ArrayList<Long>()
        val startGate = windowStartNs ?: h.startedNs
        val firstEventNs = startGate - WARMUP_NS

        class Pending(val marker: RecEvent.Marker, val epoch: Int, val stepIdx: Int, val state: LocState?, val online: PointM?, val pdr: PointM, val stepCount: Int)
        val pending = ArrayList<Pending>()

        fun afterEvent() {
            if (now < startGate) return
            if (!started) {
                started = true
                val fix = latestFix?.takeIf { useGps && now - it.timeNs <= SYSTEM_FIX_MAX_AGE_NS }
                controller.start(h.floorId, fix, startHint)
            }
            if (LocalizationController.shouldAutoStartPdr(tracker.state.value, now - startGate)) {
                tracker.startWithManualStepLength(h.stepLengthM, h.cadenceHz)
            }
            val s = tracker.state.value
            if (s === lastState) return
            val c = carry.lastOrNull()
            if (c == null || c.second != s.carryMode || c.third != s.inHoldTransition) carry += Triple(now, s.carryMode, s.inHoldTransition)
            if (s.holdChanges > (lastState?.holdChanges ?: 0)) holdTimes += now
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
                steps += ReplayStep(now, s.stepCount, s.position, est?.state, zone?.centerM, zone?.radiusM, controller.stepsSinceStart, epoch)
                stepObserver?.invoke(now, controller)
                if (firstFoundNs == null && est?.state == LocState.FOUND) firstFoundNs = now
            }
        }

        for (e in recording.events) {
            if (e.tNs < firstEventNs && e !is RecEvent.Fix && e !is RecEvent.Note) continue
            if (windowEndNs != null && e.tNs > windowEndNs) break
            if (e is RecEvent.Marker && e.tNs < startGate) continue
            when (e) {
                is RecEvent.Sample -> {
                    if (!recording.feedsPipeline(e)) continue
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
                is RecEvent.Note -> if (e.name == "screen") tracker.screenInteractive = e.detail == "on"
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
        // Time of every filter step of the traced search, to put the traced walk back on the clock.
        val timeOfFilterStep = HashMap<Int, Long>()
        var prevK = 0
        var prevT = startGate
        for (st in steps) {
            if (st.epoch != lastTraceEpoch) continue
            for (k in prevK + 1..st.filterStep) timeOfFilterStep[k] = prevT + (st.tNs - prevT) * (k - prevK) / maxOf(1, st.filterStep - prevK)
            if (st.filterStep > prevK) { prevK = st.filterStep; prevT = st.tNs }
        }
        val tracedWalk = trace.mapIndexedNotNull { i, p ->
            val k = finalSteps - (trace.size - 1 - i)
            timeOfFilterStep[k]?.let { it to p }
        }
        return ReplayResult(
            tracedWalk = tracedWalk,
            carryTimeline = carry,
            holdChangeTimes = holdTimes,
            droppedSteps = tracker.state.value.droppedStepCount,
            recoveredSteps = tracker.state.value.recoveredStepCount,
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
        const val WARMUP_NS = 3_000_000_000L
    }
}
