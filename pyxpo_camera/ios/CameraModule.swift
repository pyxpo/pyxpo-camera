import AVFoundation
import CoreImage
import ImageIO
import UIKit
import Vision

/// expo-camera on iOS: AVFoundation permissions, a capture session view (`PxCameraView`) and Vision barcode
/// scanning of image files.
final class CameraModule: PxViewModule {
    static let moduleName = "Camera"
    static let viewTypes = ["PxCameraView"]

    required init() {}

    func makeView(_ ctx: PxModuleContext, _ type: String, _ emit: @escaping (String, Any?) -> Void) -> PxNativeView {
        PxCameraView(emit)
    }

    func call(_ ctx: PxModuleContext, _ method: String, _ args: [Any], _ reply: @escaping PxReply) {
        func view() -> PxCameraView? {
            guard let tag = (args.first as? NSNumber)?.intValue, let v = ctx.view(tag) as? PxCameraView else {
                reply(nil, "The CameraView is not mounted")
                return nil
            }
            return v
        }
        let opts = args.count > 1 ? args[1] as? [String: Any] ?? [:] : [:]
        switch method {
        case "getPermission":
            reply(Self.permission(Self.media(args.first)), nil)
        case "requestPermission":
            let media = Self.media(args.first)
            if AVCaptureDevice.authorizationStatus(for: media) != .notDetermined { reply(Self.permission(media), nil); return }
            AVCaptureDevice.requestAccess(for: media) { _ in reply(Self.permission(media), nil) }
        case "isAvailable":
            reply(AVCaptureDevice.default(for: .video) != nil, nil)
        case "scanFromURL":
            let types = args.count > 1 ? args[1] as? [String] ?? ["qr"] : ["qr"]
            Self.scan(args.first as? String ?? "", types, reply)
        case "takePicture":
            view()?.takePicture(opts, reply)
        case "record":
            view()?.record(opts, reply)
        case "stopRecording":
            view()?.stopRecording()
            reply(nil, nil)
        case "pausePreview":
            view()?.setPaused(true)
            reply(nil, nil)
        case "resumePreview":
            view()?.setPaused(false)
            reply(nil, nil)
        case "pictureSizes":
            if let v = view() { reply(v.pictureSizes(), nil) }
        default:
            reply(nil, "Camera has no method \(method)")
        }
    }

    // permissions

    static func media(_ kind: Any?) -> AVMediaType { (kind as? String) == "microphone" ? .audio : .video }

    static func permission(_ media: AVMediaType) -> [String: Any] {
        switch AVCaptureDevice.authorizationStatus(for: media) {
        case .authorized: return ["status": "granted", "canAskAgain": true]
        case .notDetermined: return ["status": "undetermined", "canAskAgain": true]
        default: return ["status": "denied", "canAskAgain": false]
        }
    }

    // barcode types

    /// Expo names to AVFoundation types. UPC-A is EAN-13 with a leading 0 on iOS, so `upc_a` scans EAN-13.
    static func metadataTypes(_ names: [String]) -> [AVMetadataObject.ObjectType] {
        var out: [AVMetadataObject.ObjectType] = []
        for n in names {
            switch n {
            case "aztec": out.append(.aztec)
            case "ean13", "upc_a": out.append(.ean13)
            case "ean8": out.append(.ean8)
            case "qr": out.append(.qr)
            case "pdf417": out.append(.pdf417)
            case "upc_e": out.append(.upce)
            case "datamatrix": out.append(.dataMatrix)
            case "code39": out.append(.code39)
            case "code93": out.append(.code93)
            case "itf14": out.append(.itf14)
            case "codabar": if #available(iOS 15.4, *) { out.append(.codabar) }
            case "code128": out.append(.code128)
            default: break
            }
        }
        return Array(Set(out))
    }

    static func name(_ t: AVMetadataObject.ObjectType) -> String? {
        if #available(iOS 15.4, *), t == .codabar { return "codabar" }
        switch t {
        case .aztec: return "aztec"
        case .ean13: return "ean13"
        case .ean8: return "ean8"
        case .qr: return "qr"
        case .pdf417: return "pdf417"
        case .upce: return "upc_e"
        case .dataMatrix: return "datamatrix"
        case .code39: return "code39"
        case .code93: return "code93"
        case .itf14: return "itf14"
        case .code128: return "code128"
        default: return nil
        }
    }

