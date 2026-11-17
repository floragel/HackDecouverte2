import Foundation

// 1:1 port of FloorTransitionTracker.kt and AltitudeFloorEstimator.kt (pure, unit-tested).
// iOS source: CMAltimeter relative-altitude updates give pressure in kPa (×10 → hPa).

struct BarometerUpdate {
    let relativeAltitudeM: Double
    let deltaFromFloorM: Double
    let verticalSpeedMps: Double
    let inVerticalTransit: Bool
    let floorStepDelta: Int
}

/// Barometric floor-change and stair/elevator transit detector: 3-sample median + 0.45 s EMA,
/// slow ambient baseline (τ 45 s), discrete ±1 floor steps with hysteresis.
final class FloorTransitionTracker {
    static let defaultFloorHeightM = 4.0
    private static let minValidHpa = 850.0, maxValidHpa = 1100.0
    private static let emaTauSec = 0.45
    static let defaultVelocityWindowNs: Int64 = 1_400_000_000
    private static let ambientTauSec = 45.0, ambientMaxVzMps = 0.10, ambientMaxDeltaM = 1.25
    private static let enterTransitNearM = 1.05, enterTransitFallbackM = 1.65
    private static let enterTransitVzMps = 0.16, enterTransitStrongVzMps = 0.24
    private static let exitTransitVzMps = 0.12
    private static let exitFlatHoldNs: Int64 = 800_000_000

    private struct AltSample { let timestampNs: Int64; let altitudeM: Double }

    let floorHeightM: Double
    private let nearConnectorTriggerM: Double
    private let fallbackTriggerM: Double
    /// Android samples the barometer at 10 Hz; iPhones report ~1 Hz, so the iOS backend widens this window.
    private let velocityWindowNs: Int64

    private var referencePressureHpa: Double?
    private var medianWindow: [Double] = []
    private var smoothedAltitudeM: Double?
    private var floorBaselineAltitudeM = 0.0
    private var lastTimestampNs = Int64.min
    private var velocityHistory: [AltSample] = []
    private(set) var inVerticalTransit = false
    private var transitVerticalSign = 0
    private var flatSinceNs = Int64.min

    init(floorHeightM: Double = FloorTransitionTracker.defaultFloorHeightM, nearConnectorTriggerM: Double = 2.9, fallbackTriggerM: Double = 3.5,
         velocityWindowNs: Int64 = FloorTransitionTracker.defaultVelocityWindowNs) {
        self.floorHeightM = floorHeightM; self.nearConnectorTriggerM = nearConnectorTriggerM; self.fallbackTriggerM = fallbackTriggerM
        self.velocityWindowNs = velocityWindowNs
    }

    var hasReading: Bool { smoothedAltitudeM != nil }
    var currentDeltaFromFloorM: Double { (smoothedAltitudeM ?? 0) - floorBaselineAltitudeM }

    func reset() {
        referencePressureHpa = nil; medianWindow.removeAll(); smoothedAltitudeM = nil; floorBaselineAltitudeM = 0
        lastTimestampNs = .min; velocityHistory.removeAll(); inVerticalTransit = false; transitVerticalSign = 0; flatSinceNs = .min
    }

    /// The current altitude becomes the baseline of the user's floor (room picked / floor confirmed).
    func onManualFloorSet() {
        if let s = smoothedAltitudeM { floorBaselineAltitudeM = s }
        inVerticalTransit = false; transitVerticalSign = 0; flatSinceNs = .min
    }

