package com.lync.app.ui.go

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Elevator
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PersonPinCircle
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hackdecouverte.backend.IndoorNavigationBackend
import com.hackdecouverte.backend.NavigationSnapshot
import com.hackdecouverte.map.NavigationRoute
import com.hackdecouverte.map.NodeKey
import com.hackdecouverte.map.RejoinHint
import com.hackdecouverte.map.RouteStep
import com.hackdecouverte.pdr.AltitudeFloorEstimate
import com.hackdecouverte.pdr.AltitudeFloorSource
import com.hackdecouverte.pdr.PdrPhase
import com.hackdecouverte.pdr.PointM
import com.lync.app.R
import com.lync.app.data.places.CampusPlaces
import com.lync.app.data.places.Geo
import com.lync.app.data.places.GeoPoint
import com.lync.app.data.places.Maneuver
import com.lync.app.data.places.Place
import com.lync.app.data.places.WalkProgress
import com.lync.app.data.places.WalkRoute
import com.lync.app.data.places.WalkStep
import com.lync.app.data.places.WalkingRouter
import com.lync.app.ui.LocalNavigator
import com.lync.app.ui.components.CircleIconButton
import com.lync.app.ui.components.Pill
import com.lync.app.ui.components.PillStyle
import com.lync.app.ui.components.PrimaryButton
import com.lync.app.ui.components.pressable
import com.lync.app.ui.indoor.FloorPicker
import com.lync.app.ui.indoor.IndoorCamera
import com.lync.app.ui.indoor.IndoorCanvas
import com.lync.app.ui.indoor.RoomItem
import com.lync.app.ui.indoor.RoomPickerSheet
import com.lync.app.ui.indoor.ThemeToggleButton
import com.lync.app.ui.indoor.floorNumber
import com.lync.app.ui.indoor.instructionText
import com.lync.app.ui.indoor.rememberIndoorBackend
import com.lync.app.ui.motion.rememberHaptics
import com.lync.app.ui.theme.Campus
import com.lync.app.ui.theme.Tokens
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private enum class GoMode { Outdoor, Indoor }

/** GPS says "inside MB" when within the ground-floor graph's bounding box (+ margin). */
private const val INSIDE_MARGIN_M = 3.0
/** Leaving the building automatically needs a clean fix this far outside the footprint. */
private const val LEAVE_DISTANCE_M = 30.0
private const val REROUTE_OFF_ROUTE_M = 35.0
private const val REROUTE_MIN_INTERVAL_MS = 15_000L
private const val ARRIVED_OUTDOOR_M = 18.0
private const val INDOOR_WALK_SPEED_MPS = 1.2
/** Beyond this, walking directions make no sense: offer the system Maps app (transit, car…). */
private const val FAR_FROM_CAMPUS_M = 8_000.0
/** Last clean outdoor fix this close to the building, then GPS degrades / stops = you went in. */
private const val DOOR_RADIUS_M = 15.0
private const val GPS_LOST_AT_DOOR_S = 12.0

/**
 * One trip, door to door: "Y aller" from the calendar, the map search or a room.
 *
 * - Outside: Google-Maps-style walking directions on the street map (OSRM foot route,
 *   overview → "Démarrer" → tilted follow camera with turn-by-turn card).
 * - As soon as GPS puts you inside the John Molson building, the screen morphs into the indoor
 *   plan: your floor comes from the barometric altitude above the street (pressure sampled
 *   outside, see `AltitudeFloorEstimator`), step tracking starts at the door you came through
 *   and the route continues on the MB floor plans up to the room.
 * - Other buildings: outdoor guidance to the building, then the room / floor as text.
 */
