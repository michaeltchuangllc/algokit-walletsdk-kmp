import AVFoundation
import CoreImage
import CoreVideo
import UIKit
import composeDemoApp

final class BroadcastFrameCapture: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {

    static let shared = BroadcastFrameCapture()

    private let output = AVCaptureVideoDataOutput()
    private let captureQueue = DispatchQueue(label: "com.walletsdk.broadcastCapture", qos: .userInteractive)
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])
    private var lastFrameTime: TimeInterval = 0
    private let frameInterval: TimeInterval = 0.1
    private var isRunning = false

    private override init() {}

    func start() {
        guard !isRunning else { return }

        guard let session = App_iosKt.getBroadcastCaptureSession() as? AVCaptureSession else {
            NSLog("⚠️ BroadcastFrameCapture: no active capture session — retrying in 500ms")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.start()
            }
            return
        }

        output.setSampleBufferDelegate(self, queue: captureQueue)
        output.alwaysDiscardsLateVideoFrames = true

        session.beginConfiguration()
        if session.canAddOutput(output) {
            session.addOutput(output)
            NSLog("📷 BroadcastFrameCapture: output added")
        } else {
            NSLog("⚠️ BroadcastFrameCapture: cannot add output")
        }
        session.commitConfiguration()
        isRunning = true
    }

    func stop() {
        guard isRunning else { return }
        if let session = App_iosKt.getBroadcastCaptureSession() as? AVCaptureSession {
            session.beginConfiguration()
            session.removeOutput(output)
            session.commitConfiguration()
        }
        isRunning = false
        NSLog("📷 BroadcastFrameCapture: stopped")
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        let now = CACurrentMediaTime()
        guard now - lastFrameTime >= frameInterval else { return }
        lastFrameTime = now

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        guard let cgImage = ciContext.createCGImage(ciImage, from: ciImage.extent) else { return }
        let uiImage = UIImage(cgImage: cgImage)
        guard let jpegData = uiImage.jpegData(compressionQuality: 0.7) else { return }

        let width  = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)

        App_iosKt.notifyBroadcastFrameReady(
            data: jpegData as NSData,
            width: Int32(width),
            height: Int32(height)
        )
    }
}
