package com.hackdecouverte.backend

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hackdecouverte.loc.LocParams
import com.hackdecouverte.pdr.CarryMonitor
import com.hackdecouverte.tuning.Tuning
import com.hackdecouverte.tuning.TuningParams
import com.hackdecouverte.tuning.applyTuning
import com.hackdecouverte.tuning.from
import com.hackdecouverte.tuning.locParams
import com.hackdecouverte.loc.LocZone
import com.hackdecouverte.loc.LocState
import com.hackdecouverte.map.BuildingGraph
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.map.IndoorMap
import com.hackdecouverte.loc.LocParticle
import com.hackdecouverte.map.DestinationItem
import com.hackdecouverte.map.FloorNode
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.FloorVectorPlan
import com.hackdecouverte.map.GeoOrigin
import com.hackdecouverte.map.JunctionMatcher
import com.hackdecouverte.map.loadIndoorMap
import com.hackdecouverte.loc.LocEstimate
import com.hackdecouverte.map.MapAnchor
import com.hackdecouverte.map.NavigationRoute
import com.hackdecouverte.map.NodeKey
import com.hackdecouverte.map.OsmSurroundings
import com.hackdecouverte.map.RouteStep
import com.hackdecouverte.pdr.AltitudeFloorEstimate
import com.hackdecouverte.pdr.AltitudeFloorEstimator
import com.hackdecouverte.pdr.AltitudeFloorSource
import com.hackdecouverte.pdr.CorridorConstraintResult
import com.hackdecouverte.pdr.FloorTransitionTracker
import com.hackdecouverte.pdr.PressureAnchor
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PdrState
import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.SensorStatus
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.pdr.wrapAnglePi
import com.hackdecouverte.rec.RecEvent
import com.hackdecouverte.rec.RecordingHeader
import com.hackdecouverte.sensor.RecorderStatus
import com.hackdecouverte.sensor.TrackingService
import com.hackdecouverte.sensor.WalkRecorder
import com.hackdecouverte.sensor.GpsFix
import com.hackdecouverte.sensor.GpsLocationSource
import com.hackdecouverte.sensor.PdrSensorAdapter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface FacingOption {
    data object AutoTrueNorth : FacingOption
    data class Neighbor(val nodeId: String) : FacingOption
    data class Cardinal(val degrees: Int) : FacingOption
}

data class SuggestedFloorTransfer(
    val fromFloorId: String,
    val toFloorId: String,
    val viaLabel: String,
    val verticalGroupId: String?,
    val viaElevator: Boolean,
    val fromActiveRoute: Boolean
)

data class NearbyVerticalOptions(
    val connectorNode: FloorNode,
    val upFloorId: String?,
    val downFloorId: String?
)

data class NavigationSnapshot(
    val pdr: PdrState = PdrState(),
    /** Building being navigated (its floors, its place on Earth). */
    val site: IndoorSite = IndoorSite.MB,
    val geoOrigin: GeoOrigin = GeoOrigin.MB_ELEVATOR_CORE,
    val osmSurroundings: OsmSurroundings? = null,
    val floors: List<FloorPlan> = emptyList(),
    /** Floor currently displayed on the map (`viewedFloorId`). */
    val activeFloorId: String? = null,
    val activeFloor: FloorPlan? = null,
    val activeFloorVector: FloorVectorPlan? = null,
    val loadedFloorVectors: Map<String, FloorVectorPlan> = emptyMap(),
    /** Floor where the user physically is (`userFloorId`). */
    val userFloorId: String? = null,
    val userFloor: FloorPlan? = null,
    val isViewingUserFloor: Boolean = true,
    val startPose: NodeKey? = null,
    val startNodeId: String? = null,
    val usingGpsStart: Boolean = false,
    val hasExplicitStart: Boolean = false,
    val facing: FacingOption = FacingOption.AutoTrueNorth,
    val anchor: MapAnchor? = null,
    val mapPositionM: PointM = PointM(0.0, 0.0),
    val mapHeadingRad: Double = 0.0,
    val barometerAvailable: Boolean = false,
    val floorDeltaAltitudeM: Double = 0.0,
    val inVerticalTransit: Boolean = false,
    val suggestedTransfer: SuggestedFloorTransfer? = null,
    val nearbyVertical: NearbyVerticalOptions? = null,
    val locationPermissionGranted: Boolean = false,
    val latestGpsFix: GpsFix? = null,
    val destination: NodeKey? = null,
    val accessibleOnly: Boolean = false,
    val activeRoute: NavigationRoute? = null,
    val destinations: List<DestinationItem> = emptyList(),
    val savedStepLengthM: Double? = null,
    /** Absolute floor from barometric altitude above the street (see [AltitudeFloorEstimator]). */
    val altitudeFloor: AltitudeFloorEstimate? = null,
    /** True once a street-level pressure was captured outdoors near the building. */
    val hasStreetPressureReference: Boolean = false,
    /** Floor the barometer thinks the user is on when it differs from [userFloorId] (shown as « Floor N? »). */
    val barometerFloorSuggestionId: String? = null,
    /** Probability of [barometerFloorSuggestionId] (0–1). */
    val barometerFloorSuggestionProbability: Double = 0.0,
    /** Last corridor junction that re-anchored the dot (see [JunctionMatcher]), for telemetry. */
    val lastJunctionId: String? = null,
    /** Map localization (developer switch for now): zones, state, a sample of particles. */
    val localizationEnabled: Boolean = false,
    val localization: LocEstimate? = null,
    val localizationParticles: List<LocParticle> = emptyList(),
    /** The precise map the filter runs on (occupancy grid, doors, direction map), for the debug layers. */
    val localizationMap: IndoorMap? = null,
    /** Why the floor's localization map could not be built, if it failed. */
    val localizationError: String? = null,
    /** Walk traced back once the zone is found (backward belief propagation), oldest first. */
    val localizationTrace: List<PointM> = emptyList(),
    /** GPS disk of the search: centre and radius (max(2 × accuracy, 30 m)), null without a usable fix. */
    val localizationSystemCircle: Pair<PointM, Double>? = null,
    val localizationDebug: LocDebug? = null,
    /** Tracking survives the screen turning off (tracking service running). */
    val backgroundTracking: Boolean = false
)