    func onPressure(_ timestampNs: Int64, _ pressureHpa: Double, nearVerticalConnector: Bool) -> BarometerUpdate? {
        guard pressureHpa.isFinite, (Self.minValidHpa...Self.maxValidHpa).contains(pressureHpa) else { return nil }
        if lastTimestampNs != .min && timestampNs <= lastTimestampNs { return nil }
        let p0: Double
        if let r = referencePressureHpa { p0 = r } else { p0 = pressureHpa; referencePressureHpa = pressureHpa }
        let rawAltM = Self.pressureToRelativeAltitudeM(pressureHpa, p0)
        medianWindow.append(rawAltM)
        if medianWindow.count > 3 { medianWindow.removeFirst() }
        let medAltM = Self.medianOfUpTo3(medianWindow)

        let prevSmooth = smoothedAltitudeM
        let dtSec = lastTimestampNs == .min ? 0 : min(5, max(0, Double(timestampNs - lastTimestampNs) / 1e9))
        lastTimestampNs = timestampNs
        let smooth: Double
        if let prev = prevSmooth, dtSec > 0, dtSec < 3 {
            smooth = prev + (1 - exp(-dtSec / Self.emaTauSec)) * (medAltM - prev)
        } else {
            if prevSmooth == nil { floorBaselineAltitudeM = medAltM }
            smooth = medAltM
        }
        smoothedAltitudeM = smooth

        velocityHistory.append(AltSample(timestampNs: timestampNs, altitudeM: smooth))
        while velocityHistory.count > 2 && timestampNs - velocityHistory[0].timestampNs > velocityWindowNs { velocityHistory.removeFirst() }
        let vz = Self.regressionSlopeMps(velocityHistory)
        var deltaZ = smooth - floorBaselineAltitudeM

        let enteringByConnector = nearVerticalConnector && abs(deltaZ) >= Self.enterTransitNearM && abs(vz) >= Self.enterTransitVzMps
        let enteringByStrongClimb = abs(deltaZ) >= Self.enterTransitFallbackM && abs(vz) >= Self.enterTransitStrongVzMps
        if !inVerticalTransit {
            if enteringByConnector || enteringByStrongClimb {
                inVerticalTransit = true; transitVerticalSign = deltaZ >= 0 ? 1 : -1; flatSinceNs = .min
            } else if abs(vz) < Self.ambientMaxVzMps && abs(deltaZ) < Self.ambientMaxDeltaM && dtSec > 0 {
                floorBaselineAltitudeM += dtSec / (Self.ambientTauSec + dtSec) * (smooth - floorBaselineAltitudeM)
                deltaZ = smooth - floorBaselineAltitudeM
            }
        } else if abs(vz) < Self.exitTransitVzMps {
            if flatSinceNs == .min { flatSinceNs = timestampNs }
            else if timestampNs - flatSinceNs >= Self.exitFlatHoldNs {
                inVerticalTransit = false; transitVerticalSign = 0; flatSinceNs = .min
                if abs(deltaZ) < nearConnectorTriggerM * 0.65 { floorBaselineAltitudeM = smooth; deltaZ = 0 }
            }
        } else {
            flatSinceNs = .min
        }

        let effectiveNear = nearVerticalConnector || inVerticalTransit
        let trigger = effectiveNear ? nearConnectorTriggerM : fallbackTriggerM
        var floorStep = 0
        if effectiveNear || abs(vz) >= Self.enterTransitVzMps {
            if deltaZ >= trigger {
                floorStep = 1; floorBaselineAltitudeM += floorHeightM; deltaZ = smooth - floorBaselineAltitudeM
                inVerticalTransit = true; transitVerticalSign = 1
            } else if deltaZ <= -trigger {
                floorStep = -1; floorBaselineAltitudeM -= floorHeightM; deltaZ = smooth - floorBaselineAltitudeM
                inVerticalTransit = true; transitVerticalSign = -1
            }
        }
        return BarometerUpdate(relativeAltitudeM: smooth, deltaFromFloorM: deltaZ, verticalSpeedMps: vz, inVerticalTransit: inVerticalTransit, floorStepDelta: floorStep)
    }

    /// The building has no floor in that direction: undo the baseline move.
    func revertRejectedFloorStep(_ rejectedDelta: Int) { floorBaselineAltitudeM -= Double(rejectedDelta) * floorHeightM }

    private static func regressionSlopeMps(_ samples: [AltSample]) -> Double {
        guard samples.count >= 3, let t0 = samples.first?.timestampNs, let tl = samples.last?.timestampNs else { return 0 }
        if Double(tl - t0) / 1e9 < 0.35 { return 0 }
        let n = Double(samples.count)
        let meanT = samples.reduce(0) { $0 + Double($1.timestampNs - t0) / 1e9 } / n
        let meanZ = samples.reduce(0) { $0 + $1.altitudeM } / n
        var num = 0.0, den = 0.0
        for s in samples { let dt = Double(s.timestampNs - t0) / 1e9 - meanT; num += dt * (s.altitudeM - meanZ); den += dt * dt }
        return den > 1e-9 ? num / den : 0
    }

