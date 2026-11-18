package com.hackdecouverte.rec.train

import com.hackdecouverte.backend.LocalizationController
import com.hackdecouverte.backend.WarmStart
import com.hackdecouverte.loc.LocState
import com.hackdecouverte.loc.LocZone
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.map.OccupancyGrid
import com.hackdecouverte.pdr.CarryMonitor
import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.SensorStatus
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.rec.RecEvent
import com.hackdecouverte.rec.Recording
import com.hackdecouverte.rec.RecordingReader
import com.hackdecouverte.rec.TruthEval
import com.hackdecouverte.rec.WalkAnalysis
import com.hackdecouverte.rec.WalkReplay
import com.hackdecouverte.rec.truth.TruthPoint
import com.hackdecouverte.rec.truth.TruthRoute
import com.hackdecouverte.rec.truth.TruthTrack
import com.hackdecouverte.sensor.SensorRouter
import com.hackdecouverte.tuning.TuningParams
import com.hackdecouverte.tuning.applyTuning
import com.hackdecouverte.tuning.from
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.sin

/** A recorded walk with its traced route, the route already put on the clock ([track]). */
data class Dataset(val name: String, val recording: Recording, val route: TruthRoute, val site: IndoorSite, val track: List<TruthPoint>)

object Datasets {
    /** Builds a dataset; the route is aligned once, with the default tuning (a fixed target for training). */
    fun of(name: String, rec: Recording, route: TruthRoute, site: IndoorSite): Dataset {
        val steps = WalkEval.pdrRun(rec, TuningParams()).steps
        return Dataset(name, rec, route, site, TruthTrack.align(route, steps, rec.header.startedNs))
    }

    /** Every walk of [dir] that has a `<walk>.truth.json`. */
    fun loadAll(dir: File = File("../recordings")): List<Dataset> =
        dir.listFiles { f -> f.name.endsWith(".txt.gz") }.orEmpty().sorted().mapNotNull { f ->
            val route = TruthTrack.load(f) ?: return@mapNotNull null
            val rec = GZIPInputStream(f.inputStream()).bufferedReader().use(RecordingReader::read)
            of(f.name.removeSuffix(".txt.gz"), rec, route, IndoorSite.byId(rec.header.siteId))
        }
}

enum class StartMode { KNOWN_START, COLD_GPS, COLD_NO_GPS }

/** PDR alone: (time, position) after each accepted step, and the distance walked. */
data class PdrRun(val steps: List<Pair<Long, PointM>>, val distanceM: Double)

data class PdrMetrics(val routeDtwM: Double, val wallCrossingsPer100m: Double, val closurePct: Double?, val distanceRatio: Double)

data class FilterRun(
    val mode: StartMode, val seed: Int, val trackErrMedianM: Double?, val trackErrP90M: Double?,
    val foundAfterM: Double?, val falseFoundShare: Double, val endErrM: Double?, val supportLostAtS: Double?
)

/** One evaluation of the PDR and of the filter against a traced route (plan "produit fini", A3). */
object WalkEval {
    private const val CORRECT_M = 5.0
    private const val SUPPORT_RADIUS_M = 3.0
    private const val SUPPORT_LOST_WEIGHT = 0.01
    private const val SUPPORT_LOST_STEPS = 5

