import Foundation
import Observation

// 1:1 port of com.hackdecouverte.backend.IndoorNavigationBackend: the single facade the screens use.
// It publishes one `NavigationSnapshot` (Android: StateFlow) on the main thread.

enum FacingOption: Hashable {
    case autoTrueNorth
    case neighbor(String)
    case cardinal(Int)
}

struct SuggestedFloorTransfer: Hashable {
    let fromFloorId: String
    let toFloorId: String
    let viaLabel: String
    let verticalGroupId: String?
    let viaElevator: Bool
    let fromActiveRoute: Bool
}

struct NearbyVerticalOptions { let connectorNode: FloorNode; let upFloorId: String?; let downFloorId: String? }

struct NavigationSnapshot {
    var pdr = PdrState()
    var geoOrigin: GeoOrigin = .mbElevatorCore
    var osmSurroundings: OsmSurroundings?
    var floors: [FloorPlan] = []
    /// Floor displayed on the map.
    var activeFloorId: String?
    var activeFloor: FloorPlan?
    var activeFloorVector: FloorVectorPlan?
    var loadedFloorVectors: [String: FloorVectorPlan] = [:]
    /// Floor where the user physically is.
    var userFloorId: String?
    var userFloor: FloorPlan?
    var isViewingUserFloor = true
    var startPose: NodeKey?
    var startNodeId: String?
    var usingGpsStart = false
    var hasExplicitStart = false
    var facing: FacingOption = .autoTrueNorth
    var anchor: MapAnchor?
    var mapPositionM: PointM = .zero
    var mapHeadingRad = 0.0
    var barometerAvailable = false
    var floorDeltaAltitudeM = 0.0
    var inVerticalTransit = false
    var suggestedTransfer: SuggestedFloorTransfer?
    var nearbyVertical: NearbyVerticalOptions?
    var locationPermissionGranted = false
    var latestGpsFix: GpsFix?
    var destination: NodeKey?
    var accessibleOnly = false
    var activeRoute: NavigationRoute?
    var destinations: [DestinationItem] = []
    var savedStepLengthM: Double?
    /// Stride measured by the iPhone's pedometer (last 7 days), used until the user calibrates.
    var personalStrideM: Double?
    /// Last corridor junction that re-anchored the dot (developer screen).
    var lastJunctionId: String?
    var altitudeFloor: AltitudeFloorEstimate?
    var hasStreetPressureReference = false
}

@MainActor
@Observable
final class IndoorNavigationBackend {
    private static let prefsStepLength = "pdr_last_step_length"
    private static let prefsCadence = "pdr_last_cadence_hz"
    private static let settlingDelayNs: UInt64 = 2_000_000_000
    private static let defaultFloorId = "MB-2"
    private static let maxCampusRadiusM = 350.0
    private static let nearConnectorBaroRadiusM = 7.0
    private static let highPrecisionOutdoorGpsM = 4.5
    private static let streetReferenceMaxAccuracyM = 15.0
    private static let streetReferenceMinDistM = 45.0
    private static let streetReferenceMaxDistM = 160.0
    private static let entranceSnapMinDistM = 15.0
    /// iPhone barometers report ~1 Hz (Android 10 Hz): longer vertical-speed window.
    private static let iosBaroVelocityWindowNs: Int64 = 3_500_000_000

    private(set) var snapshot = NavigationSnapshot()
    /// Simulated walk along the route (demo / screenshots / simulator without motion sensors).
    private(set) var isSimulating = false

    @ObservationIgnored let tracker: PdrTracker
    @ObservationIgnored private let floorTracker = FloorTransitionTracker(velocityWindowNs: iosBaroVelocityWindowNs)
    @ObservationIgnored private let altitudeFloors = AltitudeFloorEstimator()
    @ObservationIgnored private var latestPressureHpa: Double?
    @ObservationIgnored private var latestPressureNs = Int64.min
    @ObservationIgnored private var adapter: PdrSensorAdapter!
    @ObservationIgnored private var gpsSource: GpsLocationSource!

