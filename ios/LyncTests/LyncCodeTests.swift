import XCTest
import SwiftUI
@testable import Lync

/// Port of Android `LyncCodeTest.kt`: encode/decode round trips and decoding rendered camera frames.
final class LyncCodeTests: XCTestCase {
    /// Deterministic RNG (SplitMix64) so frames are reproducible.
    struct SeededRNG: RandomNumberGenerator {
        var state: UInt64
        init(_ seed: UInt64) { state = seed &+ 0x9E3779B97F4A7C15 }
        mutating func next() -> UInt64 {
            state &+= 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return z ^ (z >> 31)
        }
    }

    /// Renders a code into a grayscale frame like a camera would see it.
    private func frame(_ code: Int64, w: Int, h: Int, cx: Double, cy: Double, r: Double, rotation: Double, noise: Int, seed: UInt64) -> [Int] {
        let bits = LyncCode.encode(code)
        var rnd = SeededRNG(seed)
        var px = [Int](repeating: 0, count: w * h)
        let c = cos(-rotation), s = sin(-rotation)
        for y in 0..<h {
            for x in 0..<w {
                let dx = (Double(x) - cx) / r, dy = (Double(y) - cy) / r
                let nx = dx * c - dy * s, ny = dx * s + dy * c
                let base = nx * nx + ny * ny <= 1.44 ? (LyncCode.isInk(bits, nx, ny) ? 40 : 230) : 90
                px[y * w + x] = min(255, max(0, base + Int.random(in: -noise...noise, using: &rnd)))
            }
        }
        return px
    }

    func testEncodeDecodeBits() {
        for code: Int64 in [1, 0xDEADBEEF, 123_456_789, 0xFFFFFFFF] {
            let (o, i) = LyncCode.encode(code)
            XCTAssertEqual(LyncCode.fromBits(o, i), code)
            let shift = 7
            let ro = (0..<32).map { o[($0 + shift) % 32] }, ri = (0..<32).map { i[($0 + shift) % 32] }
            XCTAssertEqual(LyncCode.fromBits(ro, ri), code, "any rotation decodes")
        }
    }

    func testDecodesRenderedFrames() {
        let codes: [Int64] = [0x1A2B3C4D, 987_654_321, 42, 0xCAFEBABE]
        for (k, code) in codes.enumerated() {
            let w = 640, h = 480
            let px = frame(code, w: w, h: h, cx: 320 + Double(k) * 6, cy: 240 - Double(k) * 5, r: 170 + Double(k) * 8, rotation: 0.37 * Double(k) + 0.1, noise: 18, seed: UInt64(k))
            XCTAssertEqual(LyncCode.decode(width: w, height: h) { x, y in px[y * w + x] }, code)
        }
    }

    func testDecodesRandomCodesAnyRotation() {
        var rnd = SeededRNG(2026)
        for k in 0..<12 {
            let code = Int64.random(in: 1..<0xFFFFFFFF, using: &rnd)
            let w = 640, h = 480
            let px = frame(code, w: w, h: h, cx: 300 + Double(Int.random(in: 0..<40, using: &rnd)), cy: 220 + Double(Int.random(in: 0..<40, using: &rnd)),
                           r: 140 + Double(Int.random(in: 0..<60, using: &rnd)), rotation: Double.random(in: 0..<6.28, using: &rnd), noise: 25, seed: UInt64(k))
            XCTAssertEqual(LyncCode.decode(width: w, height: h) { x, y in px[y * w + x] }, code)
        }
    }

    func testRejectsBlankFrames() {
        XCTAssertNil(LyncCode.decode(width: 320, height: 240) { _, _ in 200 })
    }

    func testNfcUriParsing() {
        XCTAssertEqual(NFCCardReader.parseUri("https://lync.app/c/123456"), 123_456)
        XCTAssertNil(NFCCardReader.parseUri("https://example.com/c/1"))
        XCTAssertNil(NFCCardReader.parseUri("https://lync.app/c/99999999999"))
    }

    /// Same bytes as Android `NfcCard.response()`: big-endian 32-bit code, then 90 00.
    func testNfcCardResponseParsing() {
        XCTAssertEqual(NFCCardReader.parseResponse(Data([0xDE, 0xAD, 0xBE, 0xEF]), 0x90, 0x00), 0xDEADBEEF)
        XCTAssertEqual(NFCCardReader.parseResponse(Data([0x00, 0x00, 0x00, 0x2A]), 0x90, 0x00), 42)
        XCTAssertNil(NFCCardReader.parseResponse(Data(), 0x6A, 0x82))                  // card not on screen
        XCTAssertNil(NFCCardReader.parseResponse(Data([0x01, 0x02, 0x03]), 0x90, 0x00)) // wrong length
        XCTAssertNil(NFCCardReader.parseResponse(Data([0, 0, 0, 0]), 0x90, 0x00))      // no code
    }
}

