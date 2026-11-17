import Foundation

// 1:1 port of FloorVectorPlan.kt (aligned MB SVG walls) and OsmSurroundings.kt (streets around MB).

struct MetricBounds: Hashable {
    let minEastM, minNorthM, maxEastM, maxNorthM: Double
    var widthM: Double { maxEastM - minEastM }
    var heightM: Double { maxNorthM - minNorthM }
}

/// Wall contours of one floor in True-North metres ((0,0) = MB elevator core, +y = north).
final class FloorVectorPlan {
    let floorId: String
    let viewBoundsM: MetricBounds
    let contentBoundsM: MetricBounds
    /// Interleaved (east, north) pairs.
    let packedEastNorthM: [Float]
    let contourStartPairIndex: [Int]
    let contourPointCounts: [Int]
    let contourClosed: [Bool]

    init(floorId: String, viewBoundsM: MetricBounds, contentBoundsM: MetricBounds, packedEastNorthM: [Float],
         contourStartPairIndex: [Int], contourPointCounts: [Int], contourClosed: [Bool]) {
        self.floorId = floorId; self.viewBoundsM = viewBoundsM; self.contentBoundsM = contentBoundsM
        self.packedEastNorthM = packedEastNorthM; self.contourStartPairIndex = contourStartPairIndex
        self.contourPointCounts = contourPointCounts; self.contourClosed = contourClosed
    }

    var contourCount: Int { contourPointCounts.count }
    var totalPoints: Int { packedEastNorthM.count / 2 }

    enum ParseError: Error { case noPath, noD, unterminated }

    static func parseSvg(_ floorId: String, _ svgText: String) throws -> FloorVectorPlan {
        let s = Array(svgText.utf8)
        func find(_ needle: String, from: Int = 0) -> Int {
            let n = Array(needle.utf8)
            guard n.count <= s.count, from <= s.count - n.count else { return -1 }
            var i = from
            while i <= s.count - n.count {
                if s[i] == n[0] && Array(s[i..<(i + n.count)]) == n { return i }
                i += 1
            }
            return -1
        }
        func findByte(_ b: UInt8, from: Int) -> Int { var i = from; while i < s.count { if s[i] == b { return i }; i += 1 }; return -1 }
        func str(_ a: Int, _ b: Int) -> String { String(decoding: s[a..<b], as: UTF8.self) }
        func nums(_ text: String) -> [Double] { text.split(whereSeparator: { $0 == " " || $0 == "," || $0.isNewline || $0 == "\t" }).compactMap { Double($0) } }

        // viewBox (SVG y = −north)
        var viewBox = MetricBounds(minEastM: -44, minNorthM: -40, maxEastM: 50, maxNorthM: 34)
        let vbIdx = find("viewBox=")
        if vbIdx >= 0, vbIdx + 8 < s.count, s[vbIdx + 8] == UInt8(ascii: "\"") || s[vbIdx + 8] == UInt8(ascii: "'") {
            let end = findByte(s[vbIdx + 8], from: vbIdx + 9)
            if end > 0 {
                let p = nums(str(vbIdx + 9, end))
                if p.count >= 4 { viewBox = MetricBounds(minEastM: p[0], minNorthM: -(p[1] + p[3]), maxEastM: p[0] + p[2], maxNorthM: -p[1]) }
            }
        }
        var wallsIdx = find("id=\"walls-and-text\"")
        if wallsIdx < 0 { wallsIdx = find("id='walls-and-text'") }
        let searchFrom = max(0, wallsIdx)
        var m: [Double] = [1, 0, 0, 1, 0, 0]
        let tagEnd = findByte(UInt8(ascii: ">"), from: searchFrom)
        if tagEnd > 0 {
            let header = str(searchFrom, tagEnd)
            if let r = header.range(of: "matrix("), let close = header[r.upperBound...].firstIndex(of: ")") {
                let v = nums(String(header[r.upperBound..<close]))
                if v.count == 6 { m = v }
            }
        }
        let pathStart = find("<path", from: searchFrom)
        guard pathStart >= 0 else { throw ParseError.noPath }
        // d attribute
        var dIdx = -1
        var i = pathStart + 5
        scan: while i < s.count {
            let ch = s[i]
            if ch == UInt8(ascii: ">") { break }
            if (ch == UInt8(ascii: "d") || ch == UInt8(ascii: "D")) && [32, 9, 10, 13].contains(s[i - 1]) {
                var j = i + 1
                while j < s.count && [32, 9, 10, 13].contains(s[j]) { j += 1 }
                if j < s.count && s[j] == UInt8(ascii: "=") {
                    j += 1
                    while j < s.count && [32, 9, 10, 13].contains(s[j]) { j += 1 }
                    if j < s.count && (s[j] == UInt8(ascii: "\"") || s[j] == UInt8(ascii: "'")) { dIdx = j; break scan }
                }
            }
            i += 1
        }
        guard dIdx >= 0 else { throw ParseError.noD }
        let dEnd = findByte(s[dIdx], from: dIdx + 1)
        guard dEnd > dIdx else { throw ParseError.unterminated }
        return decodePathData(floorId, viewBox, m, s, dIdx + 1, dEnd)
    }