    @ObservationIgnored private var savedStepLengthM: Double? = UserDefaults.standard.object(forKey: prefsStepLength) as? Double
    @ObservationIgnored private var savedCadenceHz: Double? = UserDefaults.standard.object(forKey: prefsCadence) as? Double
    @ObservationIgnored private var geoOrigin: GeoOrigin = .mbElevatorCore
    @ObservationIgnored private var osmSurroundings: OsmSurroundings?
    @ObservationIgnored private var floorVectors: [String: FloorVectorPlan] = [:]
    @ObservationIgnored private var loadingVectors = Set<String>()
    @ObservationIgnored private var buildingGraph: BuildingGraph
    @ObservationIgnored private var userFloorId: String?
    @ObservationIgnored private var viewedFloorId: String?
    @ObservationIgnored private var startPose: NodeKey?
    @ObservationIgnored private var usingGpsStart = false
    @ObservationIgnored private var currentFloorOriginM: PointM = .zero
    @ObservationIgnored private var facing: FacingOption = .autoTrueNorth
    @ObservationIgnored private var anchor: MapAnchor? = MapAnchor(originM: .zero, psiRad: 0)
    @ObservationIgnored private var destination: NodeKey?
    @ObservationIgnored private var accessibleOnly = false
    @ObservationIgnored private var latestGpsFix: GpsFix?
    @ObservationIgnored private var floorDeltaAltitudeM = 0.0
    @ObservationIgnored private var inVerticalTransit = false
    @ObservationIgnored private var lastPublishedAltM = 0.0
    @ObservationIgnored private var locationPermissionGranted = false
    @ObservationIgnored private var registrationFailed = false
    @ObservationIgnored private var settlingTask: Task<Void, Never>?
    @ObservationIgnored private var cachedRouteKey: RouteCacheKey?
    @ObservationIgnored private var cachedRoute: NavigationRoute?
    @ObservationIgnored private var lastPublishNs: Int64 = 0
    @ObservationIgnored private var publishScheduled = false
    @ObservationIgnored private var lastPublishedSteps = -1
    @ObservationIgnored private var lastPublishedPhase: PdrPhase = .idle
    @ObservationIgnored private var foregroundUsers = 0
    @ObservationIgnored private let junctions = JunctionMatcher()
    @ObservationIgnored private var personalStrideM: Double?
    @ObservationIgnored private var junctionCorrections = 0
    @ObservationIgnored private var simTimer: Timer?
    @ObservationIgnored private var simYaw = 0.0
    @ObservationIgnored private var simTicks = 0
    @ObservationIgnored private var simTransferTicks = 0

    private struct RouteCacheKey: Hashable {
        let floorId: String?, stepCount: Int, posBucketX: Int, posBucketY: Int, headingBucket: Int, dest: NodeKey?, accessibleOnly: Bool
    }

    init() {
        tracker = PdrTracker(clock: NavClock.nowNs, antiShakeEnabled: true, corridorAssistInitial: true)
        tracker.useTrueNorthReference = true
        buildingGraph = IndoorAssets.shared.graph
        userFloorId = buildingGraph.floor(Self.defaultFloorId)?.floorId ?? buildingGraph.floors.first?.floorId
        viewedFloorId = userFloorId

        adapter = PdrSensorAdapter(tracker: tracker, sourceProvider: { [unowned self] in tracker.state.stepSource },
                                   onPressureSample: { [weak self] t, p in MainActor.assumeIsolated { self?.handlePressureSample(t, p) } })
        gpsSource = GpsLocationSource(
            onFix: { [weak self] fix in MainActor.assumeIsolated { self?.handleGpsFix(fix) } },
            onHeading: { [weak self] t, az, high in MainActor.assumeIsolated { self?.tracker.onTrueNorthAzimuth(t, az, highConfidence: high) } },
            onAuthorization: { [weak self] granted in MainActor.assumeIsolated { self?.onLocationPermissionResult(granted) } })
        locationPermissionGranted = gpsSource.hasLocationPermission

        pushSensorStatus()
        ensureUsableStepSource()
        recomputeAnchor()

        tracker.corridorConstraint = { [weak self] localPos, localTheta in
            guard let self else { return nil }
            return MainActor.assumeIsolated {
                guard let plan = self.userFloorId.flatMap(self.buildingGraph.floor), let anc = self.anchor, !self.inVerticalTransit,
                      let m = plan.matchCorridor(anc.toMap(localPos), anc.mapHeading(localTheta)) else { return nil }
                return CorridorConstraintResult(projectedLocalPos: anc.fromMap(m.projectedPointM), alignedLocalTheta: m.alignedHeadingRad.map(anc.localHeading))
            }
        }
        tracker.onStateChange = { [weak self] s in
            MainActor.assumeIsolated {
                self?.persistGaitIfUpdated(s)
                self?.matchJunction(s)
                self?.schedulePublish()
            }
        }

        let origin = geoOrigin
        Task { [weak self] in
            let osm = await Task.detached(priority: .utility) { OsmNetworkLoader.loadInitial(origin) }.value
            self?.osmSurroundings = osm
            self?.publish()
        }
        loadVectorPlansInBackground()
        publish()
    }