class IndoorNavigationBackend(
    context: Context,
    private val scope: CoroutineScope,
    val site: IndoorSite = IndoorSite.MB
) {
    private val appContext = context.applicationContext

    /** Trained settings shipped with the app (defaults when the file is missing). */
    private val tuning: TuningParams = Tuning.load { runCatching { appContext.assets.open(it) }.getOrNull() }

    val tracker = PdrTracker(
        clock = SystemClock::elapsedRealtimeNanos,
        antiShakeEnabled = true,
        corridorAssistInitial = true,
        carry = CarryMonitor.from(tuning.carry)
    ).apply {
        useTrueNorthReference = true
        applyTuning(tuning.pdr)
    }

    private val floorTracker = FloorTransitionTracker()
    private var altitudeFloors = AltitudeFloorEstimator()
    private var latestPressureHpa: Double? = null
    private var latestPressureNs: Long = Long.MIN_VALUE
    /** Pressure smoothed over a few seconds: anchors and estimates never use a single noisy sample. */
    private var smoothPressureHpa: Double? = null
    /** Floor known but no pressure yet: anchor it on the next sample with this sigma. */
    private var pendingFloorAnchorSigmaM: Double? = null
    /** How sure we are of the tracked floor (sigma of the last anchor event); null until a floor is known. */
    private var floorTrustSigmaM: Double? = null
    private var lastFloorAnchorRefreshNs: Long = Long.MIN_VALUE
    private var baroSuggestionId: String? = null
    private var baroSuggestionProbability: Double = 0.0
    private var setupFloorFromBarometerDone = false
    private val junctions = JunctionMatcher()

    /** Map localization (spec §6.3), behind a developer switch until plan 2. */
    private val localization = LocalizationController({ floorId ->
        buildingGraph.floor(floorId)?.let { plan ->
            loadIndoorMap(plan) { path -> runCatching { appContext.assets.open(path) }.getOrNull() }
        }
    })
    private var localizationEnabled = false
    private var localizationEnabledAtNs = 0L
    private var localizationStartingFor: String? = null

    private val adapter = PdrSensorAdapter(
        context = appContext,
        tracker = tracker,
        sourceProvider = { tracker.state.value.stepSource },
        onPressureSample = ::handlePressureSample,
        onRegistrationResult = { failed ->
            registrationFailed = failed
            pushSensorStatus()
        }
    )

    private val gpsSource = GpsLocationSource(
        context = appContext,
        origin = site.origin,
        onFix = ::handleGpsFix
    )

    private val osmLoader = OsmNetworkLoader(appContext)

    /** Walk recorder for replay on a computer (debug app). */
    private val recorder = WalkRecorder(appContext)
    val recorderStatus: StateFlow<RecorderStatus> get() = recorder.status

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var savedStepLengthM: Double? =
        if (prefs.contains(KEY_LAST_STEP_LENGTH)) {
            prefs.getFloat(KEY_LAST_STEP_LENGTH, 0f).toDouble()
        } else {
            null
        }
    private var savedCadenceHz: Double? =
        if (prefs.contains(KEY_LAST_CADENCE)) {
            prefs.getFloat(KEY_LAST_CADENCE, 0f).toDouble()
        } else {
            null
        }

    /** Step length learned by the map in earlier sessions (Zee, progressive): starts the PDR, narrows the stride prior. */
    private var strideMemory = StrideMemory(
        prefs.getFloat(KEY_LEARNED_STEP, Float.NaN).toDouble().takeIf { it.isFinite() },
        prefs.getInt(KEY_STRIDE_SESSIONS, 0)
    )
    private var foundSteps = 0
    private var learnedInRun = false
    private var lastLearnFoundSteps = 0

    private var geoOrigin: GeoOrigin = site.origin
    private var osmSurroundings: OsmSurroundings? = null
    private val floorVectors = LinkedHashMap<String, FloorVectorPlan>()

    private var buildingGraph = BuildingGraph(loadBundledFloors(appContext, site.floorsDir))
    private var userFloorId: String? =
        buildingGraph.floor(site.defaultFloorId)?.floorId ?: buildingGraph.floors.firstOrNull()?.floorId
    private var viewedFloorId: String? = userFloorId

    private var startPose: NodeKey? = null
    private var usingGpsStart: Boolean = false
    private var currentFloorOriginM: PointM = PointM(0.0, 0.0)
    private var facing: FacingOption = FacingOption.AutoTrueNorth
    private var anchor: MapAnchor? = MapAnchor(PointM(0.0, 0.0), 0.0)
    private var destination: NodeKey? = null
    private var accessibleOnly: Boolean = false
    private var latestGpsFix: GpsFix? = null

    private var floorDeltaAltitudeM: Double = 0.0
    private var inVerticalTransit: Boolean = false
    private var lastPublishedAltM: Double = 0.0

    private val permissionNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private var permissionGranted = checkActivityPermission()
    private var locationPermissionGranted = gpsSource.hasLocationPermission()
    private var registrationFailed = false
    private var settlingJob: Job? = null

    private var cachedRouteKey: RouteCacheKey? = null
    private var cachedRoute: NavigationRoute? = null

    init {
        altitudeFloors = buildAltitudeEstimator()
        restoreFloorAnchor()
    }

    private val _state = MutableStateFlow(buildSnapshot(tracker.state.value))
    val state: StateFlow<NavigationSnapshot> = _state

    private data class RouteCacheKey(
        val floorId: String?,
        val stepCount: Int,
        val posBucketX: Int,
        val posBucketY: Int,
        val headingBucket: Int,
        val dest: NodeKey?,
        val accessibleOnly: Boolean
    )

    /** Screen on/off: a phone whose screen is off is not being read (no reading pose, see CarryMonitor). */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            tracker.screenInteractive = on
            recorder.note("screen", if (on) "on" else "off")
        }
    }

    init {
        adapter.onRawSample = recorder::onPipelineSample
        adapter.onRawAccuracy = recorder::onPipelineAccuracy
        tracker.screenInteractive = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        runCatching {
            ContextCompat.registerReceiver(
                appContext, screenReceiver,
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        pushSensorStatus()
        ensureUsableStepSource()
        recomputeAnchor()

        tracker.corridorConstraint = { localPos, localTheta ->
            val plan = userFloorId?.let(buildingGraph::floor)
            val anc = anchor
            if (plan != null && anc != null && !inVerticalTransit && !localizationEnabled) {
                plan.matchCorridor(anc.toMap(localPos), anc.mapHeading(localTheta))?.let {
                    CorridorConstraintResult(
                        projectedLocalPos = anc.fromMap(it.projectedPointM),
                        alignedLocalTheta = it.alignedHeadingRad?.let(anc::localHeading)
                    )
                }
            } else {
                null
            }
        }

        if (site.osmSurroundings) scope.launch {
            osmLoader.loadInitial(geoOrigin)?.let { loadedOsm ->
                osmSurroundings = loadedOsm
                _state.value = buildSnapshot(tracker.state.value)
            }
        }
        loadVectorPlansInBackground()
        scope.launch { idleWatchdog() }

        scope.launch {
            var lastLoggedSteps = -1
            tracker.state.collect { pdrState ->
                if (pdrState.stepCount != lastLoggedSteps) {
                    lastLoggedSteps = pdrState.stepCount
                    lastStepElapsedNs = SystemClock.elapsedRealtimeNanos()
                    Log.d(
                        LOG_TAG,
                        "step=${pdrState.stepCount} pos=(%.2f, %.2f) dist=%.1f held=${tracker.heldStepCount} transit=$inVerticalTransit dz=%.2f floor=$userFloorId"
                            .format(pdrState.position.x, pdrState.position.y, pdrState.distanceM, floorDeltaAltitudeM)
                    )
                }
                persistGaitIfUpdated(pdrState)
                if (localizationEnabled) {
                    // The PDR starts by itself (spec §6.1), once True North is known.
                    if (LocalizationController.shouldAutoStartPdr(pdrState, SystemClock.elapsedRealtimeNanos() - localizationEnabledAtNs)) {
                        startWithSavedOrDefaultStepLength()
                    }
                    // Back from a pause (no tracking service): carry on by itself, the filter keeps its hypotheses.
                    if (pdrState.phase == PdrPhase.PAUSED && pdrState.headingValid && !pdrState.tilted &&
                        (pdrState.trueNorthAligned || SystemClock.elapsedRealtimeNanos() - foregroundAtNs >= LocalizationController.COMPASS_WAIT_NS)
                    ) {
                        resumeFromPause()
                    }
                    localization.onPdrState(pdrState)
                    if (pdrState.stepCount != lastLocalizedStep) {
                        lastLocalizedStep = pdrState.stepCount
                        onLocalizedStep(pdrState)
                    }
                    if (recorderStatus.value.recording) {
                        // What the app believes about the carry, to compare with the walker's labels.
                        val carry = "${pdrState.carryMode} transition=${pdrState.inHoldTransition} changes=${pdrState.holdChanges} reading=${pdrState.readingPose} aligned=${pdrState.headingAligned}"
                        if (carry != lastCarryNote) {
                            lastCarryNote = carry
                            recorder.note("carry", carry)
                        }
                    }
                    if (recorderStatus.value.recording && pdrState.stepCount != lastLoggedLiveStep) {
                        lastLoggedLiveStep = pdrState.stepCount
                        recordLive(pdrState)
                    }
                } else {
                    matchJunction(pdrState)
                }
                _state.value = buildSnapshot(pdrState)
            }
        }
    }

    /** Builds the maps of the floors above and below in the background, so a floor change can carry the hypotheses. */
    private fun prepareAdjacentFloors(floorId: String) {
        val next = listOfNotNull(buildingGraph.adjacentFloorId(floorId, 1), buildingGraph.adjacentFloorId(floorId, -1))
            .filter { !localization.isPrepared(it) }
        if (next.isEmpty()) return
        scope.launch(Dispatchers.Default) { next.forEach { localization.prepare(it) } }
    }

    /** Background tracking nobody uses (phone left on a desk, forgotten in a bag) stops by itself. */
    private suspend fun idleWatchdog() {
        while (true) {
            delay(30_000L)
            if (inBackground && backgroundTracking &&
                SystemClock.elapsedRealtimeNanos() - lastStepElapsedNs > IDLE_STOP_NS
            ) {
                Log.d(LOG_TAG, "no step for 10 min in the background: tracking stops")
                recorder.note("idle_stop", "")
                stopBackgroundTracking()
            }
        }
    }

    private fun loadVectorPlansInBackground() {
        val plansSnapshot = buildingGraph.floors
        val priorityId = viewedFloorId ?: userFloorId
        val ordered = plansSnapshot.sortedBy { if (it.floorId == priorityId) 0 else 1 }
        scope.launch(Dispatchers.Default) {
            for (plan in ordered) {
                val asset = plan.svgAsset ?: continue
                if (synchronized(floorVectors) { floorVectors.containsKey(plan.floorId) }) continue
                val parsed = runCatching {
                    appContext.assets.open(asset).use { FloorVectorPlan.parseSvg(plan.floorId, it) }
                }.getOrNull() ?: continue
                synchronized(floorVectors) {
                    floorVectors[plan.floorId] = parsed
                }
                withContext(Dispatchers.Main) {
                    _state.value = buildSnapshot(tracker.state.value)
                }
            }
        }
    }

    fun refreshOsmSurroundingsFromNetwork(radiusM: Double = 280.0) {
        if (!site.osmSurroundings) return
        val targetOrigin = geoOrigin
        scope.launch {
            osmLoader.fetchLiveSurroundings(targetOrigin, radiusM)?.let { fresh ->
                osmSurroundings = fresh
                _state.value = buildSnapshot(tracker.state.value)
            }
        }
    }

    fun loadFloorPlans(plans: List<FloorPlan>) {
        buildingGraph = BuildingGraph(plans)
        altitudeFloors = buildAltitudeEstimator()
        restoreFloorAnchor()
        if (userFloorId == null || buildingGraph.floor(userFloorId!!) == null) {
            userFloorId = plans.firstOrNull()?.floorId
        }
        if (viewedFloorId == null || buildingGraph.floor(viewedFloorId!!) == null) {
            viewedFloorId = userFloorId
        }
        loadVectorPlansInBackground()
        recomputeAnchor()
        invalidateRouteAndPublish()
    }

    /**
     * Anchors the user's starting or current position to a specific room, entrance, stair, or
     * elevator node (`[floorId, nodeId]`). Manual room selection is always authoritative and
     * never overridden by coarse indoor GPS.
     */
    fun setStartPose(
        floorId: String,
        nodeId: String,
        facingOption: FacingOption = FacingOption.AutoTrueNorth,
        floorCertain: Boolean = true
    ) {
        val plan = buildingGraph.floor(floorId) ?: return
        val node = plan.node(nodeId) ?: return
        startPose = NodeKey(floorId, nodeId)
        usingGpsStart = false
        userFloorId = floorId
        viewedFloorId = floorId
        currentFloorOriginM = node.posM
        facing = when (facingOption) {
            FacingOption.AutoTrueNorth -> FacingOption.AutoTrueNorth
            is FacingOption.Neighbor ->
                if (plan.neighbors(nodeId).any { it.id == facingOption.nodeId }) {
                    facingOption
                } else {
                    FacingOption.AutoTrueNorth
                }
            is FacingOption.Cardinal -> facingOption
        }
        floorTracker.onManualFloorSet()
        if (floorCertain) markFloorKnown(AltitudeFloorEstimator.KNOWN_FLOOR_SIGMA_M)
        clearBaroSuggestion()
        inVerticalTransit = false
        floorDeltaAltitudeM = 0.0
        tracker.endVerticalHold(commit = false)
        recomputeAnchor()
        val phase = tracker.state.value.phase
        if (phase == PdrPhase.TRACKING || phase == PdrPhase.PAUSED) {
            if (!tracker.resetTrace()) {
                tracker.reanchorFloorOriginKeepHeading()
            }
        }
        invalidateRouteAndPublish()
    }

    /**
     * Explicit outdoor GPS origin action (only when explicitly requested by the user).
     */
    fun useGpsAsStartOrigin(): Boolean {
        if (!site.gpsPositionTrusted) return false
        val fix = latestGpsFix ?: return false
        if (fix.eastNorthM.norm() > MAX_CAMPUS_RADIUS_M) return false
        startPose = null
        usingGpsStart = true
        currentFloorOriginM = fix.eastNorthM
        viewedFloorId = userFloorId
        facing = FacingOption.AutoTrueNorth
        floorTracker.onManualFloorSet()
        inVerticalTransit = false
        floorDeltaAltitudeM = 0.0
        tracker.endVerticalHold(commit = false)
        recomputeAnchor()
        val phase = tracker.state.value.phase
        if (phase == PdrPhase.TRACKING || phase == PdrPhase.PAUSED) {
            if (!tracker.resetTrace()) {
                tracker.reanchorFloorOriginKeepHeading()
            }
        }
        invalidateRouteAndPublish()
        return true
    }

    fun setFacing(facingOption: FacingOption) {
        facing = facingOption
        recomputeAnchor()
        invalidateRouteAndPublish()
    }

    fun clearStartPose() {
        startPose = null
        usingGpsStart = false
        currentFloorOriginM = PointM(0.0, 0.0)
        facing = FacingOption.AutoTrueNorth
        recomputeAnchor()
        invalidateRouteAndPublish()
    }

    /**
     * Switches the floor plan displayed on the map (`viewedFloorId`).
     * In setup (`IDLE` before choosing a room), this also sets `userFloorId` so selecting a floor
     * sets the starting floor naturally. During active tracking, use [confirmUserFloor] (or climb
     * stairs/elevator with barometer) to relocate the user's physical floor.
     */
    fun switchActiveFloor(
        floorId: String,
        entryNodeId: String? = null,
        facingOption: FacingOption = FacingOption.AutoTrueNorth
    ) {
        if (buildingGraph.floor(floorId) == null) return
        if (entryNodeId != null) {
            setStartPose(floorId, entryNodeId, facingOption)
            return
        }
        viewedFloorId = floorId
        val phase = tracker.state.value.phase
        if (phase != PdrPhase.TRACKING && phase != PdrPhase.PAUSED && startPose == null) {
            userFloorId = floorId
            recomputeAnchor()
        }
        loadVectorPlansInBackground()
        invalidateRouteAndPublish()
    }

    fun returnViewToUserFloor() {
        val uf = userFloorId ?: return
        viewedFloorId = uf
        _state.value = buildSnapshot(tracker.state.value)
    }

    /**
     * Relocates the user's physical floor to [targetFloorId] (e.g. when tapping "Monter → MB-3",
     * "Je suis au MB-4", or via an explicit stairwell/elevator `verticalGroupId`), snapping onto
     * the corresponding stairwell/elevator landing on [targetFloorId] and continuing the route.
     */
    fun confirmUserFloor(
        targetFloorId: String,
        explicitVerticalGroupId: String? = null
    ) {
        if (buildingGraph.floor(targetFloorId) == null) return
        val fromFloor = userFloorId ?: targetFloorId
        val currentMapPos = anchor?.toMap(tracker.state.value.position) ?: currentFloorOriginM
        val landing = buildingGraph.resolveFloorTransition(
            fromFloorId = fromFloor,
            targetFloorId = targetFloorId,
            currentPosM = currentMapPos,
            verticalSpeedMps = 0.0,
            activeRoute = cachedRoute,
            explicitVerticalGroupId = explicitVerticalGroupId
        )
        userFloorId = targetFloorId
        viewedFloorId = targetFloorId
        currentFloorOriginM = landing?.arrivalPosM ?: currentMapPos
        floorTracker.onManualFloorSet()
        markFloorKnown(AltitudeFloorEstimator.KNOWN_FLOOR_SIGMA_M)
        clearBaroSuggestion()
        inVerticalTransit = false
        floorDeltaAltitudeM = 0.0
        tracker.endVerticalHold(commit = false)
        recomputeAnchor()
        tracker.reanchorFloorOriginKeepHeading()
        loadVectorPlansInBackground()
        invalidateRouteAndPublish()
    }

    /**
     * Outdoor → indoor hand-off: the user just entered the building (or opened navigation inside).
     * Puts them on [floorId] at the node closest to [nearM] (entrances first on the ground floor,
     * so the trace starts at the door they came through) and starts step tracking right away.
     * Returns false if the floor is unknown.
     */
    fun enterBuilding(floorId: String, nearM: PointM?): Boolean {
        val plan = buildingGraph.floor(floorId) ?: return false
        val around = nearM ?: PointM(0.0, 0.0)
        val doors = plan.nodes.filter { it.type == "exit" || it.type == "entrance" }
        val candidates = doors.takeIf { it.isNotEmpty() && around.norm() > ENTRANCE_SNAP_MIN_DIST_M }
            ?: plan.nodes.filter { it.type != "corridor" || it.label.isNotBlank() || it.name.isNotBlank() }.ifEmpty { plan.nodes }
        val start = candidates.minByOrNull { it.posM.distanceTo(around) } ?: return false
        // Entering from the street is not a certain floor (the street reference covers it)
        setStartPose(floorId, start.id, floorCertain = false)
        if (tracker.state.value.phase == PdrPhase.IDLE) startWithSavedOrDefaultStepLength()
        return true
    }

    /** Current altitude-based floor estimate (also published in the snapshot). */
    fun estimateAltitudeFloor(): AltitudeFloorEstimate =
        altitudeFloors.estimate(latestPressureNs, smoothPressureHpa ?: latestPressureHpa)

    fun setDestination(target: NodeKey?) {
        destination = target
        invalidateRouteAndPublish()
    }

    fun setAccessibleOnly(enabled: Boolean) {
        accessibleOnly = enabled
        invalidateRouteAndPublish()
    }

    fun startWithStepLength(lengthM: Double): Boolean {
        val saved = savedStepLengthM
        val cadence = if (saved != null && abs(saved - lengthM) < 0.0005) savedCadenceHz else null
        viewedFloorId = userFloorId
        floorTracker.onManualFloorSet()
        return tracker.startWithManualStepLength(lengthM, cadence)
    }

    fun startWithSavedOrDefaultStepLength(defaultM: Double = DEFAULT_STEP_LENGTH_M): Boolean {
        val len = strideMemory.learnedStepM ?: savedStepLengthM ?: defaultM
        return startWithStepLength(len)
    }

    fun startCalibration5m(): Boolean {
        viewedFloorId = userFloorId
        floorTracker.onManualFloorSet()
        return tracker.startCalibration()
    }

    fun finishCalibration5m() {
        if (!tracker.finishCalibration()) return
        if (tracker.state.value.stepSource == StepSource.FALLBACK) {
            tracker.finalizeCalibration(SystemClock.elapsedRealtimeNanos())
        } else {
            settlingJob = scope.launch {
                delay(SETTLING_DELAY_MS)
                if (tracker.state.value.phase == PdrPhase.SETTLING) {
                    tracker.finalizeCalibration(SystemClock.elapsedRealtimeNanos())
                }
            }
        }
    }

    fun resetPositionToStart() {
        val sp = startPose
        if (sp != null) {
            buildingGraph.node(sp)?.let { node ->
                userFloorId = sp.floorId
                viewedFloorId = sp.floorId
                currentFloorOriginM = node.posM
            }
        } else {
            viewedFloorId = userFloorId
        }
        floorTracker.onManualFloorSet()
        inVerticalTransit = false
        floorDeltaAltitudeM = 0.0
        tracker.endVerticalHold(commit = false)
        recomputeAnchor()
        if (!tracker.resetTrace()) {
            tracker.reanchorFloorOriginKeepHeading()
        }
        invalidateRouteAndPublish()
    }

    fun resumeFromPause() {
        viewedFloorId = userFloorId
        floorTracker.onManualFloorSet()
        tracker.resumeWithNewTrace()
        invalidateRouteAndPublish()
    }

    fun stopToSetup() {
        settlingJob?.cancel()
        settlingJob = null
        inVerticalTransit = false
        floorDeltaAltitudeM = 0.0
        tracker.endVerticalHold(commit = false)
        viewedFloorId = userFloorId
        tracker.recalibrate()
        ensureUsableStepSource()
        refreshSensorRegistrations()
        invalidateRouteAndPublish()
    }

    fun toggleCorridorAssist() {
        tracker.setCorridorAssistEnabled(!tracker.state.value.corridorAssistEnabled)
    }

    fun selectStepSource(source: StepSource) {
        if (tracker.setStepSource(source)) refreshSensorRegistrations()
    }

    /** The tracking service keeps this engine running with the screen off (localization mode, location allowed). */
    private var backgroundTracking = false
    /** The screen is off or the app is hidden while [backgroundTracking] keeps the sensors running. */
    private var inBackground = false
    private var lastStepElapsedNs = SystemClock.elapsedRealtimeNanos()
    private var foregroundAtNs = SystemClock.elapsedRealtimeNanos()
    private var lastLocalizedStep = -1

    fun onForeground() {
        if (inBackground) {
            // The sensors never stopped (tracking service): the same trace simply goes on.
            inBackground = false
            gpsSource.lowPower = false
            locationPermissionGranted = gpsSource.hasLocationPermission()
            recorder.note("foreground", "")
            _state.value = buildSnapshot(tracker.state.value)
            return
        }
        permissionGranted = checkActivityPermission()
        locationPermissionGranted = gpsSource.hasLocationPermission()
        foregroundAtNs = SystemClock.elapsedRealtimeNanos()
        pushSensorStatus()
        ensureUsableStepSource()
        tracker.onSensorFrameRestart()
        adapter.start()
        gpsSource.start()
        updateTrackingService()
        _state.value = buildSnapshot(tracker.state.value)
    }

    fun onBackground() {
        if (backgroundTracking && localizationEnabled) {
            // Screen off / phone in a pocket: keep tracking (and recording), GPS on low power.
            inBackground = true
            gpsSource.lowPower = true
            recorder.note("background", "tracking continues")
            return
        }
        pauseEverything()
    }

    /** The old foreground-only behaviour: stop the sensors, pause the trace, close any recording. */
    private fun pauseEverything() {
        inBackground = false
        saveLastZones()
        if (recorder.status.value.recording) {
            recorder.note("background", "app left the foreground")
            recorder.stop()
        }
        settlingJob?.cancel()
        settlingJob = null
        inVerticalTransit = false
        tracker.endVerticalHold(commit = false)
        gpsSource.stop()
        adapter.stop()
        tracker.pause()
    }

    /** Starts or stops the tracking service to match: localization on and the location permission granted. */
    private fun updateTrackingService() {
        val want = localizationEnabled && locationPermissionGranted
        if (want && !backgroundTracking) {
            TrackingService.onStopRequested = { scope.launch { stopBackgroundTracking() } }
            TrackingService.start(appContext, "${site.name} · localisation active")
            backgroundTracking = true
        } else if (!want && backgroundTracking) {
            TrackingService.stop(appContext)
            backgroundTracking = false
        }
    }

    /** "Arrêter" in the notification, or nobody walked for [IDLE_STOP_NS] in the background. */
    fun stopBackgroundTracking() {
        if (!backgroundTracking) return
        TrackingService.stop(appContext)
        backgroundTracking = false
        if (inBackground) pauseEverything()
        _state.value = buildSnapshot(tracker.state.value)
    }

    /** The engine is discarded (other building, app closed): stop everything for good. */
    fun shutdown() {
        pauseEverything()
        if (backgroundTracking) {
            TrackingService.stop(appContext)
            backgroundTracking = false
        }
        runCatching { appContext.unregisterReceiver(screenReceiver) }
    }

    fun onActivityPermissionResult(granted: Boolean) {
        val prev = tracker.state.value.stepSource
        permissionGranted = granted
        pushSensorStatus()
        ensureUsableStepSource()
        if (tracker.state.value.stepSource != prev) {
            refreshSensorRegistrations()
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        locationPermissionGranted = gpsSource.hasLocationPermission() || granted
        if (locationPermissionGranted) {
            gpsSource.start()
        }
        updateTrackingService()
        _state.value = buildSnapshot(tracker.state.value)
    }

    private fun handlePressureSample(timestampNs: Long, pressureHpa: Double) {
        if (!pressureHpa.isFinite() || pressureHpa !in 300.0..1_100.0) return
        val previousNs = latestPressureNs
        val resumedAfterGap = previousNs != Long.MIN_VALUE && timestampNs - previousNs > PRESSURE_GAP_NS
        val prevSmooth = smoothPressureHpa
        smoothPressureHpa = if (prevSmooth == null || resumedAfterGap || previousNs == Long.MIN_VALUE || timestampNs <= previousNs) {
            pressureHpa
        } else {
            val dt = (timestampNs - previousNs) / 1e9
            prevSmooth + (1.0 - exp(-dt / PRESSURE_SMOOTH_TAU_S)) * (pressureHpa - prevSmooth)
        }
        latestPressureHpa = pressureHpa
        latestPressureNs = timestampNs
        val uFloor = userFloorId ?: return
        updateFloorSpacing(uFloor)

        pendingFloorAnchorSigmaM?.let { sigma ->
            pendingFloorAnchorSigmaM = null
            anchorCurrentFloor(timestampNs, sigma)
        }
        if (resumedAfterGap) {
            // Sensors were off (app in the background): the relative tracker lost its baseline.
            // The pressure difference to the last known floor says whether the user changed floor meanwhile.
            floorTracker.reset()
            inVerticalTransit = false
            floorDeltaAltitudeM = 0.0
            if (reconcileFloorWithBarometer(timestampNs, RESUME_SWITCH_PROBABILITY, anchorWhenUnsure = false)) return
        }
        maybeSetSetupFloorFromBarometer(timestampNs)

        val currentMapPos = anchor?.toMap(tracker.state.value.position) ?: currentFloorOriginM
        // Map localization: "near stairs" is how much of the filter's weight is next to one.
        val nearConnector = if (localizationEnabled && localization.floorId == uFloor) {
            localization.massNear(buildingGraph.verticalConnectorPositions(uFloor), NEAR_CONNECTOR_BARO_RADIUS_M) >= NEAR_CONNECTOR_MIN_MASS
        } else {
            buildingGraph.findNearestVerticalConnector(
                floorId = uFloor,
                posM = currentMapPos,
                maxRadiusM = NEAR_CONNECTOR_BARO_RADIUS_M,
                accessibleOnly = false
            ) != null
        }

        val update = floorTracker.onPressure(timestampNs, pressureHpa, nearConnector) ?: return
        val wasInTransit = inVerticalTransit
        inVerticalTransit = update.inVerticalTransit
        floorDeltaAltitudeM = update.deltaFromFloorM

        if (update.inVerticalTransit && !wasInTransit) {
            tracker.beginVerticalHold()
        } else if (!update.inVerticalTransit && wasInTransit && update.floorStepDelta == 0) {
            // False alarm (pressurised stairwell door, HVAC, lobby): replay the buffered steps
            tracker.endVerticalHold(commit = true)
        }

        if (update.floorStepDelta != 0) {
            val targetFloor = buildingGraph.adjacentFloorId(uFloor, update.floorStepDelta)
            if (targetFloor == null) {
                floorTracker.revertRejectedFloorStep(update.floorStepDelta)
            } else if (localizationEnabled) {
                // The hypotheses next to stairs / lifts move to their landings; the stair steps are dropped.
                recorder.note("floor", "$uFloor -> $targetFloor")
                tracker.endVerticalHold(commit = false)
                userFloorId = targetFloor
                viewedFloorId = targetFloor
                val moved = localization.changeFloor(targetFloor, buildingGraph.verticalTransfers(uFloor, targetFloor))
                if (!moved) localizationStartingFor = null // target map not built yet: a fresh search starts there once it is
                recomputeAnchor()
                prepareAdjacentFloors(targetFloor)
                loadVectorPlansInBackground()
                lastPublishedAltM = floorDeltaAltitudeM
                invalidateRouteAndPublish()
                return
            } else {
                val landing = buildingGraph.resolveFloorTransition(
                    fromFloorId = uFloor,
                    targetFloorId = targetFloor,
                    currentPosM = currentMapPos,
                    verticalSpeedMps = update.verticalSpeedMps,
                    activeRoute = cachedRoute
                )
                userFloorId = targetFloor
                viewedFloorId = targetFloor
                currentFloorOriginM = landing?.arrivalPosM ?: currentMapPos
                recomputeAnchor()
                tracker.reanchorFloorOriginKeepHeading()
                loadVectorPlansInBackground()
                lastPublishedAltM = floorDeltaAltitudeM
                invalidateRouteAndPublish()
                return
            }
        }

        if (wasInTransit && !inVerticalTransit) {
            // A climb (or a false alarm) just settled: check the tracked floor against the absolute estimate
            if (reconcileFloorWithBarometer(timestampNs, TRANSIT_SWITCH_PROBABILITY, anchorWhenUnsure = true)) return
        } else if (!inVerticalTransit && abs(floorDeltaAltitudeM) < KEEPALIVE_MAX_DELTA_M) {
            // Still on the tracked floor: refresh its anchor so weather drift does not age it
            val trust = floorTrustSigmaM
            if (trust != null && (lastFloorAnchorRefreshNs == Long.MIN_VALUE || timestampNs - lastFloorAnchorRefreshNs >= FLOOR_ANCHOR_REFRESH_NS)) {
                anchorCurrentFloor(timestampNs, trust)
            }
        }
        val suggestionChanged = updateBaroSuggestion(timestampNs)

        if (suggestionChanged || wasInTransit != inVerticalTransit || abs(floorDeltaAltitudeM - lastPublishedAltM) >= 0.25) {
            lastPublishedAltM = floorDeltaAltitudeM
            _state.value = buildSnapshot(tracker.state.value)
        }
    }

    // ---------- Barometric floor (absolute estimate on top of the relative tracker) ----------

    private fun buildAltitudeEstimator(): AltitudeFloorEstimator {
        val numbers = buildingGraph.floors.map { buildingGraph.floorNumber(it.floorId) }.filter { it != Int.MAX_VALUE }
        val ground = numbers.minOrNull() ?: 1
        val top = numbers.maxOrNull() ?: ground
        return AltitudeFloorEstimator(groundFloor = ground, topFloor = top, groundFloorHeightM = site.groundFloorHeightM)
    }

    private fun floorIdForNumber(n: Int): String? =
        buildingGraph.floors.firstOrNull { buildingGraph.floorNumber(it.floorId) == n }?.floorId

    private fun updateFloorSpacing(floorId: String) {
        val n = buildingGraph.floorNumber(floorId)
        val up = buildingGraph.adjacentFloorId(floorId, +1)?.let { altitudeFloors.floorSpacingM(n, buildingGraph.floorNumber(it)) }
        val down = buildingGraph.adjacentFloorId(floorId, -1)?.let { altitudeFloors.floorSpacingM(buildingGraph.floorNumber(it), n) }
        floorTracker.setFloorSpacing(up, down)
    }

    /** The user's floor is known now: anchor the barometer there (now, or on the next sample). */
    private fun markFloorKnown(sigmaM: Double) {
        floorTrustSigmaM = sigmaM
        val now = SystemClock.elapsedRealtimeNanos()
        if (smoothPressureHpa != null && latestPressureNs != Long.MIN_VALUE && now - latestPressureNs <= PRESSURE_FRESH_NS) {
            anchorCurrentFloor(latestPressureNs, sigmaM)
        } else {
            pendingFloorAnchorSigmaM = sigmaM
        }
    }

    private fun anchorCurrentFloor(timestampNs: Long, sigmaM: Double) {
        val floorId = userFloorId ?: return
        val p = smoothPressureHpa ?: return
        altitudeFloors.anchorAtFloor(timestampNs, p, buildingGraph.floorNumber(floorId), sigmaM)
        floorTrustSigmaM = sigmaM
        lastFloorAnchorRefreshNs = timestampNs
        saveFloorAnchor()
    }

    /**
     * Compares the tracked floor with the barometer's absolute estimate. Moves the user when the
     * barometer is at least [switchProbability] sure of another floor (missed or double-counted
     * climb, floor changed while the app was closed). Returns true if the floor changed.
     */
    private fun reconcileFloorWithBarometer(timestampNs: Long, switchProbability: Double, anchorWhenUnsure: Boolean): Boolean {
        val uFloor = userFloorId ?: return false
        val est = altitudeFloors.estimate(timestampNs, smoothPressureHpa)
        if (est.source == AltitudeFloorSource.ENTRANCE_ASSUMED) {
            if (anchorWhenUnsure) anchorCurrentFloor(timestampNs, AltitudeFloorEstimator.UNCONFIRMED_FLOOR_SIGMA_M)
            return false
        }
        val current = buildingGraph.floorNumber(uFloor)
        if (est.floorNumber != current && est.probability >= switchProbability) {
            val target = floorIdForNumber(est.floorNumber) ?: return false
            Log.d(LOG_TAG, "barometer: floor $uFloor -> $target (p=%.2f, h=%.1f m ± %.1f)".format(est.probability, est.heightAboveStreetM, est.sigmaM))
            if (localizationEnabled) {
                // Same path as a tracked climb: the hypotheses next to stairs / lifts move to their landings
                recorder.note("floor", "barometer $uFloor -> $target")
                tracker.endVerticalHold(commit = false)
                userFloorId = target
                viewedFloorId = target
                if (!localization.changeFloor(target, buildingGraph.verticalTransfers(uFloor, target))) localizationStartingFor = null
                floorTracker.onManualFloorSet()
                inVerticalTransit = false
                floorDeltaAltitudeM = 0.0
                lastPublishedAltM = 0.0
                recomputeAnchor()
                prepareAdjacentFloors(target)
                anchorCurrentFloor(timestampNs, (est.sigmaM ?: 1.0).coerceIn(AltitudeFloorEstimator.TRACKED_FLOOR_SIGMA_M, AltitudeFloorEstimator.UNCONFIRMED_FLOOR_SIGMA_M))
                clearBaroSuggestion()
                loadVectorPlansInBackground()
                invalidateRouteAndPublish()
                return true
            }
            val currentMapPos = anchor?.toMap(tracker.state.value.position) ?: currentFloorOriginM
            val landing = buildingGraph.resolveFloorTransition(
                fromFloorId = uFloor,
                targetFloorId = target,
                currentPosM = currentMapPos,
                verticalSpeedMps = 0.0,
                activeRoute = cachedRoute
            )
            userFloorId = target
            viewedFloorId = target
            currentFloorOriginM = landing?.arrivalPosM ?: currentMapPos
            floorTracker.onManualFloorSet()
            inVerticalTransit = false
            floorDeltaAltitudeM = 0.0
            lastPublishedAltM = 0.0
            tracker.endVerticalHold(commit = false)
            recomputeAnchor()
            tracker.reanchorFloorOriginKeepHeading()
            val sigma = (est.sigmaM ?: 1.0).coerceIn(AltitudeFloorEstimator.TRACKED_FLOOR_SIGMA_M, AltitudeFloorEstimator.UNCONFIRMED_FLOOR_SIGMA_M)
            anchorCurrentFloor(timestampNs, sigma)
            clearBaroSuggestion()
            loadVectorPlansInBackground()
            invalidateRouteAndPublish()
            return true
        }
        when {
            est.floorNumber == current && est.confident ->
                anchorCurrentFloor(
                    timestampNs,
                    (floorTrustSigmaM ?: AltitudeFloorEstimator.TRACKED_FLOOR_SIGMA_M)
                        .coerceIn(AltitudeFloorEstimator.KNOWN_FLOOR_SIGMA_M, AltitudeFloorEstimator.TRACKED_FLOOR_SIGMA_M)
                )
            anchorWhenUnsure ->
                anchorCurrentFloor(timestampNs, AltitudeFloorEstimator.UNCONFIRMED_FLOOR_SIGMA_M)
        }
        return false
    }

    /** Before a start is chosen, pre-select the floor the barometer is sure of (spec §6.5 « Étage N ? » pre-filled). */
    private fun maybeSetSetupFloorFromBarometer(timestampNs: Long) {
        if (setupFloorFromBarometerDone || startPose != null || usingGpsStart) return
        if (tracker.state.value.phase != PdrPhase.IDLE) return
        val est = altitudeFloors.estimate(timestampNs, smoothPressureHpa)
        if (est.source == AltitudeFloorSource.ENTRANCE_ASSUMED || !est.confident) return
        val target = floorIdForNumber(est.floorNumber) ?: return
        setupFloorFromBarometerDone = true
        if (target != userFloorId) {
            Log.d(LOG_TAG, "barometer: setup floor $target (p=%.2f)".format(est.probability))
            userFloorId = target
            viewedFloorId = target
            _state.value = buildSnapshot(tracker.state.value)
        }
    }

    /** « Floor N? » when the barometer leans towards another floor but not enough to move the user. Returns true if it changed. */
    private fun updateBaroSuggestion(timestampNs: Long): Boolean {
        val uFloor = userFloorId
        var id: String? = null
        var p = 0.0
        if (uFloor != null && !inVerticalTransit) {
            val est = altitudeFloors.estimate(timestampNs, smoothPressureHpa)
            if (est.source != AltitudeFloorSource.ENTRANCE_ASSUMED &&
                est.floorNumber != buildingGraph.floorNumber(uFloor) &&
                est.probability >= SUGGEST_PROBABILITY
            ) {
                id = floorIdForNumber(est.floorNumber)
                p = est.probability
            }
        }
        val changed = id != baroSuggestionId
        baroSuggestionId = id
        baroSuggestionProbability = if (id != null) p else 0.0
        return changed
    }

    private fun clearBaroSuggestion() {
        baroSuggestionId = null
        baroSuggestionProbability = 0.0
    }

    /** Saved with wall-clock time so it survives an app restart or a reboot (spec O6). */
    private fun saveFloorAnchor() {
        val a = altitudeFloors.floorAnchor ?: return
        val ageMs = (SystemClock.elapsedRealtimeNanos() - a.timestampNs) / 1_000_000L
        prefs.edit()
            .putFloat(keyAnchor("p"), a.pressureHpa.toFloat())
            .putFloat(keyAnchor("elev"), a.elevationM.toFloat())
            .putFloat(keyAnchor("sigma"), a.sigmaM.toFloat())
            .putLong(keyAnchor("wall"), System.currentTimeMillis() - ageMs)
            .apply()
    }

    private fun restoreFloorAnchor() {
        if (!prefs.contains(keyAnchor("wall"))) return
        val ageMs = System.currentTimeMillis() - prefs.getLong(keyAnchor("wall"), 0L)
        if (ageMs < 0) return
        altitudeFloors.restoreFloorAnchor(
            PressureAnchor(
                timestampNs = SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L,
                pressureHpa = prefs.getFloat(keyAnchor("p"), 0f).toDouble(),
                elevationM = prefs.getFloat(keyAnchor("elev"), 0f).toDouble(),
                sigmaM = prefs.getFloat(keyAnchor("sigma"), 1f).toDouble(),
                maxAgeNs = Long.MAX_VALUE
            )
        )
    }

    private fun keyAnchor(field: String) = "baro_anchor_${site.id}_$field"

    private fun handleGpsFix(fix: GpsFix) {
        latestGpsFix = fix
        recorder.onFix(fix.toRecEvent())
        // Outdoors next to MB with a clean fix: this pressure is the street level for the altitude floor estimate
        val distFromCoreM = fix.eastNorthM.norm()
        val pressure = latestPressureHpa
        if (site.gpsPositionTrusted && pressure != null && fix.accuracyM <= STREET_REFERENCE_MAX_ACCURACY_M &&
            distFromCoreM in STREET_REFERENCE_MIN_DIST_M..STREET_REFERENCE_MAX_DIST_M
        ) {
            altitudeFloors.captureStreetReference(latestPressureNs, pressure)
        }
        adapter.magneticDeclinationDeg = fix.magneticDeclinationDeg
        fix.trueNorthBearingRad?.let { bearing ->
            tracker.onTrueNorthBearingFromGps(fix.elapsedRealtimeNs, bearing)
        }
        // Only apply position drift correction when using GPS start OR outdoor high-precision GNSS (<= 4.5 m)
        val anc = anchor
        val allowPositionNudge = usingGpsStart || fix.accuracyM <= HIGH_PRECISION_OUTDOOR_GPS_M
        if (site.gpsPositionTrusted && allowPositionNudge && anc != null && fix.eastNorthM.norm() <= MAX_CAMPUS_RADIUS_M && !localizationEnabled) {
            tracker.onExternalFix(anc.fromMap(fix.eastNorthM), fix.accuracyM)
        }
        if (localizationEnabled && site.gpsPositionTrusted && fix.eastNorthM.norm() <= MAX_CAMPUS_RADIUS_M) {
            localization.onSystemPosition(SystemFix(fix.eastNorthM, fix.accuracyM, fix.elapsedRealtimeNs))
        }
        _state.value = buildSnapshot(tracker.state.value)
    }

    private fun ensureUsableStepSource() {
        val s = tracker.state.value
        if (s.stepSource == StepSource.NONE && s.phase == PdrPhase.IDLE) {
            if (s.stepDetectorAvailable && (!s.activityPermissionNeeded || s.activityPermissionGranted)) {
                tracker.setStepSource(StepSource.HARDWARE)
            } else if (!s.stepDetectorAvailable && s.fallbackCapable) {
                tracker.setStepSource(StepSource.FALLBACK)
            }
        }
    }

    /**
     * Corridor-junction landmark: when the user turns at a real junction of the plan, pull the
     * dot onto the new corridor (bounded replay in [PdrTracker.onLandmark]). Fixes the along-track
     * drift that the lateral-only corridor assist cannot see.
     */
    private fun matchJunction(s: PdrState) {
        if (s.phase != PdrPhase.TRACKING || s.stepCount <= 0 || inVerticalTransit) return
        val plan = userFloorId?.let(buildingGraph::floor) ?: return
        val anc = anchor ?: return
        val mapPos = anc.toMap(s.position)
        val target = junctions.onStep(s.stepCount, mapPos, anc.mapHeading(s.headingRadians), plan) ?: return
        Log.d(LOG_TAG, "junction ${junctions.lastJunctionId}: pull %.2f m".format(target.distanceTo(mapPos)))
        tracker.onLandmark(anc.fromMap(target))
    }

    private fun recomputeAnchor() {
        // Floor / start / facing changed: past map samples no longer describe the current frame.
        junctions.reset()
        val uPlan = userFloorId?.let(buildingGraph::floor)
        val sp = startPose
        val startNodeOnUserFloor = if (sp != null && sp.floorId == userFloorId) {
            uPlan?.node(sp.nodeId)
        } else {
            null
        }
        val f = facing
        val psi = when (f) {
            FacingOption.AutoTrueNorth -> 0.0
            is FacingOption.Neighbor ->
                if (uPlan != null && startNodeOnUserFloor != null) {
                    uPlan.node(f.nodeId)?.let { FloorPlan.facingPsi(startNodeOnUserFloor, it) } ?: 0.0
                } else {
                    0.0
                }
            is FacingOption.Cardinal -> Math.toRadians(f.degrees.toDouble())
        }

        tracker.useTrueNorthReference = (f == FacingOption.AutoTrueNorth)
        val computed = MapAnchor(currentFloorOriginM, psi)
        anchor = computed
        val corridorAxis = uPlan?.dominantCorridorAxisRad ?: 0.0
        // With map localization the anchor is not trusted: heading stabilization uses learned axes only.
        tracker.mapAxisBaseLocal = if (localizationEnabled) null else wrapAnglePi(corridorAxis - computed.psiRad)
        if (localizationEnabled && userFloorId != null && localization.floorId != userFloorId) startLocalization()
    }

    /**
     * Starts recording the walk on the current floor (debug app). The live search restarts at the same
     * moment, so the phone's run and the computer's replay start together.
     */
    fun startRecording(): Boolean {
        val floorId = userFloorId ?: return false
        // A measured walk starts cold (no warm start), exactly like its replay.
        if (localizationEnabled && localization.floorId == floorId) beginSearch(floorId, allowWarm = false)
        val s = tracker.state.value
        val len = strideMemory.learnedStepM ?: savedStepLengthM ?: DEFAULT_STEP_LENGTH_M
        val prior = localization.params
        val header = RecordingHeader(
            siteId = site.id,
            floorId = floorId,
            startedWallMs = System.currentTimeMillis(),
            startedNs = SystemClock.elapsedRealtimeNanos(),
            appVersion = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull().orEmpty(),
            stepSource = s.stepSource.name,
            stepLengthM = len,
            cadenceHz = savedCadenceHz?.takeIf { savedStepLengthM != null && strideMemory.learnedStepM == null },
            declinationDeg = adapter.magneticDeclinationDeg,
            strideScaleMin = prior.strideScaleMin,
            strideScaleMax = prior.strideScaleMax
        )
        val ok = recorder.start(header, listOfNotNull(latestGpsFix?.toRecEvent()))
        if (ok) {
            lastLoggedLiveStep = -1
            lastCarryNote = null
            recorder.note("start", "phase=${s.phase} steps=${s.stepCount} localization=$localizationEnabled")
            recorder.note("screen", if (tracker.screenInteractive) "on" else "off")
        }
        _state.value = buildSnapshot(tracker.state.value)
        return ok
    }

    fun stopRecording(): java.io.File? = recorder.stop()

    /** The walker says what is happening ("poche", "escalier"…), stamped in the recording. */
    fun recordLabel(text: String): Boolean = recorder.label(text)

    private var lastCarryNote: String? = null

    /** The phone can range Wi-Fi access points by time of flight (802.11mc); the app then needs NEARBY_WIFI_DEVICES (Android 13+). */
    val wifiRttSupported: Boolean get() = recorder.wifiRttSupported

    /** Ground truth while recording: the walker is at [mapM] (plan metres). Marker number, null when not recording. */
    fun addRecordingMarker(mapM: PointM): Int? = recorder.addMarker(mapM.x, mapM.y)

    fun recordings(): List<java.io.File> = recorder.recordings()

    private var lastLoggedLiveStep = -1

    /**
     * Starts a search on [floorId]: stride prior from [strideMemory], GPS disk if fresh, and, when
     * [allowWarm], the zones where the last search ended (≤ 30 min ago, same building and floor).
     */
    private fun beginSearch(floorId: String, allowWarm: Boolean) {
        val (lo, hi) = strideMemory.prior(tracker.state.value.lengthPerStepM ?: strideMemory.learnedStepM)
        localization.params = tuning.locParams().copy(strideScaleMin = lo, strideScaleMax = hi)
        localization.start(floorId, freshSystemFix(), if (allowWarm) loadWarmStart(floorId) else null)
        foundSteps = 0
        learnedInRun = false
        lastLearnFoundSteps = 0
    }

    /** One more PDR step under map localization: stride learning and the last zones for the next warm start. */
    private fun onLocalizedStep(s: PdrState) {
        val est = localization.estimate ?: return
        val d = localization.debug ?: return
        foundSteps = if (est.state == LocState.FOUND) foundSteps + 1 else 0
        if (est.state != LocState.SEARCHING && s.stepCount % 10 == 0) saveLastZones()
        val l0 = s.lengthPerStepM ?: return
        val due = if (!learnedInRun) foundSteps >= StrideMemory.FOUND_STEPS_TO_LEARN
        else foundSteps - lastLearnFoundSteps >= StrideMemory.RELEARN_EVERY_STEPS
        if (!due || d.strideSpread > StrideMemory.MAX_SPREAD) return
        strideMemory = strideMemory.learn(l0, d.strideScale, newRun = !learnedInRun)
        learnedInRun = true
        lastLearnFoundSteps = foundSteps
        prefs.edit()
            .putFloat(KEY_LEARNED_STEP, strideMemory.learnedStepM!!.toFloat())
            .putInt(KEY_STRIDE_SESSIONS, strideMemory.sessions)
            .apply()
        recorder.note("stride_learned", "%.3f m, sessions %d".format(Locale.ROOT, strideMemory.learnedStepM, strideMemory.sessions))
        Log.d(LOG_TAG, "stride learned: ${strideMemory.learnedStepM} m (${strideMemory.sessions} sessions)")
    }

    /** Where the search stands (not while SEARCHING): building, floor, top 3 zones, time and pressure. */
    private fun saveLastZones() {
        val est = localization.estimate ?: return
        val floor = localization.floorId ?: return
        if (est.state == LocState.SEARCHING || est.zones.isEmpty()) return
        prefs.edit()
            .putString(KEY_LAST_SITE, site.id)
            .putString(KEY_LAST_FLOOR, floor)
            .putString(KEY_LAST_ZONES, est.zones.take(3).joinToString(";") { "${it.centerM.x},${it.centerM.y},${it.radiusM},${it.weight}" })
            .putLong(KEY_LAST_WALL_MS, System.currentTimeMillis())
            .putFloat(KEY_LAST_HPA, latestPressureHpa?.toFloat() ?: Float.NaN)
            .apply()
    }

    /** The last zones if they are on this building and floor, recent, and the pressure says the floor did not change. */
    private fun loadWarmStart(floorId: String): WarmStart? {
        if (prefs.getString(KEY_LAST_SITE, null) != site.id || prefs.getString(KEY_LAST_FLOOR, null) != floorId) return null
        val ageS = (System.currentTimeMillis() - prefs.getLong(KEY_LAST_WALL_MS, 0L)) / 1000.0
        if (LocalizationController.warmShare(ageS) <= 0.0) return null
        val thenHpa = prefs.getFloat(KEY_LAST_HPA, Float.NaN).toDouble()
        val nowHpa = latestPressureHpa
        if (thenHpa.isFinite() && nowHpa != null && abs(nowHpa - thenHpa) > WARM_MAX_PRESSURE_DELTA_HPA) return null
        val zones = prefs.getString(KEY_LAST_ZONES, null)?.split(';')?.mapNotNull { z ->
            val v = z.split(',').mapNotNull(String::toDoubleOrNull)
            if (v.size == 4) LocZone(PointM(v[0], v[1]), v[2], v[3], 0.0) else null
        }.orEmpty()
        return if (zones.isEmpty()) null else WarmStart(zones, ageS)
    }

    private fun recordLive(s: PdrState) {
        val est = localization.estimate
        val zone = est?.zones?.firstOrNull()
        recorder.onLive(
            RecEvent.Live(
                SystemClock.elapsedRealtimeNanos(), s.stepCount, s.position.x, s.position.y, s.headingRadians,
                est?.state?.name ?: "OFF", zone?.centerM?.x, zone?.centerM?.y, zone?.radiusM
            )
        )
    }

    private fun GpsFix.toRecEvent() = RecEvent.Fix(
        elapsedRealtimeNs, wgs84.latitude, wgs84.longitude, accuracyM, provider, null, verticalAccuracyM,
        trueNorthBearingRad?.let(Math::toDegrees), speedMps, magneticDeclinationDeg, satellitesUsed
    )

    /**
     * Developer switch for map localization (plan 1). On: the PDR starts by itself, its plan-based
     * corrections (corridor pull, junction landmarks, GPS nudge, map axis) are off, and every PDR
     * state feeds the filter. Off: everything is as before.
     */
    fun setLocalizationEnabled(enabled: Boolean) {
        if (enabled == localizationEnabled) return
        localizationEnabled = enabled
        // Map localization learns the phone→walking angle, so the phone may be held any way (spec §6.6).
        tracker.anyHoldHeading = enabled
        if (enabled) {
            localizationEnabledAtNs = SystemClock.elapsedRealtimeNanos()
            if (LocalizationController.shouldAutoStartPdr(tracker.state.value, 0L)) startWithSavedOrDefaultStepLength()
        } else {
            localization.stop()
            localizationStartingFor = null
        }
        updateTrackingService()
        recomputeAnchor()
        _state.value = buildSnapshot(tracker.state.value)
    }

    private fun startLocalization() {
        val floorId = userFloorId ?: return
        if (localizationStartingFor == floorId) return
        localizationStartingFor = floorId
        Log.d(LOG_TAG, "map localization: building the map of $floorId")
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val ok = withContext(Dispatchers.Default) { localization.prepare(floorId) }
            Log.d(LOG_TAG, "map localization: map of $floorId built in ${SystemClock.elapsedRealtime() - t0} ms")
            if (!ok) Log.w(LOG_TAG, "map localization unavailable: ${localization.lastError}")
            Log.d(LOG_TAG, "map localization prepare($floorId) ok=$ok enabled=$localizationEnabled user=$userFloorId")
            if (ok && localizationEnabled && userFloorId == floorId) beginSearch(floorId, allowWarm = true)
            if (ok) prepareAdjacentFloors(floorId)
            if (localizationStartingFor == floorId) localizationStartingFor = null
            _state.value = buildSnapshot(tracker.state.value)
        }
    }

    /** Latest GPS / network position as a search disk, if recent, near the building and the building's origin is reliable. */
    private fun freshSystemFix(): SystemFix? {
        val fix = latestGpsFix ?: return null
        if (!site.gpsPositionTrusted || fix.eastNorthM.norm() > MAX_CAMPUS_RADIUS_M) return null
        if (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNs > SYSTEM_FIX_MAX_AGE_NS) return null
        return SystemFix(fix.eastNorthM, fix.accuracyM, fix.elapsedRealtimeNs)
    }

    private fun persistGaitIfUpdated(s: PdrState) {
        val length = s.lengthPerStepM
        if (s.phase != PdrPhase.TRACKING || length == null) return
        val cadence = s.baseCadenceHz
        if (length != savedStepLengthM || cadence != savedCadenceHz) {
            prefs.edit().apply {
                putFloat(KEY_LAST_STEP_LENGTH, length.toFloat())
                if (cadence != null) putFloat(KEY_LAST_CADENCE, cadence.toFloat())
            }.apply()
            savedStepLengthM = length
            if (cadence != null) savedCadenceHz = cadence
        }
    }

    private fun invalidateRouteAndPublish() {
        cachedRouteKey = null
        cachedRoute = null
        _state.value = buildSnapshot(tracker.state.value)
    }

    private fun buildSnapshot(pdr: PdrState): NavigationSnapshot {
        val uFloorId = userFloorId
        val vFloorId = viewedFloorId ?: uFloorId
        val vPlan = vFloorId?.let(buildingGraph::floor)
        val uPlan = uFloorId?.let(buildingGraph::floor)
        val vectorsSnapshot = synchronized(floorVectors) { LinkedHashMap(floorVectors) }
        val vectorPlan = vFloorId?.let { vectorsSnapshot[it] }
        val anc = anchor
        val mapPos = anc?.toMap(pdr.position) ?: pdr.position
        val mapHeading = anc?.mapHeading(pdr.headingRadians) ?: pdr.headingRadians
        val route = resolveRoute(pdr, mapPos, mapHeading)

        val nearbyConn = uFloorId?.let {
            buildingGraph.findNearestVerticalConnector(it, mapPos, maxRadiusM = 8.5, accessibleOnly = accessibleOnly)
        }
        val nearbyVertical = if (uFloorId != null && nearbyConn != null) {
            val vg = nearbyConn.verticalGroupId
            val candidateUp = buildingGraph.adjacentFloorId(uFloorId, +1)?.takeIf { nextF ->
                vg.isNullOrBlank() || buildingGraph.floor(nextF)?.nodes?.any { it.verticalGroupId == vg } == true
            }
            val candidateDown = buildingGraph.adjacentFloorId(uFloorId, -1)?.takeIf { prevF ->
                vg.isNullOrBlank() || buildingGraph.floor(prevF)?.nodes?.any { it.verticalGroupId == vg } == true
            }
            if (candidateUp != null || candidateDown != null) {
                NearbyVerticalOptions(nearbyConn, candidateUp, candidateDown)
            } else {
                null
            }
        } else {
            null
        }

        val suggestedTransfer = computeSuggestedTransfer(uFloorId, mapPos, route)

        return NavigationSnapshot(
            pdr = pdr,
            site = site,
            geoOrigin = geoOrigin,
            osmSurroundings = osmSurroundings,
            floors = buildingGraph.floors,
            activeFloorId = vFloorId,
            activeFloor = vPlan,
            activeFloorVector = vectorPlan,
            loadedFloorVectors = vectorsSnapshot,
            userFloorId = uFloorId,
            userFloor = uPlan,
            isViewingUserFloor = vFloorId == uFloorId,
            startPose = startPose,
            startNodeId = startPose?.nodeId,
            usingGpsStart = usingGpsStart,
            hasExplicitStart = startPose != null || usingGpsStart,
            facing = facing,
            anchor = anc,
            mapPositionM = mapPos,
            mapHeadingRad = mapHeading,
            barometerAvailable = adapter.pressureSensor != null,
            floorDeltaAltitudeM = floorDeltaAltitudeM,
            inVerticalTransit = inVerticalTransit,
            suggestedTransfer = suggestedTransfer,
            nearbyVertical = nearbyVertical,
            locationPermissionGranted = locationPermissionGranted,
            latestGpsFix = latestGpsFix,
            destination = destination,
            accessibleOnly = accessibleOnly,
            activeRoute = route,
            destinations = buildingGraph.destinations,
            savedStepLengthM = savedStepLengthM,
            altitudeFloor = if (latestPressureHpa != null || altitudeFloors.hasStreetReference || altitudeFloors.floorAnchor != null) estimateAltitudeFloor() else null,
            hasStreetPressureReference = altitudeFloors.hasStreetReference,
            barometerFloorSuggestionId = baroSuggestionId,
            barometerFloorSuggestionProbability = baroSuggestionProbability,
            lastJunctionId = junctions.lastJunctionId,
            localizationEnabled = localizationEnabled,
            localization = if (localizationEnabled) localization.estimate else null,
            localizationParticles = if (localizationEnabled) localization.sampleParticles(MAX_PARTICLES_SHOWN) else emptyList(),
            localizationMap = if (localizationEnabled) localization.currentMap() else null,
            localizationError = localization.lastError,
            localizationTrace = if (localizationEnabled) localization.tracedPath() else emptyList(),
            localizationSystemCircle = if (localizationEnabled) localization.systemCircle else null,
            localizationDebug = if (localizationEnabled) localization.debug else null,
            backgroundTracking = backgroundTracking
        )
    }

    private fun computeSuggestedTransfer(
        uFloorId: String?,
        mapPos: PointM,
        route: NavigationRoute?
    ): SuggestedFloorTransfer? {
        if (uFloorId == null || route == null) return null
        val firstStep = route.steps.firstOrNull()
        if (firstStep is RouteStep.FloorTransfer && firstStep.fromFloorId == uFloorId) {
            return SuggestedFloorTransfer(
                fromFloorId = firstStep.fromFloorId,
                toFloorId = firstStep.toFloorId,
                viaLabel = firstStep.viaLabel,
                verticalGroupId = firstStep.verticalGroupId,
                viaElevator = firstStep.viaElevator,
                fromActiveRoute = true
            )
        }
        val nearRouteTransfer = route.transfers.firstOrNull {
            it.fromFloorId == uFloorId && it.departurePosM.distanceTo(mapPos) <= 8.0
        } ?: return null
        return SuggestedFloorTransfer(
            fromFloorId = nearRouteTransfer.fromFloorId,
            toFloorId = nearRouteTransfer.toFloorId,
            viaLabel = nearRouteTransfer.viaLabel,
            verticalGroupId = nearRouteTransfer.verticalGroupId,
            viaElevator = nearRouteTransfer.viaElevator,
            fromActiveRoute = true
        )
    }

    private fun resolveRoute(pdr: PdrState, mapPos: PointM, mapHeading: Double): NavigationRoute? {
        val dest = destination ?: return null
        val floorId = userFloorId ?: return null
        if (startPose == null && !usingGpsStart && pdr.stepCount == 0) {
            return null
        }
        val headingBucket = if (pdr.headingValid) {
            (Math.toDegrees(mapHeading) / 20.0).roundToInt()
        } else {
            Int.MIN_VALUE
        }
        val key = RouteCacheKey(
            floorId = floorId,
            stepCount = pdr.stepCount,
            posBucketX = (mapPos.x * 2.0).roundToInt(),
            posBucketY = (mapPos.y * 2.0).roundToInt(),
            headingBucket = headingBucket,
            dest = dest,
            accessibleOnly = accessibleOnly
        )
        if (key == cachedRouteKey) return cachedRoute
        cachedRouteKey = key
        cachedRoute = buildingGraph.findRouteFromPosition(
            floorId = floorId,
            positionM = mapPos,
            destination = dest,
            accessibleOnly = accessibleOnly,
            userMapHeadingRad = if (pdr.headingValid) mapHeading else null
        )
        return cachedRoute
    }

    private fun refreshSensorRegistrations() {
        tracker.onSensorFrameRestart()
        adapter.refreshRegistrations()
    }

    private fun checkActivityPermission(): Boolean {
        if (!permissionNeeded) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pushSensorStatus() {
        tracker.onSensorStatus(
            SensorStatus(
                rotationAvailable = adapter.rotationSensor != null,
                stepDetectorAvailable = adapter.stepSensor != null,
                fallbackCapable = adapter.fallbackCapable,
                permissionNeeded = permissionNeeded,
                permissionGranted = permissionGranted,
                registrationFailed = registrationFailed
            )
        )
    }

    companion object {
        private const val LOG_TAG = "IndoorNav"
        private const val PREFS_NAME = "pdr_prefs"
        private const val KEY_LAST_STEP_LENGTH = "last_step_length"
        private const val KEY_LAST_CADENCE = "last_cadence_hz"
        private const val SETTLING_DELAY_MS = 2_000L
        private const val MAX_CAMPUS_RADIUS_M = 350.0
        private const val SYSTEM_FIX_MAX_AGE_NS = 120_000_000_000L
        private const val MAX_PARTICLES_SHOWN = 1000
        private const val DEFAULT_STEP_LENGTH_M = 0.74
        private const val IDLE_STOP_NS = 10 * 60 * 1_000_000_000L
        private const val KEY_LEARNED_STEP = "learned_step_length"
        private const val KEY_STRIDE_SESSIONS = "stride_sessions"
        private const val KEY_LAST_SITE = "last_site"
        private const val KEY_LAST_FLOOR = "last_floor"
        private const val KEY_LAST_ZONES = "last_zones"
        private const val KEY_LAST_WALL_MS = "last_wall_ms"
        private const val KEY_LAST_HPA = "last_hpa"
        /** About 2/3 of a floor (0.47 hPa per 4 m): more and the walker probably changed floor. */
        private const val WARM_MAX_PRESSURE_DELTA_HPA = 0.3
        private const val NEAR_CONNECTOR_BARO_RADIUS_M = 7.0
        /** Map localization: share of the weight next to stairs / lifts for the barometer to accept a floor change. */
        private const val NEAR_CONNECTOR_MIN_MASS = 0.15
        /** No pressure for this long = sensors were off (background): re-check the floor. */
        private const val PRESSURE_GAP_NS = 15_000_000_000L
        private const val PRESSURE_FRESH_NS = 3_000_000_000L
        private const val PRESSURE_SMOOTH_TAU_S = 4.0
        /** Move the user after a settled climb only when the barometer is this sure of another floor. */
        private const val TRANSIT_SWITCH_PROBABILITY = 0.9
        /** After the app was in the background (no relative tracking meanwhile). */
        private const val RESUME_SWITCH_PROBABILITY = 0.8
        /** Offer « Floor N? » from this probability on. */
        private const val SUGGEST_PROBABILITY = 0.6
        private const val FLOOR_ANCHOR_REFRESH_NS = 90_000_000_000L
        private const val KEEPALIVE_MAX_DELTA_M = 1.0
        private const val HIGH_PRECISION_OUTDOOR_GPS_M = 4.5
        private const val STREET_REFERENCE_MAX_ACCURACY_M = 15.0
        private const val STREET_REFERENCE_MIN_DIST_M = 45.0
        private const val STREET_REFERENCE_MAX_DIST_M = 160.0
        private const val ENTRANCE_SNAP_MIN_DIST_M = 15.0

        fun loadBundledFloors(context: Context, floorsDir: String = IndoorSite.MB.floorsDir): List<FloorPlan> {
            val loaded = ArrayList<FloorPlan>()
            runCatching {
                val extra = context.assets.list(floorsDir).orEmpty().sorted()
                for (file in extra) {
                    if (!file.startsWith("floorplan-") || !file.endsWith(".json")) continue
                    context.assets.open("$floorsDir/$file").use { planStream ->
                        val plan = FloorPlan.parse(planStream)
                        if (loaded.none { it.floorId == plan.floorId }) {
                            loaded.add(plan)
                        }
                    }
                }
            }
            if (loaded.isEmpty() && floorsDir == IndoorSite.MB.floorsDir) {
                runCatching {
                    context.assets.open("floorplan.json").use { loaded.add(FloorPlan.parse(it)) }
                }
            }
            return loaded.sortedWith(compareBy { extractFloorNumber(it.floorId) })
        }

        private fun extractFloorNumber(floorId: String): Int =
            floorId.substringAfterLast('-', "").toIntOrNull() ?: Int.MAX_VALUE
    }
}
