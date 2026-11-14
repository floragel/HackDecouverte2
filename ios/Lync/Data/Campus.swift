import Foundation

/// Main Concordia buildings (SGW downtown + Loyola). Same list as Android `Buildings.kt`.
enum Buildings {
    static let concordia: [Building] = [
        Building(code: "H", name: "Hall building", lat: 45.49726, lng: -73.57895, radiusM: 45),
        Building(code: "LB", name: "Webster Library", lat: 45.49685, lng: -73.57800, radiusM: 45),
        Building(code: "EV", name: "EV building", lat: 45.49557, lng: -73.57790, radiusM: 50),
        Building(code: "MB", name: "John Molson (JMSB)", lat: 45.49535, lng: -73.57905, radiusM: 40),
        Building(code: "GM", name: "Guy-De Maisonneuve", lat: 45.49595, lng: -73.57880, radiusM: 25),
        Building(code: "FG", name: "Faubourg", lat: 45.49425, lng: -73.57815, radiusM: 35),
        Building(code: "GN", name: "Grey Nuns", lat: 45.49360, lng: -73.57720, radiusM: 60),
        Building(code: "VL", name: "Vanier Library, Loyola", lat: 45.45900, lng: -73.63860, radiusM: 40),
        Building(code: "SP", name: "Science Pavilion, Loyola", lat: 45.45780, lng: -73.64140, radiusM: 50),
        Building(code: "PC", name: "PERFORM Centre, Loyola", lat: 45.45710, lng: -73.63740, radiusM: 40),
        Building(code: "AD", name: "Loyola Administration", lat: 45.45820, lng: -73.64000, radiusM: 40),
    ]

    static func distanceM(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
        let dLat = (lat2 - lat1) * 111_320
        let dLng = (lng2 - lng1) * 111_320 * cos((lat1 + lat2) / 2 * .pi / 180)
        return (dLat * dLat + dLng * dLng).squareRoot()
    }

    static func at(_ lat: Double, _ lng: Double) -> Building? {
        concordia.map { ($0, distanceM(lat, lng, $0.lat, $0.lng)) }.filter { $0.1 <= $0.0.radiusM }.min { $0.1 < $1.1 }?.0
    }

    /// Groups visible friends by the building they're in. Buildings with me first, then most people.
    static func presence(_ pins: [MapPin], myPos: (Double, Double)?) -> [BuildingPresence] {
        let mine = myPos.flatMap { at($0.0, $0.1) }
        var by: [Building: [Profile]] = [:]
        for p in pins { if let b = at(p.lat, p.lng) { by[b, default: []].append(p.profile) } }
        return by.map { BuildingPresence(building: $0.key, people: $0.value, includesMe: $0.key == mine) }
            // stable order (ties by building code) so cards don't reshuffle as friends drift
            .sorted { a, b in
                let ka = (a.includesMe ? 1 : 0, a.people.count), kb = (b.includesMe ? 1 : 0, b.people.count)
                return ka != kb ? ka > kb : a.building.code < b.building.code
            }
    }
}

/// Room search on the map (JMSB rooms, floor-number format). Same as Android `Rooms.kt`.
struct Room: Hashable, Identifiable {
    let name: String, buildingCode: String, buildingName: String, floor: Int, roomNumber: String
    var id: String { name }
}

enum Rooms {
    static let sampleRooms: [Room] = {
        let floors: [Int: [String]] = [1: ["101", "105", "110", "125"], 2: ["201", "204", "210", "230"], 3: ["301", "305", "315", "340"],
                                       4: ["401", "405", "415", "430"], 5: ["501", "510", "520", "545"], 6: ["602", "615", "630"], 7: ["705", "715", "740"]]
        return floors.keys.sorted().flatMap { f in floors[f]!.map { Room(name: "\(f)-\($0)", buildingCode: "MB", buildingName: "John Molson (JMSB)", floor: f, roomNumber: $0) } }
    }()

    static func search(_ query: String) -> [Room] {
        let q = query.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return sampleRooms }
        return sampleRooms.filter { $0.name.localizedCaseInsensitiveContains(q) || $0.buildingCode.localizedCaseInsensitiveContains(q) || $0.buildingName.localizedCaseInsensitiveContains(q) }
    }
}

/// All Concordia undergraduate programs (165), bundled as concordia_programs.json.
enum ProgramsCatalog {
    static let all: [Program] = {
        guard let url = Bundle.main.url(forResource: "concordia_programs", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([Program].self, from: data)) ?? []
    }()

    static func search(_ query: String) -> [Program] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return all }
        return all.filter { $0.name.lowercased().contains(q) || $0.degree.lowercased().contains(q) }
            .sorted { ($0.name.lowercased().hasPrefix(q) ? 0 : 1) < ($1.name.lowercased().hasPrefix(q) ? 0 : 1) }
    }
}

/// Small local preferences (Android `SessionStore`).
enum Prefs {
    private static let d = UserDefaults.standard
    static var accentHex: String { get { d.string(forKey: "accent") ?? "#912338" } set { d.set(newValue, forKey: "accent") } }
    static var accent2Hex: String { get { d.string(forKey: "accent2") ?? "#D4A12A" } set { d.set(newValue, forKey: "accent2") } }
    static var notifications: Bool { get { d.object(forKey: "notifications") as? Bool ?? true } set { d.set(newValue, forKey: "notifications") } }
}