    // MARK: data loading

    private func loadVectorPlansInBackground() {
        let priority = viewedFloorId ?? userFloorId
        let ordered = buildingGraph.floors.sorted { ($0.floorId == priority ? 0 : 1) < ($1.floorId == priority ? 0 : 1) }
        for plan in ordered {
            guard let asset = plan.svgAsset, floorVectors[plan.floorId] == nil, !loadingVectors.contains(plan.floorId) else { continue }
            loadingVectors.insert(plan.floorId)
            let floorId = plan.floorId
            Task { [weak self] in
                let parsed = await Task.detached(priority: .userInitiated) {
                    IndoorAssets.shared.svgText(asset).flatMap { try? FloorVectorPlan.parseSvg(floorId, $0) }
                }.value
                guard let self else { return }
                self.loadingVectors.remove(floorId)
                if let parsed { self.floorVectors[floorId] = parsed; self.publish() }
            }
        }
    }

    func refreshOsmSurroundingsFromNetwork(radiusM: Double = 280) {
        let origin = geoOrigin
        Task { if let fresh = await OsmNetworkLoader.fetchLive(origin, radiusM: radiusM) { osmSurroundings = fresh; publish() } }
    }

    // MARK: start pose / floors

    /// Anchors the user on a room, entrance, stair or elevator. Manual choice always wins over coarse indoor GPS.
    func setStartPose(_ floorId: String, _ nodeId: String, facing facingOption: FacingOption = .autoTrueNorth) {
        guard let plan = buildingGraph.floor(floorId), let node = plan.node(nodeId) else { return }
        startPose = NodeKey(floorId: floorId, nodeId: nodeId)
        usingGpsStart = false
        userFloorId = floorId
        viewedFloorId = floorId
        currentFloorOriginM = node.posM
        switch facingOption {
        case .autoTrueNorth: facing = .autoTrueNorth
        case .neighbor(let id): facing = plan.neighbors(nodeId).contains { $0.id == id } ? facingOption : .autoTrueNorth
        case .cardinal: facing = facingOption
        }
        resetVertical()
        recomputeAnchor()
        let phase = tracker.state.phase
        if phase == .tracking || phase == .paused { if !tracker.resetTrace() { tracker.reanchorFloorOriginKeepHeading() } }
        invalidateRouteAndPublish()
    }

    /// Explicit outdoor GPS origin (only when the user asks for it).
    @discardableResult func useGpsAsStartOrigin() -> Bool {
        guard let fix = latestGpsFix, fix.eastNorthM.norm() <= Self.maxCampusRadiusM else { return false }
        startPose = nil
        usingGpsStart = true
        currentFloorOriginM = fix.eastNorthM
        viewedFloorId = userFloorId
        facing = .autoTrueNorth
        resetVertical()
        recomputeAnchor()
        let phase = tracker.state.phase
        if phase == .tracking || phase == .paused { if !tracker.resetTrace() { tracker.reanchorFloorOriginKeepHeading() } }
        invalidateRouteAndPublish()
        return true
    }

    func setFacing(_ f: FacingOption) { facing = f; recomputeAnchor(); invalidateRouteAndPublish() }

    func clearStartPose() {
        startPose = nil; usingGpsStart = false; currentFloorOriginM = .zero; facing = .autoTrueNorth
        recomputeAnchor(); invalidateRouteAndPublish()
    }

    /// Switches the displayed floor. Before tracking (no start room), it also sets the user's floor.
    func switchActiveFloor(_ floorId: String, entryNodeId: String? = nil, facing f: FacingOption = .autoTrueNorth) {
        guard buildingGraph.floor(floorId) != nil else { return }
        if let entryNodeId { setStartPose(floorId, entryNodeId, facing: f); return }
        viewedFloorId = floorId
        let phase = tracker.state.phase
        if phase != .tracking && phase != .paused && startPose == nil { userFloorId = floorId; recomputeAnchor() }
        loadVectorPlansInBackground()
        invalidateRouteAndPublish()
    }

