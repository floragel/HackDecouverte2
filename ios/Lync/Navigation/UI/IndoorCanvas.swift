import SwiftUI
import Observation

// iOS port of ui/indoor/IndoorCanvas.kt + hackdecouverte/ui (MapTransform, MapLayers, FloorVectorRenderer).

/// World (metres, east/north) <-> screen (points), with rotation for "heading up".
struct MapTransform {
    let focus: PointM
    let panX: Double, panY: Double
    let pxPerMeter: Double
    let rotationRad: Double
    let centerX: Double, centerY: Double

    func worldToScreen(_ p: PointM) -> CGPoint {
        let dx = (p.x - focus.x) * pxPerMeter, dy = -(p.y - focus.y) * pxPerMeter
        let c = cos(rotationRad), s = sin(rotationRad)
        return CGPoint(x: centerX + panX + dx * c - dy * s, y: centerY + panY + dx * s + dy * c)
    }

    func screenToWorld(_ q: CGPoint) -> PointM {
        let vx = q.x - centerX - panX, vy = q.y - centerY - panY
        let c = cos(rotationRad), s = sin(rotationRad)
        let dx = vx * c + vy * s, dy = -vx * s + vy * c
        return PointM(focus.x + dx / pxPerMeter, focus.y - dy / pxPerMeter)
    }

    /// Same mapping as an affine transform, to draw whole metric paths at once.
    var affine: CGAffineTransform {
        CGAffineTransform(translationX: -focus.x, y: -focus.y)
            .concatenating(CGAffineTransform(scaleX: pxPerMeter, y: -pxPerMeter))
            .concatenating(CGAffineTransform(rotationAngle: rotationRad))
            .concatenating(CGAffineTransform(translationX: centerX + panX, y: centerY + panY))
    }

    struct Bounds { let minX, minY, maxX, maxY: Double }
    func viewBoundsWorld(_ w: Double, _ h: Double, marginM: Double) -> Bounds {
        let c = [CGPoint(x: 0, y: 0), CGPoint(x: w, y: 0), CGPoint(x: 0, y: h), CGPoint(x: w, y: h)].map(screenToWorld)
        return Bounds(minX: c.map(\.x).min()! - marginM, minY: c.map(\.y).min()! - marginM, maxX: c.map(\.x).max()! + marginM, maxY: c.map(\.y).max()! + marginM)
    }
}

/// Camera of the indoor plan (heading-up by default, like Google Maps).
@Observable
final class IndoorCamera {
    var zoom: Double = 0.85
    var pan: CGSize = .zero
    var follow = true
    var frozenFocus: PointM = .zero
    var headUp = true
    func recenter() { follow = true; pan = .zero; headUp = true }
}

/// Wall paths in metres (north up), built once per floor (the GPU-cached bitmap of the Android version).
/// Like Android's `FloorLayers` (written but not wired there): ink shapes smaller than 1 m are the
/// upside-down room numbers and symbols photographed on the evacuation plan, so they are dropped;
/// a hole is kept only if the shape it belongs to is kept. Room numbers are redrawn from the graph.
enum FloorWallPaths {
    private static var cache: [String: Path] = [:]
    private static let minShapeM = 1.0

    static func path(_ plan: FloorVectorPlan) -> Path {
        if let p = cache[plan.floorId] { return p }
        let keep = keptContours(plan)
        var path = Path()
        let xy = plan.packedEastNorthM
        for c in 0..<plan.contourCount where keep[c] {
            let s = plan.contourStartPairIndex[c], n = plan.contourPointCounts[c]
            if n < 2 { continue }
            path.move(to: CGPoint(x: Double(xy[s * 2]), y: Double(xy[s * 2 + 1])))
            for k in 1..<n { path.addLine(to: CGPoint(x: Double(xy[(s + k) * 2]), y: Double(xy[(s + k) * 2 + 1]))) }
            if plan.contourClosed[c] { path.closeSubpath() }
        }
        cache[plan.floorId] = path
        return path
    }

