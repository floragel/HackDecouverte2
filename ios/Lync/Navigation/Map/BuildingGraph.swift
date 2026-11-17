import Foundation

// 1:1 port of com.hackdecouverte.map.BuildingGraph: multi-floor graph, Dijkstra routing
// (walk = distance, stairs 15 m/floor, elevator 20 m + 3 m/floor, step-free mode), instructions.

struct NodeKey: Hashable { let floorId: String; let nodeId: String }

struct DestinationItem: Hashable, Identifiable {
    let key: NodeKey
    let label: String
    let name: String
    let type: String
    var id: NodeKey { key }
}

struct RouteFloorSegment: Hashable { let floorId: String; let pointsM: [PointM] }

enum RouteStep: Hashable {
    case walk(floorId: String, distanceM: Double, label: String)
    case turn(floorId: String, left: Bool, intoLabel: String)
    case floorTransfer(FloorTransfer)
    case arrive(floorId: String, label: String)

    struct FloorTransfer: Hashable {
        var fromFloorId: String
        var toFloorId: String
        var viaLabel: String
        var viaElevator: Bool
        var verticalGroupId: String?
        var departurePosM: PointM = .zero
        var arrivalPosM: PointM = .zero
    }

    var floorId: String {
        switch self {
        case .walk(let f, _, _), .turn(let f, _, _), .arrive(let f, _): return f
        case .floorTransfer(let t): return t.fromFloorId
        }
    }
}

/// Language-neutral "get back on the route" hint (the UI words it in EN or FR).
enum RejoinHint { case uTurn, turnRight, turnLeft }

struct NavigationRoute: Hashable {
    let destination: NodeKey
    let destinationLabel: String
    let accessibleOnly: Bool
    let totalDistanceM: Double
    let floorSegments: [RouteFloorSegment]
    let steps: [RouteStep]
    let nextInstruction: String
    let isArrived: Bool
    var rejoin: RejoinHint?

    var transfers: [RouteStep.FloorTransfer] { steps.compactMap { if case .floorTransfer(let t) = $0 { return t }; return nil } }
    func pointsForFloor(_ floorId: String) -> [PointM] { floorSegments.filter { $0.floorId == floorId }.flatMap(\.pointsM) }
}

struct VerticalLandingResult {
    let targetFloorId: String
    let arrivalNode: FloorNode?
    let arrivalPosM: PointM
    let verticalGroupId: String?
    let viaElevator: Bool
}

final class BuildingGraph {
    static let arrivalRadiusM = 2.5

    private struct AdjEdge { let to: NodeKey; let costM: Double; let accessible: Bool; let vertical: Bool }

    let floors: [FloorPlan]
    private let stairTransferCostPerFloorM: Double
    private let elevatorBaseCostM: Double
    private let elevatorPerFloorCostM: Double
    private let floorsById: [String: FloorPlan]
    private let floorIndexById: [String: Int]
    private var adj: [NodeKey: [AdjEdge]] = [:]
    let destinations: [DestinationItem]

