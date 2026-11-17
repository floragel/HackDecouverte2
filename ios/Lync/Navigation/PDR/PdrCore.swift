import Foundation

// 1:1 port of com.hackdecouverte.pdr (PdrModels, OrientationProcessor, StepDetectorEngine, PathGeometry).
// Pure Swift, no iOS frameworks: unit-tested like the Kotlin version. Timestamps are nanoseconds.

enum PdrPhase { case idle, calibrating, settling, tracking, paused }
enum StepSource { case none, hardware, fallback }
enum PdrWarning: Hashable {
    case rotationSensorMissing, stepDetectorMissing, fallbackSensorMissing, activityPermissionDenied, sensorRegistrationFailed
    case calibrationFailedNoSteps, calibrationFailedImplausibleLength, calibrationFailedNoHeading, sessionInterrupted
}

struct PointM: Hashable {
    var x: Double
    var y: Double
    init(_ x: Double, _ y: Double) { self.x = x; self.y = y }
    static let zero = PointM(0, 0)
    static func + (a: PointM, b: PointM) -> PointM { PointM(a.x + b.x, a.y + b.y) }
    static func - (a: PointM, b: PointM) -> PointM { PointM(a.x - b.x, a.y - b.y) }
    static func * (a: PointM, k: Double) -> PointM { PointM(a.x * k, a.y * k) }
    func norm() -> Double { hypot(x, y) }
    func distanceTo(_ o: PointM) -> Double { hypot(o.x - x, o.y - y) }
    var isFinite: Bool { x.isFinite && y.isFinite }
}

struct Milestone: Hashable { let position: PointM; let distanceM: Double }

struct SensorStatus: Hashable {
    var rotationAvailable = true
    var stepDetectorAvailable = false
    var fallbackCapable = false
    var permissionNeeded = false
    var permissionGranted = false
    var registrationFailed = false
}

struct PdrState {
    var phase: PdrPhase = .idle
    var position: PointM = .zero
    var origin: PointM = .zero
    var headingRadians: Double = 0
    var headingValid = false
    var tilted = false
    var lengthPerStepM: Double?
    var currentStepLengthM: Double?
    var cadenceStepsPerMin: Double?
    var baseCadenceHz: Double?
    var stepLengthManual = false
    var distanceM: Double = 0
    var stepCount = 0
    var calibrationStepCount = 0
    var droppedStepCount = 0
    var rawPath: [PointM] = [.zero]
    var milestones: [Milestone] = []
    var stepSource: StepSource = .none
    var rotationSensorAvailable = true
    var stepDetectorAvailable = false
    var fallbackCapable = false
    var activityPermissionNeeded = false
    var activityPermissionGranted = false
    var registrationFailed = false
    var corridorAssistEnabled = false
    var trueNorthAligned = false
    var externalAccuracyM: Double?
    var warnings: Set<PdrWarning> = []

    var canStart: Bool {
        guard phase == .idle, rotationSensorAvailable, !registrationFailed, headingValid, !tilted else { return false }
        switch stepSource {
        case .hardware: return stepDetectorAvailable && (!activityPermissionNeeded || activityPermissionGranted)
        case .fallback: return fallbackCapable
        case .none: return false
        }
    }
}

// MARK: - Angles

/// Kotlin `%` keeps the sign of the dividend, like `truncatingRemainder`.
func wrapAnglePi(_ angle: Double) -> Double {
    var r = angle.truncatingRemainder(dividingBy: 2 * .pi)
    if r <= -.pi { r += 2 * .pi }
    if r > .pi { r -= 2 * .pi }
    return r
}

func angleDelta(_ from: Double, _ to: Double) -> Double { wrapAnglePi(to - from) }

func circularMean(_ angles: [Double]) -> Double? {
    if angles.isEmpty { return nil }
    var s = 0.0, c = 0.0
    for a in angles { s += sin(a); c += cos(a) }
    if hypot(s, c) / Double(angles.count) < 0.01 { return nil }
    return atan2(s, c)
}

func degToRad(_ d: Double) -> Double { d * .pi / 180 }
func radToDeg(_ r: Double) -> Double { r * 180 / .pi }