    static func symbologies(_ names: [String]) -> [VNBarcodeSymbology] {
        var out: [VNBarcodeSymbology] = []
        for n in names {
            switch n {
            case "aztec": out.append(.aztec)
            case "ean13", "upc_a": out.append(.ean13)
            case "ean8": out.append(.ean8)
            case "qr": out.append(.qr)
            case "pdf417": out.append(.pdf417)
            case "upc_e": out.append(.upce)
            case "datamatrix": out.append(.dataMatrix)
            case "code39": out.append(.code39)
            case "code93": out.append(.code93)
            case "itf14": out.append(.itf14)
            case "codabar": out.append(.codabar)
            case "code128": out.append(.code128)
            default: break
            }
        }
        return Array(Set(out))
    }

    static func name(_ s: VNBarcodeSymbology) -> String? {
        switch s {
        case .aztec: return "aztec"
        case .ean13: return "ean13"
        case .ean8: return "ean8"
        case .qr: return "qr"
        case .pdf417: return "pdf417"
        case .upce: return "upc_e"
        case .dataMatrix: return "datamatrix"
        case .code39: return "code39"
        case .code93: return "code93"
        case .itf14: return "itf14"
        case .codabar: return "codabar"
        case .code128: return "code128"
        default: return nil
        }
    }

    /// Expo's `BarcodeScanningResult`.
    static func result(_ type: String, _ data: String, _ rect: CGRect, _ corners: [CGPoint]) -> [String: Any] {
        ["type": type, "data": data, "raw": data,
         "bounds": ["origin": ["x": rect.minX, "y": rect.minY], "size": ["width": rect.width, "height": rect.height]],
         "cornerPoints": corners.map { ["x": $0.x, "y": $0.y] }]
    }

    /// Vision on a background queue. Points are image pixels, origin top-left.
    static func scan(_ url: String, _ types: [String], _ reply: @escaping PxReply) {
        let fileURL = url.hasPrefix("file:") ? URL(string: url) : URL(fileURLWithPath: url)
        DispatchQueue.global(qos: .userInitiated).async {
            guard let u = fileURL, let src = CGImageSourceCreateWithURL(u as CFURL, nil),
                  let image = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
                reply(nil, "Cannot read an image at \(url)")
                return
            }
            #if targetEnvironment(simulator)
            // Vision finds no barcodes in the Simulator (no Neural Engine): Core Image reads QR codes there.
            reply(types.contains("qr") ? qrCodes(image) : [], nil)
            return
            #else
            let request = VNDetectBarcodesRequest()
            request.symbologies = symbologies(types)
            do {
                try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
            } catch {
                reply(nil, "Barcode scanning failed: \(error.localizedDescription)")
                return
            }
            let w = CGFloat(image.width), h = CGFloat(image.height)
            func point(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * w, y: (1 - p.y) * h) }
            let out: [[String: Any]] = (request.results ?? []).compactMap { o in
                guard let type = name(o.symbology) else { return nil }
                let b = o.boundingBox
                let rect = CGRect(x: b.minX * w, y: (1 - b.maxY) * h, width: b.width * w, height: b.height * h)
                return result(type, o.payloadStringValue ?? "", rect,
                              [o.topLeft, o.topRight, o.bottomRight, o.bottomLeft].map(point))
            }
            // Vision can report one code once per symbology it matches; keep one per payload and place.
            var seen = Set<String>()
            reply(out.filter { r in
                let b = r["bounds"] as? [String: Any]
                return seen.insert("\(r["data"] ?? "")|\(String(describing: b?["origin"]))").inserted
            }, nil)
            #endif
        }
    }

    /// QR codes through Core Image (origin bottom-left), as image pixels with the origin top-left.
    static func qrCodes(_ image: CGImage) -> [[String: Any]] {
        let h = CGFloat(image.height)
        let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        let found = detector?.features(in: CIImage(cgImage: image)) ?? []
        return found.compactMap { f in
            guard let q = f as? CIQRCodeFeature else { return nil }
            let b = q.bounds
            func point(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x, y: h - p.y) }
            return result("qr", q.messageString ?? "", CGRect(x: b.minX, y: h - b.maxY, width: b.width, height: b.height),
                          [q.topLeft, q.topRight, q.bottomRight, q.bottomLeft].map(point))
        }
    }

    static func cacheFile(_ ext: String) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Camera")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("\(UUID().uuidString).\(ext)")
    }
}

/// The preview view: its layer is the session's preview layer.
final class PxCameraPreview: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

