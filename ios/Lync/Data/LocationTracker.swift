import Foundation
import CoreLocation
import Observation

/**
 * Single source of the user's live position (Android: LocationTracker + LocationSharingService).
 *
 *  - GPS runs while someone "holds" it: sharing (also in the background, with the blue
 *    location indicator) and/or the map screen (to draw your own dot, even in ghost mode).
 *  - Positions are pushed only when sharing is on, throttled (every 15 s or after 20 m).
 */
@Observable
final class LocationTracker: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private weak var repo: CampusRepository?
    private var holders = Set<String>()
    private var lastPush: (Double, Double, Date)?
    private var sharing = false

    var position: (lat: Double, lng: Double)?
    var authorization: CLAuthorizationStatus

    init(repo: CampusRepository) {
        self.repo = repo
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 10
        manager.pausesLocationUpdatesAutomatically = false
    }

    var hasPermission: Bool { authorization == .authorizedWhenInUse || authorization == .authorizedAlways }

    func requestPermission() { manager.requestWhenInUseAuthorization() }

    func acquire(_ holder: String) {
        holders.insert(holder)
        if hasPermission { manager.startUpdatingLocation() }
    }

    func release(_ holder: String) {
        holders.remove(holder)
        if holders.isEmpty { manager.stopUpdatingLocation() }
    }

    /// Start / stop sharing to match the user's setting.
    func sync(sharingOn: Bool) {
        sharing = sharingOn && hasPermission
        manager.allowsBackgroundLocationUpdates = sharing
        manager.showsBackgroundLocationIndicator = sharing
        if sharing { acquire("sharing") } else { release("sharing"); lastPush = nil }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorization = manager.authorizationStatus
        if hasPermission && !holders.isEmpty { manager.startUpdatingLocation() }
        NotificationCenter.default.post(name: .lyncLocationPermissionChanged, object: nil)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        let p = (loc.coordinate.latitude, loc.coordinate.longitude)
        position = (p.0, p.1)
        guard sharing, let repo, let me = repo.me, me.locationSharing != .off else { return }
        let now = Date()
        let moved = lastPush.map { Buildings.distanceM($0.0, $0.1, p.0, p.1) } ?? .greatestFiniteMagnitude
        if lastPush == nil || now.timeIntervalSince(lastPush!.2) >= 15 || moved >= 20 {
            lastPush = (p.0, p.1, now)
            Task { try? await repo.pushLocation(lat: p.0, lng: p.1) }
        }
    }
}

extension Notification.Name { static let lyncLocationPermissionChanged = Notification.Name("lyncLocationPermissionChanged") }