/// Android rotation vector (x, y, z[, w]) -> row-major 3×3 rotation matrix (device -> world ENU).
func rotationMatrixFromVector(_ values: [Double]) -> [Double]? {
    guard values.count >= 3 else { return nil }
    var x = values[0], y = values[1], z = values[2]
    var w = values.count >= 4 ? values[3] : max(0, 1 - x * x - y * y - z * z).squareRoot()
    guard x.isFinite, y.isFinite, z.isFinite, w.isFinite else { return nil }
    let n = (x * x + y * y + z * z + w * w).squareRoot()
    if n < 1e-9 { return nil }
    x /= n; y /= n; z /= n; w /= n
    return [
        1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
        2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
        2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
    ]
}

func worldVerticalFromRotationMatrix(_ r: [Double]) -> (Double, Double, Double) { (r[6], r[7], r[8]) }

// MARK: - Orientation

/// Swing-twist heading of the phone's "forward" axis, buffered 2.5 s so delayed step events
/// are matched against the heading at their own timestamp.
final class OrientationProcessor {
    private struct YawSample { let timestampNs: Int64; let yaw: Double; let valid: Bool }
    private let bufferNs: Int64, meanWindowNs: Int64, minHorizontalNorm: Double
    private var samples: [YawSample] = []
    private var lastTimestampNs = Int64.min
    private(set) var lastValidYaw: Double?
    private(set) var lastTilted = false

    init(bufferNs: Int64 = 2_500_000_000, meanWindowNs: Int64 = 150_000_000, minHorizontalNorm: Double = 0.12) {
        self.bufferNs = bufferNs; self.meanWindowNs = meanWindowNs; self.minHorizontalNorm = minHorizontalNorm
    }

    func onRotationVector(_ timestampNs: Int64, _ values: [Double]) {
        guard let r = rotationMatrixFromVector(values) else { return }
        onRotationMatrix(timestampNs, r)
    }

    /// Same as `onRotationVector`, from a matrix (CoreMotion gives one directly).
    func onRotationMatrix(_ timestampNs: Int64, _ r: [Double]) {
        let flatNorm = hypot(r[1], r[4])
        let sinYaw = r[1] - r[3], cosYaw = r[0] + r[4]
        let twistNorm = hypot(sinYaw, cosYaw)
        let valid = flatNorm >= minHorizontalNorm && twistNorm >= 0.2 && sinYaw.isFinite && cosYaw.isFinite
        if timestampNs <= lastTimestampNs && !samples.isEmpty { return }
        lastTimestampNs = timestampNs
        lastTilted = !valid
        let yaw = atan2(sinYaw, cosYaw)
        samples.append(YawSample(timestampNs: timestampNs, yaw: yaw, valid: valid))
        if valid { lastValidYaw = yaw }
        let cutoff = timestampNs - bufferNs
        if let firstKept = samples.firstIndex(where: { $0.timestampNs >= cutoff }), firstKept > 0 { samples.removeFirst(firstKept) }
    }

    func meanHeadingAt(_ timestampNs: Int64, windowNs: Int64? = nil) -> Double? {
        guard let newest = samples.last(where: { $0.timestampNs <= timestampNs }), newest.valid else { return nil }
        let from = timestampNs - (windowNs ?? meanWindowNs)
        return circularMean(samples.filter { $0.timestampNs >= from && $0.timestampNs <= timestampNs && $0.valid }.map(\.yaw))
    }

    func stableHeadingAt(_ timestampNs: Int64) -> Double? {
        guard let newest = samples.last(where: { $0.timestampNs <= timestampNs }), newest.valid else { return nil }
        let preTapEnd = timestampNs - 100_000_000, preTapStart = timestampNs - 500_000_000
        let preTap = samples.filter { $0.timestampNs >= preTapStart && $0.timestampNs <= preTapEnd && $0.valid }.map(\.yaw)
        if preTap.count >= 3, let stable = circularMean(preTap), abs(angleDelta(stable, newest.yaw)) <= degToRad(25) { return stable }
        return meanHeadingAt(timestampNs)
    }

    func turnRateRadPerSecAt(_ timestampNs: Int64, spanNs: Int64 = 250_000_000) -> Double {
        if samples.count < 2 { return 0 }
        let from = timestampNs - spanNs
        var prev: YawSample?
        var total = 0.0
        var firstTs = Int64.max, lastTs = Int64.min
        for s in samples where s.timestampNs >= from && s.timestampNs <= timestampNs && s.valid {
            if let p = prev { total += abs(angleDelta(p.yaw, s.yaw)) }
            firstTs = min(firstTs, s.timestampNs); lastTs = max(lastTs, s.timestampNs)
            prev = s
        }
        let dtS = Double(lastTs &- firstTs) / 1e9
        return dtS >= 0.03 ? total / dtS : 0
    }

