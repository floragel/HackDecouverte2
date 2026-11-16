import SwiftUI
import AVFoundation
import CoreNFC

/// Scan a Lync code with the camera, or tap an Android phone / NFC sticker (Core NFC).
/// Both end on the same celebration + result card.
struct ScannerScreen: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(Router.self) private var router
    @Environment(\.palette) private var c
    @State private var found: Person?
    @State private var lookingUp = false
    @State private var problem: String?          // why the last scan didn't connect
    @State private var lastFailed: (code: Int64, at: Date)?
    @State private var celebrating = false
    @State private var cameraAllowed = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    @State private var pulse = false
    @State private var nfc = NFCCardReader()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if cameraAllowed { CameraScanner(paused: found != nil) { code in onCode(code, source: "code") }.ignoresSafeArea() }

            // dim everything except the circle; it tightens and turns green on detection
            let locked = lookingUp || found != nil
            GeometryReader { g in
                let r = g.size.width * 0.36
                ZStack {
                    Color.black.opacity(0.55).mask(ZStack { Rectangle(); Circle().frame(width: 2 * r, height: 2 * r).blendMode(.destinationOut) }.compositingGroup())
                    Circle().stroke(locked ? Tokens.live : .white, lineWidth: locked ? 5 : 3)
                        .frame(width: 2 * r, height: 2 * r).scaleEffect(locked ? 0.82 : (pulse ? 1.02 : 0.96))
                        .animation(locked ? Motion.bouncy : .easeInOut(duration: 1.4).repeatForever(autoreverses: true), value: locked ? 1 : (pulse ? 2 : 3))
                }
                .frame(width: g.size.width, height: g.size.height)
            }
            .ignoresSafeArea()
            .onAppear { pulse = true }

            VStack(spacing: 6) {
                HStack {
                    Button { router.back() } label: { Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).foregroundStyle(.white).frame(width: 44, height: 44).background(Circle().fill(.white.opacity(0.15))) }
                    Spacer()
                }
                Spacer()
                Text(L("scan_title")).font(.display(28)).foregroundStyle(.white)
                Text(L(cameraAllowed ? "scan_hint" : "scan_need_camera")).font(.text(14)).foregroundStyle(.white.opacity(0.75)).multilineTextAlignment(.center)
                if NFCCardReader.available {
                    Button { nfc.start { onCode($0, source: "nfc") } } label: {
                        Label(L("scan_nfc_ready"), systemImage: "wave.3.right").font(.text(14, "SemiBold")).foregroundStyle(.white)
                            .padding(.horizontal, 14).padding(.vertical, 9).background(Capsule().fill(.white.opacity(0.15)))
                    }
                    .padding(.top, 10)
                }
                if env.repository.isDemo && found == nil {
                    // demo / jury mode: simulate scanning a classmate's card (works in the simulator)
                    Pill(text: L("scan_simulate"), style: .tonal) {
                        Task {
                            let classmates = (try? await env.repository.classmates()) ?? []
                            var next = classmates.first { $0.connection != .connected }
                            if next == nil { next = ((try? await env.repository.suggestions()) ?? []).first }
                            if let code = next?.profile.lyncCode { onCode(code, source: "code") }
                        }
                    }
                    .padding(.top, 10)
                }
                if let problem {
                    Text(problem).font(.text(14, "SemiBold")).foregroundStyle(.white).multilineTextAlignment(.center)
                        .padding(.horizontal, 14).padding(.vertical, 9).background(Capsule().fill(.black.opacity(0.45))).padding(.top, 10)
                }
            }
            .padding(16).padding(.bottom, 24)

            if let p = found, !celebrating {
                VStack(spacing: 0) {
                    Spacer()
                    VStack(spacing: 0) {
                        Avatar(url: p.profile.avatarUrl, initials: p.profile.initials, size: 84, ring: c.accent)
                        Text(p.profile.fullName).lyncText(.headline).padding(.top, 12)
                        Text(programLine(p.profile)).lyncText(.bodyMedium, c.textMuted)
                        Text(L(p.connection == .connected ? "scan_connected" : "scan_requested", p.profile.firstName)).lyncText(.titleSmall, c.accent).padding(.top, 10)
                        HStack(spacing: 8) {
                            Pill(text: L("scan_again"), style: .tonal, fullWidth: true, vertical: 12) { found = nil; problem = nil; lastFailed = nil }
                            Pill(text: L("scan_view_profile"), fullWidth: true, vertical: 12) { let id = p.id; router.back(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { router.user(id) } }
                        }
                        .padding(.top, 18)
                    }
                    .padding(22).background(RoundedRectangle(cornerRadius: Tokens.radiusCard, style: .continuous).fill(c.bg)).padding(12)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            if celebrating, let p = found {
                ConnectCelebration(me: env.me, other: p.profile, sharedCourses: p.sharedCourses) { withAnimation { celebrating = false } }
            }
        }
        .task {
            if !cameraAllowed { cameraAllowed = await AVCaptureDevice.requestAccess(for: .video) }
        }
        .onDisappear { nfc.stop() }
    }

    private func onCode(_ code: Int64, source: String) {
        guard found == nil, !lookingUp else { return }
        // the camera keeps seeing the same code: don't re-query it every frame after a failure
        if let f = lastFailed, f.code == code, Date().timeIntervalSince(f.at) < 4 { return }
        if code == env.me?.lyncCode { problem = L("scan_self"); lastFailed = (code, Date()); return }
        lookingUp = true
        Task {
            defer { lookingUp = false }
            do {
                guard let person = try await env.repository.profileByCode(code) else {
                    // a valid Lync code (its checksum passed) that isn't in this server's database:
                    // the other phone is in demo mode, on another backend, or at another university
                    problem = L("scan_not_found", String(code)); lastFailed = (code, Date()); return
                }
                problem = nil
                if person.connection == .none { try? await env.repository.connect(person.id, source: source) }
                let fresh = (try? await env.repository.profile(person.id)) ?? person
                withAnimation { found = fresh; celebrating = true }
            } catch {
                problem = L("scan_error"); lastFailed = (code, Date())
            }
        }
    }

}