    init(floors: [FloorPlan], stairTransferCostPerFloorM: Double = 15, elevatorBaseCostM: Double = 20, elevatorPerFloorCostM: Double = 3) {
        self.floors = floors
        self.stairTransferCostPerFloorM = stairTransferCostPerFloorM
        self.elevatorBaseCostM = elevatorBaseCostM
        self.elevatorPerFloorCostM = elevatorPerFloorCostM
        floorsById = Dictionary(floors.map { ($0.floorId, $0) }, uniquingKeysWith: { _, b in b })
        floorIndexById = Dictionary(floors.enumerated().map { ($1.floorId, $0) }, uniquingKeysWith: { _, b in b })

        let destTypes: Set<String> = ["room", "stairs", "elevator", "entrance", "exit", "tunnel"]
        destinations = floors.flatMap { plan -> [DestinationItem] in
            var seenRoomLabels = Set<String>()
            return plan.nodes
                .filter { destTypes.contains($0.type) && (!$0.label.isEmpty || !$0.name.isEmpty) }
                .sorted { a, b in
                    let ra = a.type != "room", rb = b.type != "room"
                    return ra != rb ? !ra : a.title < b.title
                }
                .filter { n in n.type == "room" && !n.label.isEmpty ? seenRoomLabels.insert(n.label).inserted : true }
                .map { DestinationItem(key: NodeKey(floorId: plan.floorId, nodeId: $0.id), label: $0.title, name: $0.name, type: $0.type) }
        }

        for plan in floors {
            for n in plan.nodes { adj[NodeKey(floorId: plan.floorId, nodeId: n.id), default: []] = adj[NodeKey(floorId: plan.floorId, nodeId: n.id)] ?? [] }
            for e in plan.edges {
                guard let a = plan.node(e.fromId), let b = plan.node(e.toId) else { continue }
                let dist = a.posM.distanceTo(b.posM)
                let acc = e.accessible && a.type != "stairs" && b.type != "stairs"
                let ka = NodeKey(floorId: plan.floorId, nodeId: a.id), kb = NodeKey(floorId: plan.floorId, nodeId: b.id)
                adj[ka, default: []].append(AdjEdge(to: kb, costM: dist, accessible: acc, vertical: false))
                adj[kb, default: []].append(AdjEdge(to: ka, costM: dist, accessible: acc, vertical: false))
            }
        }
        linkVerticalGroups()
    }

    func floorNumber(_ floorId: String) -> Int {
        if let dash = floorId.lastIndex(of: "-"), let n = Int(floorId[floorId.index(after: dash)...]) { return n }
        return (floorIndexById[floorId] ?? 0) + 1
    }

    private func linkVerticalGroups() {
        guard floors.count >= 2 else { return }
        let floorRank = Dictionary(floors.map { ($0.floorId, floorNumber($0.floorId)) }, uniquingKeysWith: { a, _ in a })
        var groups: [String: [(FloorPlan, FloorNode)]] = [:]
        var groupOrder: [String] = []
        for plan in floors {
            for n in plan.nodes {
                guard let g = n.verticalGroupId, !g.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
                if groups[g] == nil { groupOrder.append(g) }
                groups[g, default: []].append((plan, n))
            }
        }
        for g in groupOrder {
            let entries = groups[g]!
            var byFloor: [String: [FloorNode]] = [:]
            for (p, n) in entries { byFloor[p.floorId, default: []].append(n) }
            let floorIds = floors.map(\.floorId).filter { byFloor[$0] != nil }
            let isElevatorGroup = entries.allSatisfy { $0.1.type == "elevator" }
            if isElevatorGroup {
                for i in floorIds.indices {
                    for j in (i + 1)..<max(i + 1, floorIds.count) {
                        let fA = floorIds[i], fB = floorIds[j]
                        let delta = max(1, abs((floorRank[fB] ?? j) - (floorRank[fA] ?? i)))
                        let cost = elevatorBaseCostM + elevatorPerFloorCostM * Double(delta)
                        for na in byFloor[fA] ?? [] {
                            guard let nb = (byFloor[fB] ?? []).min(by: { $0.posM.distanceTo(na.posM) < $1.posM.distanceTo(na.posM) }) else { continue }
                            let ka = NodeKey(floorId: fA, nodeId: na.id), kb = NodeKey(floorId: fB, nodeId: nb.id)
                            adj[ka, default: []].append(AdjEdge(to: kb, costM: cost, accessible: true, vertical: true))
                            adj[kb, default: []].append(AdjEdge(to: ka, costM: cost, accessible: true, vertical: true))
                        }
                    }
                }
            } else if floorIds.count >= 2 {
                for i in 0..<(floorIds.count - 1) {
                    let fA = floorIds[i], fB = floorIds[i + 1]
                    let delta = max(1, abs((floorRank[fB] ?? (i + 1)) - (floorRank[fA] ?? i)))
                    let cost = stairTransferCostPerFloorM * Double(delta)
                    for na in byFloor[fA] ?? [] {
                        guard let nb = (byFloor[fB] ?? []).min(by: { $0.posM.distanceTo(na.posM) < $1.posM.distanceTo(na.posM) }) else { continue }
                        let elevator = na.type == "elevator" && nb.type == "elevator"
                        let ka = NodeKey(floorId: fA, nodeId: na.id), kb = NodeKey(floorId: fB, nodeId: nb.id)
                        adj[ka, default: []].append(AdjEdge(to: kb, costM: cost, accessible: elevator, vertical: true))
                        adj[kb, default: []].append(AdjEdge(to: ka, costM: cost, accessible: elevator, vertical: true))
                    }
                }
            }
        }
    }

