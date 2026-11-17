import XCTest
@testable import Lync

// Port of the Android map/places tests: BuildingGraphTest, FloorPlanTest, CorridorTurnRegressionTest,
// MbVectorAndOsmTest, MapAnchorTest, CampusPlacesTest, WalkingRouterTest. Reads the real bundled MB data.

final class IndoorMapTests: XCTestCase {
    private var floors: [FloorPlan] { IndoorAssets.shared.floors }
    private var mb2: FloorPlan { floors.first { $0.floorId == "MB-2" }! }

    func testLoadsSevenFloorsAndRoutesAcrossFloors() throws {
        XCTAssertEqual(floors.count, 7)
        XCTAssertEqual(floors.reduce(0) { $0 + $1.nodes.count }, 761)
        let graph = BuildingGraph(floors: floors)
        XCTAssertGreaterThanOrEqual(graph.destinations.count, 250)
        let m2 = graph.floor("MB-2")!
        let r255 = m2.nodes.first { $0.label == "MB-2.255" }!, r210 = m2.nodes.first { $0.label == "MB-2.210" }!
        let mono = try XCTUnwrap(graph.findRouteFromNode(NodeKey(floorId: "MB-2", nodeId: r255.id), NodeKey(floorId: "MB-2", nodeId: r210.id)))
        XCTAssertFalse(mono.isArrived)
        XCTAssertEqual(mono.destinationLabel, "MB-2.210")
        XCTAssertTrue((15.0...75.0).contains(mono.totalDistanceM))
        XCTAssertGreaterThanOrEqual(mono.pointsForFloor("MB-2").count, 3)
        // Step-free route MB-1 exit -> MB-6 must be one direct elevator ride
        let exit199 = graph.floor("MB-1")!.nodes.first { $0.label == "Exit 1.199" }!
        let room6 = graph.floor("MB-6")!.nodes.first { $0.type == "room" }!
        let pmr = try XCTUnwrap(graph.findRouteFromNode(NodeKey(floorId: "MB-1", nodeId: exit199.id), NodeKey(floorId: "MB-6", nodeId: room6.id), accessibleOnly: true))
        XCTAssertEqual(pmr.transfers.count, 1)
        XCTAssertTrue(pmr.transfers[0].viaElevator)
        XCTAssertEqual(pmr.transfers[0].fromFloorId, "MB-1")
        XCTAssertEqual(pmr.transfers[0].toFloorId, "MB-6")
    }

    func testAccessibleModeBlocksStairs() {
        let graph = BuildingGraph(floors: floors)
        let m2 = graph.floor("MB-2")!
        let start = NodeKey(floorId: "MB-2", nodeId: m2.nodes.first { $0.label == "MB-2.255" }!.id)
        let stairs = NodeKey(floorId: "MB-2", nodeId: m2.nodes.first { $0.type == "stairs" }!.id)
        XCTAssertNotNil(graph.findRouteFromNode(start, stairs))
        XCTAssertNil(graph.findRouteFromNode(start, stairs, accessibleOnly: true))
    }

    func testMidCorridorProjectionAndArrival() throws {
        let graph = BuildingGraph(floors: [mb2])
        let a = mb2.node("MB-2-n012")!, b = mb2.node("MB-2-n011")!
        let heading = atan2(b.posM.x - a.posM.x, b.posM.y - a.posM.y)
        let mid = PointM((a.posM.x + b.posM.x) / 2, (a.posM.y + b.posM.y) / 2)
        let dest = mb2.nodes.first { $0.label == "MB-2.285" }!
        let route = try XCTUnwrap(graph.findRouteFromPosition("MB-2", mid, NodeKey(floorId: "MB-2", nodeId: dest.id), userMapHeadingRad: heading))
        XCTAssertFalse(route.nextInstruction.isEmpty)
        let arrived = try XCTUnwrap(graph.findRouteFromPosition("MB-2", PointM(dest.posM.x + 0.4, dest.posM.y - 0.4), NodeKey(floorId: "MB-2", nodeId: dest.id)))
        XCTAssertTrue(arrived.isArrived)
    }