/// Camera preview + live Lync code decoding on the luma plane.
struct CameraScanner: UIViewRepresentable {
    var paused: Bool
    var onCode: (Int64) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = context.coordinator.session
        view.previewLayer.videoGravity = .resizeAspectFill
        context.coordinator.configure()
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) { context.coordinator.paused = paused }

    static func dismantleUIView(_ view: PreviewView, coordinator: Coordinator) { coordinator.stop() }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    final class Coordinator: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
        let session = AVCaptureSession()
        let onCode: (Int64) -> Void
        var paused = false
        private var lastCode: Int64?
        private let queue = DispatchQueue(label: "lync.scanner")

        init(onCode: @escaping (Int64) -> Void) { self.onCode = onCode }

        func configure() {
            queue.async { [self] in
                session.beginConfiguration()
                session.sessionPreset = .hd1280x720
                guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                      let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { session.commitConfiguration(); return }
                session.addInput(input)
                let output = AVCaptureVideoDataOutput()
                output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
                output.alwaysDiscardsLateVideoFrames = true
                output.setSampleBufferDelegate(self, queue: queue)
                if session.canAddOutput(output) { session.addOutput(output) }
                session.commitConfiguration()
                session.startRunning()
            }
        }

        func stop() { queue.async { [session] in session.stopRunning() } }

        func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
            guard !paused, let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
            CVPixelBufferLockBaseAddress(pb, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
            guard let base = CVPixelBufferGetBaseAddressOfPlane(pb, 0) else { return }
            let w = CVPixelBufferGetWidthOfPlane(pb, 0), h = CVPixelBufferGetHeightOfPlane(pb, 0), stride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
            let ptr = base.assumingMemoryBound(to: UInt8.self)
            let code = LyncCode.decode(width: w, height: h) { x, y in Int(ptr[y * stride + x]) }
            // require the same code twice in a row to avoid false reads
            if let code {
                if code == lastCode { DispatchQueue.main.async { self.onCode(code) } }
                lastCode = code
            }
        }
    }
}

/**
 * NFC reader (Core NFC, needs the "NFC Tag Reading" capability — paid developer account).
 *  - Android phone showing its card: Host Card Emulation, AID F04C594E4301 → 4-byte code + 9000.
 *  - Lync sticker: NDEF URI "https://lync.app/c/<code>" on NTAG/Ultralight (MIFARE), Type 4 (ISO 7816)
 *    or ISO 15693 tags.
 * iPhones can read but not emulate a card, so iPhone ↔ iPhone goes through the camera code.
 */
