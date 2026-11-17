import Foundation

// 1:1 port of com.lync.app.data.places: CampusPlaces (free text -> place), CampusDirectory (search),
// WalkingRouter (OSRM foot routing + progress along the route).

struct GeoPoint: Hashable, Codable { let lat: Double; let lng: Double }

/// Where the user wants to go, from free text ("JMSB, MB-2.210", "H-920", "EV 3.102", "Hall").
struct Place: Hashable {
    let building: Building
    let roomLabel: String?
    let floor: Int?
    var point: GeoPoint { GeoPoint(lat: building.lat, lng: building.lng) }
    /// Only the John Molson building has indoor plans + step tracking for now.
    var hasIndoorMap: Bool { building.code == CampusPlaces.indoorBuilding }
    var title: String { roomLabel ?? building.name }
}

enum CampusPlaces {
    static let indoorBuilding = "MB"

    private static let aliases: [(String, String)] = [
        ("JMSB", "MB"), ("JOHN MOLSON", "MB"), ("MOLSON", "MB"),
        ("HALL", "H"), ("WEBSTER", "LB"), ("LIBRARY", "LB"), ("BIBLIOTHEQUE", "LB"),
        ("ENGINEERING", "EV"), ("VISUAL ARTS", "EV"), ("GREY NUNS", "GN"), ("SOEURS GRISES", "GN"),
        ("FAUBOURG", "FG"), ("VANIER", "VL"), ("PERFORM", "PC"), ("SCIENCE PAVILION", "SP"),
    ]