    private static func decodePathData(_ floorId: String, _ viewBox: MetricBounds, _ m: [Double], _ s: [UInt8], _ start: Int, _ end: Int) -> FloorVectorPlan {
        let a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5]
        var coords: [Float] = []; coords.reserveCapacity(16_384)
        var starts: [Int] = [], counts: [Int] = [], closed: [Bool] = []
        var curStartPair = -1, curPairs = 0
        var cmd = UInt8(ascii: "M")
        var rx = 0.0, ry = 0.0
        var minE = Double.infinity, minN = Double.infinity, maxE = -Double.infinity, maxN = -Double.infinity

        func finish(_ isClosed: Bool) {
            if curStartPair >= 0 && curPairs >= 2 { starts.append(curStartPair); counts.append(curPairs); closed.append(isClosed) }
            else if curStartPair >= 0 && curPairs == 1 { coords.removeLast(2) }
            curStartPair = -1; curPairs = 0
        }
        func emit(_ x: Double, _ y: Double) {
            let east = a * x + c * y + e, north = -(b * x + d * y + f)
            minE = min(minE, east); maxE = max(maxE, east); minN = min(minN, north); maxN = max(maxN, north)
            coords.append(Float(east)); coords.append(Float(north)); curPairs += 1
        }
        var idx = start
        func skipSep() { while idx < end, [32, 44, 10, 13, 9].contains(s[idx]) { idx += 1 } }
        func readNumber() -> Double? {
            skipSep()
            guard idx < end else { return nil }
            let first = s[idx]
            guard (48...57).contains(first) || first == 45 || first == 43 || first == 46 else { return nil }
            let numStart = idx
            if first == 45 || first == 43 { idx += 1 }
            var seenDot = false
            while idx < end {
                let ch = s[idx]
                if (48...57).contains(ch) { idx += 1 }
                else if ch == 46 && !seenDot { seenDot = true; idx += 1 }
                else if ch == 101 || ch == 69 { idx += 1; if idx < end && (s[idx] == 43 || s[idx] == 45) { idx += 1 } }
                else { break }
            }
            return Double(String(decoding: s[numStart..<idx], as: UTF8.self))
        }
        func isLetter(_ ch: UInt8) -> Bool { (65...90).contains(ch) || (97...122).contains(ch) }

        while idx < end {
            skipSep()
            if idx >= end { break }
            let ch = s[idx]
            if isLetter(ch) {
                idx += 1
                switch ch {
                case UInt8(ascii: "Z"), UInt8(ascii: "z"): finish(true); continue
                case UInt8(ascii: "M"), UInt8(ascii: "m"): finish(false); curStartPair = coords.count / 2; cmd = ch
                default: cmd = ch
                }
            }
            switch cmd {
            case UInt8(ascii: "M"):
                guard let x = readNumber(), let y = readNumber() else { continue }
                if curStartPair < 0 { curStartPair = coords.count / 2 }
                rx = x; ry = y; emit(rx, ry); cmd = UInt8(ascii: "L")
            case UInt8(ascii: "m"):
                guard let dx = readNumber(), let dy = readNumber() else { continue }
                if curStartPair < 0 { curStartPair = coords.count / 2 }
                rx += dx; ry += dy; emit(rx, ry); cmd = UInt8(ascii: "l")
            case UInt8(ascii: "L"):
                guard let x = readNumber(), let y = readNumber() else { continue }
                rx = x; ry = y; emit(rx, ry)
            case UInt8(ascii: "l"):
                guard let dx = readNumber(), let dy = readNumber() else { continue }
                rx += dx; ry += dy; emit(rx, ry)
            case UInt8(ascii: "H"): guard let x = readNumber() else { continue }; rx = x; emit(rx, ry)
            case UInt8(ascii: "h"): guard let dx = readNumber() else { continue }; rx += dx; emit(rx, ry)
            case UInt8(ascii: "V"): guard let y = readNumber() else { continue }; ry = y; emit(rx, ry)
            case UInt8(ascii: "v"): guard let dy = readNumber() else { continue }; ry += dy; emit(rx, ry)
            default:
                if readNumber() == nil { idx += 1 } // unknown command or stray char: skip (avoids an endless loop)
            }
        }
        finish(false)
        let content = minE.isFinite ? MetricBounds(minEastM: minE, minNorthM: minN, maxEastM: maxE, maxNorthM: maxN) : viewBox
        return FloorVectorPlan(floorId: floorId, viewBoundsM: viewBox, contentBoundsM: content, packedEastNorthM: coords,
                               contourStartPairIndex: starts, contourPointCounts: counts, contourClosed: closed)
    }
}

