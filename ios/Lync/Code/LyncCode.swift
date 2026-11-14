import Foundation

/**
 * The Lync code — our own scannable code (not a QR code). 1:1 port of Android `LyncCode.kt`.
 *
 *   ┌─ finder ring (solid)            r 0.90 … 1.00
 *   ├─ outer data ring, 32 segments   r ≈ 0.76   -> the 32-bit profile code
 *   ├─ inner data ring, 32 segments   r ≈ 0.58   -> 16-bit CRC + 16-bit check
 *   └─ Lync mark in the middle        r < 0.40   (ignored by the decoder)
 *
 * A "1" bit is part of a rounded arc (consecutive 1s merge), a "0" bit is a tiny dot.
 * Decoding is rotation-free: every rotation is tried and the CRC decides.
 */
enum LyncCode {
    static let segments = 32
    static let finderInner = 0.90
    static let ringOuter = 0.76
    static let ringInner = 0.58
    static let stroke = 0.12
    static let dot = 0.025
    private static let segAngle = 2 * Double.pi / Double(segments)

    /// Angle (radians, clockwise from 12 o'clock in screen coordinates) of segment i's center.
    static func segmentAngle(_ i: Int) -> Double { Double(i) * segAngle }

    static func crc16(_ code: Int64) -> Int {
        var crc = 0xFFFF
        for shift in [24, 16, 8, 0] {
            crc ^= Int((code >> Int64(shift)) & 0xFF) << 8
            for _ in 0..<8 { crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1 }
            crc &= 0xFFFF
        }
        return crc
    }

    private static func check16(_ code: Int64) -> Int { Int(((code >> 16) ^ code ^ 0xA55A) & 0xFFFF) }

    /// Outer ring bits, inner ring bits (each 32, MSB first, segment 0 at the top).
    static func encode(_ code: Int64) -> ([Bool], [Bool]) {
        let outer = (0..<segments).map { (code >> Int64(31 - $0)) & 1 == 1 }
        let word = (Int64(crc16(code)) << 16) | Int64(check16(code))
        let inner = (0..<segments).map { (word >> Int64(31 - $0)) & 1 == 1 }
        return (outer, inner)
    }

    private static func toLong(_ bits: [Bool], _ shift: Int) -> Int64 {
        var v: Int64 = 0
        for i in 0..<segments { v = (v << 1) | (bits[(i + shift) % segments] ? 1 : 0) }
        return v
    }

    /// Tries every rotation (and the mirrored reading) and returns the code if a CRC matches.
    static func fromBits(_ outer: [Bool], _ inner: [Bool]) -> Int64? {
        for mirrored in [false, true] {
            let o = mirrored ? (0..<segments).map { outer[(segments - $0) % segments] } : outer
            let n = mirrored ? (0..<segments).map { inner[(segments - $0) % segments] } : inner
            for shift in 0..<segments {
                let code = toLong(o, shift), word = toLong(n, shift)
                if Int(word >> 16) == crc16(code) && Int(word & 0xFFFF) == check16(code) { return code }
            }
        }
        return nil
    }

    /// Ink test in normalized coordinates (center 0,0, R = 1, y down). Used by tests.
    static func isInk(_ bits: ([Bool], [Bool]), _ x: Double, _ y: Double) -> Bool {
        let r = (x * x + y * y).squareRoot()
        if r >= finderInner && r <= 1 { return true }
        let angle = (atan2(x, -y) + 2 * .pi).truncatingRemainder(dividingBy: 2 * .pi)
        for (ringR, ring) in [(ringOuter, bits.0), (ringInner, bits.1)] where abs(r - ringR) <= stroke {
            let seg = Int((angle / segAngle).rounded()) % segments
            for s in [seg, (seg + 1) % segments, (seg + segments - 1) % segments] where inkForSegment(ring, s, ringR, x, y) { return true }
        }
        return false
    }

    private static func inkForSegment(_ ring: [Bool], _ s: Int, _ ringR: Double, _ x: Double, _ y: Double) -> Bool {
        let half = stroke / 2
        let cx = ringR * sin(segmentAngle(s)), cy = -ringR * cos(segmentAngle(s))
        let d = ((x - cx) * (x - cx) + (y - cy) * (y - cy)).squareRoot()
        if !ring[s] { return d <= dot }
        if d <= half { return true }
        if ring[(s + 1) % segments] {
            let a0 = segmentAngle(s), a1 = a0 + segAngle
            let a = (atan2(x, -y) + 2 * .pi).truncatingRemainder(dividingBy: 2 * .pi)
            let within = a1 > 2 * .pi ? (a >= a0 || a <= a1 - 2 * .pi) : (a >= a0 && a <= a1)
            if within && abs((x * x + y * y).squareRoot() - ringR) <= half { return true }
        }
        return false
    }

    // MARK: decoding