    fun pdrRun(rec: Recording, tuning: TuningParams): PdrRun {
        val h = rec.header
        var now = h.startedNs
        val tracker = PdrTracker(clock = { now }, antiShakeEnabled = true, corridorAssistInitial = true, carry = CarryMonitor.from(tuning.carry)).apply {
            useTrueNorthReference = true
            anyHoldHeading = true
            applyTuning(tuning.pdr)
        }
        tracker.onSensorStatus(SensorStatus(rotationAvailable = true, stepDetectorAvailable = true, fallbackCapable = true, permissionNeeded = false, permissionGranted = true))
        tracker.setStepSource(StepSource.HARDWARE)
        val router = SensorRouter(tracker, { tracker.state.value.stepSource })
        router.magneticDeclinationDeg = h.declinationDeg
        val steps = ArrayList<Pair<Long, PointM>>()
        var lastCount = 0
        for (e in rec.events) {
            when (e) {
                is RecEvent.Sample -> if (e.pipeline) {
                    now = maxOf(now, e.tNs + e.lagUs * 1000)
                    router.onSample(e.type, e.tNs, e.values, e.accuracy)
                    if (LocalizationController.shouldAutoStartPdr(tracker.state.value, now - h.startedNs)) tracker.startWithManualStepLength(h.stepLengthM, h.cadenceHz)
                    val s = tracker.state.value
                    if (s.stepCount > lastCount) {
                        lastCount = s.stepCount
                        steps += now to s.position
                    }
                }
                is RecEvent.Fix -> e.bearingDeg?.let { tracker.onTrueNorthBearingFromGps(e.tNs, Math.toRadians(it)) }
                is RecEvent.Note -> if (e.name == "screen") tracker.screenInteractive = e.detail == "on"
                else -> {}
            }
        }
        return PdrRun(steps, tracker.state.value.distanceM)
    }

    fun pdrRun(ds: Dataset, tuning: TuningParams): PdrRun = pdrRun(ds.recording, tuning)

    fun pdr(ds: Dataset, tuning: TuningParams): PdrMetrics {
        val run = pdrRun(ds, tuning)
        val pts = listOf(PointM(0.0, 0.0)) + run.steps.map { it.second }
        val (dtw, rotDeg, k) = TruthEval.score(pts, ds.route.path)
        val routeLen = ds.route.path.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        val r = Math.toRadians(rotDeg.toDouble())
        val o = ds.route.path[0]
        val placed = pts.map { p -> PointM(o.x + k * (p.x * cos(r) + p.y * sin(r)), o.y + k * (-p.x * sin(r) + p.y * cos(r))) }
        val grid = WalkAnalysis.mapProvider(ds.site, tuning.filter.graphDoorWidthM)(ds.route.floorId)?.grid
        var crossings = 0
        if (grid != null) for ((a, b) in placed.zipWithNext()) {
            val n = (a.distanceTo(b) / 0.05).toInt().coerceAtLeast(1)
            var inWall = false
            for (i in 1..n) {
                val w = grid.cellAt(PointM(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)) == OccupancyGrid.WALL
                if (w && !inWall) crossings++
                inWall = w
            }
        }
        val loop = ds.route.path.first().distanceTo(ds.route.path.last()) <= 2.0
        return PdrMetrics(
            routeDtwM = dtw,
            wallCrossingsPer100m = crossings * 100.0 / routeLen.coerceAtLeast(1e-6),
            closurePct = if (loop && run.distanceM > 0) pts.last().norm() / run.distanceM * 100 else null,
            distanceRatio = run.distanceM / routeLen.coerceAtLeast(1e-6)
        )
    }

    private fun arcAt(track: List<TruthPoint>, tNs: Long): Double? =
        track.lastOrNull { it.tNs <= tNs }?.arcM ?: track.firstOrNull()?.takeIf { it.tNs >= tNs }?.arcM