@Composable
fun GoScreen(query: String) {
    val place = remember(query) { CampusPlaces.resolve(query) }
    if (place == null) {
        PlaceNotFound(query)
        return
    }
    val (backend, snap) = rememberIndoorBackend()
    GoTrip(place, backend, snap)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoTrip(place: Place, backend: IndoorNavigationBackend, snap: NavigationSnapshot) {
    val nav = LocalNavigator.current
    val c = Campus.colors
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val context = LocalContext.current

    // ----- Permissions: location (outdoor + building detection) and steps (indoor) -----
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        backend.onLocationPermissionResult(r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        r[Manifest.permission.ACTIVITY_RECOGNITION]?.let { backend.onActivityPermissionResult(it) }
    }
    fun askPermissions() = permissions.launch(
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.toTypedArray(),
    )
    LaunchedEffect(Unit) {
        val stepsMissing = snap.pdr.activityPermissionNeeded && !snap.pdr.activityPermissionGranted
        if (!snap.locationPermissionGranted || stepsMissing) askPermissions()
    }

    // ----- Destination in the MB graph (when the place has an indoor map) -----
    val destKey: NodeKey? = remember(place, snap.destinations) {
        if (!place.hasIndoorMap) return@remember null
        val label = place.roomLabel ?: return@remember null
        val k = CampusPlaces.roomKey(label)
        snap.destinations.firstOrNull { it.type == "room" && CampusPlaces.roomKey(it.label) == k }?.key
    }
    LaunchedEffect(destKey) { if (destKey != null) backend.setDestination(destKey) }
    val destFloor = destKey?.floorId?.let { floorNumber(it).toIntOrNull() } ?: place.floor

    // Ground-floor footprint (metres around the elevator core) for "am I inside?"
    val groundBox = remember(snap.floors) {
        snap.floors.firstOrNull { it.floorId == "MB-1" }?.nodes?.takeIf { it.isNotEmpty() }?.let { ns ->
            doubleArrayOf(ns.minOf { it.posM.x }, ns.minOf { it.posM.y }, ns.maxOf { it.posM.x }, ns.maxOf { it.posM.y })
        }
    }
    fun outsideBy(p: PointM): Double {
        val b = groundBox ?: return p.norm() - 30.0
        val dx = maxOf(b[0] - INSIDE_MARGIN_M - p.x, 0.0, p.x - b[2] - INSIDE_MARGIN_M)
        val dy = maxOf(b[1] - INSIDE_MARGIN_M - p.y, 0.0, p.y - b[3] - INSIDE_MARGIN_M)
        return kotlin.math.hypot(dx, dy)
    }

    // ----- Trip state -----
    var mode by rememberSaveable { mutableStateOf(GoMode.Outdoor) }
    var autoIndoor by rememberSaveable { mutableStateOf(true) }
    /** Entered by hand ("I'm already inside"): GPS never pulls the user back outside then. */
    var manualIndoor by rememberSaveable { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(false) }
    var camera by remember { mutableStateOf(OutdoorCamera.Overview) }
    var lastOutdoorM by remember { mutableStateOf<PointM?>(null) }
    var insideStreak by remember { mutableIntStateOf(0) }
    var outsideStreak by remember { mutableIntStateOf(0) }
    var welcome by remember { mutableStateOf<AltitudeFloorEstimate?>(null) }
    var askFloor by remember { mutableStateOf(false) }
    /** "Set my position": pick the room / stairs / door you stand at, then the pedometer takes over from there. */
    var pickPosition by remember { mutableStateOf(false) }
    val indoorCamera = remember { IndoorCamera() }
    /** Indoors the pedometer should run; started as soon as the compass heading is usable (it is not, right after the sensors restart). */
    var wantTracking by remember { mutableStateOf(false) }
    /** How far outside the footprint the last clean outdoor fix was (to catch "GPS died at the door"). */
    var lastOutsideByM by remember { mutableStateOf(Double.MAX_VALUE) }

    val fix = snap.latestGpsFix
    val me = fix?.wgs84?.let { GeoPoint(it.latitude, it.longitude) }
    val latestFix by rememberUpdatedState(fix)

    fun enterIndoor(manual: Boolean = false) {
        if (!place.hasIndoorMap) return
        manualIndoor = manual
        val estimate = backend.estimateAltitudeFloor()
        val floorId = "MB-${estimate.floorNumber}".takeIf { id -> snap.floors.any { it.floorId == id } } ?: "MB-1"
        destKey?.let(backend::setDestination)
        backend.enterBuilding(floorId, lastOutdoorM ?: fix?.eastNorthM)
        wantTracking = true
        indoorCamera.recenter()
        welcome = estimate
        // Entered by hand or no street pressure to compare: let the user set their own reference point
        pickPosition = manual
        askFloor = !manual && estimate.source == AltitudeFloorSource.ENTRANCE_ASSUMED && lastOutdoorM == null
        mode = GoMode.Indoor
        haptics.confirm()
    }

    // Automatic outdoor <-> indoor switch, driven by every GPS fix
    LaunchedEffect(fix?.elapsedRealtimeNs) {
        val f = fix ?: return@LaunchedEffect
        val out = outsideBy(f.eastNorthM)
        if (out > 0.0 && f.accuracyM <= 25.0) { lastOutdoorM = f.eastNorthM; lastOutsideByM = out }
        when (mode) {
            GoMode.Outdoor -> if (place.hasIndoorMap && autoIndoor) {
                // Indoors GPS gets coarse: accept a wide fix as long as it is centred in the building
                insideStreak = if (out == 0.0 && f.accuracyM <= 60.0) insideStreak + 1 else 0
                // Right at a door and the fix suddenly degrades: walls block the sky, you just went in
                val degradedAtDoor = lastOutsideByM <= DOOR_RADIUS_M && f.accuracyM > 30.0 && out <= DOOR_RADIUS_M + f.accuracyM
                if (insideStreak >= 2 || degradedAtDoor) enterIndoor()
            }
            GoMode.Indoor -> if (!manualIndoor) {
                outsideStreak = if (out > LEAVE_DISTANCE_M && f.accuracyM <= 10.0) outsideStreak + 1 else 0
                if (outsideStreak >= 3) {
                    outsideStreak = 0; insideStreak = 0
                    backend.stopToSetup()
                    wantTracking = false
                    mode = GoMode.Outdoor
                    camera = if (started) OutdoorCamera.Follow else OutdoorCamera.Overview
                }
            }
        }
    }
    // GPS often stops completely inside: no fix for a while right after being at a door = inside
    LaunchedEffect(mode, autoIndoor, lastOutsideByM) {
        if (mode != GoMode.Outdoor || !autoIndoor || !place.hasIndoorMap || lastOutsideByM > DOOR_RADIUS_M) return@LaunchedEffect
        while (true) {
            delay(2_000)
            val last = latestFix ?: continue
            val ageS = (android.os.SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNs) / 1e9
            if (ageS >= GPS_LOST_AT_DOOR_S && outsideBy(last.eastNorthM) <= DOOR_RADIUS_M) { enterIndoor(); break }
        }
    }
    // Start the pedometer as soon as it can (heading valid, phone flat enough), and stop asking once it runs
    LaunchedEffect(mode, wantTracking, snap.pdr.canStart, snap.pdr.phase) {
        if (mode != GoMode.Indoor || !wantTracking) return@LaunchedEffect
        when (snap.pdr.phase) {
            PdrPhase.TRACKING, PdrPhase.CALIBRATING, PdrPhase.SETTLING -> wantTracking = false
            PdrPhase.IDLE -> if (snap.pdr.canStart && backend.startWithSavedOrDefaultStepLength()) wantTracking = false
            PdrPhase.PAUSED -> Unit
        }
    }
    LaunchedEffect(welcome) { if (welcome != null) { delay(5_000); welcome = null } }

    // ----- Outdoor route -----
    // MB: walk to the entrance closest to you (graph exits of floor 1), other buildings: their centre
    val outdoorTarget: GeoPoint = remember(place, snap.floors, lastOutdoorM == null) {
        if (!place.hasIndoorMap) return@remember place.point
        val doors = snap.floors.firstOrNull { it.floorId == "MB-1" }?.nodes?.filter { it.type == "exit" || it.type == "entrance" }.orEmpty()
        val from = lastOutdoorM ?: fix?.eastNorthM
        val door = if (from != null) doors.minByOrNull { it.posM.distanceTo(from) } else null
        door?.let { snap.geoOrigin.toWgs84(it.posM) }?.let { GeoPoint(it.latitude, it.longitude) } ?: place.point
    }
    var route by remember { mutableStateOf<WalkRoute?>(null) }
    var lastRouteAt by remember { mutableLongStateOf(0L) }
    val progress = remember(route, me) { val r = route; if (r != null && me != null) WalkProgress.of(r, me) else null }
    val farAway = me != null && Geo.distanceM(me, outdoorTarget) > FAR_FROM_CAMPUS_M
    val needRoute = mode == GoMode.Outdoor && me != null && !farAway && (route == null || (progress?.offRouteM ?: 0.0) > REROUTE_OFF_ROUTE_M)
    LaunchedEffect(needRoute, outdoorTarget) {
        val from = me ?: return@LaunchedEffect
        if (!needRoute) return@LaunchedEffect
        val wait = REROUTE_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRouteAt)
        if (route != null && wait > 0) delay(wait)
        route = WalkingRouter.route(from, outdoorTarget)
        lastRouteAt = System.currentTimeMillis()
    }
    val distToTarget = me?.let { Geo.distanceM(it, outdoorTarget) }
    val arrivedOutside = !place.hasIndoorMap && distToTarget != null &&
        (distToTarget <= maxOf(ARRIVED_OUTDOOR_M, place.building.radiusM * 0.6) || (progress?.remainingM ?: Double.MAX_VALUE) <= ARRIVED_OUTDOOR_M)

    var topPx by remember { mutableFloatStateOf(0f) }
    var bottomPx by remember { mutableFloatStateOf(0f) }
    val pad = with(density) { PaddingPx(32.dp.roundToPx(), topPx.toInt() + 40.dp.roundToPx(), 32.dp.roundToPx(), bottomPx.toInt() + 24.dp.roundToPx()) }

    Box(Modifier.fillMaxSize().background(c.surface)) {
        // ===== The map: street map outside, floor plans inside — morphs like zooming into the building =====
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                if (targetState == GoMode.Indoor) {
                    (fadeIn(tween(520)) + scaleIn(tween(620), initialScale = 0.82f)) togetherWith (fadeOut(tween(380)) + scaleOut(tween(620), targetScale = 1.35f))
                } else {
                    (fadeIn(tween(520)) + scaleIn(tween(620), initialScale = 1.25f)) togetherWith (fadeOut(tween(380)) + scaleOut(tween(620), targetScale = 0.85f))
                }
            },
            label = "goMode",
        ) { m ->
            when (m) {
                GoMode.Outdoor -> OutdoorMap(
                    route = route, me = me,
                    meBearingDeg = fix?.trueNorthBearingRad?.let { Math.toDegrees(it) },
                    destination = outdoorTarget,
                    camera = if (me == null && camera == OutdoorCamera.Follow) OutdoorCamera.Overview else camera,
                    followBearingDeg = progress?.bearingDeg,
                    padding = pad,
                    onUserMovedMap = { if (camera != OutdoorCamera.Free) camera = OutdoorCamera.Free },
                )
                GoMode.Indoor -> IndoorCanvas(snap, indoorCamera, c, Modifier.fillMaxSize(), bottomInsetPx = bottomPx)
            }
        }

        // ===== Top: back + destination, or the big turn-by-turn card =====
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp)
                .onSizeChanged { topPx = it.height.toFloat() },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val indoorRoute = snap.activeRoute
            when {
                mode == GoMode.Indoor && indoorRoute != null && snap.pdr.phase == PdrPhase.TRACKING ->
                    ManeuverCard(indoorIcon(indoorRoute), indoorDistance(indoorRoute), instructionText(indoorRoute), null, arrived = indoorRoute.isArrived)
                mode == GoMode.Outdoor && started && progress != null && !arrivedOutside -> {
                    val next = progress.nextStep
                    val then = next?.let { n -> route?.let { r -> r.steps.indexOf(n).takeIf { it >= 0 }?.let { r.steps.getOrNull(it + 1) } } }
                    ManeuverCard(
                        icon = maneuverIcon(next?.maneuver ?: Maneuver.STRAIGHT),
                        distance = formatDistance(progress.distanceToNextStepM),
                        text = next?.let { stepText(it, place) } ?: stringResource(R.string.nav_follow),
                        then = then?.takeIf { progress.distanceToNextStepM < 120 }?.let { stepText(it, place) },
                        arrived = false,
                    )
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.shadow(8.dp, CircleShape).clip(CircleShape)) {
                        CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), { nav.back() }, filled = false)
                    }
                    Spacer(Modifier.width(10.dp))
                    DestinationChip(place, destFloor, Modifier.weight(1f))
                }
            }
            if (mode == GoMode.Indoor) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FloorBadge(snap) { askFloor = true }
                    if (snap.pdr.tilted) HintChip(stringResource(R.string.indoor_tilt))
                    else if (!snap.pdr.headingValid && snap.pdr.phase != PdrPhase.IDLE) HintChip(stringResource(R.string.indoor_heading_wait))
                }
            }
        }
        if (mode == GoMode.Indoor) {
            FloorPicker(
                floors = snap.floors.map { it.floorId },
                active = snap.activeFloorId,
                onPick = { haptics.tick(); backend.switchActiveFloor(it) },
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset { IntOffset(0, topPx.toInt() + 8.dp.roundToPx()) },
            )
        }

        // ===== Welcome to the building =====
        AnimatedVisibility(
            welcome != null,
            enter = slideInVertically(tween(420)) { -it } + fadeIn(),
            exit = slideOutVertically(tween(320)) { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(12.dp),
        ) {
            welcome?.let { WelcomeCard(place, it, askingFloor = askFloor) }
        }

        // ===== Bottom: map buttons + trip sheet =====
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height.toFloat() }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (mode == GoMode.Indoor) {
                    RoundButton(Icons.Rounded.Explore, stringResource(R.string.indoor_heading_up), active = indoorCamera.headUp) { haptics.tick(); indoorCamera.headUp = !indoorCamera.headUp }
                }
                ThemeToggleButton()
                RoundButton(Icons.Rounded.MyLocation, stringResource(R.string.indoor_recenter), active = mode == GoMode.Outdoor && camera == OutdoorCamera.Follow) {
                    haptics.tick()
                    if (mode == GoMode.Indoor) indoorCamera.recenter()
                    else camera = if (started && me != null) OutdoorCamera.Follow else OutdoorCamera.Overview
                }
            }
            TripSheet {
                JourneyStrip(place, mode, arrived = arrivedOutside || snap.activeRoute?.isArrived == true)
                Spacer(Modifier.height(14.dp))
                when (mode) {
                    GoMode.Outdoor -> OutdoorSheet(
                        place = place, destFloor = destFloor, route = route, progress = progress,
                        hasFix = me != null, locationGranted = snap.locationPermissionGranted,
                        started = started, arrived = arrivedOutside,
                        farAwayM = if (farAway) distToTarget else null,
                        onOpenMaps = { openInMapsApp(context, outdoorTarget, place.title) },
                        onAllow = ::askPermissions,
                        onStart = { haptics.confirm(); started = true; camera = OutdoorCamera.Follow },
                        onInside = { enterIndoor(manual = true) },
                        onEnd = { nav.back() },
                    )
                    GoMode.Indoor -> IndoorSheet(
                        snap = snap, backend = backend, place = place,
                        onAskSteps = ::askPermissions,
                        onSetPosition = { haptics.tick(); pickPosition = true },
                        onOutside = {
                            haptics.tick()
                            autoIndoor = false
                            backend.stopToSetup()
                            wantTracking = false
                            mode = GoMode.Outdoor
                            camera = OutdoorCamera.Overview
                        },
                        onEnd = { nav.back() },
                    )
                }
            }
        }
    }

    if (pickPosition && mode == GoMode.Indoor) {
        val floorFirst = snap.userFloorId
        RoomPickerSheet(
            title = stringResource(R.string.go_position_title),
            items = remember(snap.destinations, floorFirst) {
                snap.destinations
                    .map { RoomItem(it.key.floorId, it.key.nodeId, it.label, it.name) }
                    .sortedBy { if (it.floorId == floorFirst) 0 else 1 }
            },
            onPick = { item ->
                haptics.confirm()
                // The picked spot becomes the reference; every detected step moves you from there
                backend.setStartPose(item.floorId, item.nodeId)
                wantTracking = true
                indoorCamera.recenter()
                pickPosition = false; askFloor = false; welcome = null
            },
            onDismiss = { pickPosition = false },
        )
    }

    if (askFloor && mode == GoMode.Indoor) {
        FloorAskSheet(
            floors = snap.floors.map { it.floorId },
            current = snap.userFloorId,
            altitude = snap.altitudeFloor,
            onPick = { id -> haptics.tick(); backend.confirmUserFloor(id); askFloor = false; welcome = null },
            onDismiss = { askFloor = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets
// ---------------------------------------------------------------------------------------------

@Composable
private fun TripSheet(content: @Composable () -> Unit) {
    val c = Campus.colors
    Column(
        Modifier.fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(topStart = Tokens.RadiusCard, topEnd = Tokens.RadiusCard))
            .clip(RoundedCornerShape(topStart = Tokens.RadiusCard, topEnd = Tokens.RadiusCard))
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 10.dp, bottom = 18.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(c.border))
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun OutdoorSheet(
    place: Place,
    destFloor: Int?,
    route: WalkRoute?,
    progress: WalkProgress?,
    hasFix: Boolean,
    locationGranted: Boolean,
    started: Boolean,
    arrived: Boolean,
    farAwayM: Double?,
    onOpenMaps: () -> Unit,
    onAllow: () -> Unit,
    onStart: () -> Unit,
    onInside: () -> Unit,
    onEnd: () -> Unit,
) {
    val c = Campus.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            arrived -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.go_arrived_building, place.building.name), style = MaterialTheme.typography.titleLarge, color = c.text)
                        roomHint(place, destFloor)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textMuted) }
                    }
                }
                Text(stringResource(R.string.go_no_indoor_map), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                PrimaryButton(stringResource(R.string.go_end), onEnd)
            }
            !locationGranted -> {
                Text(stringResource(R.string.go_location_needed), style = MaterialTheme.typography.titleMedium, color = c.text)
                PrimaryButton(stringResource(R.string.indoor_allow), onAllow)
                if (place.hasIndoorMap) Pill(stringResource(R.string.go_inside), onInside, style = PillStyle.Tonal, modifier = Modifier.fillMaxWidth())
            }
            farAwayM != null -> {
                Text(stringResource(R.string.go_far, formatDistance(farAwayM)), style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(stringResource(R.string.go_far_hint), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                PrimaryButton(stringResource(R.string.go_open_maps), onOpenMaps)
                if (place.hasIndoorMap) Pill(stringResource(R.string.go_inside), onInside, style = PillStyle.Tonal, modifier = Modifier.fillMaxWidth())
            }
            !hasFix || route == null -> {
                Text(stringResource(R.string.go_locating), style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(place.building.name, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                if (place.hasIndoorMap) Pill(stringResource(R.string.go_inside), onInside, style = PillStyle.Tonal, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                val remaining = progress?.remainingM ?: route.lengthM
                val seconds = remaining / WalkingRouter.WALK_SPEED_MPS
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        stringResource(R.string.go_minutes, ((seconds / 60).roundToInt()).coerceAtLeast(1)),
                        style = MaterialTheme.typography.headlineMedium, color = c.accent, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatDistance(remaining) + "  ·  " + stringResource(R.string.go_eta, etaText(seconds)),
                        style = MaterialTheme.typography.titleSmall, color = c.textMuted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (route.approximate) Text(stringResource(R.string.go_approximate), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                if (!started) {
                    PrimaryButton(stringResource(R.string.go_start), onStart)
                    if (place.hasIndoorMap) Pill(stringResource(R.string.go_inside), onInside, style = PillStyle.Tonal, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(stringResource(R.string.go_end), onEnd, style = PillStyle.Tonal)
                        if (place.hasIndoorMap) Pill(stringResource(R.string.go_inside), onInside, style = PillStyle.Outline)
                    }
                }
            }
        }
    }
}

@Composable
private fun IndoorSheet(
    snap: NavigationSnapshot,
    backend: IndoorNavigationBackend,
    place: Place,
    onAskSteps: () -> Unit,
    onSetPosition: () -> Unit,
    onOutside: () -> Unit,
    onEnd: () -> Unit,
) {
    val c = Campus.colors
    val s = snap.pdr
    val route = snap.activeRoute
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            route?.isArrived == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.nav_arrived, route.destinationLabel), style = MaterialTheme.typography.titleLarge, color = c.text)
            }
            s.activityPermissionNeeded && !s.activityPermissionGranted -> Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.RadiusControl)).background(c.accentSoft).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.indoor_steps_permission), style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Pill(stringResource(R.string.indoor_allow), onAskSteps)
            }
            s.phase == PdrPhase.SETTLING -> Text(stringResource(R.string.indoor_settling), style = MaterialTheme.typography.titleMedium, color = c.text)
            s.phase == PdrPhase.IDLE || s.phase == PdrPhase.CALIBRATING -> {
                Text(place.title, style = MaterialTheme.typography.titleLarge, color = c.text)
                PrimaryButton(
                    stringResource(if (s.canStart || s.headingValid) R.string.go_indoor_start else R.string.indoor_heading_wait),
                    { backend.startWithSavedOrDefaultStepLength() }, enabled = s.canStart,
                )
            }
            s.phase == PdrPhase.PAUSED -> {
                Text(stringResource(R.string.indoor_paused), style = MaterialTheme.typography.titleMedium, color = c.text)
                // A pause resets the compass frame: resume starts a new trace from the last reference point (start room or floor landing)
                PrimaryButton(
                    stringResource(if (s.headingValid && !s.tilted) R.string.indoor_resume else R.string.indoor_heading_wait),
                    backend::resumeFromPause, enabled = s.headingValid && !s.tilted,
                )
            }
            route != null -> {
                val seconds = route.totalDistanceM / INDOOR_WALK_SPEED_MPS
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        stringResource(R.string.go_minutes, ((seconds / 60).roundToInt()).coerceAtLeast(1)),
                        style = MaterialTheme.typography.headlineMedium, color = c.accent, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatDistance(route.totalDistanceM) + "  ·  " + route.destinationLabel,
                        style = MaterialTheme.typography.titleSmall, color = c.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            else -> Text(place.title, style = MaterialTheme.typography.titleLarge, color = c.text)
        }
        if (route?.isArrived != true) PositionRow(snap, onSetPosition)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(stringResource(R.string.go_end), onEnd, style = PillStyle.Tonal)
            val suggested = snap.suggestedTransfer
            if (s.phase == PdrPhase.TRACKING && suggested != null) {
                Pill(
                    stringResource(R.string.indoor_floor, floorNumber(suggested.toFloorId)),
                    { backend.confirmUserFloor(suggested.toFloorId, suggested.verticalGroupId) },
                )
            }
            Pill(stringResource(R.string.go_outside), onOutside, style = PillStyle.Outline)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