    func floor(_ floorId: String) -> FloorPlan? { floorsById[floorId] }
    func node(_ key: NodeKey) -> FloorNode? { floorsById[key.floorId]?.node(key.nodeId) }

    /// Nearest stairwell or elevator on `floorId` within `maxRadiusM`.
    func findNearestVerticalConnector(_ floorId: String, _ posM: PointM, maxRadiusM: Double = 10, accessibleOnly: Bool = false) -> FloorNode? {
        floorsById[floorId]?.nodes
            .filter { n in
                let isVert = !(n.verticalGroupId ?? "").trimmingCharacters(in: .whitespaces).isEmpty || n.type == "stairs" || n.type == "elevator"
                return isVert && (!accessibleOnly || n.type == "elevator") && n.posM.distanceTo(posM) <= maxRadiusM
            }
            .min { $0.posM.distanceTo(posM) < $1.posM.distanceTo(posM) }
    }

    func adjacentFloorId(_ fromFloorId: String, _ floorDelta: Int) -> String? {
        guard let idx = floorIndexById[fromFloorId], floors.indices.contains(idx + floorDelta) else { return nil }
        return floors[idx + floorDelta].floorId
    }

    /// Where the user lands on `targetFloorId` (stair/elevator shafts, elevator speed, active route).
    func resolveFloorTransition(_ fromFloorId: String, _ targetFloorId: String, _ currentPosM: PointM, verticalSpeedMps: Double = 0,
                                activeRoute: NavigationRoute? = nil, explicitVerticalGroupId: String? = nil) -> VerticalLandingResult? {
        guard let fromPlan = floorsById[fromFloorId], let targetPlan = floorsById[targetFloorId] else { return nil }
        if fromFloorId == targetFloorId { return VerticalLandingResult(targetFloorId: targetFloorId, arrivalNode: nil, arrivalPosM: currentPosM, verticalGroupId: nil, viaElevator: false) }
        var targetByGroup: [String: [FloorNode]] = [:]
        for n in targetPlan.nodes { if let g = n.verticalGroupId, !g.trimmingCharacters(in: .whitespaces).isEmpty { targetByGroup[g, default: []].append(n) } }
        func nearest(_ g: String, to p: PointM) -> FloorNode? { targetByGroup[g]?.min { $0.posM.distanceTo(p) < $1.posM.distanceTo(p) } }
        func landing(_ n: FloorNode, _ g: String) -> VerticalLandingResult {
            VerticalLandingResult(targetFloorId: targetFloorId, arrivalNode: n, arrivalPosM: n.posM, verticalGroupId: g, viaElevator: n.type == "elevator")
        }

        // 1. explicit shaft
        if let g = explicitVerticalGroupId, !g.trimmingCharacters(in: .whitespaces).isEmpty, let a = nearest(g, to: currentPosM) { return landing(a, g) }
        // 2. fast vertical speed => elevator
        if abs(verticalSpeedMps) >= 0.9,
           let e = fromPlan.nodes.filter({ $0.type == "elevator" && $0.verticalGroupId.map { targetByGroup[$0] != nil } == true && $0.posM.distanceTo(currentPosM) <= 18 })
            .min(by: { $0.posM.distanceTo(currentPosM) < $1.posM.distanceTo(currentPosM) }),
           let g = e.verticalGroupId, let a = nearest(g, to: e.posM) {
            return VerticalLandingResult(targetFloorId: targetFloorId, arrivalNode: a, arrivalPosM: a.posM, verticalGroupId: g, viaElevator: true)
        }
        // 3. active route transfer in the same direction, close by
        let goingUp = floorNumber(targetFloorId) > floorNumber(fromFloorId)
        if let t = activeRoute?.transfers.first(where: { t in
            t.fromFloorId == fromFloorId && (floorNumber(t.toFloorId) > floorNumber(fromFloorId)) == goingUp &&
                t.verticalGroupId.map { targetByGroup[$0] != nil } == true && t.departurePosM.distanceTo(currentPosM) <= 14
        }), let g = t.verticalGroupId, let a = nearest(g, to: t.departurePosM) { return landing(a, g) }
        // 4. nearest connector to the target floor within 15 m
        if let d = fromPlan.nodes.filter({ $0.verticalGroupId.map { !$0.isEmpty && targetByGroup[$0] != nil } == true && $0.posM.distanceTo(currentPosM) <= 15 })
            .min(by: { $0.posM.distanceTo(currentPosM) < $1.posM.distanceTo(currentPosM) }),
           let g = d.verticalGroupId, let a = nearest(g, to: d.posM) { return landing(a, g) }
        // 5. any route transfer arriving on the target floor
        if let t = activeRoute?.transfers.first(where: { $0.toFloorId == targetFloorId && $0.verticalGroupId.map { targetByGroup[$0] != nil } == true }),
           let g = t.verticalGroupId, let a = nearest(g, to: t.arrivalPosM) { return landing(a, g) }
        // 6. shared metric frame: keep (east, north), projected on the target plan
        let projected = projectStartOntoPlan(targetPlan, currentPosM, accessibleOnly: false)?.projectedPointM ?? currentPosM
        return VerticalLandingResult(targetFloorId: targetFloorId, arrivalNode: nil, arrivalPosM: projected, verticalGroupId: nil, viaElevator: false)
    }