    /// Finds a Lync code roughly centered in a w×h grayscale frame (0 = black). Returns the code or nil.
    static func decode(width w: Int, height h: Int, luma: (Int, Int) -> Int) -> Int64? {
        let threshold = otsu(w, h, luma)
        var cx = Double(w) / 2, cy = Double(h) / 2
        let maxR = 0.46 * Double(min(w, h))
        var radius = 0.0
        for pass in 0..<2 {
            let start = pass == 0 ? maxR : radius * 1.25
            var pts: [(Double, Double)] = []
            for k in 0..<48 {
                let a = Double(k) * 2 * .pi / 48
                let dx = sin(a), dy = -cos(a)
                var r = start
                var seenLight = 0
                while r > maxR * 0.15 {
                    let x = Int(cx + dx * r), y = Int(cy + dy * r)
                    if x > 0 && x < w - 1 && y > 0 && y < h - 1 {
                        let dark = luma(x, y) < threshold
                        if !dark { seenLight += 1 }
                        else if seenLight >= 3 {
                            if luma(Int(cx + dx * (r - 2)), Int(cy + dy * (r - 2))) < threshold { pts.append((cx + dx * r, cy + dy * r)); break }
                        } else { seenLight = 0 }
                    }
                    r -= 1
                }
            }
            guard pts.count >= 12, let fit = fitCircle(pts) else { return nil }
            let kept = pts.filter { abs((($0.0 - fit.0) * ($0.0 - fit.0) + ($0.1 - fit.1) * ($0.1 - fit.1)).squareRoot() - fit.2) < fit.2 * 0.08 }
            guard kept.count >= 12, let fit2 = fitCircle(kept) else { return nil }
            cx = fit2.0; cy = fit2.1; radius = fit2.2
        }
        if radius < 20 { return nil }

        func dark(_ nx: Double, _ ny: Double) -> Bool {
            let x = Int(cx + nx * radius), y = Int(cy + ny * radius)
            return x >= 0 && x < w && y >= 0 && y < h && luma(x, y) < threshold
        }
        // The camera image can be rotated by any angle: search the sub-segment phase in 1/8 steps.
        for step in 0..<8 {
            let rot = (Double(step) / 8 - 0.5) * segAngle
            func ring(_ ringR: Double) -> [Bool] {
                (0..<segments).map { i in
                    let spread = 0.035 / ringR
                    var votes = 0
                    for d in [-spread, 0, spread] {
                        let a = segmentAngle(i) + d + rot
                        for rr in [ringR - 0.02, ringR + 0.02] where dark(rr * sin(a), -rr * cos(a)) { votes += 1 }
                    }
                    return votes >= 5 // a dot lights only the 2 center samples, an arc all 6
                }
            }
            if let code = fromBits(ring(ringOuter), ring(ringInner)) { return code }
        }
        return nil
    }

    private static func otsu(_ w: Int, _ h: Int, _ luma: (Int, Int) -> Int) -> Int {
        var hist = [Int](repeating: 0, count: 256)
        let step = max(2, min(w, h) / 160)
        var n = 0
        for y in stride(from: h / 8, to: h * 7 / 8, by: step) { for x in stride(from: w / 8, to: w * 7 / 8, by: step) { hist[min(255, max(0, luma(x, y)))] += 1; n += 1 } }
        var sum = 0.0
        for i in 0..<256 { sum += Double(i * hist[i]) }
        var sumB = 0.0, wB = 0, best = 0.0, t = 128
        for i in 0..<256 {
            wB += hist[i]; if wB == 0 { continue }
            let wF = n - wB; if wF == 0 { break }
            sumB += Double(i * hist[i])
            let mB = sumB / Double(wB), mF = (sum - sumB) / Double(wF)
            let between = Double(wB) * Double(wF) * (mB - mF) * (mB - mF)
            if between > best { best = between; t = i }
        }
        return t
    }

    /// Kåsa least-squares circle fit -> (cx, cy, r).
    private static func fitCircle(_ pts: [(Double, Double)]) -> (Double, Double, Double)? {
        var sx = 0.0, sy = 0.0, sxx = 0.0, syy = 0.0, sxy = 0.0, sxz = 0.0, syz = 0.0, sz = 0.0
        let n = Double(pts.count)
        for (x, y) in pts { let z = x * x + y * y; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; sxz += x * z; syz += y * z; sz += z }
        var m = [[sxx, sxy, sx, -sxz], [sxy, syy, sy, -syz], [sx, sy, n, -sz]]
        for col in 0..<3 {
            guard let p = (col..<3).max(by: { abs(m[$0][col]) < abs(m[$1][col]) }) else { return nil }
            m.swapAt(col, p)
            if abs(m[col][col]) < 1e-9 { return nil }
            for r in 0..<3 where r != col {
                let f = m[r][col] / m[col][col]
                for k in col..<4 { m[r][k] -= f * m[col][k] }
            }
        }
        let d = m[0][3] / m[0][0], e = m[1][3] / m[1][1], f = m[2][3] / m[2][2]
        let cx = -d / 2, cy = -e / 2, r2 = cx * cx + cy * cy - f
        return r2 > 0 ? (cx, cy, r2.squareRoot()) : nil
    }
}