/** "Ton point de départ : MB-2.255 · Étage 2 — Changer": the reference the pedometer counts from. */
@Composable
private fun PositionRow(snap: NavigationSnapshot, onClick: () -> Unit) {
    val c = Campus.colors
    val start = snap.startPose?.let { sp -> snap.floors.firstOrNull { it.floorId == sp.floorId }?.node(sp.nodeId)?.let { it to sp.floorId } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.RadiusControl)).background(c.surface).pressable(onClick, 0.98f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PersonPinCircle, null, tint = c.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.go_position_label), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            Text(
                start?.let { (node, floorId) -> listOf(node.label.ifBlank { node.name }, stringResource(R.string.indoor_floor, floorNumber(floorId))).joinToString(" · ") }
                    ?: stringResource(R.string.go_position_hint),
                style = MaterialTheme.typography.titleSmall, color = if (start != null) c.text else c.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.go_position_change), style = MaterialTheme.typography.labelLarge, color = c.accent)
    }
}

/** Google-Maps-like instruction card, in the university colour. */
@Composable
private fun ManeuverCard(icon: ImageVector, distance: String?, text: String, then: String?, arrived: Boolean) {
    val c = Campus.colors
    Column(Modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))) {
        Row(
            Modifier.fillMaxWidth().background(c.accent).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (distance != null && !arrived) {
                    Text(distance, style = MaterialTheme.typography.headlineSmall, color = c.onAccent, fontWeight = FontWeight.Bold)
                }
                Text(text, style = MaterialTheme.typography.titleMedium, color = c.onAccent.copy(alpha = 0.92f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (then != null) {
            Text(
                stringResource(R.string.go_then, then),
                style = MaterialTheme.typography.labelLarge, color = c.onAccent,
                modifier = Modifier.fillMaxWidth().background(lerpDark(c.accent)).padding(horizontal = 18.dp, vertical = 9.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun lerpDark(color: Color) = Color(color.red * 0.78f, color.green * 0.78f, color.blue * 0.78f, 1f)

@Composable
private fun DestinationChip(place: Place, floor: Int?, modifier: Modifier = Modifier) {
    val c = Campus.colors
    Row(
        modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.bg).padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = c.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.go_to, place.title), style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(place.building.name, floor?.let { stringResource(R.string.indoor_floor, it.toString()) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Dehors → Bâtiment → Étage → Salle : where you are in the trip. */
@Composable
private fun JourneyStrip(place: Place, mode: GoMode, arrived: Boolean) {
    val c = Campus.colors
    val stages = buildList {
        add(Icons.AutoMirrored.Rounded.DirectionsWalk to stringResource(R.string.go_stage_outside))
        add(Icons.Rounded.Apartment to place.building.code)
        if (place.roomLabel != null) add(Icons.Rounded.SportsScore to place.roomLabel.substringAfter('-'))
    }
    val current = when {
        arrived -> stages.lastIndex
        mode == GoMode.Indoor -> 1
        else -> 0
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        stages.forEachIndexed { i, (icon, label) ->
            val done = i < current
            val on = i == current
            Row(
                Modifier.clip(CircleShape).background(if (on) c.text else c.surface).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (done) Icons.Rounded.CheckCircle else icon, null, tint = if (on) c.bg else if (done) c.accent else c.textMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.bg else c.text, maxLines = 1)
            }
            if (i < stages.lastIndex) {
                Box(Modifier.weight(1f).padding(horizontal = 4.dp).height(2.dp).clip(CircleShape).background(if (i < current) c.accent else c.border))
            }
        }
    }
}

@Composable
private fun FloorBadge(snap: NavigationSnapshot, onClick: () -> Unit) {
    val c = Campus.colors
    val floor = snap.userFloorId?.let(::floorNumber) ?: "?"
    val alt = snap.altitudeFloor
    Row(
        Modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.bg).pressable(onClick).padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Height, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.indoor_floor, floor), style = MaterialTheme.typography.labelLarge, color = c.text)
        val h = alt?.heightAboveStreetM
        if (h != null && alt.source == AltitudeFloorSource.BAROMETER_STREET_REFERENCE) {
            Spacer(Modifier.width(6.dp))
            Text(String.format(Locale.getDefault(), "%+.0f m", h), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
    }
}

@Composable
private fun HintChip(text: String) {
    val c = Campus.colors
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = c.bg, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.text).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun WelcomeCard(place: Place, estimate: AltitudeFloorEstimate, askingFloor: Boolean) {
    val c = Campus.colors
    Row(
        Modifier.fillMaxWidth().shadow(18.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.text).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Apartment, null, tint = c.onAccent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.go_welcome, place.building.name), style = MaterialTheme.typography.titleMedium, color = c.bg)
            Text(
                when {
                    askingFloor -> stringResource(R.string.go_floor_ask)
                    estimate.source == AltitudeFloorSource.BAROMETER_STREET_REFERENCE -> stringResource(R.string.go_floor_altitude, estimate.floorNumber)
                    else -> stringResource(R.string.go_floor_entrance, estimate.floorNumber)
                },
                style = MaterialTheme.typography.bodyMedium, color = c.bg.copy(alpha = 0.72f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloorAskSheet(floors: List<String>, current: String?, altitude: AltitudeFloorEstimate?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Campus.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bg,
        shape = RoundedCornerShape(topStart = Tokens.RadiusCard, topEnd = Tokens.RadiusCard),
    ) {
        Column(Modifier.padding(horizontal = Tokens.Gutter).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.go_floor_ask), style = MaterialTheme.typography.titleLarge, color = c.text)
            altitude?.heightAboveStreetM?.let {
                Text(stringResource(R.string.go_altitude_hint, String.format(Locale.getDefault(), "%.1f", it), altitude.floorNumber), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                floors.sortedBy { floorNumber(it).toIntOrNull() ?: 0 }.forEach { id ->
                    val on = id == current
                    val suggested = altitude?.floorNumber?.toString() == floorNumber(id)
                    Box(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(Tokens.RadiusControl))
                            .background(if (on) c.text else c.surface)
                            .then(if (suggested && !on) Modifier.border(2.dp, c.accent, RoundedCornerShape(Tokens.RadiusControl)) else Modifier)
                            .pressable({ onPick(id) }, 0.92f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(floorNumber(id), style = MaterialTheme.typography.titleMedium, color = if (on) c.bg else c.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    val c = Campus.colors
    Box(
        Modifier.size(48.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(if (active) c.text else c.bg).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (active) c.bg else c.text) }
}

@Composable
private fun PlaceNotFound(query: String) {
    val c = Campus.colors
    val nav = LocalNavigator.current
    Column(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).padding(Tokens.Gutter),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), { nav.back() })
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Rounded.Map, null, tint = c.textMuted, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.go_not_found, query), style = MaterialTheme.typography.headlineSmall, color = c.text)
        PrimaryButton(stringResource(R.string.map_title), { nav.map() })
    }
}

// ---------------------------------------------------------------------------------------------
// Wording
// ---------------------------------------------------------------------------------------------

private fun maneuverIcon(m: Maneuver): ImageVector = when (m) {
    Maneuver.LEFT -> Icons.Rounded.TurnLeft
    Maneuver.RIGHT -> Icons.Rounded.TurnRight
    Maneuver.SLIGHT_LEFT -> Icons.Rounded.TurnSlightLeft
    Maneuver.SLIGHT_RIGHT -> Icons.Rounded.TurnSlightRight
    Maneuver.SHARP_LEFT -> Icons.Rounded.TurnSharpLeft
    Maneuver.SHARP_RIGHT -> Icons.Rounded.TurnSharpRight
    Maneuver.UTURN -> Icons.Rounded.UTurnLeft
    Maneuver.ARRIVE -> Icons.Rounded.SportsScore
    Maneuver.DEPART -> Icons.AutoMirrored.Rounded.DirectionsWalk
    Maneuver.STRAIGHT -> Icons.Rounded.Straight
}

@Composable
private fun stepText(step: WalkStep, place: Place): String {
    if (step.maneuver == Maneuver.ARRIVE) return stringResource(R.string.go_m_arrive, place.building.name)
    val verb = stringResource(
        when (step.maneuver) {
            Maneuver.DEPART -> R.string.go_m_depart
            Maneuver.LEFT -> R.string.go_m_left
            Maneuver.RIGHT -> R.string.go_m_right
            Maneuver.SLIGHT_LEFT -> R.string.go_m_slight_left
            Maneuver.SLIGHT_RIGHT -> R.string.go_m_slight_right
            Maneuver.SHARP_LEFT -> R.string.go_m_sharp_left
            Maneuver.SHARP_RIGHT -> R.string.go_m_sharp_right
            Maneuver.UTURN -> R.string.nav_uturn
            else -> R.string.go_m_straight
        },
    )
    return if (step.street.isNotBlank()) stringResource(R.string.go_on_street, verb, step.street) else verb
}

private fun indoorIcon(route: NavigationRoute): ImageVector {
    if (route.isArrived) return Icons.Rounded.SportsScore
    when (route.rejoin) {
        RejoinHint.U_TURN -> return Icons.Rounded.UTurnLeft
        RejoinHint.TURN_LEFT -> return Icons.Rounded.TurnLeft
        RejoinHint.TURN_RIGHT -> return Icons.Rounded.TurnRight
        null -> Unit
    }
    val first = route.steps.firstOrNull()
    val next = if (first is RouteStep.Walk) route.steps.getOrNull(1) else first
    return when (next) {
        is RouteStep.Turn -> if (next.left) Icons.Rounded.TurnLeft else Icons.Rounded.TurnRight
        is RouteStep.FloorTransfer -> if (next.viaElevator) Icons.Rounded.Elevator else Icons.Rounded.Stairs
        is RouteStep.Arrive -> Icons.Rounded.SportsScore
        else -> Icons.Rounded.Straight
    }
}

private fun indoorDistance(route: NavigationRoute): String? =
    (route.steps.firstOrNull() as? RouteStep.Walk)?.let { formatDistance(it.distanceM) }

@Composable
private fun roomHint(place: Place, floor: Int?): String? {
    val room = place.roomLabel ?: return null
    return if (floor != null) stringResource(R.string.go_room_floor, room, floor) else room
}

private fun formatDistance(m: Double): String = when {
    m < 100 -> "${(m / 5).roundToInt() * 5} m"
    m < 1000 -> "${(m / 10).roundToInt() * 10} m"
    else -> String.format(Locale.getDefault(), "%.1f km", m / 1000)
}

/** Long trips (bus, metro, car): hand over to the phone's Maps app, it ends at the building door. */
private fun openInMapsApp(context: android.content.Context, to: GeoPoint, label: String) {
    val uri = android.net.Uri.parse("geo:${to.lat},${to.lng}?q=${to.lat},${to.lng}(" + android.net.Uri.encode(label) + ")")
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
}

private fun etaText(seconds: Double): String =
    LocalTime.now().plusSeconds(seconds.toLong()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
