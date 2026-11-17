import Foundation

/// Bundled indoor data (folder reference `Indoor/` in the app bundle):
/// `floors/floorplan-MB-1..7.json`, `floor-plans/MB/MB-floor-01..07.svg`, `osm/mb_surroundings.json`.
/// Same files as the Android assets.
final class IndoorAssets {
    static let shared = IndoorAssets()

    private let root: URL? = Bundle.main.resourceURL?.appendingPathComponent("Indoor", isDirectory: true)
    private let lock = NSLock()
    private var _floors: [FloorPlan]?
    private var _graph: BuildingGraph?

    /// MB-1 ... MB-7, sorted by floor number (Android `loadBundledFloors`).
    var floors: [FloorPlan] {
        lock.lock(); defer { lock.unlock() }
        if let f = _floors { return f }
        var loaded: [FloorPlan] = []
        if let dir = root?.appendingPathComponent("floors"),
           let files = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
            for file in files.sorted() where file.hasSuffix(".json") {
                if let data = try? Data(contentsOf: dir.appendingPathComponent(file)), let plan = try? FloorPlan.parse(data),
                   !loaded.contains(where: { $0.floorId == plan.floorId }) {
                    loaded.append(plan)
                }
            }
        }
        let sorted = loaded.sorted { Self.floorNumber($0.floorId) < Self.floorNumber($1.floorId) }
        _floors = sorted
        return sorted
    }

    var graph: BuildingGraph {
        if let g = lock.withLock({ _graph }) { return g }
        let g = BuildingGraph(floors: floors)
        lock.withLock { _graph = g }
        return g
    }

    static func floorNumber(_ floorId: String) -> Int {
        floorId.split(separator: "-").last.flatMap { Int($0) } ?? .max
    }

    func svgText(_ asset: String) -> String? {
        guard let url = root?.appendingPathComponent(asset) else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    func bundledOsm() -> OsmSurroundings? {
        guard let url = root?.appendingPathComponent("osm/mb_surroundings.json"), let data = try? Data(contentsOf: url) else { return nil }
        return try? OsmSurroundings.parseJson(data)
    }
}

/// iOS counterpart of `OsmNetworkLoader`: bundled MB surroundings / disk cache, then a live
/// refresh from the OpenStreetMap API.
enum OsmNetworkLoader {
    private static var cacheDir: URL? {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("osm", isDirectory: true)
        if let dir { try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true) }
        return dir
    }

    private static func cacheFile(_ o: GeoOrigin) -> URL? {
        cacheDir?.appendingPathComponent(String(format: "osm_%.4f_%.4f.json", o.latitude, o.longitude))
    }

    static func loadInitial(_ origin: GeoOrigin = .mbElevatorCore) -> OsmSurroundings? {
        if let f = cacheFile(origin), let data = try? Data(contentsOf: f), let cached = try? OsmSurroundings.parseJson(data) { return cached }
        return IndoorAssets.shared.bundledOsm()
    }

    static func fetchLive(_ origin: GeoOrigin, radiusM: Double = 280, focusBuildingRef: String = "MB") async -> OsmSurroundings? {
        let b = origin.bboxAround(radiusM)
        guard let url = URL(string: String(format: "https://api.openstreetmap.org/api/0.6/map?bbox=%.6f,%.6f,%.6f,%.6f", b.minLon, b.minLat, b.maxLon, b.maxLat)) else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.setValue("Lync-campus-app/0.1", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await URLSession.shared.data(for: req), (200..<300).contains((resp as? HTTPURLResponse)?.statusCode ?? 0) else { return nil }
        let parsed = OsmSurroundings.parseOsmXml(data, origin: origin, focusBuildingRef: focusBuildingRef)
        guard !parsed.buildings.isEmpty || !parsed.roads.isEmpty else { return nil }
        if let f = cacheFile(origin), let json = try? parsed.toJsonData() { try? json.write(to: f) }
        return parsed
    }
}