// MARK: - OpenStreetMap surroundings

struct OsmBuilding: Hashable { let id: String; let name: String; let ref: String; let isFocusBuilding: Bool; let polygonM: [PointM]; let labelCenterM: PointM }
struct OsmRoad: Hashable { let kind: String; let name: String; let widthM: Double; let polylineM: [PointM] }
struct OsmPark: Hashable { let name: String; let polygonM: [PointM] }
struct OsmPoi: Hashable { let kind: String; let name: String; let positionM: PointM }

final class OsmSurroundings {
    let origin: GeoOrigin
    let parks: [OsmPark]
    let buildings: [OsmBuilding]
    let roads: [OsmRoad]
    let pois: [OsmPoi]

    init(origin: GeoOrigin, parks: [OsmPark], buildings: [OsmBuilding], roads: [OsmRoad], pois: [OsmPoi]) {
        self.origin = origin; self.parks = parks; self.buildings = buildings; self.roads = roads; self.pois = pois
    }

    static let roadWidthsM: [String: Double] = [
        "primary": 11, "primary_link": 8, "secondary": 10, "secondary_link": 7.5, "tertiary": 8, "residential": 7,
        "living_street": 6, "unclassified": 6.5, "pedestrian": 5.5, "service": 3.8, "footway": 2.2, "cycleway": 2.2,
    ]

    private struct BundleDTO: Codable {
        struct B: Codable { var id: String?; var name: String?; var ref: String?; var is_focus: Bool?; var points: [[Double]]? }
        struct R: Codable { var kind: String?; var name: String?; var width_m: Double?; var points: [[Double]]? }
        struct P: Codable { var name: String?; var points: [[Double]]? }
        struct I: Codable { var kind: String?; var name: String?; var x: Double?; var y: Double? }
        let origin_lat: Double
        let origin_lon: Double
        var parks: [P]?; var buildings: [B]?; var roads: [R]?; var pois: [I]?
    }

    private static func pts(_ raw: [[Double]]?) -> [PointM] { (raw ?? []).compactMap { $0.count >= 2 ? PointM($0[0], $0[1]) : nil } }

    static func parseJson(_ data: Data) throws -> OsmSurroundings {
        let dto = try JSONDecoder().decode(BundleDTO.self, from: data)
        let parks = (dto.parks ?? []).compactMap { p -> OsmPark? in let v = pts(p.points); return v.count >= 3 ? OsmPark(name: p.name ?? "", polygonM: v) : nil }
        let buildings = (dto.buildings ?? []).compactMap { b -> OsmBuilding? in
            let v = pts(b.points)
            return v.count >= 3 ? OsmBuilding(id: b.id ?? "", name: b.name ?? "", ref: b.ref ?? "", isFocusBuilding: b.is_focus ?? false, polygonM: v, labelCenterM: centroid(v)) : nil
        }
        let roads = (dto.roads ?? []).compactMap { r -> OsmRoad? in let v = pts(r.points); return v.count >= 2 ? OsmRoad(kind: r.kind ?? "", name: r.name ?? "", widthM: r.width_m ?? 6, polylineM: v) : nil }
        let pois = (dto.pois ?? []).map { OsmPoi(kind: $0.kind ?? "", name: $0.name ?? "", positionM: PointM($0.x ?? 0, $0.y ?? 0)) }
        return OsmSurroundings(origin: GeoOrigin(latitude: dto.origin_lat, longitude: dto.origin_lon), parks: parks, buildings: buildings, roads: roads, pois: pois)
    }