    func testStairAndElevatorLandingsAndMultiFlightMerge() throws {
        let graph = BuildingGraph(floors: floors)
        let s1 = graph.floor("MB-1")!.nodes.first { $0.verticalGroupId == "stairs_295" }!
        let s2 = graph.floor("MB-2")!.nodes.first { $0.verticalGroupId == "stairs_295" }!
        let landing = try XCTUnwrap(graph.resolveFloorTransition("MB-1", "MB-2", PointM(s1.posM.x + 1.2, s1.posM.y - 0.8), verticalSpeedMps: 0.35))
        XCTAssertEqual(landing.verticalGroupId, "stairs_295")
        XCTAssertEqual(landing.arrivalNode?.id, s2.id)
        let elev5 = graph.floor("MB-5")!.nodes.first { $0.verticalGroupId == "elevator_central" }!
        let elevLanding = try XCTUnwrap(graph.resolveFloorTransition("MB-2", "MB-5", PointM(-3, -1.5), verticalSpeedMps: 1.6))
        XCTAssertTrue(elevLanding.viaElevator)
        XCTAssertEqual(elevLanding.arrivalNode?.id, elev5.id)
        let start285 = graph.floor("MB-2")!.nodes.first { $0.label == "MB-2.285" }!
        let stair4 = graph.floor("MB-4")!.nodes.first { $0.verticalGroupId == "stairs_294" }!
        let near4 = graph.floor("MB-4")!.nodes.filter { $0.type == "room" }.min { $0.posM.distanceTo(stair4.posM) < $1.posM.distanceTo(stair4.posM) }!
        let route = try XCTUnwrap(graph.findRouteFromNode(NodeKey(floorId: "MB-2", nodeId: start285.id), NodeKey(floorId: "MB-4", nodeId: near4.id)))
        XCTAssertEqual(route.transfers.count, 1)
        XCTAssertEqual(route.transfers[0].fromFloorId, "MB-2")
        XCTAssertEqual(route.transfers[0].toFloorId, "MB-4")
    }

    func testFlexibleRoomSearch() {
        XCTAssertTrue(BuildingGraph.matchesSearchQuery("M.2.255", label: "MB-2.255", name: "Classroom", floorId: "MB-2"))
        XCTAssertTrue(BuildingGraph.matchesSearchQuery("m2.255", label: "MB-2.255", name: "Classroom", floorId: "MB-2"))
        XCTAssertTrue(BuildingGraph.matchesSearchQuery("2.255", label: "MB-2.255", name: "Classroom", floorId: "MB-2"))
        XCTAssertTrue(BuildingGraph.matchesSearchQuery("mb 2 255", label: "MB-2.255", name: "Classroom", floorId: "MB-2"))
        XCTAssertFalse(BuildingGraph.matchesSearchQuery("M.4.206", label: "MB-2.255", name: "Classroom", floorId: "MB-2"))
    }

    // MARK: FloorPlan

    func testParsesMb2InTrueNorthFrame() {
        let p = mb2
        XCTAssertEqual(p.widthM, 94); XCTAssertEqual(p.heightM, 74)
        XCTAssertEqual(p.nodes.count, 97); XCTAssertEqual(p.edges.count, 98)
        XCTAssertTrue(p.isGeoReferenced)
        XCTAssertEqual(p.svgAsset, "floor-plans/MB/MB-floor-02.svg")
        let elev = p.node("MB-2-n001")!
        XCTAssertEqual(elev.type, "elevator")
        XCTAssertEqual(elev.posM.x, -0.23, accuracy: 0.05)
        XCTAssertEqual(elev.posM.y, 0.19, accuracy: 0.05)
        XCTAssertEqual(abs(p.dominantCorridorAxisRad), degToRad(40.5), accuracy: degToRad(3)) // Montréal street grid
        let r = p.node("MB-2-n102")!
        XCTAssertEqual(r.label, "MB-2.255")
        XCTAssertEqual(r.posM.x, r.x * 94 - 44, accuracy: 1e-6)
        XCTAssertEqual(r.posM.y, -(r.y * 74 - 34), accuracy: 1e-6)
        XCTAssertEqual(p.neighbors("MB-2-n102").map(\.id), ["MB-2-n101"])
        XCTAssertTrue(p.neighbors("inconnu").isEmpty)
        XCTAssertFalse(p.edges.first { $0.fromId == "MB-2-n116" && $0.toId == "MB-2-n117" }!.accessible)
        let firstCorridor = p.labeledNodes.firstIndex { $0.type == "corridor" }
        let lastOther = p.labeledNodes.lastIndex { $0.type != "corridor" }
        if let fc = firstCorridor, let lo = lastOther { XCTAssertGreaterThan(fc, lo) }
        XCTAssertThrowsError(try FloorPlan.parse("{ pas du json valide"))
    }