    func returnViewToUserFloor() { if let uf = userFloorId { viewedFloorId = uf; publish() } }

    /// Moves the user's physical floor (tapped "Floor 3", or a known stair/elevator shaft) and snaps to the landing.
    func confirmUserFloor(_ targetFloorId: String, explicitVerticalGroupId: String? = nil) {
        guard buildingGraph.floor(targetFloorId) != nil else { return }
        let fromFloor = userFloorId ?? targetFloorId
        let currentMapPos = anchor?.toMap(tracker.state.position) ?? currentFloorOriginM
        let landing = buildingGraph.resolveFloorTransition(fromFloor, targetFloorId, currentMapPos, activeRoute: cachedRoute, explicitVerticalGroupId: explicitVerticalGroupId)
        userFloorId = targetFloorId
        viewedFloorId = targetFloorId
        currentFloorOriginM = landing?.arrivalPosM ?? currentMapPos
        resetVertical()
        recomputeAnchor()
        tracker.reanchorFloorOriginKeepHeading()
        loadVectorPlansInBackground()
        invalidateRouteAndPublish()
    }

    /// Outdoor -> indoor hand-off: puts the user at the door closest to `nearM` on `floorId` and starts tracking.
    @discardableResult func enterBuilding(_ floorId: String, nearM: PointM?) -> Bool {
        guard let plan = buildingGraph.floor(floorId) else { return false }
        let around = nearM ?? .zero
        let doors = plan.nodes.filter { $0.type == "exit" || $0.type == "entrance" }
        var candidates = !doors.isEmpty && around.norm() > Self.entranceSnapMinDistM ? doors
            : plan.nodes.filter { $0.type != "corridor" || !$0.label.isEmpty || !$0.name.isEmpty }
        if candidates.isEmpty { candidates = plan.nodes }
        guard let start = candidates.min(by: { $0.posM.distanceTo(around) < $1.posM.distanceTo(around) }) else { return false }
        setStartPose(floorId, start.id)
        if tracker.state.phase == .idle { startWithSavedOrDefaultStepLength() }
        return true
    }

    func estimateAltitudeFloor() -> AltitudeFloorEstimate { altitudeFloors.estimate(latestPressureNs, latestPressureHpa) }

    func setDestination(_ target: NodeKey?) { destination = target; invalidateRouteAndPublish() }
    func setAccessibleOnly(_ on: Bool) { accessibleOnly = on; invalidateRouteAndPublish() }

    // MARK: tracking lifecycle

    @discardableResult func startWithStepLength(_ lengthM: Double) -> Bool {
        let cadence = savedStepLengthM.map { abs($0 - lengthM) < 0.0005 } == true ? savedCadenceHz : nil
        viewedFloorId = userFloorId
        floorTracker.onManualFloorSet()
        junctions.reset()
        let ok = tracker.startWithManualStepLength(lengthM, cadenceHz: cadence)
        publish()
        return ok
    }

    /// Measured step > the iPhone pedometer's stride > 0.74 m.
    @discardableResult func startWithSavedOrDefaultStepLength(_ defaultM: Double = 0.74) -> Bool {
        startWithStepLength(savedStepLengthM ?? personalStrideM ?? defaultM)
    }

    @discardableResult func startCalibration5m() -> Bool {
        viewedFloorId = userFloorId
        floorTracker.onManualFloorSet()
        return tracker.startCalibration()
    }