    /// Signed area + bounding box per contour; outers (same winding as the biggest shape) are kept when ≥ 1 m,
    /// holes follow their smallest enclosing outer.
    static func keptContours(_ plan: FloorVectorPlan) -> [Bool] {
        let n = plan.contourCount, xy = plan.packedEastNorthM
        guard n > 0 else { return [] }
        var area = [Double](repeating: 0, count: n)
        var box = [(Double, Double, Double, Double)](repeating: (0, 0, 0, 0), count: n)
        for c in 0..<n {
            let s = plan.contourStartPairIndex[c], k = plan.contourPointCounts[c]
            var a = 0.0, minX = Double.greatestFiniteMagnitude, minY = minX, maxX = -minX, maxY = -minX
            for i in 0..<k {
                let x = Double(xy[(s + i) * 2]), y = Double(xy[(s + i) * 2 + 1])
                let j = i == 0 ? k - 1 : i - 1
                a += Double(xy[(s + j) * 2]) * y - x * Double(xy[(s + j) * 2 + 1])
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
            area[c] = a / 2
            box[c] = (minX, minY, maxX, maxY)
        }
        guard let biggest = (0..<n).max(by: { abs(area[$0]) < abs(area[$1]) }) else { return [Bool](repeating: true, count: n) }
        let inkSign = area[biggest] > 0 ? 1.0 : -1.0
        let outers = (0..<n).filter { area[$0] * inkSign > 0 }
        let keptOuter = Set(outers.filter { max(box[$0].2 - box[$0].0, box[$0].3 - box[$0].1) >= minShapeM })

        func inside(_ px: Double, _ py: Double, _ c: Int) -> Bool {
            let s = plan.contourStartPairIndex[c], k = plan.contourPointCounts[c]
            var inside = false
            var j = k - 1
            for i in 0..<k {
                let xi = Double(xy[(s + i) * 2]), yi = Double(xy[(s + i) * 2 + 1])
                let xj = Double(xy[(s + j) * 2]), yj = Double(xy[(s + j) * 2 + 1])
                if (yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi { inside.toggle() }
                j = i
            }
            return inside
        }
        var keep = [Bool](repeating: false, count: n)
        for c in keptOuter { keep[c] = true }
        for c in 0..<n where area[c] * inkSign <= 0 {
            let s = plan.contourStartPairIndex[c]
            let px = Double(xy[s * 2]), py = Double(xy[s * 2 + 1])
            var parent = -1
            for o in outers {
                let b = box[o]
                if px < b.0 || px > b.2 || py < b.1 || py > b.3 { continue }
                if (parent < 0 || abs(area[o]) < abs(area[parent])) && inside(px, py, o) { parent = o }
            }
            keep[c] = keptOuter.contains(parent)
        }
        return keep
    }
}

/// Google-Maps-like palette of the indoor map.
enum MapPalette {
    static let land = Color(hex: 0xF1F3F4), park = Color(hex: 0xCEEAD6), road = Color.white, roadCasing = Color(hex: 0xDADCE0)
    static let footway = Color(hex: 0xE8EAED), building = Color(hex: 0xE8EAED), buildingStroke = Color(hex: 0xDADCE0)
    static let focusBuilding = Color.white, focusBuildingStroke = Color(hex: 0xBDC1C6)
    static let routeFill = Color(hex: 0x4285F4), routeCasing = Color(hex: 0x1967D2)
    static let trailRecent = Color(hex: 0x8AB4F8), trailOld = Color(hex: 0xD2E3FC)
    static let userBlue = Color(hex: 0x1A73E8), stairs = Color(hex: 0xE37400), elevator = Color(hex: 0x8E24AA)
    static let pin = Color(hex: 0xEA4335), pinDark = Color(hex: 0xB31412), labelText = Color(hex: 0x3C4043)
}

/// Smooths the dot (glides between steps) and the heading (shortest arc, ignores tremor), frame by frame.
private final class CanvasSmoother {
    var pos: PointM?
    var heading = 0.0
    var target = 0.0
    var lastNs: Int64 = 0

    func step(targetPos: PointM, rawHeading: Double, nowNs: Int64) -> (PointM, Double) {
        let dt = lastNs == 0 ? 0.016 : min(0.2, Double(nowNs - lastNs) / 1e9)
        lastNs = nowNs
        // dot: snap if far (new floor, new start), else glide (~0.42 s like the Android tween)
        if let p = pos, p.distanceTo(targetPos) <= 4 {
            let k = 1 - exp(-dt / 0.14)
            pos = p + (targetPos - p) * k
        } else { pos = targetPos }
        let d = angleDelta(target, rawHeading)
        if abs(d) > 0.008 { target += d }
        heading += (target - heading) * (1 - exp(-dt / 0.06))
        return (pos!, heading)
    }
}

struct IndoorCanvas: View {
    @Environment(\.palette) private var c
    let snapshot: NavigationSnapshot
    @Bindable var camera: IndoorCamera
    var bottomInset: CGFloat = 0
    @State private var smoother = CanvasSmoother()
    @State private var pinchStart: Double?
    @State private var dragStart: CGSize?

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30)) { timeline in
            let nowNs = NavClock.nowNs()
            let (display, heading) = smoother.step(targetPos: snapshot.mapPositionM, rawHeading: snapshot.mapHeadingRad, nowNs: nowNs)
            let pulse = (timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.8)) / 1.8
            Canvas { ctx, size in draw(&ctx, size, display, heading, pulse) }
        }
        .contentShape(Rectangle())
        .gesture(SimultaneousGesture(
            MagnificationGesture()
                .onChanged { v in
                    if pinchStart == nil { pinchStart = camera.zoom }
                    camera.zoom = min(8, max(0.04, (pinchStart ?? camera.zoom) * v))
                }
                .onEnded { _ in pinchStart = nil },
            DragGesture(minimumDistance: 4)
                .onChanged { v in
                    if dragStart == nil {
                        dragStart = camera.pan
                        if camera.follow { camera.follow = false; camera.frozenFocus = smoother.pos ?? snapshot.mapPositionM }
                    }
                    let s = dragStart ?? .zero
                    camera.pan = CGSize(width: s.width + v.translation.width, height: s.height + v.translation.height)
                }
                .onEnded { _ in dragStart = nil }
        ))
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize, _ display: PointM, _ smoothHeading: Double, _ pulse: Double) {
        let s = snapshot.pdr
        let dark = c.isDark
        let viewed = snapshot.activeFloorId
        let route = snapshot.activeRoute
        let pxPerM = 56 * camera.zoom
        let routePts = viewed.flatMap { route?.pointsForFloor($0) } ?? []
        let defaultFocus = snapshot.isViewingUserFloor ? display : (routePts.first ?? display)
        let focus = camera.follow ? defaultFocus : camera.frozenFocus
        let headUp = camera.headUp && snapshot.isViewingUserFloor && s.headingValid
        let rotation = headUp ? -smoothHeading : 0
        let visibleH = max(size.height - bottomInset, size.height * 0.35)
        let t = MapTransform(focus: focus, panX: camera.pan.width, panY: camera.pan.height, pxPerMeter: pxPerM, rotationRad: rotation,
                             centerX: size.width / 2, centerY: visibleH * (headUp && camera.follow ? 0.58 : 0.5))
        let vb = t.viewBoundsWorld(size.width, size.height, marginM: 25)
        func inView(_ p: PointM, _ m: Double = 0) -> Bool { p.x >= vb.minX - m && p.x <= vb.maxX + m && p.y >= vb.minY - m && p.y <= vb.maxY + m }
        func poly(_ pts: [PointM], close: Bool) -> Path {
            var p = Path()
            guard let f = pts.first else { return p }
            p.move(to: t.worldToScreen(f))
            for q in pts.dropFirst() { p.addLine(to: t.worldToScreen(q)) }
            if close { p.closeSubpath() }
            return p
        }

        // 1-2. OpenStreetMap land, parks, streets, buildings
        let land = dark ? c.surface : MapPalette.land
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(land))
        if let osm = snapshot.osmSurroundings {
            for p in osm.parks where p.polygonM.contains(where: { inView($0) }) {
                ctx.fill(poly(p.polygonM, close: true), with: .color(dark ? Color(hex: 0x1C2B22) : MapPalette.park))
            }
            for road in osm.roads where road.polylineM.contains(where: { inView($0) }) {
                let path = poly(road.polylineM, close: false)
                let w = min(64, max(2.5, road.widthM * pxPerM))
                let footway = road.kind == "footway" || road.kind == "cycleway"
                if !footway { ctx.stroke(path, with: .color(dark ? c.border : MapPalette.roadCasing), style: StrokeStyle(lineWidth: w + 2.2, lineCap: .round, lineJoin: .round)) }
                ctx.stroke(path, with: .color(footway ? (dark ? c.surfaceAlt : MapPalette.footway) : (dark ? c.bg : MapPalette.road)), style: StrokeStyle(lineWidth: w, lineCap: .round, lineJoin: .round))
            }
            for b in osm.buildings where inView(b.labelCenterM, 60) {
                let path = poly(b.polygonM, close: true)
                if b.isFocusBuilding {
                    ctx.fill(path, with: .color(dark ? c.bg : MapPalette.focusBuilding))
                    ctx.stroke(path, with: .color(dark ? c.textMuted : MapPalette.focusBuildingStroke), lineWidth: 2.4)
                } else {
                    ctx.fill(path, with: .color(dark ? c.surfaceAlt : MapPalette.building))
                    ctx.stroke(path, with: .color(dark ? c.border : MapPalette.buildingStroke), lineWidth: 1.2)
                    let tag = b.ref.isEmpty ? b.name : b.ref
                    if !tag.isEmpty && pxPerM >= 2.2 {
                        haloText(&ctx, String(tag.prefix(20)), t.worldToScreen(b.labelCenterM), size: 10.5, color: dark ? c.textMuted : Color(hex: 0x70757A), bold: true, halo: !dark)
                    }
                }
            }
            if pxPerM >= 2.6 && !dark {
                var drawn = Set<String>()
                for road in osm.roads where !road.name.isEmpty && !drawn.contains(road.name) && road.polylineM.count >= 2 {
                    var bestIdx = -1, bestLen = 0.0
                    for i in 0..<(road.polylineM.count - 1) {
                        let a = road.polylineM[i], b = road.polylineM[i + 1]
                        guard inView(PointM((a.x + b.x) / 2, (a.y + b.y) / 2)) else { continue }
                        let sa = t.worldToScreen(a), sb = t.worldToScreen(b)
                        let len = hypot(sb.x - sa.x, sb.y - sa.y)
                        if len > bestLen { bestLen = len; bestIdx = i }
                    }
                    if bestIdx >= 0 && bestLen >= 75 {
                        drawn.insert(road.name)
                        let sa = t.worldToScreen(road.polylineM[bestIdx]), sb = t.worldToScreen(road.polylineM[bestIdx + 1])
                        var deg = radToDeg(atan2(sb.y - sa.y, sb.x - sa.x))
                        if deg > 90 { deg -= 180 }; if deg < -90 { deg += 180 }
                        haloText(&ctx, road.name, CGPoint(x: (sa.x + sb.x) / 2, y: (sa.y + sb.y) / 2), size: 9.5, color: Color(hex: 0x5F6368), bold: false, halo: true, rotationDeg: deg)
                    }
                }
            }
            for poi in osm.pois {
                let p = t.worldToScreen(poi.positionM)
                ctx.fill(Circle().path(in: CGRect(x: p.x - 4.5, y: p.y - 4.5, width: 9, height: 9)), with: .color(Color(hex: 0x0284C7)))
                ctx.fill(Circle().path(in: CGRect(x: p.x - 2, y: p.y - 2, width: 4, height: 4)), with: .color(.white))
            }
        }

        // 3. MB walls of the viewed floor (or the graph edges while the SVG loads)
        if let vp = snapshot.activeFloorVector {
            ctx.fill(FloorWallPaths.path(vp).applying(t.affine), with: .color(dark ? c.text.opacity(0.82) : Color(hex: 0x5F6368)))
        } else if let plan = snapshot.activeFloor {
            for e in plan.edges {
                guard let a = plan.node(e.fromId)?.posM, let z = plan.node(e.toId)?.posM else { continue }
                var p = Path(); p.move(to: t.worldToScreen(a)); p.addLine(to: t.worldToScreen(z))
                ctx.stroke(p, with: .color(c.surfaceAlt), style: StrokeStyle(lineWidth: min(16, max(4, 1.5 * pxPerM)), lineCap: .round))
            }
        }

        // 4. Upright stairs, elevators and room numbers
        if let plan = snapshot.activeFloor, pxPerM >= 3 {
            var drawnRooms = Set<String>()
            for n in plan.nodes where inView(n.posM) {
                let sc = t.worldToScreen(n.posM)
                switch n.type {
                case "stairs": iconBadge(&ctx, sc, MapPalette.stairs, glyph: .stairs)
                case "elevator": iconBadge(&ctx, sc, MapPalette.elevator, glyph: .elevator)
                case "room":
                    if pxPerM >= 14, !n.label.isEmpty, drawnRooms.insert(n.label).inserted {
                        let text = n.label.hasPrefix("MB-") ? String(n.label.dropFirst(3)) : n.label
                        haloText(&ctx, text, CGPoint(x: sc.x, y: sc.y), size: 10.5, color: dark ? c.text : MapPalette.labelText, bold: true, halo: !dark)
                    }
                default: break
                }
            }
        }

        // 5. Walked trail on the user's floor
        if snapshot.isViewingUserFloor && s.rawPath.count >= 2 {
            var pts = s.rawPath.dropLast().map { t.worldToScreen(snapshot.anchor?.toMap($0) ?? $0) }
            pts.append(t.worldToScreen(display))
            drawTrail(&ctx, pts)
            let st = pts[0]
            ctx.fill(Circle().path(in: CGRect(x: st.x - 5.5, y: st.y - 5.5, width: 11, height: 11)), with: .color(.white))
            ctx.stroke(Circle().path(in: CGRect(x: st.x - 5.5, y: st.y - 5.5, width: 11, height: 11)), with: .color(Color(hex: 0x5F6368)), lineWidth: 2)
        }

        // 6. Route line, destination pin, floor-transfer callouts
        if routePts.count >= 2 {
            var pts: [CGPoint] = []
            if snapshot.isViewingUserFloor && route?.floorSegments.first?.floorId == viewed { pts.append(t.worldToScreen(display)) }
            pts += routePts.map(t.worldToScreen)
            drawRoute(&ctx, pts)
            // room numbers are already written on the plan from 14 px/m: no duplicate label on the pin then
            if let route, route.destination.floorId == viewed, let tip = pts.last { drawPin(&ctx, tip, label: dark || pxPerM >= 14 ? nil : route.destinationLabel) }
        }
        if let route, let viewed {
            for tr in route.transfers {
                if tr.fromFloorId == viewed {
                    let up = IndoorAssets.floorNumber(tr.toFloorId) > IndoorAssets.floorNumber(tr.fromFloorId)
                    routePill(&ctx, t.worldToScreen(tr.departurePosM), "\(up ? "↑" : "↓") \(L(tr.viaElevator ? "nav_pill_elevator" : "nav_pill_stairs")) \(floorShort(tr.toFloorId))")
                } else if tr.toFloorId == viewed {
                    routePill(&ctx, t.worldToScreen(tr.arrivalPosM), L("nav_pill_from", floorShort(tr.fromFloorId)))
                }
            }
        }

        // 7. Outdoor GNSS halo
        if let fix = snapshot.latestGpsFix, fix.accuracyM <= 12, fix.eastNorthM.norm() <= 350 {
            let r = min(70, max(8, fix.accuracyM * pxPerM)), p = t.worldToScreen(fix.eastNorthM)
            ctx.fill(Circle().path(in: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)), with: .color(MapPalette.userBlue.opacity(0.08)))
        }

        // 8. Blue dot + heading beam
        if snapshot.isViewingUserFloor {
            let here = t.worldToScreen(display)
            let ahead = t.worldToScreen(PointM(display.x + sin(smoothHeading), display.y + cos(smoothHeading)))
            let beam: Double? = s.headingValid ? atan2(ahead.x - here.x, here.y - ahead.y) : nil
            drawUser(&ctx, here, beam, pulse, valid: s.headingValid && !s.tilted)
        }
        drawNorthAndScale(&ctx, size, rotation, pxPerM)
    }

    private func floorShort(_ id: String) -> String { L("indoor_floor", String(IndoorAssets.floorNumber(id))) }

    // MARK: drawing helpers (MapLayers.kt)

    private func haloText(_ ctx: inout GraphicsContext, _ s: String, _ at: CGPoint, size: CGFloat, color: Color, bold: Bool, halo: Bool, rotationDeg: Double = 0) {
        ctx.drawLayer { l in
            l.translateBy(x: at.x, y: at.y)
            if rotationDeg != 0 { l.rotate(by: .degrees(rotationDeg)) }
            let font = Font.system(size: size, weight: bold ? .bold : .medium)
            if halo {
                let white = l.resolve(Text(s).font(font).foregroundColor(.white))
                for (dx, dy) in [(-1.2, 0.0), (1.2, 0), (0, -1.2), (0, 1.2), (-0.9, -0.9), (0.9, 0.9), (-0.9, 0.9), (0.9, -0.9)] {
                    l.draw(white, at: CGPoint(x: dx, y: dy), anchor: .center)
                }
            }
            l.draw(l.resolve(Text(s).font(font).foregroundColor(color)), at: .zero, anchor: .center)
        }
    }

    private enum Glyph { case stairs, elevator }

    private func iconBadge(_ ctx: inout GraphicsContext, _ c: CGPoint, _ color: Color, glyph: Glyph) {
        let s = 8.5
        ctx.fill(RoundedRectangle(cornerRadius: 5).path(in: CGRect(x: c.x - s - 1.5, y: c.y - s - 1.5, width: 2 * s + 3, height: 2 * s + 3)), with: .color(.white))
        ctx.fill(RoundedRectangle(cornerRadius: 4).path(in: CGRect(x: c.x - s, y: c.y - s, width: 2 * s, height: 2 * s)), with: .color(color))
        var p = Path()
        switch glyph {
        case .stairs:
            let st = s * 0.55
            p.move(to: CGPoint(x: c.x - st, y: c.y + st)); p.addLine(to: CGPoint(x: c.x - st / 3, y: c.y + st))
            p.addLine(to: CGPoint(x: c.x - st / 3, y: c.y + st / 3)); p.addLine(to: CGPoint(x: c.x + st / 3, y: c.y + st / 3))
            p.addLine(to: CGPoint(x: c.x + st / 3, y: c.y - st / 3)); p.addLine(to: CGPoint(x: c.x + st, y: c.y - st / 3))
            p.addLine(to: CGPoint(x: c.x + st, y: c.y - st))
            ctx.stroke(p, with: .color(.white), style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
        case .elevator:
            let w = s * 0.42
            p.move(to: CGPoint(x: c.x, y: c.y - s * 0.62)); p.addLine(to: CGPoint(x: c.x + w, y: c.y - s * 0.1)); p.addLine(to: CGPoint(x: c.x - w, y: c.y - s * 0.1)); p.closeSubpath()
            p.move(to: CGPoint(x: c.x, y: c.y + s * 0.62)); p.addLine(to: CGPoint(x: c.x + w, y: c.y + s * 0.1)); p.addLine(to: CGPoint(x: c.x - w, y: c.y + s * 0.1)); p.closeSubpath()
            ctx.fill(p, with: .color(.white))
        }
    }

    private func smooth(_ pts: [CGPoint]) -> Path {
        var p = Path()
        guard let f = pts.first else { return p }
        p.move(to: f)
        if pts.count == 2 { p.addLine(to: pts[1]); return p }
        for i in 1..<max(1, pts.count - 1) {
            let cpt = pts[i], n = pts[i + 1]
            p.addQuadCurve(to: CGPoint(x: (cpt.x + n.x) / 2, y: (cpt.y + n.y) / 2), control: cpt)
        }
        p.addLine(to: pts.last!)
        return p
    }

    /// Soft light-blue trail, brighter on its most recent part.
    private func drawTrail(_ ctx: inout GraphicsContext, _ pts: [CGPoint]) {
        let fade = min(pts.count - 1, 40), solidEnd = pts.count - fade
        let style = StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round)
        if solidEnd >= 2 { ctx.stroke(smooth(Array(pts[0..<solidEnd])), with: .color(MapPalette.trailOld), style: style) }
        for i in max(0, solidEnd - 1)..<(pts.count - 1) {
            let k = min(1, max(0, Double(i - solidEnd + 1) / Double(max(1, fade))))
            var seg = Path(); seg.move(to: pts[i]); seg.addLine(to: pts[i + 1])
            ctx.stroke(seg, with: .color(Color(hex: 0xD2E3FC).mix(MapPalette.trailRecent, k)), style: style)
        }
    }

    /// Dark-blue casing, blue fill, white direction chevrons.
    private func drawRoute(_ ctx: inout GraphicsContext, _ pts: [CGPoint]) {
        var path = Path(); path.move(to: pts[0]); for q in pts.dropFirst() { path.addLine(to: q) }
        ctx.stroke(path, with: .color(MapPalette.routeCasing), style: StrokeStyle(lineWidth: 9, lineCap: .round, lineJoin: .round))
        ctx.stroke(path, with: .color(MapPalette.routeFill), style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round))
        let spacing = 34.0, half = 2.4
        var carry = spacing * 0.6
        for i in 0..<(pts.count - 1) {
            let a = pts[i], b = pts[i + 1]
            let len = hypot(b.x - a.x, b.y - a.y)
            if len < 1 { continue }
            let ux = (b.x - a.x) / len, uy = (b.y - a.y) / len
            var d = carry
            while d < len {
                let cx = a.x + ux * d, cy = a.y + uy * d, px = -uy, py = ux
                var ch = Path()
                ch.move(to: CGPoint(x: cx - ux * half + px * half * 1.2, y: cy - uy * half + py * half * 1.2))
                ch.addLine(to: CGPoint(x: cx + ux * half, y: cy + uy * half))
                ch.addLine(to: CGPoint(x: cx - ux * half - px * half * 1.2, y: cy - uy * half - py * half * 1.2))
                ctx.stroke(ch, with: .color(.white), style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
                d += spacing
            }
            carry = d - len
        }
    }

    private func drawPin(_ ctx: inout GraphicsContext, _ tip: CGPoint, label: String?) {
        let r = 10.0, center = CGPoint(x: tip.x, y: tip.y - 2.6 * r)
        ctx.fill(Ellipse().path(in: CGRect(x: tip.x - 6, y: tip.y - 2, width: 12, height: 4)), with: .color(.black.opacity(0.2)))
        var body = Path()
        let ang = degToRad(32)
        body.move(to: tip)
        body.addLine(to: CGPoint(x: center.x - r * sin(ang), y: center.y + r * cos(ang)))
        body.addArc(center: center, radius: r, startAngle: .degrees(90 + 32), endAngle: .degrees(90 - 32), clockwise: false)
        body.closeSubpath()
        ctx.fill(body, with: .color(MapPalette.pin))
        ctx.stroke(body, with: .color(MapPalette.pinDark), lineWidth: 1)
        ctx.fill(Circle().path(in: CGRect(x: center.x - r * 0.38, y: center.y - r * 0.38, width: r * 0.76, height: r * 0.76)), with: .color(MapPalette.pinDark))
        if let label, !label.isEmpty { haloText(&ctx, label, CGPoint(x: tip.x, y: tip.y + 12), size: 11.5, color: MapPalette.labelText, bold: true, halo: true) }
    }

    private func routePill(_ ctx: inout GraphicsContext, _ anchor: CGPoint, _ text: String) {
        let resolved = ctx.resolve(Text(text).font(.system(size: 11, weight: .bold)).foregroundColor(Color(hex: 0x1967D2)))
        let w = resolved.measure(in: CGSize(width: 400, height: 40)).width + 16
        let rect = CGRect(x: anchor.x - w / 2, y: anchor.y - 32, width: w, height: 20)
        var shadow = ctx
        shadow.addFilter(.shadow(color: .black.opacity(0.25), radius: 3, y: 1))
        shadow.fill(RoundedRectangle(cornerRadius: 10).path(in: rect), with: .color(.white))
        ctx.stroke(RoundedRectangle(cornerRadius: 10).path(in: rect), with: .color(Color(hex: 0x1967D2)), lineWidth: 1.5)
        ctx.draw(resolved, at: CGPoint(x: rect.midX, y: rect.midY), anchor: .center)
    }

    private func drawUser(_ ctx: inout GraphicsContext, _ c: CGPoint, _ beam: Double?, _ pulse: Double, valid: Bool) {
        let halo = 14 + 22 * pulse
        ctx.fill(Circle().path(in: CGRect(x: c.x - halo, y: c.y - halo, width: 2 * halo, height: 2 * halo)), with: .color(MapPalette.userBlue.opacity(0.22 * (1 - pulse))))
        if let h = beam {
            let len = 58.0, half = degToRad(30)
            var p = Path(); p.move(to: c)
            for k in 0...12 { let a = h - half + 2 * half * Double(k) / 12; p.addLine(to: CGPoint(x: c.x + len * sin(a), y: c.y - len * cos(a))) }
            p.closeSubpath()
            let col = valid ? MapPalette.userBlue : Color(hex: 0x9AA0A6)
            ctx.fill(p, with: .radialGradient(Gradient(colors: [col.opacity(0.5), col.opacity(0)]), center: c, startRadius: 0, endRadius: len))
        }
        ctx.fill(Circle().path(in: CGRect(x: c.x - 11, y: c.y - 11 + 1.2, width: 22, height: 22)), with: .color(.black.opacity(0.25)))
        ctx.fill(Circle().path(in: CGRect(x: c.x - 10.5, y: c.y - 10.5, width: 21, height: 21)), with: .color(.white))
        ctx.fill(Circle().path(in: CGRect(x: c.x - 7.5, y: c.y - 7.5, width: 15, height: 15)), with: .color(MapPalette.userBlue))
    }

    private func drawNorthAndScale(_ ctx: inout GraphicsContext, _ size: CGSize, _ rotation: Double, _ pxPerM: Double) {
        let bottom = size.height - bottomInset
        let center = CGPoint(x: 35, y: bottom - 58)
        ctx.fill(Circle().path(in: CGRect(x: center.x - 15, y: center.y - 15, width: 30, height: 30)), with: .color(c.bg))
        ctx.stroke(Circle().path(in: CGRect(x: center.x - 15, y: center.y - 15, width: 30, height: 30)), with: .color(c.border), lineWidth: 1)
        let nx = sin(rotation), ny = -cos(rotation)
        var needle = Path(); needle.move(to: center); needle.addLine(to: CGPoint(x: center.x + nx * 10, y: center.y + ny * 10))
        ctx.stroke(needle, with: .color(Color(hex: 0xDC2626)), style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
        ctx.draw(Text("N").font(.system(size: 9, weight: .bold)).foregroundColor(c.textMuted), at: CGPoint(x: center.x - nx * 6, y: center.y - ny * 6), anchor: .center)
        let barM = [2, 5, 10, 20, 50, 100, 200].first { Double($0) * pxPerM >= 48 } ?? 200
        let y = bottom - 16
        var bar = Path(); bar.move(to: CGPoint(x: 20, y: y)); bar.addLine(to: CGPoint(x: 20 + Double(barM) * pxPerM, y: y))
        ctx.stroke(bar, with: .color(c.textMuted), lineWidth: 2.5)
        // OpenStreetMap attribution must stay visible
        ctx.draw(Text("\(barM) m · © OpenStreetMap").font(.system(size: 10)).foregroundColor(c.textMuted), at: CGPoint(x: 20, y: y - 5), anchor: .bottomLeading)
    }
}

extension Color {
    /// Linear blend toward `other` (k in 0…1).
    func mix(_ other: Color, _ k: Double) -> Color {
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0, r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        UIColor(self).getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        UIColor(other).getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return Color(.sRGB, red: r1 + (r2 - r1) * k, green: g1 + (g2 - g1) * k, blue: b1 + (b2 - b1) * k, opacity: a1 + (a2 - a1) * k)
    }
}