    func testMatchCorridorProjectsLaterally() throws {
        let a = mb2.node("MB-2-n012")!, b = mb2.node("MB-2-n011")!
        let h = atan2(b.posM.x - a.posM.x, b.posM.y - a.posM.y)
        let mid = PointM((a.posM.x + b.posM.x) * 0.5, (a.posM.y + b.posM.y) * 0.5)
        let m = try XCTUnwrap(mb2.matchCorridor(mid + PointM(cos(h) * 0.9, -sin(h) * 0.9), h + degToRad(5)))
        XCTAssertEqual(m.projectedPointM.x, mid.x, accuracy: 1e-4)
        XCTAssertEqual(m.projectedPointM.y, mid.y, accuracy: 1e-4)
        XCTAssertLessThan(abs(angleDelta(h, m.alignedHeadingRad!)), 1e-5)
        XCTAssertNil(mb2.matchCorridor(PointM(42, -38), h + .pi / 2))
    }

    /// Real 90° right turn on MB-2 with a +37° gyro offset: follows the new corridor, no jumps.
    func testCorridorTurnRegression() {
        let start = mb2.node("MB-2-n160")!, corner = mb2.node("MB-2-n014")!, after = mb2.node("MB-2-n150")!
        let h1 = atan2(corner.posM.x - start.posM.x, corner.posM.y - start.posM.y)
        let h2 = atan2(after.posM.x - corner.posM.x, after.posM.y - corner.posM.y)
        let anchor = MapAnchor(originM: start.posM, psiRad: 0)
        var now: Int64 = 50_000_000_000
        let ms: Int64 = 1_000_000
        let tr = PdrTracker(clock: { now }, corridorAssistInitial: true)
        tr.useTrueNorthReference = true
        tr.mapAxisBaseLocal = wrapAnglePi(mb2.dominantCorridorAxisRad - anchor.psiRad)
        let plan = mb2
        tr.corridorConstraint = { p, theta in
            plan.matchCorridor(anchor.toMap(p), anchor.mapHeading(theta)).map {
                CorridorConstraintResult(projectedLocalPos: anchor.fromMap($0.projectedPointM), alignedLocalTheta: $0.alignedHeadingRad.map(anchor.localHeading))
            }
        }
        tr.onSensorStatus(SensorStatus(rotationAvailable: true, stepDetectorAvailable: true, fallbackCapable: true, permissionNeeded: false, permissionGranted: true))
        func feed(_ from: Int64, _ to: Int64, _ yaw: Double) {
            var t = from
            while t <= to { tr.onRotationVector(t, rotationVectorFor(wrapAnglePi(yaw + degToRad(37)))); tr.onTrueNorthAzimuth(t, yaw); t += 20 * ms }
        }
        feed(now - 600 * ms, now, h1)
        XCTAssertTrue(tr.startWithManualStepLength(0.66))
        var t = now
        for _ in 0..<5 { feed(t + 20 * ms, t + 500 * ms, h1 + degToRad(1.5)); t += 500 * ms; now = t; tr.onStep(t, source: .hardware) }
        for i in 0..<3 { feed(t + 20 * ms, t + 250 * ms, h1 + degToRad(30 * Double(i + 1))); t += 250 * ms }
        for _ in 0..<10 { feed(t + 20 * ms, t + 500 * ms, h2 + degToRad(2)); t += 500 * ms; now = t; tr.onStep(t, source: .hardware) }
        let path = tr.state.rawPath.map(anchor.toMap)
        for i in 1..<path.count { XCTAssertLessThan(path[i].distanceTo(path[i - 1]), 1.0) }
        XCTAssertLessThan(abs(angleDelta(h2, anchor.mapHeading(tr.state.headingRadians))), degToRad(8))
    }

    // MARK: Vector plans, OSM, anchor

    func testParsesAllSevenAlignedSvgs() throws {
        for n in 1...7 {
            let svg = try XCTUnwrap(IndoorAssets.shared.svgText(String(format: "floor-plans/MB/MB-floor-%02d.svg", n)))
            let v = try FloorVectorPlan.parseSvg("MB-\(n)", svg)
            XCTAssertEqual(v.viewBoundsM.minEastM, -44, accuracy: 1e-6)
            XCTAssertEqual(v.viewBoundsM.maxEastM, 50, accuracy: 1e-6)
            XCTAssertEqual(v.viewBoundsM.minNorthM, -40, accuracy: 1e-6)
            XCTAssertEqual(v.viewBoundsM.maxNorthM, 34, accuracy: 1e-6)
            XCTAssertGreaterThan(v.contourCount, 200)
            XCTAssertGreaterThan(v.totalPoints, 5000)
            XCTAssertTrue((-44.0...(-25.0)).contains(v.contentBoundsM.minEastM))
            XCTAssertTrue((35.0...50.0).contains(v.contentBoundsM.maxEastM))
            XCTAssertTrue((-40.0...(-25.0)).contains(v.contentBoundsM.minNorthM))
            XCTAssertTrue((25.0...34.0).contains(v.contentBoundsM.maxNorthM))
        }
    }