    func findRouteFromNode(_ start: NodeKey, _ destination: NodeKey, accessibleOnly: Bool = false, userMapHeadingRad: Double? = nil) -> NavigationRoute? {
        guard let s = node(start) else { return nil }
        return findRouteFromPosition(start.floorId, s.posM, destination, accessibleOnly: accessibleOnly, userMapHeadingRad: userMapHeadingRad)
    }

    func findRouteFromPosition(_ floorId: String, _ positionM: PointM, _ destination: NodeKey, accessibleOnly: Bool = false, userMapHeadingRad: Double? = nil) -> NavigationRoute? {
        guard let startPlan = floorsById[floorId], let destPlan = floorsById[destination.floorId], let primaryDest = node(destination) else { return nil }
        // multi-door rooms share one label on a floor: go to the closest door
        let destCandidates: Set<NodeKey> = primaryDest.type == "room" && !primaryDest.label.isEmpty
            ? Set(destPlan.nodes.filter { $0.type == "room" && $0.label == primaryDest.label }.map { NodeKey(floorId: destination.floorId, nodeId: $0.id) })
            : [destination]

        if floorId == destination.floorId,
           let nearDoor = destCandidates.compactMap(node).min(by: { positionM.distanceTo($0.posM) < positionM.distanceTo($1.posM) }),
           positionM.distanceTo(nearDoor.posM) <= Self.arrivalRadiusM {
            let lbl = nearDoor.title
            return NavigationRoute(destination: destination, destinationLabel: lbl, accessibleOnly: accessibleOnly, totalDistanceM: positionM.distanceTo(nearDoor.posM),
                                   floorSegments: [RouteFloorSegment(floorId: floorId, pointsM: [positionM, nearDoor.posM])],
                                   steps: [.arrive(floorId: destination.floorId, label: lbl)], nextInstruction: "Arrivé à \(lbl)", isArrived: true)
        }

        guard let entry = projectStartOntoPlan(startPlan, positionM, accessibleOnly: accessibleOnly) else { return nil }
        var dist: [NodeKey: Double] = [:]
        var prev: [NodeKey: NodeKey] = [:]
        var heap = MinHeap()
        for (candidate, initCost) in entry.anchors where initCost < (dist[candidate] ?? .infinity) {
            dist[candidate] = initCost
            prev[candidate] = nil
            heap.push(candidate, initCost)
        }
        var reached: NodeKey?
        while let (u, d) = heap.pop() {
            if d > (dist[u] ?? .infinity) { continue }
            if destCandidates.contains(u) { reached = u; break }
            for e in adj[u] ?? [] {
                if accessibleOnly && !e.accessible { continue }
                let nd = d + e.costM
                if nd < (dist[e.to] ?? .infinity) { dist[e.to] = nd; prev[e.to] = u; heap.push(e.to, nd) }
            }
        }
        guard let finalKey = reached, let totalCost = dist[finalKey] else { return nil }
        let finalNode = node(finalKey) ?? primaryDest
        var chain: [NodeKey] = []
        var cur: NodeKey? = finalKey
        while let c = cur { chain.append(c); cur = prev[c] }
        chain.reverse()
        return buildRouteResult(floorId, entry.projectedPointM, chain, destination, finalNode, accessibleOnly, totalCost, userMapHeadingRad)
    }