    /// "MB-2.210", "MB 2.210", "MB2210", "H-920", "H 920", "EV-3.102", "MB S2.330".
    private static let roomRegex = try! NSRegularExpression(pattern: #"\b(MB|H|LB|EV|GM|FG|GN|VL|SP|PC|AD|CC|FC)\s*[-.\s]?\s*(?:(S?\d{1,2})\s*\.\s*)?(\d{2,4})\b"#, options: .caseInsensitive)
    /// Bare MB-style room "2.210".
    private static let bareMbRoom = try! NSRegularExpression(pattern: #"(?<![\w.])([1-7])\.(\d{3})(?![\w.])"#)

    private static func groups(_ re: NSRegularExpression, _ s: String) -> [String]? {
        guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return (0..<m.numberOfRanges).map { i in Range(m.range(at: i), in: s).map { String(s[$0]) } ?? "" }
    }

    static func resolve(_ text: String?) -> Place? {
        let raw = (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty { return nil }
        let upper = raw.uppercased()
        if let g = groups(roomRegex, upper), let b = building(g[1].uppercased()) {
            return roomPlace(b, g[2], g[3])
        }
        let code = aliases.first { upper.contains($0.0) }?.1
            ?? Buildings.concordia.first { b in upper.range(of: "\\b\(b.code)\\b", options: .regularExpression) != nil }?.code
        if let g = groups(bareMbRoom, upper), code == nil || code == indoorBuilding {
            return roomPlace(building(indoorBuilding)!, g[1], g[2])
        }
        if let code, let b = building(code) { return Place(building: b, roomLabel: nil, floor: nil) }
        return Buildings.concordia.first { upper.contains($0.name.uppercased()) }.map { Place(building: $0, roomLabel: nil, floor: nil) }
    }

    private static func roomPlace(_ b: Building, _ floorPart: String, _ number: String) -> Place {
        if b.code == indoorBuilding {
            // JMSB: "MB-2.210" (floor 2, room 210); "MB2210" / "MB 2210" also accepted
            let floor: String, room: String
            if !floorPart.isEmpty { floor = floorPart; room = number }
            else if number.count == 4 { floor = String(number.prefix(1)); room = String(number.dropFirst()) }
            else { floor = String(number.prefix(1)); room = number }
            return Place(building: b, roomLabel: "\(b.code)-\(floor).\(room)", floor: Int(floor))
        }
        // Hall-style numbering: H-920 is on floor 9, H-1020 on floor 10, EV-3.102 on floor 3
        let floor: Int? = !floorPart.isEmpty ? Int(floorPart) : number.count >= 3 ? Int(number.dropLast(2)) : nil
        let label = !floorPart.isEmpty ? "\(b.code)-\(floorPart).\(number)" : "\(b.code)-\(floorPart + number)"
        return Place(building: b, roomLabel: label, floor: floor)
    }

    static func building(_ code: String) -> Building? { Buildings.concordia.first { $0.code.caseInsensitiveCompare(code) == .orderedSame } }

    /// Comparable key: "MB-2.210", "mb 2210", "M.2.210" -> "mb2210".
    static func roomKey(_ label: String) -> String {
        var s = String(label.lowercased().filter { $0.isLetter || $0.isNumber })
        let c = Array(s)
        if c.count >= 2, c[0] == "m", c[1].isNumber { s = "mb" + String(c.dropFirst()) }
        return s
    }
}

/// One search result: a building, or a real JMSB room of the MB 1-7 graph.
struct PlaceSuggestion: Hashable, Identifiable {
    let query: String
    let title: String
    let subtitle: String
    let building: Building
    let floor: Int?
    let isRoom: Bool
    var id: String { "\(isRoom ? "r" : "b")-\(title)" }
}

enum CampusDirectory {
    private static var cachedRooms: [PlaceSuggestion]?

    /// Every labelled MB room (same graph the indoor navigation routes on).
    static func rooms() -> [PlaceSuggestion] {
        if let r = cachedRooms { return r }
        guard let mb = CampusPlaces.building(CampusPlaces.indoorBuilding) else { return [] }
        var seen = Set<String>()
        let list = IndoorAssets.shared.graph.destinations
            .filter { $0.type == "room" && $0.label.hasPrefix("MB-") && seen.insert($0.label).inserted }
            .map { d -> PlaceSuggestion in
                let floor = d.key.floorId.split(separator: "-").last.flatMap { Int($0) }
                let sub = (d.name.components(separatedBy: " (").first ?? d.name)
                return PlaceSuggestion(query: d.label, title: d.label, subtitle: sub.prefix(1).uppercased() + sub.dropFirst(), building: mb, floor: floor, isRoom: true)
            }
            .sorted { $0.title < $1.title }
        cachedRooms = list
        return list
    }

    static func search(_ query: String, rooms: [PlaceSuggestion], limit: Int = 30) -> [PlaceSuggestion] {
        let q = query.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return [] }
        let buildings = Buildings.concordia
            .filter { $0.code.caseInsensitiveCompare(q) == .orderedSame || $0.name.localizedCaseInsensitiveContains(q) || (q.count >= 2 && $0.code.uppercased().hasPrefix(q.uppercased())) }
            .map { PlaceSuggestion(query: $0.code, title: $0.name, subtitle: $0.code, building: $0, floor: nil, isRoom: false) }
        let key = CampusPlaces.roomKey(q)
        let roomHits = rooms.filter { BuildingGraph.matchesSearchQuery(q, label: $0.title, name: $0.subtitle) || (key.count >= 2 && CampusPlaces.roomKey($0.title).contains(key)) }
        // A typed room that isn't in the MB graph (e.g. "H-920") still resolves to its building
        var typed: [PlaceSuggestion] = []
        if let p = CampusPlaces.resolve(q), let label = p.roomLabel, !p.hasIndoorMap,
           !roomHits.contains(where: { CampusPlaces.roomKey($0.title) == CampusPlaces.roomKey(label) }) {
            typed = [PlaceSuggestion(query: q, title: label, subtitle: p.building.name, building: p.building, floor: p.floor, isRoom: true)]
        }
        return Array((buildings + typed + roomHits).prefix(limit))
    }
}

// MARK: - Walking router

enum Maneuver { case depart, straight, slightLeft, left, sharpLeft, slightRight, right, sharpRight, uturn, arrive }

struct WalkStep: Hashable { let maneuver: Maneuver; let street: String; let distanceM: Double; let at: GeoPoint }

/// A walking route. `approximate` = no routing service reachable (straight line to the door).
struct WalkRoute: Hashable {
    let points: [GeoPoint]
    let distanceM: Double
    let durationS: Double
    let steps: [WalkStep]
    let approximate: Bool
    let cumulativeM: [Double]
    /// Distance along `points` of each step's maneuver ("in 80 m, turn left").
    let stepAlongM: [Double]

    init(points: [GeoPoint], distanceM: Double, durationS: Double, steps: [WalkStep], approximate: Bool) {
        self.points = points; self.distanceM = distanceM; self.durationS = durationS; self.steps = steps; self.approximate = approximate
        var acc = [Double](repeating: 0, count: points.count)
        for i in points.indices.dropFirst() { acc[i] = acc[i - 1] + Geo.distanceM(points[i - 1], points[i]) }
        cumulativeM = acc
        var from = 0
        stepAlongM = steps.map { st in
            let (along, idx) = Geo.project(points, acc, st.at, from)
            from = idx
            return along
        }
    }

    var lengthM: Double { cumulativeM.last ?? 0 }
}

/// Where the user is on the route right now.
struct WalkProgress: Hashable {
    let alongM: Double
    let remainingM: Double
    let offRouteM: Double
    let nextStep: WalkStep?
    let distanceToNextStepM: Double
    /// Route direction at the user (degrees clockwise from north), for the follow camera.
    let bearingDeg: Double

    static func of(_ route: WalkRoute, _ me: GeoPoint) -> WalkProgress {
        let (along, seg) = Geo.project(route.points, route.cumulativeM, me, 0)
        let onLine = Geo.pointAt(route.points, route.cumulativeM, along)
        let nextIdx = route.stepAlongM.firstIndex { $0 > along + 4 }
        let a = route.points.indices.contains(seg) ? route.points[seg] : me
        let b = route.points.indices.contains(seg + 1) ? route.points[seg + 1] : a
        return WalkProgress(alongM: along, remainingM: max(0, route.lengthM - along), offRouteM: Geo.distanceM(me, onLine),
                            nextStep: nextIdx.map { route.steps[$0] }, distanceToNextStepM: nextIdx.map { max(0, route.stepAlongM[$0] - along) } ?? 0,
                            bearingDeg: Geo.bearingDeg(a, b))
    }
}

/// Small flat-earth helpers, plenty for a campus-sized walk.
enum Geo {
    private static let mPerDeg = 111_320.0

    static func distanceM(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let dLat = (b.lat - a.lat) * mPerDeg
        let dLng = (b.lng - a.lng) * mPerDeg * cos(degToRad((a.lat + b.lat) / 2))
        return (dLat * dLat + dLng * dLng).squareRoot()
    }

    static func bearingDeg(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let dx = (b.lng - a.lng) * cos(degToRad((a.lat + b.lat) / 2)), dy = b.lat - a.lat
        return (radToDeg(atan2(dx, dy)) + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Closest point of the polyline to `p`, searched from segment `fromSeg`: (distance along, segment index).
    static func project(_ points: [GeoPoint], _ cumulative: [Double], _ p: GeoPoint, _ fromSeg: Int) -> (Double, Int) {
        guard points.count >= 2 else { return (0, 0) }
        let k = cos(degToRad(p.lat))
        let start = min(max(fromSeg, 0), points.count - 2)
        var best = Double.greatestFiniteMagnitude, bestAlong = 0.0, bestSeg = start
        for i in start..<(points.count - 1) {
            let a = points[i], b = points[i + 1]
            let ax = (a.lng - p.lng) * k, ay = a.lat - p.lat, bx = (b.lng - p.lng) * k, by = b.lat - p.lat
            let dx = bx - ax, dy = by - ay
            let len2 = dx * dx + dy * dy
            let t = len2 > 0 ? min(1, max(0, -(ax * dx + ay * dy) / len2)) : 0
            let cx = ax + t * dx, cy = ay + t * dy
            let d = cx * cx + cy * cy
            if d < best { best = d; bestSeg = i; bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i]) }
        }
        return (bestAlong, bestSeg)
    }

    static func pointAt(_ points: [GeoPoint], _ cumulative: [Double], _ alongM: Double) -> GeoPoint {
        guard let last = points.last else { return GeoPoint(lat: 0, lng: 0) }
        for i in points.indices.dropFirst() where cumulative[i] >= alongM {
            let seg = cumulative[i] - cumulative[i - 1]
            let t = seg > 0 ? (alongM - cumulative[i - 1]) / seg : 0
            let a = points[i - 1], b = points[i]
            return GeoPoint(lat: a.lat + t * (b.lat - a.lat), lng: a.lng + t * (b.lng - a.lng))
        }
        return last
    }
}

/// Walking directions from the free OpenStreetMap routing service (OSRM foot profile, no key).
/// Offline / service down -> straight line at walking pace, flagged `approximate`.
enum WalkingRouter {
    private static let endpoint = "https://routing.openstreetmap.de/routed-foot/route/v1/foot"
    static let walkSpeedMps = 1.3

    static func route(_ from: GeoPoint, _ to: GeoPoint) async -> WalkRoute {
        (try? await fetch(from, to)) ?? straightLine(from, to)
    }

    static func straightLine(_ from: GeoPoint, _ to: GeoPoint) -> WalkRoute {
        let d = Geo.distanceM(from, to)
        return WalkRoute(points: [from, to], distanceM: d, durationS: d / walkSpeedMps,
                         steps: [WalkStep(maneuver: .depart, street: "", distanceM: d, at: from), WalkStep(maneuver: .arrive, street: "", distanceM: 0, at: to)],
                         approximate: true)
    }

    private static func fetch(_ from: GeoPoint, _ to: GeoPoint) async throws -> WalkRoute? {
        guard let url = URL(string: "\(endpoint)/\(from.lng),\(from.lat);\(to.lng),\(to.lat)?overview=full&geometries=geojson&steps=true") else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 8)
        req.setValue("Lync-campus-app/0.1", forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return parse(data)
    }

    /// Parses an OSRM /route response (tested).
    static func parse(_ data: Data) -> WalkRoute? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any], root["code"] as? String == "Ok",
              let route = (root["routes"] as? [[String: Any]])?.first,
              let coords = (route["geometry"] as? [String: Any])?["coordinates"] as? [[Double]] else { return nil }
        let points = coords.compactMap { $0.count >= 2 ? GeoPoint(lat: $0[1], lng: $0[0]) : nil }
        guard points.count >= 2 else { return nil }
        let steps: [WalkStep] = ((route["legs"] as? [[String: Any]]) ?? []).flatMap { leg in
            ((leg["steps"] as? [[String: Any]]) ?? []).map(step)
        }
        let distance = (route["distance"] as? Double) ?? 0
        // OSRM's foot speed is optimistic in town: keep a realistic student pace
        return WalkRoute(points: points, distanceM: distance, durationS: max((route["duration"] as? Double) ?? 0, distance / walkSpeedMps), steps: steps, approximate: false)
    }

    private static func step(_ o: [String: Any]) -> WalkStep {
        let m = o["maneuver"] as? [String: Any]
        let type = m?["type"] as? String ?? "", modifier = m?["modifier"] as? String ?? ""
        let loc = m?["location"] as? [Double] ?? []
        let at = GeoPoint(lat: loc.count > 1 ? loc[1] : 0, lng: loc.first ?? 0)
        let maneuver: Maneuver
        switch (type, modifier) {
        case ("depart", _): maneuver = .depart
        case ("arrive", _): maneuver = .arrive
        case (_, "uturn"): maneuver = .uturn
        case (_, "sharp left"): maneuver = .sharpLeft
        case (_, "left"): maneuver = .left
        case (_, "slight left"): maneuver = .slightLeft
        case (_, "sharp right"): maneuver = .sharpRight
        case (_, "right"): maneuver = .right
        case (_, "slight right"): maneuver = .slightRight
        default: maneuver = .straight
        }
        return WalkStep(maneuver: maneuver, street: o["name"] as? String ?? "", distanceM: o["distance"] as? Double ?? 0, at: at)
    }
}
