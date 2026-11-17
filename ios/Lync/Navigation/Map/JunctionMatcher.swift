import Foundation

/**
 * Corridor-junction landmark matching (iOS addition to the engine, pure and unit-tested).
 *
 * PDR drifts *along* corridors (a step length 10 % off = 2 m error after 20 m) and the corridor assist
 * only corrects sideways, within 2 m. A turn, though, happens at a real junction. When the walking
 * direction settles on a new heading ≥ 55° away from the previous one, the matcher looks for a plan
 * junction within 7 m that has an edge back along the old heading and an edge along the new one.
 * The user is then really on that new corridor: it returns the projection of the current position
 * onto it, which `PdrTracker.onLandmark` replays gently (≤ 20 cm per step).
 */
final class JunctionMatcher {
    struct Sample { let step: Int; let pos: PointM; let heading: Double }

    static let minTurnRad = degToRad(55)
    static let matchToleranceRad = degToRad(30)
    static let maxJunctionDistanceM = 7.0
    static let minCorrectionM = 0.6
    private static let cooldownSteps = 10

    private var samples: [Sample] = []
    private var cooldownUntilStep = Int.min
    private(set) var lastJunctionId: String?

    func reset() { samples.removeAll(); cooldownUntilStep = .min; lastJunctionId = nil }

    /// Call once per accepted step with the map position and walking heading (0 = north, clockwise).
    func onStep(_ step: Int, mapPos: PointM, mapHeading: Double, plan: FloorPlan) -> PointM? {
        if let last = samples.last, last.step == step { return nil }
        samples.append(Sample(step: step, pos: mapPos, heading: mapHeading))
        if samples.count > 14 { samples.removeFirst(samples.count - 14) }
        guard samples.count >= 9, step >= cooldownUntilStep else { return nil }

        let n = samples.count
        let recent = samples[(n - 3)...].map(\.heading)
        guard let post = circularMean(recent), recent.allSatisfy({ abs(angleDelta(post, $0)) <= degToRad(15) }),
              let pre = circularMean(samples[(n - 9)..<(n - 6)].map(\.heading)),
              abs(angleDelta(pre, post)) >= Self.minTurnRad else { return nil }

        // where the turn happened: first sample that left the old heading
        let turnSample = samples[(n - 8)...].first { abs(angleDelta(pre, $0.heading)) > degToRad(25) } ?? samples[n - 4]
        guard let (junction, outDir) = bestJunction(plan, near: turnSample.pos, pre: pre, post: post) else { return nil }

        let u = PointM(sin(outDir), cos(outDir))
        let t = max(0, (mapPos.x - junction.posM.x) * u.x + (mapPos.y - junction.posM.y) * u.y)
        let target = junction.posM + u * t
        cooldownUntilStep = step + Self.cooldownSteps
        lastJunctionId = junction.id
        return target.distanceTo(mapPos) >= Self.minCorrectionM ? target : nil
    }

    /// Closest node with an edge back along `pre` (where we came from) and an edge along `post`.
    func bestJunction(_ plan: FloorPlan, near p: PointM, pre: Double, post: Double) -> (FloorNode, Double)? {
        var best: (FloorNode, Double, Double)?
        let back = wrapAnglePi(pre + .pi)
        for node in plan.nodes {
            let d = node.posM.distanceTo(p)
            guard d <= Self.maxJunctionDistanceM else { continue }
            let nbrs = plan.neighbors(node.id)
            guard nbrs.count >= 2 else { continue }
            let dirs = nbrs.compactMap { nb -> Double? in nb.posM.distanceTo(node.posM) < 0.5 ? nil : atan2(nb.posM.x - node.posM.x, nb.posM.y - node.posM.y) }
            guard dirs.contains(where: { abs(angleDelta(back, $0)) <= Self.matchToleranceRad }),
                  let out = dirs.min(by: { abs(angleDelta(post, $0)) < abs(angleDelta(post, $1)) }),
                  abs(angleDelta(post, out)) <= Self.matchToleranceRad else { continue }
            if best == nil || d < best!.2 { best = (node, out, d) }
        }
        return best.map { ($0.0, $0.1) }
    }
}

/// Personal stride from the iPhone's own pedometer (Core Motion keeps a per-user stride model,
/// refined every time the user walks outdoors with GPS). Used instead of the fixed 0.74 m default
/// until the user measures their step.
enum StrideEstimate {
    static let minSteps = 300
    static let range = 0.45...1.05

    static func fromPedometer(distanceM: Double, steps: Int) -> Double? {
        guard steps >= minSteps, distanceM.isFinite, distanceM > 0 else { return nil }
        let stride = distanceM / Double(steps)
        return range.contains(stride) ? stride : nil
    }
}