    static func pressureToRelativeAltitudeM(_ pressureHpa: Double, _ referenceHpa: Double) -> Double {
        44330 * (1 - pow(pressureHpa / referenceHpa, 0.190294957))
    }

    private static func medianOfUpTo3(_ v: [Double]) -> Double {
        switch v.count {
        case 0: return 0
        case 1: return v[0]
        case 2: return (v[0] + v[1]) * 0.5
        default: let a = v[0], b = v[1], c = v[2]; return max(min(a, b), min(max(a, b), c))
        }
    }
}

enum AltitudeFloorSource { case barometerStreetReference, entranceAssumed }

struct AltitudeFloorEstimate: Equatable {
    let floorNumber: Int
    let heightAboveStreetM: Double?
    let source: AltitudeFloorSource
    let confident: Bool
}

/// Absolute floor from a street-level pressure sample taken outside (weather moves absolute
/// pressure ±20 hPa, so pressure alone can't give a floor).
final class AltitudeFloorEstimator {
    private static let referenceSmoothWindowNs: Int64 = 30 * 1_000_000_000
    let groundFloor: Int, topFloor: Int
    let groundFloorHeightM: Double, floorHeightM: Double
    private let referenceMaxAgeNs: Int64, confidenceToleranceM: Double
    private var streetPressureHpa: Double?
    private var streetReferenceNs = Int64.min

    init(groundFloor: Int = 1, topFloor: Int = 7, groundFloorHeightM: Double = 5, floorHeightM: Double = FloorTransitionTracker.defaultFloorHeightM,
         referenceMaxAgeNs: Int64 = 45 * 60 * 1_000_000_000, confidenceToleranceM: Double = 1.4) {
        self.groundFloor = groundFloor; self.topFloor = topFloor; self.groundFloorHeightM = groundFloorHeightM
        self.floorHeightM = floorHeightM; self.referenceMaxAgeNs = referenceMaxAgeNs; self.confidenceToleranceM = confidenceToleranceM
    }

    var hasStreetReference: Bool { streetPressureHpa != nil }

    func captureStreetReference(_ timestampNs: Int64, _ pressureHpa: Double) {
        guard pressureHpa.isFinite, (300...1100).contains(pressureHpa) else { return }
        if let prev = streetPressureHpa, (0...Self.referenceSmoothWindowNs).contains(timestampNs - streetReferenceNs) {
            streetPressureHpa = prev + 0.3 * (pressureHpa - prev)
        } else {
            streetPressureHpa = pressureHpa
        }
        streetReferenceNs = timestampNs
    }

    func clearReference() { streetPressureHpa = nil; streetReferenceNs = .min }

    func floorElevationM(_ floor: Int) -> Double { floor <= groundFloor ? 0 : groundFloorHeightM + Double(floor - groundFloor - 1) * floorHeightM }

    func estimate(_ timestampNs: Int64, _ pressureHpa: Double?) -> AltitudeFloorEstimate {
        guard let ref = streetPressureHpa, (0...referenceMaxAgeNs).contains(timestampNs &- streetReferenceNs),
              let p = pressureHpa, p.isFinite else {
            return AltitudeFloorEstimate(floorNumber: groundFloor, heightAboveStreetM: nil, source: .entranceAssumed, confident: false)
        }
        let h = Self.heightAboveM(p, ref)
        let floor = (groundFloor...topFloor).min { abs(floorElevationM($0) - h) < abs(floorElevationM($1) - h) } ?? groundFloor
        return AltitudeFloorEstimate(floorNumber: floor, heightAboveStreetM: h, source: .barometerStreetReference,
                                     confident: abs(floorElevationM(floor) - h) <= confidenceToleranceM)
    }

    static func heightAboveM(_ pressureHpa: Double, _ referenceHpa: Double) -> Double { 44330 * (1 - pow(pressureHpa / referenceHpa, 1 / 5.255)) }
}