    func finishCalibration5m() {
        guard tracker.finishCalibration() else { return }
        if tracker.state.stepSource == .fallback {
            tracker.finalizeCalibration(NavClock.nowNs())
        } else {
            settlingTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: Self.settlingDelayNs)
                guard let self, !Task.isCancelled, self.tracker.state.phase == .settling else { return }
                self.tracker.finalizeCalibration(NavClock.nowNs())
            }
        }
    }

    func resetPositionToStart() {
        if let sp = startPose, let node = buildingGraph.node(sp) {
            userFloorId = sp.floorId; viewedFloorId = sp.floorId; currentFloorOriginM = node.posM
        } else {
            viewedFloorId = userFloorId
        }
        resetVertical()
        recomputeAnchor()
        if !tracker.resetTrace() { tracker.reanchorFloorOriginKeepHeading() }
        invalidateRouteAndPublish()
    }

    func resumeFromPause() {
        viewedFloorId = userFloorId
        floorTracker.onManualFloorSet()
        tracker.resumeWithNewTrace()
        invalidateRouteAndPublish()
    }

    func stopToSetup() {
        stopSimulatedWalk()
        settlingTask?.cancel(); settlingTask = nil
        inVerticalTransit = false; floorDeltaAltitudeM = 0
        tracker.endVerticalHold(commit: false)
        viewedFloorId = userFloorId
        tracker.recalibrate()
        ensureUsableStepSource()
        refreshSensorRegistrations()
        invalidateRouteAndPublish()
    }

    func toggleCorridorAssist() { tracker.setCorridorAssistEnabled(!tracker.state.corridorAssistEnabled) }

    func selectStepSource(_ source: StepSource) { if tracker.setStepSource(source) { refreshSensorRegistrations() } }

    /// Screens call this when they appear (several screens may hold the engine).
    func onForeground() {
        foregroundUsers += 1
        guard foregroundUsers == 1 else { return }
        locationPermissionGranted = gpsSource.hasLocationPermission
        pushSensorStatus()
        ensureUsableStepSource()
        tracker.onSensorFrameRestart()
        registrationFailed = !adapter.start()
        pushSensorStatus()
        gpsSource.start()
        if personalStrideM == nil { adapter.personalStride { [weak self] s in MainActor.assumeIsolated { self?.personalStrideM = s; self?.publish() } } }
        publish()
    }

    func onBackground() {
        foregroundUsers = max(0, foregroundUsers - 1)
        guard foregroundUsers == 0 else { return }
        stopSimulatedWalk()
        settlingTask?.cancel(); settlingTask = nil
        inVerticalTransit = false
        tracker.endVerticalHold(commit: false)
        gpsSource.stop()
        adapter.stop()
        tracker.pause()
    }

    func requestLocationPermission() { gpsSource.requestPermission() }
    var locationUndetermined: Bool { gpsSource.authorizationUndetermined }

    func onLocationPermissionResult(_ granted: Bool) {
        locationPermissionGranted = gpsSource.hasLocationPermission || granted
        if locationPermissionGranted && foregroundUsers > 0 { gpsSource.start() }
        publish()
    }

    // MARK: sensors

    private func resetVertical() {
        junctions.reset()
        floorTracker.onManualFloorSet()
        inVerticalTransit = false
        floorDeltaAltitudeM = 0
        tracker.endVerticalHold(commit: false)
    }

    private func handlePressureSample(_ timestampNs: Int64, _ pressureHpa: Double) {
        latestPressureHpa = pressureHpa
        latestPressureNs = timestampNs
        guard let uFloor = userFloorId else { return }
        let currentMapPos = anchor?.toMap(tracker.state.position) ?? currentFloorOriginM
        let nearConnector = buildingGraph.findNearestVerticalConnector(uFloor, currentMapPos, maxRadiusM: Self.nearConnectorBaroRadiusM) != nil
        guard let update = floorTracker.onPressure(timestampNs, pressureHpa, nearVerticalConnector: nearConnector) else { return }
        let wasInTransit = inVerticalTransit
        inVerticalTransit = update.inVerticalTransit
        floorDeltaAltitudeM = update.deltaFromFloorM
        if update.inVerticalTransit && !wasInTransit {
            tracker.beginVerticalHold()
        } else if !update.inVerticalTransit && wasInTransit && update.floorStepDelta == 0 {
            tracker.endVerticalHold(commit: true) // false alarm (stairwell door, HVAC): replay the steps
        }
        if update.floorStepDelta != 0 {
            if let target = buildingGraph.adjacentFloorId(uFloor, update.floorStepDelta) {
                let landing = buildingGraph.resolveFloorTransition(uFloor, target, currentMapPos, verticalSpeedMps: update.verticalSpeedMps, activeRoute: cachedRoute)
                userFloorId = target
                viewedFloorId = target
                currentFloorOriginM = landing?.arrivalPosM ?? currentMapPos
                recomputeAnchor()
                junctions.reset()
                tracker.reanchorFloorOriginKeepHeading()
                loadVectorPlansInBackground()
                lastPublishedAltM = floorDeltaAltitudeM
                invalidateRouteAndPublish()
                return
            }
            floorTracker.revertRejectedFloorStep(update.floorStepDelta)
        }
        if wasInTransit != inVerticalTransit || abs(floorDeltaAltitudeM - lastPublishedAltM) >= 0.25 {
            lastPublishedAltM = floorDeltaAltitudeM
            publish()
        }
    }

    private func handleGpsFix(_ fix: GpsFix) {
        latestGpsFix = fix
        // Outdoors next to MB with a clean fix: this pressure is the street level for the floor estimate
        let dist = fix.eastNorthM.norm()
        if let p = latestPressureHpa, fix.accuracyM <= Self.streetReferenceMaxAccuracyM,
           (Self.streetReferenceMinDistM...Self.streetReferenceMaxDistM).contains(dist) {
            altitudeFloors.captureStreetReference(latestPressureNs, p)
        }
        if let bearing = fix.trueNorthBearingRad { tracker.onTrueNorthBearingFromGps(fix.elapsedRealtimeNs, bearing) }
        // Position nudges only with a GPS start or a high-precision outdoor fix (≤ 4.5 m)
        if let anc = anchor, usingGpsStart || fix.accuracyM <= Self.highPrecisionOutdoorGpsM, dist <= Self.maxCampusRadiusM {
            tracker.onExternalFix(anc.fromMap(fix.eastNorthM), accuracyM: fix.accuracyM)
        }
        publish()
    }

    /// Corridor-junction landmark: when the user turns at a real junction, pull the dot onto the new corridor.
    private func matchJunction(_ s: PdrState) {
        guard s.phase == .tracking, s.stepCount > 0, !inVerticalTransit,
              let plan = userFloorId.flatMap(buildingGraph.floor), let anc = anchor else { return }
        let mapPos = anc.toMap(s.position), heading = anc.mapHeading(s.headingRadians)
        if let target = junctions.onStep(s.stepCount, mapPos: mapPos, mapHeading: heading, plan: plan) {
            junctionCorrections += 1
            tracker.onLandmark(anc.fromMap(target))
        }
    }

    private func ensureUsableStepSource() {
        let s = tracker.state
        guard s.stepSource == .none, s.phase == .idle else { return }
        if s.stepDetectorAvailable && (!s.activityPermissionNeeded || s.activityPermissionGranted) { tracker.setStepSource(.hardware) }
        else if !s.stepDetectorAvailable && s.fallbackCapable { tracker.setStepSource(.fallback) }
    }

    private func recomputeAnchor() {
        let uPlan = userFloorId.flatMap(buildingGraph.floor)
        let startNode: FloorNode? = startPose.flatMap { sp in sp.floorId == userFloorId ? uPlan?.node(sp.nodeId) : nil }
        let psi: Double
        switch facing {
        case .autoTrueNorth: psi = 0
        case .neighbor(let id): psi = (uPlan?.node(id)).flatMap { n in startNode.map { FloorPlan.facingPsi($0, n) } } ?? 0
        case .cardinal(let deg): psi = degToRad(Double(deg))
        }
        tracker.useTrueNorthReference = facing == .autoTrueNorth
        let computed = MapAnchor(originM: currentFloorOriginM, psiRad: psi)
        anchor = computed
        tracker.mapAxisBaseLocal = wrapAnglePi((uPlan?.dominantCorridorAxisRad ?? 0) - computed.psiRad)
    }

    private func persistGaitIfUpdated(_ s: PdrState) {
        guard s.phase == .tracking, let length = s.lengthPerStepM else { return }
        let cadence = s.baseCadenceHz
        if length != savedStepLengthM || cadence != savedCadenceHz {
            UserDefaults.standard.set(length, forKey: Self.prefsStepLength)
            if let cadence { UserDefaults.standard.set(cadence, forKey: Self.prefsCadence) }
            savedStepLengthM = length
            if let cadence { savedCadenceHz = cadence }
        }
    }

    private func refreshSensorRegistrations() { tracker.onSensorFrameRestart(); adapter.refreshRegistrations() }

    private func pushSensorStatus() {
        tracker.onSensorStatus(SensorStatus(rotationAvailable: isSimulating || adapter.rotationAvailable, stepDetectorAvailable: false,
                                            fallbackCapable: isSimulating || adapter.fallbackCapable, permissionNeeded: false, permissionGranted: true,
                                            registrationFailed: isSimulating ? false : registrationFailed))
    }

    // MARK: simulated walk

    /// Walks the active route with synthetic sensors (heading toward the next route point, a step every
    /// 0.56 s), changes floor at stairs/elevators, stops on arrival. The real engine does everything else.
    func startSimulatedWalk() {
        guard simTimer == nil, destination != nil else { return }
        isSimulating = true
        adapter.stop()
        facing = .autoTrueNorth
        recomputeAnchor()
        pushSensorStatus()
        ensureUsableStepSource()
        simYaw = steeringHeading() ?? 0
        simTicks = 0
        simTimer = Timer.scheduledTimer(withTimeInterval: 0.02, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.simTick() }
        }
        publish()
    }

    func stopSimulatedWalk() {
        simTimer?.invalidate(); simTimer = nil
        guard isSimulating else { return }
        isSimulating = false
        pushSensorStatus()
        if foregroundUsers > 0 { registrationFailed = !adapter.start(); pushSensorStatus() }
        publish()
    }

    private func steeringHeading() -> Double? {
        guard let r = cachedRoute ?? snapshot.activeRoute, let seg = r.floorSegments.first, seg.floorId == userFloorId else { return nil }
        let here = anchor?.toMap(tracker.state.position) ?? currentFloorOriginM
        guard let target = seg.pointsM.dropFirst().first(where: { $0.distanceTo(here) > 0.6 }) else { return nil }
        return atan2(target.x - here.x, target.y - here.y)
    }

    private func simTick() {
        let t = NavClock.nowNs()
        simTicks += 1
        if let want = steeringHeading() {
            let d = angleDelta(simYaw, want)
            simYaw = wrapAnglePi(simYaw + max(-degToRad(7), min(degToRad(7), d)))
        }
        let a = -simYaw // flat phone, yaw clockwise from north (Android rotation-matrix convention)
        tracker.onRotationMatrix(t, [cos(a), -sin(a), 0, sin(a), cos(a), 0, 0, 0, 1])
        tracker.onTrueNorthAzimuth(t, simYaw)
        let s = tracker.state
        switch s.phase {
        case .idle where simTicks > 15:
            if s.canStart { startWithSavedOrDefaultStepLength() }
        case .tracking:
            if let r = cachedRoute {
                if r.isArrived { return } // stand at the door (sensors keep running until "Stop simulation")
                if case .floorTransfer(let tr)? = r.steps.first, tr.fromFloorId == userFloorId {
                    simTransferTicks += 1
                    if simTransferTicks >= 100 { // wait ~2 s at the stairs/elevator, then ride
                        simTransferTicks = 0
                        confirmUserFloor(tr.toFloorId, explicitVerticalGroupId: tr.verticalGroupId)
                    }
                    return
                }
            }
            if simTicks % 28 == 0 { tracker.onStep(t, source: .fallback) }
        default: break
        }
    }

    // MARK: publishing

    private func invalidateRouteAndPublish() { cachedRouteKey = nil; cachedRoute = nil; publish() }

    /// Sensors fire at 60 Hz: steps, phase and floor changes publish at once, the rest at most ~15 Hz.
    private func schedulePublish() {
        let s = tracker.state
        if s.stepCount != lastPublishedSteps || s.phase != lastPublishedPhase || NavClock.nowNs() - lastPublishNs >= 66_000_000 { publish(); return }
        guard !publishScheduled else { return }
        publishScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.07) { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.publishScheduled = false
                self.publish()
            }
        }
    }

    private func publish() {
        lastPublishNs = NavClock.nowNs()
        let pdr = tracker.state
        lastPublishedSteps = pdr.stepCount
        lastPublishedPhase = pdr.phase
        snapshot = buildSnapshot(pdr)
    }

    private func buildSnapshot(_ pdr: PdrState) -> NavigationSnapshot {
        let uFloorId = userFloorId
        let vFloorId = viewedFloorId ?? uFloorId
        let anc = anchor
        let mapPos = anc?.toMap(pdr.position) ?? pdr.position
        let mapHeading = anc?.mapHeading(pdr.headingRadians) ?? pdr.headingRadians
        let route = resolveRoute(pdr, mapPos, mapHeading)

        var nearby: NearbyVerticalOptions?
        if let uf = uFloorId, let conn = buildingGraph.findNearestVerticalConnector(uf, mapPos, maxRadiusM: 8.5, accessibleOnly: accessibleOnly) {
            let vg = conn.verticalGroupId
            func ok(_ f: String) -> Bool { (vg ?? "").isEmpty || buildingGraph.floor(f)?.nodes.contains { $0.verticalGroupId == vg } == true }
            let up = buildingGraph.adjacentFloorId(uf, 1).flatMap { ok($0) ? $0 : nil }
            let down = buildingGraph.adjacentFloorId(uf, -1).flatMap { ok($0) ? $0 : nil }
            if up != nil || down != nil { nearby = NearbyVerticalOptions(connectorNode: conn, upFloorId: up, downFloorId: down) }
        }

        var snap = NavigationSnapshot()
        snap.pdr = pdr
        snap.geoOrigin = geoOrigin
        snap.osmSurroundings = osmSurroundings
        snap.floors = buildingGraph.floors
        snap.activeFloorId = vFloorId
        snap.activeFloor = vFloorId.flatMap(buildingGraph.floor)
        snap.activeFloorVector = vFloorId.flatMap { floorVectors[$0] }
        snap.loadedFloorVectors = floorVectors
        snap.userFloorId = uFloorId
        snap.userFloor = uFloorId.flatMap(buildingGraph.floor)
        snap.isViewingUserFloor = vFloorId == uFloorId
        snap.startPose = startPose
        snap.startNodeId = startPose?.nodeId
        snap.usingGpsStart = usingGpsStart
        snap.hasExplicitStart = startPose != nil || usingGpsStart
        snap.facing = facing
        snap.anchor = anc
        snap.mapPositionM = mapPos
        snap.mapHeadingRad = mapHeading
        snap.barometerAvailable = adapter.pressureAvailable
        snap.floorDeltaAltitudeM = floorDeltaAltitudeM
        snap.inVerticalTransit = inVerticalTransit
        snap.suggestedTransfer = computeSuggestedTransfer(uFloorId, mapPos, route)
        snap.nearbyVertical = nearby
        snap.locationPermissionGranted = locationPermissionGranted
        snap.latestGpsFix = latestGpsFix
        snap.destination = destination
        snap.accessibleOnly = accessibleOnly
        snap.activeRoute = route
        snap.destinations = buildingGraph.destinations
        snap.savedStepLengthM = savedStepLengthM
        snap.personalStrideM = personalStrideM
        snap.lastJunctionId = junctions.lastJunctionId
        snap.altitudeFloor = latestPressureHpa != nil || altitudeFloors.hasStreetReference ? estimateAltitudeFloor() : nil
        snap.hasStreetPressureReference = altitudeFloors.hasStreetReference
        return snap
    }

    private func computeSuggestedTransfer(_ uFloorId: String?, _ mapPos: PointM, _ route: NavigationRoute?) -> SuggestedFloorTransfer? {
        guard let uf = uFloorId, let route else { return nil }
        if case .floorTransfer(let t)? = route.steps.first, t.fromFloorId == uf {
            return SuggestedFloorTransfer(fromFloorId: t.fromFloorId, toFloorId: t.toFloorId, viaLabel: t.viaLabel, verticalGroupId: t.verticalGroupId, viaElevator: t.viaElevator, fromActiveRoute: true)
        }
        guard let t = route.transfers.first(where: { $0.fromFloorId == uf && $0.departurePosM.distanceTo(mapPos) <= 8 }) else { return nil }
        return SuggestedFloorTransfer(fromFloorId: t.fromFloorId, toFloorId: t.toFloorId, viaLabel: t.viaLabel, verticalGroupId: t.verticalGroupId, viaElevator: t.viaElevator, fromActiveRoute: true)
    }

    private func resolveRoute(_ pdr: PdrState, _ mapPos: PointM, _ mapHeading: Double) -> NavigationRoute? {
        guard let dest = destination, let floorId = userFloorId else { return nil }
        if startPose == nil && !usingGpsStart && pdr.stepCount == 0 { return nil }
        let key = RouteCacheKey(floorId: floorId, stepCount: pdr.stepCount, posBucketX: Int((mapPos.x * 2).rounded()), posBucketY: Int((mapPos.y * 2).rounded()),
                                headingBucket: pdr.headingValid ? Int((radToDeg(mapHeading) / 20).rounded()) : .min, dest: dest, accessibleOnly: accessibleOnly)
        if key == cachedRouteKey { return cachedRoute }
        cachedRouteKey = key
        cachedRoute = buildingGraph.findRouteFromPosition(floorId, mapPos, dest, accessibleOnly: accessibleOnly, userMapHeadingRad: pdr.headingValid ? mapHeading : nil)
        return cachedRoute
    }
}
