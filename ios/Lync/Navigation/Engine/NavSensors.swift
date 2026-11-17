import Foundation
import CoreMotion
import CoreLocation

/// The single clock of the navigation engine: seconds since boot (same base as CoreMotion
/// timestamps), in nanoseconds. Like Android's elapsedRealtimeNanos, never wall-clock time.
enum NavClock {
    static func nowNs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1e9) }
    /// CoreMotion `CMLogItem.timestamp` (seconds since boot) -> ns.
    static func ns(uptime: TimeInterval) -> Int64 { Int64(uptime * 1e9) }
    /// A CoreLocation `Date` -> the same boot-based clock.
    static func ns(from date: Date) -> Int64 { nowNs() - Int64(Date().timeIntervalSince(date) * 1e9) }
}

/**
 * iOS counterpart of Android `PdrSensorAdapter`.
 *  - Device motion at 60 Hz (gyro + accelerometer, arbitrary yaw = Android GAME_ROTATION_VECTOR)
 *    -> swing-twist heading in `PdrTracker`.
 *  - Steps: iPhones have no per-step hardware event (Android TYPE_STEP_DETECTOR), so the engine's
 *    accelerometer detector (`FallbackStepDetector`) runs on gravity-projected user acceleration.
 *  - Barometer: `CMAltimeter` pressure (kPa -> hPa), about 1 Hz.
 *  - True north comes from CoreLocation's heading (see `GpsLocationSource`).
 */
final class PdrSensorAdapter {
    private static let gravityMss = 9.80665
    private let motion = CMMotionManager()
    private let altimeter = CMAltimeter()
    private let tracker: PdrTracker
    private let sourceProvider: () -> StepSource
    private let fallback: FallbackStepDetector
    private let onPressureSample: (Int64, Double) -> Void
    private var registered = false
    /// CoreMotion's matrix convention is checked against gravity once (device->world vs world->device).
    private var transposeMatrix: Bool?

    var rotationAvailable: Bool { motion.isDeviceMotionAvailable }
    var fallbackCapable: Bool { motion.isDeviceMotionAvailable || motion.isAccelerometerAvailable }
    var pressureAvailable: Bool { CMAltimeter.isRelativeAltitudeAvailable() }

    private let pedometer = CMPedometer()

    /// The iPhone's own stride over the last 7 days (distance / steps), or nil (no data, no permission).
    func personalStride(_ completion: @escaping (Double?) -> Void) {
        guard CMPedometer.isStepCountingAvailable(), CMPedometer.isDistanceAvailable() else { completion(nil); return }
        pedometer.queryPedometerData(from: Date().addingTimeInterval(-7 * 86_400), to: Date()) { data, _ in
            let stride = data.flatMap { d in d.distance.flatMap { StrideEstimate.fromPedometer(distanceM: $0.doubleValue, steps: d.numberOfSteps.intValue) } }
            DispatchQueue.main.async { completion(stride) }
        }
    }

    init(tracker: PdrTracker, sourceProvider: @escaping () -> StepSource, fallback: FallbackStepDetector = FallbackStepDetector(adaptive: true),
         onPressureSample: @escaping (Int64, Double) -> Void) {
        self.tracker = tracker
        self.sourceProvider = sourceProvider
        self.fallback = fallback
        self.onPressureSample = onPressureSample
        fallback.onStep = { [weak tracker] t in tracker?.onStep(t, source: .fallback) }
    }

    @discardableResult
    func start() -> Bool {
        if registered { return true }
        registered = true
        var failed = false
        if motion.isDeviceMotionAvailable {
            motion.deviceMotionUpdateInterval = 1.0 / 60
            if sourceProvider() == .fallback { fallback.start(NavClock.nowNs()) }
            let frames = CMMotionManager.availableAttitudeReferenceFrames()
            let frame: CMAttitudeReferenceFrame = frames.contains(.xArbitraryCorrectedZVertical) ? .xArbitraryCorrectedZVertical : .xArbitraryZVertical
            motion.startDeviceMotionUpdates(using: frame, to: .main) { [weak self] m, _ in
                guard let self, let m, self.registered else { return }
                self.handle(m)
            }
        } else {
            failed = true
        }
        if CMAltimeter.isRelativeAltitudeAvailable() {
            altimeter.startRelativeAltitudeUpdates(to: .main) { [weak self] data, _ in
                guard let self, let data, self.registered else { return }
                self.onPressureSample(NavClock.ns(uptime: data.timestamp), data.pressure.doubleValue * 10)
            }
        }
        return !failed
    }

    func stop() {
        guard registered else { return }
        registered = false
        motion.stopDeviceMotionUpdates()
        altimeter.stopRelativeAltitudeUpdates()
        fallback.stop()
    }

    /// Re-registers after a step-source change (resets the reference frame, like Android).
    func refreshRegistrations() {
        guard registered else { return }
        stop()
        start()
    }