    private struct StartProjection { let projectedPointM: PointM; let anchors: [(NodeKey, Double)] }

    private func projectStartOntoPlan(_ plan: FloorPlan, _ pos: PointM, accessibleOnly: Bool) -> StartProjection? {
        var bestProj: PointM?, bestDist = Double.infinity
        var bestA: FloorNode?, bestB: FloorNode?
        for e in plan.edges {
            guard let a = plan.node(e.fromId), let b = plan.node(e.toId) else { continue }
            let acc = e.accessible && a.type != "stairs" && b.type != "stairs"
            if accessibleOnly && !acc { continue }
            let ab = b.posM - a.posM
            let lenSq = ab.x * ab.x + ab.y * ab.y
            if lenSq < 1e-6 { continue }
            let t = min(1, max(0, ((pos.x - a.posM.x) * ab.x + (pos.y - a.posM.y) * ab.y) / lenSq))
            let proj = a.posM + ab * t
            let d = pos.distanceTo(proj)
            if d < bestDist { bestDist = d; bestProj = proj; bestA = a; bestB = b }
        }
        if let p = bestProj, let a = bestA, let b = bestB {
            return StartProjection(projectedPointM: p, anchors: [(NodeKey(floorId: plan.floorId, nodeId: a.id), p.distanceTo(a.posM)),
                                                                  (NodeKey(floorId: plan.floorId, nodeId: b.id), p.distanceTo(b.posM))])
        }
        guard let nearest = plan.nodes.filter({ !accessibleOnly || $0.type != "stairs" }).min(by: { $0.posM.distanceTo(pos) < $1.posM.distanceTo(pos) }) else { return nil }
        return StartProjection(projectedPointM: nearest.posM, anchors: [(NodeKey(floorId: plan.floorId, nodeId: nearest.id), 0)])
    }

    private func buildRouteResult(_ startFloorId: String, _ projectedStart: PointM, _ chain: [NodeKey], _ destination: NodeKey, _ destNode: FloorNode,
                                  _ accessibleOnly: Bool, _ totalCostM: Double, _ userMapHeadingRad: Double?) -> NavigationRoute {
        var segments: [RouteFloorSegment] = []
        var currentFloor = startFloorId
        var pts = [projectedStart]
        for key in chain {
            guard let n = node(key) else { continue }
            if key.floorId != currentFloor {
                if pts.count >= 2 { segments.append(RouteFloorSegment(floorId: currentFloor, pointsM: pts)) }
                currentFloor = key.floorId
                pts = [n.posM]
            } else if pts.last!.distanceTo(n.posM) > 0.05 {
                pts.append(n.posM)
            }
        }
        if pts.count >= 2 { segments.append(RouteFloorSegment(floorId: currentFloor, pointsM: pts)) }
        else if segments.isEmpty { segments.append(RouteFloorSegment(floorId: startFloorId, pointsM: [projectedStart, destNode.posM])) }

        let steps = buildInstructions(startFloorId, projectedStart, chain, destNode)
        let first = segments.first?.pointsM ?? []
        return NavigationRoute(destination: destination, destinationLabel: destNode.title, accessibleOnly: accessibleOnly, totalDistanceM: totalCostM,
                               floorSegments: segments, steps: steps, nextInstruction: formatNextInstruction(first, steps, userMapHeadingRad),
                               isArrived: false, rejoin: Self.rejoinHint(first, userMapHeadingRad))
    }