/// Port of Android `IcsParserTest.kt`.
final class IcsParserTests: XCTestCase {
    func testParseSimpleCourseEvent() {
        let ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        SUMMARY:COMP 248 - Object-Oriented Programming I
        LOCATION:Hall Building, H-920
        DESCRIPTION:Prof. Adams\\, section U
        DTSTART:20261116T101500
        DTEND:20261116T113000
        END:VEVENT
        END:VCALENDAR
        """
        let events = IcsParser.parse(ics)
        XCTAssertEqual(events.count, 1)
        let e = events[0]
        XCTAssertEqual(e.title, "COMP 248 - Object-Oriented Programming I")
        XCTAssertEqual(e.location, "Hall Building, H-920")
        XCTAssertEqual(e.description, "Prof. Adams, section U")
        XCTAssertEqual(e.kind, .course)
        XCTAssertEqual(e.courseCode, "COMP 248")
        XCTAssertEqual(e.emoji, "📚")
    }

    func testParseSocialPartyEvent() {
        let ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        SUMMARY:Concordia Robotics 5@7 Gala
        LOCATION:EV Atrium
        DESCRIPTION:End of semester celebration
        DTSTART:20261120T170000
        DTEND:20261120T210000
        END:VEVENT
        END:VCALENDAR
        """
        let events = IcsParser.parse(ics)
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].title, "Concordia Robotics 5@7 Gala")
        XCTAssertEqual(events[0].kind, .socialEvent)
    }

    func testParseRecurringWeeklyEvent() {
        let ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        SUMMARY:MATH 203 - Calculus I
        LOCATION:JMSB MB-2.210
        DTSTART:20261102T131500
        DTEND:20261102T143000
        RRULE:FREQ=WEEKLY;BYDAY=TU,TH
        END:VEVENT
        END:VCALENDAR
        """
        let events = IcsParser.parse(ics)
        XCTAssertEqual(events.count, 30) // 15 weeks × 2 days
        XCTAssertTrue(events.allSatisfy { $0.kind == .course && $0.courseCode == "MATH 203" })
        // occurrences land on Tuesdays and Thursdays only
        let weekdays = Set(events.map { Calendar.current.component(.weekday, from: $0.startsAt) })
        XCTAssertEqual(weekdays, [3, 5])
    }

    func testCalendarWeekStartsMonday() {
        // Saturday 14 Nov 2026 → week of Monday 9 … Sunday 15
        let sat = DateComponents(calendar: .current, year: 2026, month: 11, day: 14).date!
        let week = MainActor.assumeIsolated { CalendarModel.week(of: sat) }
        XCTAssertEqual(week.count, 7)
        XCTAssertEqual(Calendar.current.component(.weekday, from: week[0]), 2)
        XCTAssertEqual(Calendar.current.component(.day, from: week[0]), 9)
        XCTAssertEqual(Calendar.current.component(.day, from: week[6]), 15)
    }
}

/// Renders the real SwiftUI `LyncCodeView` (what the back of the iPhone card shows) and decodes it,
/// so an Android or iPhone camera is guaranteed to read the code an iPhone draws.
@MainActor
final class LyncCodeRenderTests: XCTestCase {
    private func luma(of view: some View, size: CGFloat) -> (w: Int, h: Int, px: [UInt8])? {
        let renderer = ImageRenderer(content: view.frame(width: size, height: size).padding(size * 0.15).background(Color.white))
        renderer.scale = 1
        guard let cg = renderer.cgImage else { return nil }
        let w = cg.width, h = cg.height
        var px = [UInt8](repeating: 255, count: w * h)
        let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w, space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)
        ctx?.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        return (w, h, px)
    }

    func testRenderedCardCodeDecodes() throws {
        let concordia = Color(hex: 0x912338)
        for code: Int64 in [1, 42, 0x1A2B3C4D, 987_654_321, 0xCAFEBABE, 0xFFFFFFFF, 0x80000001, 0x0F0F0F0F] {
            let img = try XCTUnwrap(luma(of: LyncCodeView(code: code, ink: Tokens.night, data: concordia), size: 360))
            let decoded = LyncCode.decode(width: img.w, height: img.h) { x, y in Int(img.px[y * img.w + x]) }
            XCTAssertEqual(decoded, code, "iPhone-drawn code \(code) must decode")
        }
    }

    /// Same code seen upside down / sideways (phone held any way) still decodes.
    func testRenderedCodeDecodesRotated() throws {
        let code: Int64 = 0x5EED1234
        for degrees in [90.0, 180.0, 33.0] {
            let view = LyncCodeView(code: code, ink: Tokens.night, data: Color(hex: 0x912338)).rotationEffect(.degrees(degrees))
            let img = try XCTUnwrap(luma(of: view, size: 360))
            XCTAssertEqual(LyncCode.decode(width: img.w, height: img.h) { x, y in Int(img.px[y * img.w + x]) }, code, "rotated \(degrees)°")
        }
    }
}