/// One `CameraView`. The session is configured and started on its own queue; UI and replies on main.
final class PxCameraView: NSObject, PxNativeView, AVCaptureMetadataOutputObjectsDelegate,
                          AVCapturePhotoCaptureDelegate, AVCaptureFileOutputRecordingDelegate {
    private let preview = PxCameraPreview()
    var view: UIView { preview }

    private let emit: (String, Any?) -> Void
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "dev.pyxpo.camera.session")

    private var facing = "back"
    private var flash = "off"
    private var torch = false
    private var zoom: CGFloat = 0
    private var mode = "picture"
    private var mute = false
    private var active = true
    private var paused = false
    private var mirror = false
    private var animateShutter = true
    private var quality: String?
    private var barcodeTypes: [String]?
    private var configured = false

    private var device: AVCaptureDevice?
    private var photoOut: AVCapturePhotoOutput?
    private var movieOut: AVCaptureMovieFileOutput?
    private var metaOut: AVCaptureMetadataOutput?
    private var photoReplies: [Int64: ([String: Any], PxReply)] = [:]
    private var recordReply: PxReply?
    private var lastScan = Date.distantPast

    init(_ emit: @escaping (String, Any?) -> Void) {
        self.emit = emit
        super.init()
        preview.backgroundColor = .black
        preview.clipsToBounds = true
        preview.previewLayer.session = session
        preview.previewLayer.videoGravity = .resizeAspectFill
    }

    func update(_ props: [String: Any], _ removed: [String]) {
        var reconfigure = !configured
        func set<T: Equatable>(_ key: String, _ value: inout T, reconfigures: Bool = false) {
            guard let v = props[key] as? T else { return }
            if reconfigures && v != value { reconfigure = true }
            value = v
        }
        set("facing", &facing, reconfigures: true)
        set("mode", &mode, reconfigures: true)
        set("mute", &mute, reconfigures: true)
        set("mirror", &mirror)
        set("flash", &flash)
        set("animate_shutter", &animateShutter)
        set("active", &active)
        if let q = props["video_quality"] as? String, q != quality { quality = q; reconfigure = true }
        if removed.contains("video_quality") && quality != nil { quality = nil; reconfigure = true }
        let scanning = props["on_barcode_scanned"] != nil || (barcodeTypes != nil && !removed.contains("on_barcode_scanned"))
        let types = scanning ? (props["barcode_types"] as? [String] ?? barcodeTypes) : nil
        if types != barcodeTypes { barcodeTypes = types; reconfigure = true }
        if let t = props["enable_torch"] as? Bool { torch = t }
        if let z = props["zoom"] as? NSNumber { zoom = CGFloat(z.doubleValue) }
        configured = true
        let (torch, zoom, run) = (torch, zoom, active && !paused)
        if reconfigure {
            configure()
        } else {
            queue.async { [weak self] in self?.applyDevice(torch: torch, zoom: zoom) }
        }
        queue.async { [weak self] in self?.setRunning(run) }
    }

    func dispose() {
        let s = session
        queue.async {
            if s.isRunning { s.stopRunning() }
        }
        movieOut?.stopRecording()
    }

    func setPaused(_ p: Bool) {
        paused = p
        preview.previewLayer.connection?.isEnabled = !p
        let run = active && !p
        queue.async { [weak self] in self?.setRunning(run) }
    }

    private func setRunning(_ run: Bool) {
        if run && !session.isRunning && !session.inputs.isEmpty { session.startRunning() }
        if !run && session.isRunning { session.stopRunning() }
    }

    private func fail(_ message: String) {
        DispatchQueue.main.async { [emit] in emit("mount_error", ["message": message]) }
    }

    private func applyDevice(torch: Bool, zoom: CGFloat) {
        guard let d = device, (try? d.lockForConfiguration()) != nil else { return }
        let maxZoom = min(d.activeFormat.videoMaxZoomFactor, 16)
        d.videoZoomFactor = 1 + max(0, min(zoom, 1)) * (maxZoom - 1)
        if d.hasTorch && d.isTorchModeSupported(torch ? .on : .off) { d.torchMode = torch ? .on : .off }
        d.unlockForConfiguration()
    }

    private func configure() {
        let facing = facing, mode = mode, mute = mute, quality = quality, types = barcodeTypes
        let torch = torch, zoom = zoom
        queue.async { [weak self] in
            guard let self = self else { return }
            guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
                self.fail("Camera permission is not granted")
                return
            }
            let position: AVCaptureDevice.Position = facing == "front" ? .front : .back
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position),
                  let input = try? AVCaptureDeviceInput(device: device) else {
                self.fail("This device has no \(facing) camera")
                return
            }
            let s = self.session
            s.beginConfiguration()
            s.inputs.forEach { s.removeInput($0) }
            s.outputs.forEach { s.removeOutput($0) }
            self.photoOut = nil
            self.movieOut = nil
            self.metaOut = nil
            s.sessionPreset = mode == "video" ? Self.preset(quality) : .photo
            if s.canAddInput(input) { s.addInput(input) }
            self.device = device
            if mode == "video" {
                let movie = AVCaptureMovieFileOutput()
                if s.canAddOutput(movie) { s.addOutput(movie); self.movieOut = movie }
                if !mute, AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
                   let mic = AVCaptureDevice.default(for: .audio), let micInput = try? AVCaptureDeviceInput(device: mic),
                   s.canAddInput(micInput) {
                    s.addInput(micInput)
                }
            } else {
                let photo = AVCapturePhotoOutput()
                if s.canAddOutput(photo) { s.addOutput(photo); self.photoOut = photo }
            }
            if let types = types {
                let meta = AVCaptureMetadataOutput()
                if s.canAddOutput(meta) {
                    s.addOutput(meta)
                    let wanted = CameraModule.metadataTypes(types)
                    meta.metadataObjectTypes = wanted.filter { meta.availableMetadataObjectTypes.contains($0) }
                    meta.setMetadataObjectsDelegate(self, queue: .main)
                    self.metaOut = meta
                }
            }
            s.commitConfiguration()
            self.applyDevice(torch: torch, zoom: zoom)
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.queue.async { self.setRunning(self.active && !self.paused) }
                self.emit("camera_ready", nil)
            }
        }
    }

    private static func preset(_ q: String?) -> AVCaptureSession.Preset {
        switch q {
        case "2160p": return .hd4K3840x2160
        case "1080p": return .hd1920x1080
        case "720p": return .hd1280x720
        case "480p": return .vga640x480
        case "4:3": return .vga640x480
        default: return .high
        }
    }

    private func rotate(_ c: AVCaptureConnection?) {
        guard let c = c else { return }
        if #available(iOS 17.0, *) {
            if c.isVideoRotationAngleSupported(90) { c.videoRotationAngle = 90 }
        } else if c.isVideoOrientationSupported {
            c.videoOrientation = .portrait
        }
    }

    // barcodes

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard barcodeTypes != nil, Date().timeIntervalSince(lastScan) >= 0.5 else { return }
        for o in objects {
            guard let code = o as? AVMetadataMachineReadableCodeObject, let type = CameraModule.name(code.type),
                  let t = preview.previewLayer.transformedMetadataObject(for: code) as? AVMetadataMachineReadableCodeObject
            else { continue }
            lastScan = Date()
            emit("barcode_scanned", CameraModule.result(type, code.stringValue ?? "", t.bounds, t.corners))
            return
        }
    }

    // photos

    func takePicture(_ o: [String: Any], _ reply: @escaping PxReply) {
        guard let out = photoOut else {
            reply(nil, mode == "video" ? "takePictureAsync needs mode=\"picture\"" : "The camera is not ready")
            return
        }
        let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
        let fm: AVCaptureDevice.FlashMode = flash == "on" ? .on : flash == "auto" ? .auto : .off
        if out.supportedFlashModes.contains(fm) { settings.flashMode = fm }
        var opts = o
        opts["flip"] = facing == "front" && (o["mirror"] as? Bool ?? mirror)
        photoReplies[settings.uniqueID] = (opts, reply)
        if animateShutter {
            preview.alpha = 0
            UIView.animate(withDuration: 0.2) { self.preview.alpha = 1 }
        }
        queue.async { [weak self] in
            guard let self = self else { return }
            self.rotate(out.connection(with: .video))
            out.capturePhoto(with: settings, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let id = photo.resolvedSettings.uniqueID
        DispatchQueue.main.async { [weak self] in
            guard let (o, reply) = self?.photoReplies.removeValue(forKey: id) else { return }
            if let e = error { reply(nil, "Taking the picture failed: \(e.localizedDescription)"); return }
            guard let data = photo.fileDataRepresentation() else { reply(nil, "The camera returned no image"); return }
            DispatchQueue.global(qos: .userInitiated).async { Self.save(data, o, reply) }
        }
    }

    private static func save(_ data: Data, _ o: [String: Any], _ reply: PxReply) {
        guard let img = UIImage(data: data) else { reply(nil, "The camera returned no image"); return }
        let flip = o["flip"] as? Bool ?? false
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        let size = img.size
        let upright = UIGraphicsImageRenderer(size: size, format: fmt).image { ctx in
            if flip {
                ctx.cgContext.translateBy(x: size.width, y: 0)
                ctx.cgContext.scaleBy(x: -1, y: 1)
            }
            img.draw(in: CGRect(origin: .zero, size: size))
        }
        let png = (o["imageType"] as? String) == "png"
        let quality = CGFloat((o["quality"] as? NSNumber)?.doubleValue ?? 1)
        guard let out = png ? upright.pngData() : upright.jpegData(compressionQuality: quality) else {
            reply(nil, "Encoding the picture failed")
            return
        }
        let url = CameraModule.cacheFile(png ? "png" : "jpg")
        do { try out.write(to: url) } catch { reply(nil, "Saving the picture failed: \(error.localizedDescription)"); return }
        var res: [String: Any] = ["uri": url.absoluteString, "width": Int(size.width), "height": Int(size.height),
                                  "format": png ? "png" : "jpg"]
        if o["base64"] as? Bool == true { res["base64"] = out.base64EncodedString() }
        if o["exif"] as? Bool == true { res["exif"] = exif(data) }
        reply(res, nil)
    }

    private static func exif(_ data: Data) -> [String: Any] {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [String: Any] else { return [:] }
        var out: [String: Any] = [:]
        for key in [kCGImagePropertyExifDictionary, kCGImagePropertyTIFFDictionary, kCGImagePropertyGPSDictionary] {
            for (k, v) in props[key as String] as? [String: Any] ?? [:] where JSONSerialization.isValidJSONObject([v]) {
                out[k] = v
            }
        }
        if let orientation = props[kCGImagePropertyOrientation as String] { out["Orientation"] = orientation }
        return out
    }

    // videos

    func record(_ o: [String: Any], _ reply: @escaping PxReply) {
        guard let out = movieOut else {
            reply(nil, mode != "video" ? "recordAsync needs mode=\"video\"" : "The camera is not ready")
            return
        }
        if recordReply != nil || out.isRecording { reply(nil, "A recording is already in progress"); return }
        recordReply = reply
        let mirrored = facing == "front" && mirror
        let url = CameraModule.cacheFile("mov")
        let maxDuration = (o["maxDuration"] as? NSNumber)?.doubleValue
        let maxSize = (o["maxFileSize"] as? NSNumber)?.int64Value
        queue.async { [weak self] in
            guard let self = self else { return }
            out.maxRecordedDuration = maxDuration.map { CMTime(seconds: $0, preferredTimescale: 600) } ?? .invalid
            out.maxRecordedFileSize = maxSize ?? 0
            let c = out.connection(with: .video)
            self.rotate(c)
            if let c = c, c.isVideoMirroringSupported {
                c.automaticallyAdjustsVideoMirroring = false
                c.isVideoMirrored = mirrored
            }
            out.startRecording(to: url, recordingDelegate: self)
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo url: URL, from connections: [AVCaptureConnection],
                    error: Error?) {
        // A recording that hit its time or size limit ends with an error that says it finished successfully.
        let finished = error == nil ||
            ((error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool ?? false)
        DispatchQueue.main.async { [weak self] in
            guard let reply = self?.recordReply else { return }
            self?.recordReply = nil
            if finished { reply(["uri": url.absoluteString], nil) }
            else { reply(nil, "Recording failed: \(error?.localizedDescription ?? "unknown error")") }
        }
    }

    func stopRecording() {
        let out = movieOut
        queue.async { if out?.isRecording == true { out?.stopRecording() } }
    }

    /// Video sizes of the current camera, largest first.
    func pictureSizes() -> [String] {
        guard let d = device else { return [] }
        let dims: [CMVideoDimensions] = d.formats.map { CMVideoFormatDescriptionGetDimensions($0.formatDescription) }
        let sorted = dims.sorted { (a: CMVideoDimensions, b: CMVideoDimensions) -> Bool in
            Int(a.width) * Int(a.height) > Int(b.width) * Int(b.height)
        }
        var seen = Set<String>()
        var out: [String] = []
        for s in sorted {
            let name = "\(s.width)x\(s.height)"
            if seen.insert(name).inserted { out.append(name) }
        }
        return out
    }
}