    func testBundledOsmSurroundings() throws {
        let osm = try XCTUnwrap(IndoorAssets.shared.bundledOsm())
        XCTAssertEqual(osm.origin.latitude, GeoOrigin.mbElevatorCore.latitude, accuracy: 1e-5)
        XCTAssertGreaterThan(osm.buildings.count, 100)
        XCTAssertGreaterThan(osm.roads.count, 50)
        XCTAssertTrue(osm.buildings.contains { $0.isFocusBuilding && $0.ref == "MB" })
        XCTAssertTrue(osm.roads.contains { $0.name.localizedCaseInsensitiveContains("Guy") })
        XCTAssertTrue(osm.roads.contains { $0.name.localizedCaseInsensitiveContains("Maisonneuve") })
    }

    func testOsmXmlAndGeoOriginRoundTrip() {
        let o = GeoOrigin.mbElevatorCore
        let back = o.toEastNorthM(o.toWgs84(PointM(25, -40)))
        XCTAssertEqual(back.x, 25, accuracy: 1e-4); XCTAssertEqual(back.y, -40, accuracy: 1e-4)
        let n = [PointM(-10, -10), PointM(10, -10), PointM(10, 10), PointM(-10, 10)].map(o.toWgs84)
        let xml = """
        <osm version="0.6">
          <node id="1" lat="\(n[0].latitude)" lon="\(n[0].longitude)"/>
          <node id="2" lat="\(n[1].latitude)" lon="\(n[1].longitude)"/>
          <node id="3" lat="\(n[2].latitude)" lon="\(n[2].longitude)"/>
          <node id="4" lat="\(n[3].latitude)" lon="\(n[3].longitude)"/>
          <node id="5" lat="\(o.latitude)" lon="\(o.longitude)"><tag k="railway" v="subway_entrance"/><tag k="name" v="Guy-Concordia"/></node>
          <way id="10"><nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/><tag k="building" v="university"/><tag k="ref" v="MB"/></way>
          <way id="11"><nd ref="1"/><nd ref="3"/><tag k="highway" v="primary"/><tag k="name" v="Rue Guy"/></way>
        </osm>
        """
        let parsed = OsmSurroundings.parseOsmXml(Data(xml.utf8), origin: o)
        XCTAssertEqual(parsed.buildings.count, 1)
        XCTAssertTrue(parsed.buildings[0].isFocusBuilding)
        XCTAssertEqual(parsed.roads.map(\.name), ["Rue Guy"])
        XCTAssertEqual(parsed.pois.count, 1)
    }

    func testMapAnchor() {
        let north = MapAnchor(originM: .zero, psiRad: 0)
        XCTAssertEqual(north.toMap(PointM(0, 1)).y, 1, accuracy: 1e-9)
        let east = MapAnchor(originM: .zero, psiRad: .pi / 2)
        XCTAssertEqual(east.toMap(PointM(0, 1)).x, 1, accuracy: 1e-9)
        XCTAssertEqual(east.toMap(PointM(1, 0)).y, -1, accuracy: 1e-9)
        let m = MapAnchor(originM: PointM(10, 20), psiRad: .pi / 2).toMap(PointM(2, 3))
        XCTAssertEqual(m.x, 13, accuracy: 1e-9); XCTAssertEqual(m.y, 18, accuracy: 1e-9)
        let w = MapAnchor(originM: .zero, psiRad: 2.5).mapHeading(1.5)
        XCTAssertEqual(w, 2.5 + 1.5 - 2 * .pi, accuracy: 1e-9)
        let a = MapAnchor(originM: PointM(43, 67.3), psiRad: -1.15)
        let rt = a.fromMap(a.toMap(PointM(-2.4, 7.8)))
        XCTAssertEqual(rt.x, -2.4, accuracy: 1e-9); XCTAssertEqual(rt.y, 7.8, accuracy: 1e-9)
    }

    // MARK: Places