    func clear() { samples.removeAll(); lastTimestampNs = .min; lastValidYaw = nil; lastTilted = false }
}

// MARK: - Fallback step detector (accelerometer)

/// Vertical-acceleration threshold crossing with refractory period. On iOS this is the main
/// step source (there is no per-step hardware event like Android's TYPE_STEP_DETECTOR).
final class FallbackStepDetector {
    var onStep: ((Int64) -> Void)?
    private let warmupNs: Int64, minIntervalNs: Int64, thresholdMss: Double, rearmMss: Double, smoothTauS: Double, gravityTauS: Double
    private var active = false
    private var startNs: Int64 = 0
    private var lastSeenNs = Int64.min, lastGravityNs = Int64.min, lastSmoothNs = Int64.min
    private var gx = 0.0, gy = 0.0, gz = 0.0
    private var gravityReady = false
    private var smoothed = 0.0
    private var smoothReady = false
    private var armed = true
    private var lastStepNs = Int64.min

    /// iOS improvement (opt-in, Android behaviour unchanged when false): the threshold follows the gait.
    /// It is half the vertical-acceleration peak of the last 2 s (clamped 0.55…1.6 m/s²), so soft steps
    /// (slow walk, phone held gently) are caught and the heel-strike bounce of strong steps isn't counted
    /// twice. Below a 0.8 m/s² peak nothing counts (hand tremor, standing still).
    let adaptive: Bool
    static let adaptiveMinPeakMss = 0.8
    private var bucketMax: [(start: Int64, peak: Double)] = []
    private static let bucketNs: Int64 = 100_000_000
    private static let windowNs: Int64 = 2_000_000_000

    init(warmupNs: Int64 = 500_000_000, minIntervalNs: Int64 = 330_000_000, thresholdMss: Double = 1.0, rearmMss: Double = 0.3,
         smoothTauS: Double = 0.04, gravityTauS: Double = 0.5, adaptive: Bool = false) {
        self.warmupNs = warmupNs; self.minIntervalNs = minIntervalNs; self.thresholdMss = thresholdMss
        self.rearmMss = rearmMss; self.smoothTauS = smoothTauS; self.gravityTauS = gravityTauS
        self.adaptive = adaptive
    }

    /// Current (threshold, rearm) pair; nil = adaptive gate closed (too little motion to be walking).
    private func thresholds(_ t: Int64) -> (Double, Double)? {
        guard adaptive else { return (thresholdMss, rearmMss) }
        let peak = bucketMax.map(\.peak).max() ?? 0
        if peak < Self.adaptiveMinPeakMss { return nil }
        let th = min(1.6, max(0.55, 0.5 * peak))
        return (th, th * 0.3)
    }

    private func trackPeak(_ t: Int64, _ v: Double) {
        let start = t - t % Self.bucketNs
        if let last = bucketMax.last, last.start == start { bucketMax[bucketMax.count - 1].peak = max(last.peak, v) }
        else { bucketMax.append((start, v)) }
        while let first = bucketMax.first, t - first.start > Self.windowNs { bucketMax.removeFirst() }
    }

    func start(_ nowNs: Int64) {
        active = true; startNs = nowNs
        lastSeenNs = .min; lastGravityNs = .min; lastSmoothNs = .min
        gravityReady = false; smoothReady = false; smoothed = 0; armed = true; lastStepNs = .min
        bucketMax.removeAll()
    }

    func stop() { active = false }

    /// Linear acceleration (gravity removed) projected on the gravity direction (ux, uy, uz).
    func onLinearAcceleration(_ t: Int64, ax: Double, ay: Double, az: Double, ux: Double, uy: Double, uz: Double) {
        guard active, t > lastSeenNs else { return }
        lastSeenNs = t
        let n = (ux * ux + uy * uy + uz * uz).squareRoot()
        guard n >= 1e-6, n.isFinite else { return }
        feed(t, (ax * ux + ay * uy + az * uz) / n)
    }