final class NFCCardReader: NSObject, NFCTagReaderSessionDelegate {
    private var session: NFCTagReaderSession?
    private var onCode: ((Int64) -> Void)?
    static let aid = Data([0xF0, 0x4C, 0x59, 0x4E, 0x43, 0x01])
    static var available: Bool { NFCTagReaderSession.readingAvailable }

    func start(_ onCode: @escaping (Int64) -> Void) {
        guard Self.available, session == nil else { return }
        self.onCode = onCode
        session = NFCTagReaderSession(pollingOption: [.iso14443, .iso15693], delegate: self, queue: nil)
        session?.alertMessage = L("nfc_hold_near")
        session?.begin()
    }

    func stop() { session?.invalidate(); session = nil }

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        DispatchQueue.main.async { if self.session === session { self.session = nil } }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        if tags.count > 1 {
            session.alertMessage = L("nfc_one_at_a_time")
            DispatchQueue.global().asyncAfter(deadline: .now() + 0.6) { session.restartPolling() }
            return
        }
        guard let tag = tags.first else { return }
        session.connect(to: tag) { [weak self] error in
            guard let self else { return }
            if error != nil { return self.fail(session, L("nfc_read_failed")) }
            switch tag {
            case .iso7816(let card):
                if card.initialSelectedAID.uppercased() == "F04C594E4301" { self.askAndroidCard(card, session) }
                else { self.readNDEF(card, session) } // Type 4 sticker
            case .miFare(let t): self.readNDEF(t, session)
            case .iso15693(let t): self.readNDEF(t, session)
            default: self.fail(session, L("nfc_not_lync"))
            }
        }
    }

    /// The Android card answers SELECT with its code (the discovery SELECT's response isn't exposed, so ask again).
    private func askAndroidCard(_ card: NFCISO7816Tag, _ session: NFCTagReaderSession) {
        let apdu = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0xA4, p1Parameter: 0x04, p2Parameter: 0x00, data: Self.aid, expectedResponseLength: 256)
        card.sendCommand(apdu: apdu) { data, sw1, sw2, error in
            if let code = Self.parseResponse(data, sw1, sw2), error == nil { self.finish(code, session) }
            else { self.fail(session, L("nfc_card_hidden")) }
        }
    }

    private func readNDEF(_ tag: NFCNDEFTag, _ session: NFCTagReaderSession) {
        tag.queryNDEFStatus { status, _, error in
            guard error == nil, status != .notSupported else { return self.fail(session, L("nfc_not_lync")) }
            tag.readNDEF { message, _ in
                if let code = message.flatMap(Self.code(in:)) { self.finish(code, session) }
                else { self.fail(session, L("nfc_not_lync")) }
            }
        }
    }

    private func finish(_ code: Int64, _ session: NFCTagReaderSession) {
        session.alertMessage = L("nfc_success")
        session.invalidate()
        DispatchQueue.main.async { self.onCode?(code) }
    }

    private func fail(_ session: NFCTagReaderSession, _ message: String) { session.invalidate(errorMessage: message) }

    // MARK: parsing (pure, unit-tested)

    static func parseResponse(_ data: Data, _ sw1: UInt8, _ sw2: UInt8) -> Int64? {
        guard sw1 == 0x90, sw2 == 0x00, data.count == 4 else { return nil }
        let code = data.reduce(Int64(0)) { ($0 << 8) | Int64($1) }
        return code > 0 ? code : nil
    }

    static func code(in message: NFCNDEFMessage) -> Int64? {
        for record in message.records {
            if let url = record.wellKnownTypeURIPayload(), let code = parseUri(url.absoluteString) { return code }
            if let text = String(data: record.payload, encoding: .utf8), let code = parseUri(text) { return code }
        }
        return nil
    }

    static func parseUri(_ uri: String) -> Int64? {
        guard let r = uri.range(of: #"lync\.app/c/(\d+)"#, options: .regularExpression) else { return nil }
        return Int64(uri[r].split(separator: "/").last ?? "").flatMap { $0 > 0 && $0 <= 0xFFFFFFFF ? $0 : nil }
    }
}