    private func handle(_ m: CMDeviceMotion) {
        let t = NavClock.ns(uptime: m.timestamp)
        let g = m.gravity, a = m.userAcceleration
        // World "up" in device coordinates (Android: last row of R); iOS gravity points down.
        let up = (-g.x, -g.y, -g.z)
        let rm = m.attitude.rotationMatrix
        let asIs = [rm.m11, rm.m12, rm.m13, rm.m21, rm.m22, rm.m23, rm.m31, rm.m32, rm.m33]
        let transposed = [rm.m11, rm.m21, rm.m31, rm.m12, rm.m22, rm.m32, rm.m13, rm.m23, rm.m33]
        if transposeMatrix == nil {
            let dotA = asIs[6] * up.0 + asIs[7] * up.1 + asIs[8] * up.2
            let dotT = transposed[6] * up.0 + transposed[7] * up.1 + transposed[8] * up.2
            if abs(dotA - dotT) > 0.5 { transposeMatrix = dotT > dotA } // only decidable when the phone isn't flat
        }
        // Android-convention R (device -> world, Z up). Flat phone: both conventions agree on the yaw sign
        // only up to transposition, so until decided, use the documented CoreMotion convention (transposed).
        let r = (transposeMatrix ?? true) ? transposed : asIs
        tracker.onRotationMatrix(t, r)

        // Android linear acceleration (m/s², specific force) = −userAcceleration·g on iOS.
        let lx = -a.x * Self.gravityMss, ly = -a.y * Self.gravityMss, lz = -a.z * Self.gravityMss
        tracker.onLinearAccelerationMagnitude(t, (lx * lx + ly * ly + lz * lz).squareRoot())
        if sourceProvider() == .fallback {
            fallback.onLinearAcceleration(t, ax: lx, ay: ly, az: lz, ux: up.0, uy: up.1, uz: up.2)
        }
    }
}

struct GpsFix: Hashable {
    let wgs84: Wgs84Point
    let eastNorthM: PointM
    let accuracyM: Double
    let verticalAccuracyM: Double?
    /// GPS ground track (0 = North) when walking fast enough with a reliable course.
    let trueNorthBearingRad: Double?
    let speedMps: Double
    let magneticDeclinationDeg: Double
    let elapsedRealtimeNs: Int64
}

/**
 * iOS counterpart of Android `GpsLocationSource` (+ the compass part of the sensor adapter):
 * best-accuracy CoreLocation fixes in the MB metric frame, and `CLHeading.trueHeading`
 * (already corrected for declination) as the True-North azimuth for `PdrTracker`.
 */
final class GpsLocationSource: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    var origin: GeoOrigin
    private let onFix: (GpsFix) -> Void
    private let onHeading: (Int64, Double, Bool) -> Void
    private let onAuthorization: (Bool) -> Void
    private var running = false
    private var declinationDeg = GeoOrigin.defaultMontrealDeclinationDeg

    init(origin: GeoOrigin = .mbElevatorCore, onFix: @escaping (GpsFix) -> Void, onHeading: @escaping (Int64, Double, Bool) -> Void,
         onAuthorization: @escaping (Bool) -> Void) {
        self.origin = origin
        self.onFix = onFix
        self.onHeading = onHeading
        self.onAuthorization = onAuthorization
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .otherNavigation
        manager.headingFilter = 1
        manager.headingOrientation = .portrait
    }

    var hasLocationPermission: Bool {
        let s = manager.authorizationStatus
        return s == .authorizedWhenInUse || s == .authorizedAlways
    }

    var authorizationUndetermined: Bool { manager.authorizationStatus == .notDetermined }

    func requestPermission() { manager.requestWhenInUseAuthorization() }

    func start() {
        guard !running else { return }
        running = true
        if hasLocationPermission {
            manager.startUpdatingLocation()
            if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
            if let last = manager.location { handle(last) }
        }
    }

    func stop() {
        guard running else { return }
        running = false
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        onAuthorization(hasLocationPermission)
        if running && hasLocationPermission {
            manager.startUpdatingLocation()
            if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for l in locations { handle(l) }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading h: CLHeading) {
        let t = NavClock.ns(from: h.timestamp)
        if h.trueHeading >= 0 && h.magneticHeading >= 0 {
            declinationDeg = wrapDeg(h.trueHeading - h.magneticHeading)
        }
        let deg = h.trueHeading >= 0 ? h.trueHeading : h.magneticHeading + declinationDeg
        guard deg.isFinite, h.headingAccuracy >= 0 else { return }
        onHeading(t, wrapAnglePi(degToRad(deg)), h.headingAccuracy <= 25)
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool { false }

    private func wrapDeg(_ d: Double) -> Double { var x = d.truncatingRemainder(dividingBy: 360); if x > 180 { x -= 360 }; if x < -180 { x += 360 }; return x }

    private func handle(_ loc: CLLocation) {
        let lat = loc.coordinate.latitude, lon = loc.coordinate.longitude
        guard lat.isFinite, lon.isFinite, loc.horizontalAccuracy >= 0 else { return }
        let acc = loc.horizontalAccuracy
        let speed = max(0, loc.speed)
        let bearingOk = loc.course >= 0 && speed >= 0.9 && acc <= 15 && (loc.courseAccuracy < 0 || loc.courseAccuracy <= 28)
        onFix(GpsFix(wgs84: Wgs84Point(latitude: lat, longitude: lon), eastNorthM: origin.toEastNorthM(lat, lon), accuracyM: acc,
                     verticalAccuracyM: loc.verticalAccuracy >= 0 ? loc.verticalAccuracy : nil,
                     trueNorthBearingRad: bearingOk ? degToRad(loc.course) : nil, speedMps: speed,
                     magneticDeclinationDeg: declinationDeg, elapsedRealtimeNs: NavClock.ns(from: loc.timestamp)))
    }
}