    func toJsonData() throws -> Data {
        func raw(_ p: [PointM]) -> [[Double]] { p.map { [$0.x, $0.y] } }
        let dto = BundleDTO(origin_lat: origin.latitude, origin_lon: origin.longitude,
                            parks: parks.map { .init(name: $0.name, points: raw($0.polygonM)) },
                            buildings: buildings.map { .init(id: $0.id, name: $0.name, ref: $0.ref, is_focus: $0.isFocusBuilding, points: raw($0.polygonM)) },
                            roads: roads.map { .init(kind: $0.kind, name: $0.name, width_m: $0.widthM, points: raw($0.polylineM)) },
                            pois: pois.map { .init(kind: $0.kind, name: $0.name, x: $0.positionM.x, y: $0.positionM.y) })
        return try JSONEncoder().encode(dto)
    }

    /// Raw OpenStreetMap `/api/0.6/map` (or Overpass) XML -> metric surroundings at `origin`.
    static func parseOsmXml(_ xml: Data, origin: GeoOrigin, focusBuildingRef: String = "MB") -> OsmSurroundings {
        let collector = OsmXmlCollector()
        let parser = XMLParser(data: xml)
        parser.delegate = collector
        parser.parse()
        var nodes: [String: PointM] = [:]
        var pois: [OsmPoi] = []
        for n in collector.nodes {
            let p = origin.toEastNorthM(n.lat, n.lon)
            nodes[n.id] = p
            if n.tags["railway"] == "subway_entrance" || n.tags["station"] == "subway" { pois.append(OsmPoi(kind: "metro", name: n.tags["name"] ?? "Métro", positionM: p)) }
        }
        var parks: [OsmPark] = [], buildings: [OsmBuilding] = [], roads: [OsmRoad] = []
        for w in collector.ways {
            let tags = w.tags
            if tags["indoor"] != nil { continue }
            if tags["tunnel"] == "yes", let h = tags["highway"], ["footway", "steps", "corridor"].contains(h) { continue }
            let p = w.refs.compactMap { nodes[$0] }
            if p.count < 2 { continue }
            let poly = p.count > 3 && p.first == p.last ? Array(p.dropLast()) : p
            if tags["building"] != nil && p.count >= 3 {
                let ref = tags["ref"] ?? "", name = tags["name"] ?? ""
                let focus = (!focusBuildingRef.isEmpty && ref == focusBuildingRef) || name.localizedCaseInsensitiveContains("Molson")
                buildings.append(OsmBuilding(id: w.id, name: name, ref: ref, isFocusBuilding: focus, polygonM: poly, labelCenterM: centroid(poly)))
            } else if let h = tags["highway"], let width = roadWidthsM[h] {
                if h == "footway", let fw = tags["footway"], ["sidewalk", "crossing"].contains(fw) { continue }
                roads.append(OsmRoad(kind: h, name: tags["name"] ?? "", widthM: width, polylineM: p))
            } else if p.count >= 3, ["park", "garden", "pitch"].contains(tags["leisure"] ?? "") || ["grass", "recreation_ground"].contains(tags["landuse"] ?? "") {
                parks.append(OsmPark(name: tags["name"] ?? "", polygonM: poly))
            }
        }
        return OsmSurroundings(origin: origin, parks: parks, buildings: buildings, roads: roads, pois: pois)
    }

    private static func centroid(_ p: [PointM]) -> PointM {
        let n = Double(max(1, p.count))
        return PointM(p.reduce(0) { $0 + $1.x } / n, p.reduce(0) { $0 + $1.y } / n)
    }
}

private final class OsmXmlCollector: NSObject, XMLParserDelegate {
    struct Node { let id: String; let lat: Double; let lon: Double; var tags: [String: String] = [:] }
    struct Way { let id: String; var refs: [String] = []; var tags: [String: String] = [:] }
    var nodes: [Node] = []
    var ways: [Way] = []
    private var currentNode: Node?
    private var currentWay: Way?

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes a: [String: String] = [:]) {
        switch name {
        case "node":
            if let id = a["id"], let lat = a["lat"].flatMap(Double.init), let lon = a["lon"].flatMap(Double.init) { currentNode = Node(id: id, lat: lat, lon: lon) }
        case "way": currentWay = Way(id: a["id"] ?? "")
        case "nd": if let r = a["ref"] { currentWay?.refs.append(r) }
        case "tag":
            guard let k = a["k"], !k.isEmpty else { return }
            let v = a["v"] ?? ""
            if currentWay != nil { currentWay?.tags[k] = v } else { currentNode?.tags[k] = v }
        default: break
        }
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        if name == "node", let n = currentNode { nodes.append(n); currentNode = nil }
        if name == "way", let w = currentWay { ways.append(w); currentWay = nil }
    }
}
