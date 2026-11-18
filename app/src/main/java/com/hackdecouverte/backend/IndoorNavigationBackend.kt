package com.hackdecouverte.backend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.hackdecouverte.map.BuildingGraph
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
import com.hackdecouverte.pdr.CorridorConstraintResult
import com.hackdecouverte.pdr.FloorTransitionTracker
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PdrState
import com.hackdecouverte.pdr.PdrTracker
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.pdr.SensorStatus
import com.hackdecouverte.pdr.StepSource
import com.hackdecouverte.pdr.wrapAnglePi
import com.hackdecouverte.sensor.GpsFix
import com.hackdecouverte.sensor.GpsLocationSource
import com.hackdecouverte.sensor.PdrSensorAdapter
import kotlin.math.abs
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
    /** Last corridor junction that re-anchored the dot (see [JunctionMatcher]), for telemetry. */
    val lastJunctionId: String? = null,
    /** Map localization (developer switch for now): zones, state, a sample of particles. */
    val localizationEnabled: Boolean = false,
    val localization: LocEstimate? = null,
    val localizationParticles: List<PointM> = emptyList()
)

class IndoorNavigationBackend(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext

    val tracker = PdrTracker(
        clock = SystemClock::elapsedRealtimeNanos,
        antiShakeEnabled = true,
        corridorAssistInitial = true
    ).apply {
        useTrueNorthReference = true
    }

    private val floorTracker = FloorTransitionTracker()
    private val altitudeFloors = AltitudeFloorEstimator()
    private var latestPressureHpa: Double? = null
    private var latestPressureNs: Long = Long.MIN_VALUE
    private val junctions = JunctionMatcher()

    /** Map localization (spec §6.3), behind a developer switch until plan 2. */
    private val localization = LocalizationController({ floorId ->
        floorId.removePrefix("MB-").toIntOrNull()?.let { n ->
            loadIndoorMap(n) { path -> runCatching { appContext.assets.open(path) }.getOrNull() }
        }
    })
    private var localizationEnabled = false
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
        origin = GeoOrigin.MB_ELEVATOR_CORE,
        onFix = ::handleGpsFix
    )

    private val osmLoader = OsmNetworkLoader(appContext)

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

    private var geoOrigin: GeoOrigin = GeoOrigin.MB_ELEVATOR_CORE
    private var osmSurroundings: OsmSurroundings? = null
    private val floorVectors = LinkedHashMap<String, FloorVectorPlan>()

    private var buildingGraph = BuildingGraph(loadBundledFloors(appContext))
    private var userFloorId: String? =
        buildingGraph.floor(DEFAULT_FLOOR_ID)?.floorId ?: buildingGraph.floors.firstOrNull()?.floorId
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

    init {
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

        scope.launch {
            osmLoader.loadInitial(geoOrigin)?.let { loadedOsm ->
                osmSurroundings = loadedOsm
                _state.value = buildSnapshot(tracker.state.value)
            }
        }
        loadVectorPlansInBackground()

        scope.launch {
            var lastLoggedSteps = -1
            tracker.state.collect { pdrState ->
                if (pdrState.stepCount != lastLoggedSteps) {
                    lastLoggedSteps = pdrState.stepCount
                    Log.d(
                        LOG_TAG,
                        "step=${pdrState.stepCount} pos=(%.2f, %.2f) dist=%.1f held=${tracker.heldStepCount} transit=$inVerticalTransit dz=%.2f floor=$userFloorId"
                            .format(pdrState.position.x, pdrState.position.y, pdrState.distanceM, floorDeltaAltitudeM)
                    )
                }
                persistGaitIfUpdated(pdrState)
                if (localizationEnabled) {
                    if (pdrState.canStart) startWithSavedOrDefaultStepLength() // the PDR starts by itself (spec §6.1)
                    localization.onPdrState(pdrState)
                } else {
                    matchJunction(pdrState)
                }
                _state.value = buildSnapshot(pdrState)
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
        facingOption: FacingOption = FacingOption.AutoTrueNorth
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
        setStartPose(floorId, start.id)
        if (tracker.state.value.phase == PdrPhase.IDLE) startWithSavedOrDefaultStepLength()
        return true
    }

    /** Current altitude-based floor estimate (also published in the snapshot). */
    fun estimateAltitudeFloor(): AltitudeFloorEstimate =
        altitudeFloors.estimate(latestPressureNs, latestPressureHpa)

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

    fun startWithSavedOrDefaultStepLength(defaultM: Double = 0.74): Boolean {
        val len = savedStepLengthM ?: defaultM
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

    fun onForeground() {
        permissionGranted = checkActivityPermission()
        locationPermissionGranted = gpsSource.hasLocationPermission()
        pushSensorStatus()
        ensureUsableStepSource()
        tracker.onSensorFrameRestart()
        adapter.start()
        gpsSource.start()
        _state.value = buildSnapshot(tracker.state.value)
    }

    fun onBackground() {
        settlingJob?.cancel()
        settlingJob = null
        inVerticalTransit = false
        tracker.endVerticalHold(commit = false)
        gpsSource.stop()
        adapter.stop()
        tracker.pause()
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
        _state.value = buildSnapshot(tracker.state.value)
    }

    private fun handlePressureSample(timestampNs: Long, pressureHpa: Double) {
        latestPressureHpa = pressureHpa
        latestPressureNs = timestampNs
        val uFloor = userFloorId ?: return
        val currentMapPos = anchor?.toMap(tracker.state.value.position) ?: currentFloorOriginM
        val nearConnector = buildingGraph.findNearestVerticalConnector(
            floorId = uFloor,
            posM = currentMapPos,
            maxRadiusM = NEAR_CONNECTOR_BARO_RADIUS_M,
            accessibleOnly = false
        ) != null

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

        if (wasInTransit != inVerticalTransit || abs(floorDeltaAltitudeM - lastPublishedAltM) >= 0.25) {
            lastPublishedAltM = floorDeltaAltitudeM
            _state.value = buildSnapshot(tracker.state.value)
        }
    }

    private fun handleGpsFix(fix: GpsFix) {
        latestGpsFix = fix
        // Outdoors next to MB with a clean fix: this pressure is the street level for the altitude floor estimate
        val distFromCoreM = fix.eastNorthM.norm()
        val pressure = latestPressureHpa
        if (pressure != null && fix.accuracyM <= STREET_REFERENCE_MAX_ACCURACY_M &&
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
        if (allowPositionNudge && anc != null && fix.eastNorthM.norm() <= MAX_CAMPUS_RADIUS_M && !localizationEnabled) {
            tracker.onExternalFix(anc.fromMap(fix.eastNorthM), fix.accuracyM)
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
     * Developer switch for map localization (plan 1). On: the PDR starts by itself, its plan-based
     * corrections (corridor pull, junction landmarks, GPS nudge, map axis) are off, and every PDR
     * state feeds the filter. Off: everything is as before.
     */
    fun setLocalizationEnabled(enabled: Boolean) {
        if (enabled == localizationEnabled) return
        localizationEnabled = enabled
        if (enabled) {
            val s = tracker.state.value
            if (s.canStart) startWithSavedOrDefaultStepLength()
        } else {
            localization.stop()
            localizationStartingFor = null
        }
        recomputeAnchor()
        _state.value = buildSnapshot(tracker.state.value)
    }

    private fun startLocalization() {
        val floorId = userFloorId ?: return
        if (localizationStartingFor == floorId) return
        localizationStartingFor = floorId
        scope.launch {
            val ok = withContext(Dispatchers.Default) { localization.prepare(floorId) }
            if (ok && localizationEnabled && userFloorId == floorId) localization.start(floorId)
            if (localizationStartingFor == floorId) localizationStartingFor = null
            _state.value = buildSnapshot(tracker.state.value)
        }
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
            altitudeFloor = if (latestPressureHpa != null || altitudeFloors.hasStreetReference) estimateAltitudeFloor() else null,
            hasStreetPressureReference = altitudeFloors.hasStreetReference,
            lastJunctionId = junctions.lastJunctionId,
            localizationEnabled = localizationEnabled,
            localization = if (localizationEnabled) localization.estimate else null,
            localizationParticles = if (localizationEnabled) localization.samplePositions(MAX_PARTICLES_SHOWN) else emptyList()
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
        private const val DEFAULT_FLOOR_ID = "MB-2"
        private const val MAX_CAMPUS_RADIUS_M = 350.0
        private const val MAX_PARTICLES_SHOWN = 400
        private const val NEAR_CONNECTOR_BARO_RADIUS_M = 7.0
        private const val HIGH_PRECISION_OUTDOOR_GPS_M = 4.5
        private const val STREET_REFERENCE_MAX_ACCURACY_M = 15.0
        private const val STREET_REFERENCE_MIN_DIST_M = 45.0
        private const val STREET_REFERENCE_MAX_DIST_M = 160.0
        private const val ENTRANCE_SNAP_MIN_DIST_M = 15.0

        fun loadBundledFloors(context: Context): List<FloorPlan> {
            val loaded = ArrayList<FloorPlan>()
            runCatching {
                val extra = context.assets.list("floors").orEmpty().sorted()
                for (file in extra) {
                    if (!file.endsWith(".json")) continue
                    context.assets.open("floors/$file").use { planStream ->
                        val plan = FloorPlan.parse(planStream)
                        if (loaded.none { it.floorId == plan.floorId }) {
                            loaded.add(plan)
                        }
                    }
                }
            }
            if (loaded.isEmpty()) {
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