    fun filter(ds: Dataset, tuning: TuningParams, mode: StartMode, seed: Int): FilterRun {
        val start = ds.route.path[0]
        val supports = ArrayList<Pair<Long, Double>>()
        val observer = { tNs: Long, c: LocalizationController ->
            val truth = TruthTrack.positionAt(ds.track, tNs)
            if (truth != null) {
                val ps = c.sampleParticles(2000)
                val total = ps.sumOf { it.weight }.takeIf { it > 0 } ?: 1.0
                supports += tNs to ps.filter { it.positionM.distanceTo(truth) <= SUPPORT_RADIUS_M }.sumOf { it.weight } / total
            }
        }
        val r = WalkReplay(
            ds.recording, ds.site.origin, WalkAnalysis.mapProvider(ds.site, tuning.filter.graphDoorWidthM), seed, tuning,
            useGps = mode == StartMode.COLD_GPS,
            startHint = if (mode == StartMode.KNOWN_START) WarmStart(listOf(LocZone(start, 1.0, 1.0, 0.0)), 0.0) else null,
            stepObserver = observer
        ).run()
        val errs = r.steps.map { st -> st to (TruthTrack.positionAt(ds.track, st.tNs)?.let { q -> st.estimate?.distanceTo(q) }) }
        val firstCorrectFound = errs.firstOrNull { (st, e) -> st.state == LocState.FOUND && e != null && e <= CORRECT_M }?.first
        val measured = errs.filter { (st, e) ->
            e != null && (st.state == LocState.APPROXIMATE || st.state == LocState.FOUND) &&
                (mode == StartMode.KNOWN_START || (firstCorrectFound != null && st.tNs >= firstCorrectFound.tNs))
        }.map { it.second!! }.sorted()
        val found = errs.filter { (st, e) -> st.state == LocState.FOUND && e != null }
        var run = 0
        var lostAt: Long? = null
        for ((t, w) in supports) {
            run = if (w < SUPPORT_LOST_WEIGHT) run + 1 else 0
            if (run == SUPPORT_LOST_STEPS && lostAt == null) lostAt = t
        }
        return FilterRun(
            mode = mode, seed = seed,
            trackErrMedianM = measured.getOrNull(measured.size / 2),
            trackErrP90M = measured.getOrNull(((measured.size - 1) * 0.9).toInt()),
            foundAfterM = firstCorrectFound?.let { arcAt(ds.track, it.tNs) },
            falseFoundShare = if (found.isEmpty()) 0.0 else found.count { it.second!! > CORRECT_M }.toDouble() / found.size,
            endErrM = errs.lastOrNull { it.second != null }?.second,
            supportLostAtS = lostAt?.let { (it - ds.recording.header.startedNs) / 1e9 }
        )
    }

    /** One row per PDR step: how much weight sits on the truth, and the context (door distance, route label). */
    data class SupportRow(val tS: Double, val arcM: Double, val support: Double, val zoneErrM: Double?, val state: LocState?, val nearestDoorM: Double?, val label: String?)

    fun support(ds: Dataset, tuning: TuningParams, mode: StartMode, seed: Int): List<SupportRow> {
        val map = WalkAnalysis.mapProvider(ds.site, tuning.filter.graphDoorWidthM)(ds.route.floorId)
        val rows = ArrayList<SupportRow>()
        val t0 = ds.recording.header.startedNs
        val labelPoints = ds.route.labels.mapNotNull { l -> ds.route.path.getOrNull(l.pointIndex)?.let { it to l.label } }
        WalkReplay(
            ds.recording, ds.site.origin, WalkAnalysis.mapProvider(ds.site, tuning.filter.graphDoorWidthM), seed, tuning,
            useGps = mode == StartMode.COLD_GPS,
            startHint = if (mode == StartMode.KNOWN_START) WarmStart(listOf(LocZone(ds.route.path[0], 1.0, 1.0, 0.0)), 0.0) else null,
            stepObserver = { tNs, c ->
                val truth = TruthTrack.positionAt(ds.track, tNs)
                if (truth != null) {
                    val ps = c.sampleParticles(2000)
                    val total = ps.sumOf { it.weight }.takeIf { it > 0 } ?: 1.0
                    val zone = c.estimate?.zones?.firstOrNull()
                    rows += SupportRow(
                        (tNs - t0) / 1e9, arcAt(ds.track, tNs) ?: 0.0,
                        ps.filter { it.positionM.distanceTo(truth) <= SUPPORT_RADIUS_M }.sumOf { it.weight } / total,
                        zone?.centerM?.distanceTo(truth), c.estimate?.state,
                        map?.doors?.minOfOrNull { it.centerM.distanceTo(truth) },
                        labelPoints.filter { it.first.distanceTo(truth) <= 2.0 }.joinToString(",") { it.second }.ifEmpty { null }
                    )
                }
            }
        ).run()
        return rows
    }

    /** Runs [jobs] on a fixed pool (all processors); results in the order of [jobs]. */
    fun <T> parallel(jobs: List<() -> T>): List<T> {
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            return jobs.map { j -> pool.submit<T> { j() } }.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }
}
