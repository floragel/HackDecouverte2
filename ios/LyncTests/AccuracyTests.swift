import XCTest
@testable import Lync

/// Tests for the iOS accuracy improvements on top of the Android engine:
/// adaptive step detector, corridor-junction landmark correction, personal stride.
final class AccuracyTests: XCTestCase {
    private let ms: Int64 = 1_000_000

    private func countSteps(adaptive: Bool, seconds: Double = 6, _ signal: (Double) -> Double) -> Int {
        let d = FallbackStepDetector(adaptive: adaptive)
        var n = 0
        d.onStep = { _ in n += 1 }
        d.start(0)
        var t: Int64 = 0
        while Double(t) / 1e9 <= seconds {
            d.onLinearAcceleration(t, ax: 0, ay: 0, az: signal(Double(t) / 1e9), ux: 0, uy: 0, uz: 1)
            t += 10 * ms
        }
        return n
    }

    /// Slow walk / phone held gently: 0.9 m/s² bounce at 1.6 Hz never reaches the fixed 1.0 threshold.
    func testAdaptiveDetectorCatchesSoftSteps() {
        let soft: (Double) -> Double = { 0.9 * sin(2 * .pi * 1.6 * $0) }
        let truth = Int(1.6 * (6 - 0.5)) // after the 0.5 s warm-up
        XCTAssertEqual(countSteps(adaptive: false, soft), 0)
        XCTAssertEqual(Double(countSteps(adaptive: true, soft)), Double(truth), accuracy: 1)
    }

    /// Strong steps with a heel-strike bounce (2nd harmonic): one count per step, not two.
    func testAdaptiveDetectorIgnoresHeelBounce() {
        let strong: (Double) -> Double = { t in 3.2 * sin(2 * .pi * 1.8 * t) + 1.6 * sin(2 * .pi * 3.6 * t + 1.2) }
        let truth = Int(1.8 * (6 - 0.5))
        let adaptive = countSteps(adaptive: true, strong), fixed = countSteps(adaptive: false, strong)
        XCTAssertEqual(Double(adaptive), Double(truth), accuracy: 1)
        XCTAssertLessThanOrEqual(abs(adaptive - truth), abs(fixed - truth))
    }

    /// Hand tremor / standing still never counts.
    func testAdaptiveDetectorStaysQuietOnTremor() {
        var seed: UInt64 = 42
        func noise() -> Double { seed = seed &* 6364136223846793005 &+ 1; return Double(seed >> 33) / Double(1 << 31) - 0.5 }
        XCTAssertEqual(countSteps(adaptive: true) { _ in 1.2 * noise() }, 0)
        XCTAssertEqual(countSteps(adaptive: true) { _ in 0 }, 0)
    }

    // MARK: junction landmark

    /// L-shaped corridor A(0,0) → B(0,20) → C(25,20).
    private func lPlan() -> FloorPlan {
        let nodes = [FloorNode(id: "A", type: "corridor", posM: PointM(0, 0)), FloorNode(id: "B", type: "corridor", posM: PointM(0, 20)),
                     FloorNode(id: "C", type: "corridor", posM: PointM(25, 20))]
        return FloorPlan(floorId: "T-1", widthM: 40, heightM: 40, nodes: nodes, edges: [FloorEdge(fromId: "A", toId: "B"), FloorEdge(fromId: "B", toId: "C")])
    }

    /// Step length 14 % too long: the dot overshoots the junction by ~3 m, out of reach of the 2 m corridor
    /// assist. After the turn the junction landmark brings it back onto the new corridor, without jumps.
    private func walkL(landmarks: Bool) -> (end: PointM, maxStepM: Double) {
        var now: Int64 = 10_000_000_000
        let tr = PdrTracker(clock: { now })
        tr.onSensorStatus(SensorStatus(rotationAvailable: true, stepDetectorAvailable: true, fallbackCapable: true, permissionNeeded: false, permissionGranted: true))
        let plan = lPlan(), anchor = MapAnchor(originM: .zero, psiRad: 0), matcher = JunctionMatcher()
        func feed(_ yaw: Double) { var t = now - 480 * ms; while t <= now { tr.onRotationVector(t, rotationVectorFor(yaw)); t += 20 * ms } }
        feed(0)
        XCTAssertTrue(tr.startWithManualStepLength(0.8)) // true stride 0.7 m
        func step(_ yaw: Double) {
            now += 500 * ms
            feed(yaw)
            tr.onStep(now, source: .hardware)
            let s = tr.state
            if landmarks, let target = matcher.onStep(s.stepCount, mapPos: anchor.toMap(s.position), mapHeading: anchor.mapHeading(s.headingRadians), plan: plan) {
                tr.onLandmark(anchor.fromMap(target))
            }
        }
        for _ in 0..<29 { step(0) }          // 20.3 m north for real, PDR says 23.2 m
        for _ in 0..<30 { step(.pi / 2) }    // then east
        let path = tr.state.rawPath
        let maxStep = zip(path, path.dropFirst()).map { $0.distanceTo($1) }.max() ?? 0
        return (tr.state.position, maxStep)
    }

    func testJunctionLandmarkFixesAlongTrackDrift() {
        let raw = walkL(landmarks: false), fixed = walkL(landmarks: true)
        XCTAssertGreaterThan(abs(raw.end.y - 20), 3)       // without: still 3 m off the corridor
        XCTAssertLessThan(abs(fixed.end.y - 20), 0.5)       // with: back on the corridor
        XCTAssertLessThanOrEqual(fixed.maxStepM, 0.8 + PdrTracker.maxLandmarkStepM + 1e-6) // bounded, no jump
    }

    func testJunctionMatcherNeedsAMatchingJunction() {
        let plan = lPlan(), m = JunctionMatcher()
        // turning right (east) at B matches; turning left (west) has no corridor there
        XCTAssertNotNil(m.bestJunction(plan, near: PointM(0, 22), pre: 0, post: .pi / 2))
        XCTAssertNil(m.bestJunction(plan, near: PointM(0, 22), pre: 0, post: -.pi / 2))
        XCTAssertNil(m.bestJunction(plan, near: PointM(0, 35), pre: 0, post: .pi / 2)) // too far from the junction
    }

    /// Real MB-2 corner (n160 → n014 → n150): the matcher recognises it from noisy headings.
    func testJunctionMatcherOnRealMbCorner() throws {
        let mb2 = try XCTUnwrap(IndoorAssets.shared.floors.first { $0.floorId == "MB-2" })
        let a = mb2.node("MB-2-n160")!, b = mb2.node("MB-2-n014")!, c = mb2.node("MB-2-n150")!
        let h1 = atan2(b.posM.x - a.posM.x, b.posM.y - a.posM.y), h2 = atan2(c.posM.x - b.posM.x, c.posM.y - b.posM.y)
        let found = JunctionMatcher().bestJunction(mb2, near: b.posM + PointM(sin(h1) * 2, cos(h1) * 2), pre: h1 + degToRad(6), post: h2 - degToRad(5))
        XCTAssertEqual(found?.0.id, "MB-2-n014")
    }

    func testPersonalStrideBounds() {
        XCTAssertEqual(StrideEstimate.fromPedometer(distanceM: 7_100, steps: 10_000)!, 0.71, accuracy: 1e-9)
        XCTAssertNil(StrideEstimate.fromPedometer(distanceM: 100, steps: 120))      // too few steps
        XCTAssertNil(StrideEstimate.fromPedometer(distanceM: 15_000, steps: 10_000)) // 1.5 m: implausible
        XCTAssertNil(StrideEstimate.fromPedometer(distanceM: .nan, steps: 10_000))
    }
}