    func testCampusPlaces() {
        let mb = CampusPlaces.resolve("JMSB, MB-2.210")!
        XCTAssertEqual(mb.building.code, "MB"); XCTAssertEqual(mb.roomLabel, "MB-2.210"); XCTAssertEqual(mb.floor, 2); XCTAssertTrue(mb.hasIndoorMap)
        let h = CampusPlaces.resolve("Hall Building, H-920")!
        XCTAssertEqual(h.building.code, "H"); XCTAssertEqual(h.roomLabel, "H-920"); XCTAssertEqual(h.floor, 9); XCTAssertFalse(h.hasIndoorMap)
        XCTAssertEqual(CampusPlaces.resolve("EV Building, EV-3.102")!.floor, 3)
        XCTAssertEqual(CampusPlaces.resolve("MB 1.437")!.roomLabel, "MB-1.437")
        XCTAssertEqual(CampusPlaces.resolve("mb2255")!.roomLabel, "MB-2.255")
        XCTAssertEqual(CampusPlaces.resolve("3.210")!.roomLabel, "MB-3.210")
        XCTAssertEqual(CampusPlaces.roomKey("MB-2.255"), CampusPlaces.roomKey("M.2.255"))
        XCTAssertEqual(CampusPlaces.resolve("John Molson")!.building.code, "MB")
        XCTAssertEqual(CampusPlaces.resolve("Webster Library")!.building.code, "LB")
        XCTAssertNil(CampusPlaces.resolve("JMSB")!.roomLabel)
        XCTAssertNotNil(CampusPlaces.resolve("H"))
        XCTAssertNil(CampusPlaces.resolve("Café chez Paul"))
        XCTAssertNil(CampusPlaces.resolve(""))
    }

    func testCampusDirectorySearch() {
        let rooms = CampusDirectory.rooms()
        XCTAssertGreaterThan(rooms.count, 200)
        XCTAssertTrue(CampusDirectory.search("2.255", rooms: rooms).contains { $0.title == "MB-2.255" })
        XCTAssertTrue(CampusDirectory.search("Hall", rooms: rooms).contains { !$0.isRoom && $0.building.code == "H" })
        XCTAssertTrue(CampusDirectory.search("H-920", rooms: rooms).contains { $0.isRoom && $0.title == "H-920" })
    }

    private let osrm = """
    {"code":"Ok","routes":[{"distance":200.0,"duration":140.0,
      "geometry":{"type":"LineString","coordinates":[[-73.5780,45.4972],[-73.5785,45.4962],[-73.5795,45.4957]]},
      "legs":[{"steps":[
        {"name":"Rue Bishop","distance":120,"maneuver":{"type":"depart","modifier":"left","location":[-73.5780,45.4972]}},
        {"name":"Boulevard De Maisonneuve","distance":80,"maneuver":{"type":"turn","modifier":"right","location":[-73.5785,45.4962]}},
        {"name":"","distance":0,"maneuver":{"type":"arrive","location":[-73.5795,45.4957]}}
      ]}]}]}
    """

    func testWalkingRouter() throws {
        let r = try XCTUnwrap(WalkingRouter.parse(Data(osrm.utf8)))
        XCTAssertEqual(r.points.count, 3)
        XCTAssertEqual(r.steps.map(\.maneuver), [.depart, .right, .arrive])
        XCTAssertEqual(r.steps[1].street, "Boulevard De Maisonneuve")
        XCTAssertFalse(r.approximate)
        XCTAssertGreaterThanOrEqual(r.durationS, 200 / WalkingRouter.walkSpeedMps - 1e-6)
        let p = WalkProgress.of(r, GeoPoint(lat: 45.49695, lng: -73.57813))
        XCTAssertEqual(p.nextStep?.maneuver, .right)
        XCTAssertLessThan(p.offRouteM, 5)
        XCTAssertEqual(p.remainingM, r.lengthM - p.alongM, accuracy: 1e-6)
        XCTAssertEqual(p.distanceToNextStepM, r.stepAlongM[1] - p.alongM, accuracy: 1e-6)
        XCTAssertTrue((180.0...230.0).contains(p.bearingDeg))
        XCTAssertGreaterThan(WalkProgress.of(r, GeoPoint(lat: 45.4990, lng: -73.5750)).offRouteM, 200)
        XCTAssertNil(WalkingRouter.parse(Data(#"{"code":"NoRoute"}"#.utf8)))
        let a = GeoPoint(lat: 45.4972, lng: -73.5780), b = GeoPoint(lat: 45.4952, lng: -73.5790)
        let line = WalkingRouter.straightLine(a, b)
        XCTAssertTrue(line.approximate)
        XCTAssertEqual(line.lengthM, Geo.distanceM(a, b), accuracy: 1e-6)
    }
}