    private func buildInstructions(_ startFloorId: String, _ projectedStart: PointM, _ chain: [NodeKey], _ destNode: FloorNode) -> [RouteStep] {
        struct Leg { let floorId: String; let from: PointM; let to: PointM; let label: String }
        var raw: [RouteStep] = []
        var prevFloor = startFloorId
        var prevPt = projectedStart
        var prevNode: FloorNode?
        var legs: [Leg] = []

        func flush() {
            guard let first = legs.first else { return }
            var accDist = first.from.distanceTo(first.to)
            var accHeading = atan2(first.to.x - first.from.x, first.to.y - first.from.y)
            var accLabel = first.label
            let fId = first.floorId
            for leg in legs.dropFirst() {
                let d = leg.from.distanceTo(leg.to)
                if d < 0.25 { continue }
                let h = atan2(leg.to.x - leg.from.x, leg.to.y - leg.from.y)
                let turn = angleDelta(accHeading, h)
                if abs(turn) <= degToRad(28) {
                    accDist += d
                    if accLabel.trimmingCharacters(in: .whitespaces).isEmpty && !leg.label.trimmingCharacters(in: .whitespaces).isEmpty { accLabel = leg.label }
                    accHeading = h
                } else {
                    if accDist >= 0.5 { raw.append(.walk(floorId: fId, distanceM: accDist, label: accLabel)) }
                    raw.append(.turn(floorId: fId, left: turn < 0, intoLabel: leg.label))
                    accDist = d; accHeading = h; accLabel = leg.label
                }
            }
            if accDist >= 0.5 { raw.append(.walk(floorId: fId, distanceM: accDist, label: accLabel)) }
            legs.removeAll()
        }

        for key in chain {
            guard let n = node(key) else { continue }
            if key.floorId != prevFloor {
                flush()
                let via = prevNode ?? n
                let t = RouteStep.FloorTransfer(fromFloorId: prevFloor, toFloorId: key.floorId, viaLabel: via.title,
                                                viaElevator: via.type == "elevator" && n.type == "elevator",
                                                verticalGroupId: via.verticalGroupId ?? n.verticalGroupId, departurePosM: via.posM, arrivalPosM: n.posM)
                // merge multi-flight transfers through the same shaft (MB-2 → MB-3 → MB-4)
                if case .floorTransfer(var last)? = raw.last, last.toFloorId == t.fromFloorId, last.viaElevator == t.viaElevator,
                   last.verticalGroupId == nil || last.verticalGroupId == t.verticalGroupId {
                    last.toFloorId = t.toFloorId
                    last.arrivalPosM = t.arrivalPosM
                    raw[raw.count - 1] = .floorTransfer(last)
                } else {
                    raw.append(.floorTransfer(t))
                }
                prevFloor = key.floorId; prevPt = n.posM; prevNode = n
            } else {
                if prevPt.distanceTo(n.posM) >= 0.25 {
                    let segLabel = n.type == "corridor" ? Self.cleanCorridorName(n.label) : n.label
                    legs.append(Leg(floorId: key.floorId, from: prevPt, to: n.posM, label: segLabel))
                    prevPt = n.posM
                }
                prevNode = n
            }
        }
        flush()
        raw.append(.arrive(floorId: chain.last?.floorId ?? startFloorId, label: destNode.title))
        return raw
    }

    private static func cleanCorridorName(_ raw: String) -> String {
        (raw.components(separatedBy: " - ").first ?? raw).trimmingCharacters(in: .whitespaces)
    }

    static func rejoinHint(_ firstFloorPoints: [PointM], _ userMapHeadingRad: Double?) -> RejoinHint? {
        guard firstFloorPoints.count >= 2, let h = userMapHeadingRad else { return nil }
        let a = firstFloorPoints[0], b = firstFloorPoints[1]
        if a.distanceTo(b) < 0.8 { return nil }
        let diffDeg = radToDeg(angleDelta(h, atan2(b.x - a.x, b.y - a.y)))
        if abs(diffDeg) > 135 { return .uTurn }
        if diffDeg > 45 { return .turnRight }
        if diffDeg < -45 { return .turnLeft }
        return nil
    }

