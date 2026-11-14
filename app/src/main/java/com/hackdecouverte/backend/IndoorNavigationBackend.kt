package com.hackdecouverte.backend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.hackdecouverte.map.BuildingGraph
import com.hackdecouverte.map.DestinationItem
import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.FloorVectorPlan
import com.hackdecouverte.map.GeoOrigin
import com.hackdecouverte.map.MapAnchor
import com.hackdecouverte.map.NavigationRoute
import com.hackdecouverte.map.NodeKey
import com.hackdecouverte.map.OsmSurroundings
import com.hackdecouverte.pdr.CorridorConstraintResult
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

data class NavigationSnapshot(
    val pdr: PdrState = PdrState(),
    val geoOrigin: GeoOrigin = GeoOrigin.MB_ELEVATOR_CORE,
    val osmSurroundings: OsmSurroundings? = null,
    val floors: List<FloorPlan> = emptyList(),
    val activeFloorId: String? = null,
    val activeFloor: FloorPlan? = null,
    val activeFloorVector: FloorVectorPlan? = null,
    val startNodeId: String? = null,
    val usingGpsStart: Boolean = false,
    val facing: FacingOption = FacingOption.AutoTrueNorth,
    val anchor: MapAnchor? = null,
    val mapPositionM: PointM = PointM(0.0, 0.0),
    val mapHeadingRad: Double = 0.0,
    val locationPermissionGranted: Boolean = false,
    val latestGpsFix: GpsFix? = null,
    val destination: NodeKey? = null,
    val accessibleOnly: Boolean = false,
    val activeRoute: NavigationRoute? = null,
    val destinations: List<DestinationItem> = emptyList(),
    val savedStepLengthM: Double? = null
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

    private val adapter = PdrSensorAdapter(
        context = appContext,
        tracker = tracker,
        sourceProvider = { tracker.state.value.stepSource },
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
    private val floorVectors = HashMap<String, FloorVectorPlan>()

    private var buildingGraph = BuildingGraph(loadBundledFloors(appContext))
    private var activeFloorId: String? =
        buildingGraph.floor(DEFAULT_FLOOR_ID)?.floorId ?: buildingGraph.floors.firstOrNull()?.floorId
    private var startNodeId: String? = null
    private var gpsStartOriginM: PointM? = null
    private var facing: FacingOption = FacingOption.AutoTrueNorth
    private var anchor: MapAnchor? = MapAnchor(PointM(0.0, 0.0), 0.0)
    private var destination: NodeKey? = null
    private var accessibleOnly: Boolean = false
    private var latestGpsFix: GpsFix? = null

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
            val plan = activeFloorId?.let(buildingGraph::floor)
            val anc = anchor
            if (plan != null && anc != null) {
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

        // Load OpenStreetMap campus surroundings & MB-1..7 SVG vector floor plans in background
        scope.launch {
            osmLoader.loadInitial(geoOrigin)?.let { loadedOsm ->
                osmSurroundings = loadedOsm
                _state.value = buildSnapshot(tracker.state.value)
            }
        }
        loadVectorPlansInBackground()

        scope.launch {
            tracker.state.collect { pdrState ->
                persistGaitIfUpdated(pdrState)
                _state.value = buildSnapshot(pdrState)
            }
        }
    }

    private fun loadVectorPlansInBackground() {
        val plansSnapshot = buildingGraph.floors
        val priorityId = activeFloorId
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
                if (plan.floorId == activeFloorId) {
                    withContext(Dispatchers.Main) {
                        _state.value = buildSnapshot(tracker.state.value)
                    }
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
        if (activeFloorId == null || buildingGraph.floor(activeFloorId!!) == null) {
            activeFloorId = plans.firstOrNull()?.floorId
        }
        loadVectorPlansInBackground()
        recomputeAnchor()
        invalidateRouteAndPublish()
    }

    fun setStartPose(
        floorId: String,
        nodeId: String,
        facingOption: FacingOption = FacingOption.AutoTrueNorth
    ) {
        activeFloorId = floorId
        startNodeId = nodeId
        gpsStartOriginM = null
        val plan = buildingGraph.floor(floorId)
        facing = when (facingOption) {
            FacingOption.AutoTrueNorth -> FacingOption.AutoTrueNorth
            is FacingOption.Neighbor ->
                if (plan?.neighbors(nodeId)?.any { it.id == facingOption.nodeId } == true) {
                    facingOption
                } else {
                    FacingOption.AutoTrueNorth
                }
            is FacingOption.Cardinal -> facingOption
        }
        recomputeAnchor()
        invalidateRouteAndPublish()
    }

    fun useGpsAsStartOrigin(): Boolean {
        val fix = latestGpsFix ?: return false
        if (fix.eastNorthM.norm() > MAX_CAMPUS_RADIUS_M) return false
        startNodeId = null
        gpsStartOriginM = fix.eastNorthM
        facing = FacingOption.AutoTrueNorth
        recomputeAnchor()
        if (tracker.state.value.phase == PdrPhase.TRACKING) {
            tracker.resetTrace()
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
        startNodeId = null
        gpsStartOriginM = latestGpsFix?.eastNorthM?.takeIf { it.norm() <= MAX_CAMPUS_RADIUS_M }
        facing = FacingOption.AutoTrueNorth
        recomputeAnchor()
        invalidateRouteAndPublish()
    }

    fun switchActiveFloor(
        floorId: String,
        entryNodeId: String? = null,
        facingOption: FacingOption = FacingOption.AutoTrueNorth
    ) {
        if (buildingGraph.floor(floorId) == null) return
        activeFloorId = floorId
        if (entryNodeId != null) {
            setStartPose(floorId, entryNodeId, facingOption)
            tracker.resetTrace()
        } else {
            recomputeAnchor()
            invalidateRouteAndPublish()
        }
    }

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
        return tracker.startWithManualStepLength(lengthM, cadence)
    }

    fun startWithSavedOrDefaultStepLength(defaultM: Double = 0.74): Boolean {
        val len = savedStepLengthM ?: defaultM
        return startWithStepLength(len)
    }

    fun startCalibration5m(): Boolean = tracker.startCalibration()

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
        tracker.resetTrace()
        invalidateRouteAndPublish()
    }

    fun resumeFromPause() {
        tracker.resumeWithNewTrace()
        invalidateRouteAndPublish()
    }

    fun stopToSetup() {
        settlingJob?.cancel()
        settlingJob = null
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

    private fun handleGpsFix(fix: GpsFix) {
        latestGpsFix = fix
        adapter.magneticDeclinationDeg = fix.magneticDeclinationDeg
        fix.trueNorthBearingRad?.let { bearing ->
            tracker.onTrueNorthBearingFromGps(fix.elapsedRealtimeNs, bearing)
        }
        if (startNodeId == null && gpsStartOriginM == null &&
            fix.accuracyM <= 40.0 && fix.eastNorthM.norm() <= MAX_CAMPUS_RADIUS_M
        ) {
            gpsStartOriginM = fix.eastNorthM
            recomputeAnchor()
        }
        val anc = anchor
        if (anc != null && fix.eastNorthM.norm() <= MAX_CAMPUS_RADIUS_M) {
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

    private fun recomputeAnchor() {
        val plan = activeFloorId?.let(buildingGraph::floor)
        val startNode = startNodeId?.let { plan?.node(it) }
        val originM = startNode?.posM ?: gpsStartOriginM ?: PointM(0.0, 0.0)
        val f = facing

        val psi = when (f) {
            FacingOption.AutoTrueNorth -> 0.0
            is FacingOption.Neighbor ->
                if (plan != null && startNode != null) {
                    plan.node(f.nodeId)?.let { FloorPlan.facingPsi(startNode, it) } ?: 0.0
                } else {
                    0.0
                }
            is FacingOption.Cardinal -> Math.toRadians(f.degrees.toDouble())
        }

        tracker.useTrueNorthReference = (f == FacingOption.AutoTrueNorth)
        val computed = MapAnchor(originM, psi)
        anchor = computed
        val corridorAxis = plan?.dominantCorridorAxisRad ?: 0.0
        tracker.mapAxisBaseLocal = wrapAnglePi(corridorAxis - computed.psiRad)
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
        val fId = activeFloorId
        val plan = fId?.let(buildingGraph::floor)
        val vectorPlan = fId?.let { synchronized(floorVectors) { floorVectors[it] } }
        val anc = anchor
        val mapPos = anc?.toMap(pdr.position) ?: pdr.position
        val mapHeading = anc?.mapHeading(pdr.headingRadians) ?: pdr.headingRadians
        val route = resolveRoute(pdr, mapPos, mapHeading)
        return NavigationSnapshot(
            pdr = pdr,
            geoOrigin = geoOrigin,
            osmSurroundings = osmSurroundings,
            floors = buildingGraph.floors,
            activeFloorId = fId,
            activeFloor = plan,
            activeFloorVector = vectorPlan,
            startNodeId = startNodeId,
            usingGpsStart = startNodeId == null && gpsStartOriginM != null,
            facing = facing,
            anchor = anc,
            mapPositionM = mapPos,
            mapHeadingRad = mapHeading,
            locationPermissionGranted = locationPermissionGranted,
            latestGpsFix = latestGpsFix,
            destination = destination,
            accessibleOnly = accessibleOnly,
            activeRoute = route,
            destinations = buildingGraph.destinations,
            savedStepLengthM = savedStepLengthM
        )
    }

    private fun resolveRoute(pdr: PdrState, mapPos: PointM, mapHeading: Double): NavigationRoute? {
        val dest = destination ?: return null
        val floorId = activeFloorId ?: return null
        if (startNodeId == null && gpsStartOriginM == null && pdr.stepCount == 0) {
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
        private const val PREFS_NAME = "pdr_prefs"
        private const val KEY_LAST_STEP_LENGTH = "last_step_length"
        private const val KEY_LAST_CADENCE = "last_cadence_hz"
        private const val SETTLING_DELAY_MS = 2_000L
        private const val DEFAULT_FLOOR_ID = "MB-2"
        private const val MAX_CAMPUS_RADIUS_M = 350.0

        fun loadBundledFloors(context: Context): List<FloorPlan> {
            val loaded = ArrayList<FloorPlan>()
            runCatching {
                context.assets.open("floorplan.json").use { loaded.add(FloorPlan.parse(it)) }
            }
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
            return loaded.sortedWith(compareBy { extractFloorNumber(it.floorId) })
        }

        private fun extractFloorNumber(floorId: String): Int =
            floorId.substringAfterLast('-', "").toIntOrNull() ?: Int.MAX_VALUE
    }
}
