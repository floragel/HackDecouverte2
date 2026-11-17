import Foundation

// 1:1 port of com.hackdecouverte.map: GeoReference, MapAnchor, FloorPlan (pure, unit-tested).

struct Wgs84Point: Hashable {
    let latitude: Double
    let longitude: Double
    var isFinite: Bool { latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude) && (-180...180).contains(longitude) }
}

struct GeoBbox: Hashable { let minLon, minLat, maxLon, maxLat: Double }

/// Local True-North metric frame (east, north) around an origin.
struct GeoOrigin: Hashable {
    static let earthRadiusM = 6_378_137.0
    /// Centre of the 6 central elevator cars of the John Molson Building (MB), = (0, 0) of every MB plan.
    static let mbElevatorCore = GeoOrigin(latitude: 45.4952549, longitude: -73.5790496)
    /// Magnetic declination in Montréal (~14.2° West).
    static let defaultMontrealDeclinationDeg = -14.2

    let latitude: Double
    let longitude: Double
    private var cosLat: Double { cos(degToRad(latitude)) }

    func toEastNorthM(_ lat: Double, _ lon: Double) -> PointM {
        PointM(degToRad(lon - longitude) * Self.earthRadiusM * cosLat, degToRad(lat - latitude) * Self.earthRadiusM)
    }
    func toEastNorthM(_ p: Wgs84Point) -> PointM { toEastNorthM(p.latitude, p.longitude) }

    func toWgs84(_ en: PointM) -> Wgs84Point {
        Wgs84Point(latitude: latitude + radToDeg(en.y / Self.earthRadiusM), longitude: longitude + radToDeg(en.x / (Self.earthRadiusM * cosLat)))
    }

    func bboxAround(_ radiusM: Double) -> GeoBbox {
        let sw = toWgs84(PointM(-radiusM, -radiusM)), ne = toWgs84(PointM(radiusM, radiusM))
        return GeoBbox(minLon: sw.longitude, minLat: sw.latitude, maxLon: ne.longitude, maxLat: ne.latitude)
    }

    static func trueNorthBearingRad(_ from: PointM, _ to: PointM) -> Double { atan2(to.x - from.x, to.y - from.y) }
    static func unitFromTrueNorthRad(_ az: Double) -> PointM { PointM(sin(az), cos(az)) }
}

/// Projects local PDR points onto the plan: toMap(p) = origin + r·p.x + f·p.y, ψ clockwise from plan-up.
struct MapAnchor: Hashable {
    let originM: PointM
    let psiRad: Double

    func toMap(_ p: PointM) -> PointM {
        let c = cos(psiRad), s = sin(psiRad)
        return PointM(originM.x + c * p.x + s * p.y, originM.y - s * p.x + c * p.y)
    }

    func fromMap(_ m: PointM) -> PointM {
        let c = cos(psiRad), s = sin(psiRad)
        let dx = m.x - originM.x, dy = m.y - originM.y
        return PointM(c * dx - s * dy, s * dx + c * dy)
    }

    func mapHeading(_ pdrHeadingRad: Double) -> Double { wrapAnglePi(psiRad + pdrHeadingRad) }
    func localHeading(_ mapHeadingRad: Double) -> Double { wrapAnglePi(mapHeadingRad - psiRad) }
}

struct FloorNode: Hashable, Identifiable {
    let id: String
    let type: String
    var label: String = ""
    var name: String = ""
    var verticalGroupId: String?
    var x: Double = 0
    var y: Double = 0
    var eastM: Double?
    var northM: Double?
    var posM: PointM = .zero

    /// "2.210" style label, else the long name.
    var title: String { label.isEmpty ? name : label }
}

struct FloorEdge: Hashable { let fromId: String; let toId: String; var accessible = true }

struct GeoTransform: Codable, Hashable {
    let m00, m01, tx, m10, m11, ty: Double
    func apply(_ x: Double, _ y: Double) -> PointM { PointM(m00 * x + m01 * y + tx, m10 * x + m11 * y + ty) }
}

private struct FloorPlanDTO: Decodable {
    struct Dims: Decodable { var width = 100.0; var height = 100.0 }
    struct Node: Decodable {
        let id: String; let type: String
        var label: String?; var name: String?
        var vertical_group_id: String?
        var x: Double?; var y: Double?
        var east_m: Double?; var north_m: Double?
    }
    struct Edge: Decodable { let from: String; let to: String; var accessible: Bool? }
    let floor_id: String
    var svg_asset: String?
    var coordinate_note: String?
    var estimated_dimensions_meters: Dims?
    var geo_transform: GeoTransform?
    var nodes: [Node]?
    var edges: [Edge]?
}

final class FloorPlan {
    let floorId: String
    let widthM: Double
    let heightM: Double
    let nodes: [FloorNode]
    let edges: [FloorEdge]
    let svgAsset: String?
    let isGeoReferenced: Bool
    private let nodeById: [String: FloorNode]
    let labeledNodes: [FloorNode]
    /// Dominant orthogonal corridor orientation in [−π/4, π/4] relative to +y (North) (~40.8° for MB).
    private(set) var dominantCorridorAxisRad = 0.0

    init(floorId: String, widthM: Double, heightM: Double, nodes: [FloorNode], edges: [FloorEdge], svgAsset: String? = nil, isGeoReferenced: Bool = false) {
        self.floorId = floorId; self.widthM = widthM; self.heightM = heightM
        self.nodes = nodes; self.edges = edges; self.svgAsset = svgAsset; self.isGeoReferenced = isGeoReferenced
        var byId: [String: FloorNode] = [:]
        for n in nodes where byId[n.id] == nil { byId[n.id] = n } // associateBy keeps the last; ids are unique in practice
        for n in nodes { byId[n.id] = n }
        nodeById = byId
        labeledNodes = nodes.filter { !$0.label.isEmpty || !$0.name.isEmpty }
            .sorted { a, b in
                let ca = a.type == "corridor", cb = b.type == "corridor"
                return ca != cb ? !ca : a.title < b.title
            }
        dominantCorridorAxisRad = computeDominantAxis()
    }