    func onAccelerometer(_ t: Int64, ax: Double, ay: Double, az: Double) {
        guard active, t > lastSeenNs else { return }
        lastSeenNs = t
        guard ax.isFinite, ay.isFinite, az.isFinite else { return }
        if !gravityReady { gx = ax; gy = ay; gz = az; gravityReady = true; lastGravityNs = t; return }
        let dt = Double(t - lastGravityNs) / 1e9
        guard dt > 0 else { return }
        lastGravityNs = t
        let alpha = dt / (gravityTauS + dt)
        gx += alpha * (ax - gx); gy += alpha * (ay - gy); gz += alpha * (az - gz)
        let n = (gx * gx + gy * gy + gz * gz).squareRoot()
        guard n >= 1e-6 else { return }
        feed(t, ((ax - gx) * gx + (ay - gy) * gy + (az - gz) * gz) / n)
    }

    private func feed(_ t: Int64, _ vertical: Double) {
        guard vertical.isFinite else { return }
        if !smoothReady { smoothed = vertical; smoothReady = true; lastSmoothNs = t; return }
        let dt = Double(t - lastSmoothNs) / 1e9
        guard dt > 0 else { return }
        lastSmoothNs = t
        let previous = smoothed
        smoothed += (dt / (smoothTauS + dt)) * (vertical - smoothed)
        if adaptive { trackPeak(t, smoothed) }
        if t < startNs + warmupNs { return }
        guard let (threshold, rearm) = thresholds(t) else { armed = true; return }
        let refractoryOk = lastStepNs == .min || t - lastStepNs >= minIntervalNs
        let rising = previous < threshold && smoothed >= threshold
        if armed && rising && refractoryOk {
            armed = false; lastStepNs = t; onStep?(t)
        } else if !armed && smoothed < rearm {
            armed = true
        }
    }
}

// MARK: - Path geometry

enum PathSegment {
    case line(PointM, PointM)
    case quad(PointM, PointM, PointM) // start, control, end

    var start: PointM { switch self { case .line(let s, _), .quad(let s, _, _): s } }
    var end: PointM { switch self { case .line(_, let e), .quad(_, _, let e): e } }

    func eval(_ t: Double) -> PointM {
        switch self {
        case .line(let s, let e): return s + (e - s) * t
        case .quad(let s, let c, let e): let u = 1 - t; return s * (u * u) + c * (2 * u * t) + e * (t * t)
        }
    }

    func derivative(_ t: Double) -> PointM {
        switch self {
        case .line(let s, let e): return e - s
        case .quad(let s, let c, let e): return (c - s) * (2 * (1 - t)) + (e - c) * (2 * t)
        }
    }
}

enum PathGeometry {
    private static func mid(_ a: PointM, _ b: PointM) -> PointM { PointM((a.x + b.x) / 2, (a.y + b.y) / 2) }

    /// Quadratic midpoint smoothing (display only; distances use raw points).
    static func smoothedSegments(_ points: [PointM]) -> [PathSegment] {
        let n = points.count
        if n < 2 { return [] }
        if n == 2 { return [.line(points[0], points[1])] }
        var out: [PathSegment] = [.line(points[0], mid(points[0], points[1]))]
        for i in 1..<(n - 1) { out.append(.quad(mid(points[i - 1], points[i]), points[i], mid(points[i], points[i + 1]))) }
        out.append(.line(mid(points[n - 2], points[n - 1]), points[n - 1]))
        return out
    }

    static func pathLength(_ points: [PointM]) -> Double {
        var total = 0.0
        for i in points.indices.dropFirst() { total += points[i - 1].distanceTo(points[i]) }
        return total
    }
}

final class MilestoneTracker {
    private let intervalM: Double
    private var nextM: Double
    private(set) var milestones: [Milestone] = []

    init(intervalM: Double = 5) { self.intervalM = intervalM; nextM = intervalM }

    func reset() { nextM = intervalM; milestones.removeAll() }

    func onSegment(_ from: PointM, _ fromDistanceM: Double, _ to: PointM, _ toDistanceM: Double) {
        let span = toDistanceM - fromDistanceM
        guard span > 0 else { return }
        while nextM - toDistanceM <= 1e-9 {
            let f = min(1, max(0, (nextM - fromDistanceM) / span))
            milestones.append(Milestone(position: from + (to - from) * f, distanceM: nextM))
            nextM += intervalM
        }
    }

    static func compute(_ points: [PointM], _ distancesM: [Double], intervalM: Double = 5) -> [Milestone] {
        let t = MilestoneTracker(intervalM: intervalM)
        let n = min(points.count, distancesM.count)
        if n > 1 { for i in 1..<n { t.onSegment(points[i - 1], distancesM[i - 1], points[i], distancesM[i]) } }
        return t.milestones
    }
}