    /// French developer text (the app words instructions itself, see NavStrings).
    private func formatNextInstruction(_ firstFloorPoints: [PointM], _ steps: [RouteStep], _ userMapHeadingRad: Double?) -> String {
        switch Self.rejoinHint(firstFloorPoints, userMapHeadingRad) {
        case .uTurn: return "Faites demi-tour"
        case .turnRight: return "Tournez à droite pour rejoindre l'itinéraire"
        case .turnLeft: return "Tournez à gauche pour rejoindre l'itinéraire"
        case nil: break
        }
        guard let first = steps.first else { return "Suivez l'itinéraire" }
        switch first {
        case .walk(_, let d, _):
            var suffix = ""
            if steps.count > 1 {
                switch steps[1] {
                case .turn(_, let left, _): suffix = left ? " puis à gauche" : " puis à droite"
                case .floorTransfer(let t):
                    let up = floorNumber(t.toFloorId) > floorNumber(t.fromFloorId)
                    suffix = t.viaElevator ? " vers l'ascenseur (\(t.toFloorId))" : up ? " vers l'escalier ↑ \(t.toFloorId)" : " vers l'escalier ↓ \(t.toFloorId)"
                case .arrive(_, let l): suffix = " jusqu'à \(l)"
                default: break
                }
            }
            return "Continuer tout droit sur \(max(1, Int(d))) m\(suffix)"
        case .turn(_, let left, let into): return left ? "Tourner à gauche (\(into))" : "Tourner à droite (\(into))"
        case .floorTransfer(let t):
            let up = floorNumber(t.toFloorId) > floorNumber(t.fromFloorId)
            if t.viaElevator { return "Prendre l'ascenseur (\(t.viaLabel)) → \(t.toFloorId)" }
            return up ? "Monter par l'escalier \(t.viaLabel) → \(t.toFloorId)" : "Descendre par l'escalier \(t.viaLabel) → \(t.toFloorId)"
        case .arrive(_, let l): return "Arrivé à \(l)"
        }
    }

    // MARK: search

    private static func normalizeRoomToken(_ raw: String) -> String {
        var s = raw.lowercased().replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: "_", with: "").replacingOccurrences(of: ".", with: "")
        // "m2255" -> "mb2255" so "M.2.255" matches "MB-2.255"
        let chars = Array(s)
        if chars.count >= 2, chars[0] == "m", chars[1].isNumber { s = "mb" + String(chars.dropFirst()) }
        return s
    }

    static func matchesSearchQuery(_ query: String, label: String, name: String, floorId: String = "") -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return true }
        let combined = "\(floorId) \(label) \(name)"
        if combined.localizedCaseInsensitiveContains(q) { return true }
        let nq = normalizeRoomToken(q)
        if nq.isEmpty { return true }
        return normalizeRoomToken(label).contains(nq) || normalizeRoomToken(combined).contains(nq)
    }
}

/// Binary min-heap for Dijkstra (stable enough: ties are broken by insertion order like Java's PriorityQueue in practice).
private struct MinHeap {
    private var items: [(NodeKey, Double, Int)] = []
    private var counter = 0

    mutating func push(_ k: NodeKey, _ cost: Double) {
        items.append((k, cost, counter)); counter += 1
        var i = items.count - 1
        while i > 0 {
            let p = (i - 1) / 2
            if less(i, p) { items.swapAt(i, p); i = p } else { break }
        }
    }

    mutating func pop() -> (NodeKey, Double)? {
        guard !items.isEmpty else { return nil }
        let top = items[0]
        let last = items.removeLast()
        if !items.isEmpty {
            items[0] = last
            var i = 0
            while true {
                let l = 2 * i + 1, r = l + 1
                var m = i
                if l < items.count && less(l, m) { m = l }
                if r < items.count && less(r, m) { m = r }
                if m == i { break }
                items.swapAt(i, m); i = m
            }
        }
        return (top.0, top.1)
    }

    private func less(_ a: Int, _ b: Int) -> Bool { items[a].1 != items[b].1 ? items[a].1 < items[b].1 : items[a].2 < items[b].2 }
}