    private func computeDominantAxis() -> Double {
        var sumSin = 0.0, sumCos = 0.0
        for e in edges {
            guard let a = nodeById[e.fromId], let b = nodeById[e.toId], a.type == "corridor", b.type == "corridor" else { continue }
            let dx = b.posM.x - a.posM.x, dy = b.posM.y - a.posM.y
            let len = hypot(dx, dy)
            if len < 1 { continue }
            let h4 = 4 * atan2(dx, dy)
            sumSin += len * sin(h4); sumCos += len * cos(h4)
        }
        return hypot(sumSin, sumCos) > 1e-3 ? atan2(sumSin, sumCos) / 4 : 0
    }

    func node(_ id: String) -> FloorNode? { nodeById[id] }

    func neighbors(_ nodeId: String) -> [FloorNode] {
        edges.compactMap { e in
            if e.fromId == nodeId { return nodeById[e.toId] }
            if e.toId == nodeId { return nodeById[e.fromId] }
            return nil
        }
    }

    static func defaultSvgAssetForFloor(_ floorId: String) -> String? {
        guard floorId.hasPrefix("MB-"), let num = Int(floorId.dropFirst(3)), (1...7).contains(num) else { return nil }
        return String(format: "floor-plans/MB/MB-floor-%02d.svg", num)
    }

    private static let noteOffsetRegex = try! NSRegularExpression(pattern: #"east\s*=\s*x\*width\s*\+\s*(-?\d+(?:\.\d+)?)\s*,\s*north\s*=\s*-\(y\*height\s*\+\s*(-?\d+(?:\.\d+)?)\)"#)

    static func parse(_ data: Data) throws -> FloorPlan {
        let dto = try JSONDecoder().decode(FloorPlanDTO.self, from: data)
        let dims = dto.estimated_dimensions_meters ?? .init()
        let gt = dto.geo_transform
        var noteOffsets: (Double, Double)?
        if let note = dto.coordinate_note,
           let m = noteOffsetRegex.firstMatch(in: note, range: NSRange(note.startIndex..., in: note)),
           let r1 = Range(m.range(at: 1), in: note), let r2 = Range(m.range(at: 2), in: note),
           let e = Double(note[r1]), let s = Double(note[r2]) {
            noteOffsets = (e, s)
        }
        let rawNodes = dto.nodes ?? []
        let anyDirectMetric = rawNodes.contains { $0.east_m != nil && $0.north_m != nil }
        let nodes: [FloorNode] = rawNodes.map { n in
            let x = n.x ?? 0, y = n.y ?? 0
            let p: PointM
            if let e = n.east_m, let no = n.north_m { p = PointM(e, no) }
            else if let gt { p = gt.apply(x, y) }
            else if let off = noteOffsets { p = PointM(x * dims.width + off.0, -(y * dims.height + off.1)) }
            else { p = PointM(x * dims.width, (1 - y) * dims.height) }
            return FloorNode(id: n.id, type: n.type, label: n.label ?? "", name: n.name ?? "", verticalGroupId: n.vertical_group_id,
                             x: x, y: y, eastM: n.east_m, northM: n.north_m, posM: p)
        }
        let edges = (dto.edges ?? []).map { FloorEdge(fromId: $0.from, toId: $0.to, accessible: $0.accessible ?? true) }
        return FloorPlan(floorId: dto.floor_id, widthM: dims.width, heightM: dims.height, nodes: nodes, edges: edges,
                         svgAsset: dto.svg_asset ?? defaultSvgAssetForFloor(dto.floor_id),
                         isGeoReferenced: gt != nil || noteOffsets != nil || anyDirectMetric)
    }

    static func parse(_ text: String) throws -> FloorPlan { try parse(Data(text.utf8)) }

    static func facingPsi(_ from: FloorNode, _ to: FloorNode) -> Double { atan2(to.posM.x - from.posM.x, to.posM.y - from.posM.y) }

    struct CorridorMatch { let projectedPointM: PointM; let distanceToEdgeM: Double; let alignedHeadingRad: Double? }

    func matchCorridor(_ pointM: PointM, _ headingRad: Double, maxLateralM: Double = 2, endMarginM: Double = 2, maxHeadingErrRad: Double = degToRad(20)) -> CorridorMatch? {
        var best: CorridorMatch?
        for e in edges where e.accessible {
            guard let a = nodeById[e.fromId], let b = nodeById[e.toId], a.type == "corridor", b.type == "corridor" else { continue }
            let ab = b.posM - a.posM
            let len = ab.norm()
            if len < 0.5 { continue }
            let u = ab * (1 / len)
            let fwd = atan2(u.x, u.y), rev = wrapAnglePi(fwd + .pi)
            let dFwd = abs(angleDelta(fwd, headingRad)), dRev = abs(angleDelta(rev, headingRad))
            if min(dFwd, dRev) > maxHeadingErrRad { continue }
            let ap = pointM - a.posM
            let along = ap.x * u.x + ap.y * u.y
            if along < -endMarginM || along > len + endMarginM { continue }
            let lateral = abs(ap.x * u.y - ap.y * u.x)
            if lateral > maxLateralM { continue }
            if best == nil || lateral < best!.distanceToEdgeM {
                best = CorridorMatch(projectedPointM: a.posM + u * along, distanceToEdgeM: lateral, alignedHeadingRad: dFwd <= dRev ? fwd : rev)
            }
        }
        return best
    }
}
