import UIKit
import sharedDemoApp
import Foundation
import WebRTC
import AVFoundation

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    private var activeStreamingService: LiquidAuthService?
    private var broadcastVideoCapturer: RTCCameraVideoCapturer?
    private var broadcastMedia: SharedBroadcastMedia?
    private var broadcastCaptureStopping = false
    private var broadcastGeneration: UInt64 = 0
    private var cancelledBroadcastIds: Set<String> = []
    private struct PendingBroadcastStart {
        let token: UUID
        let generation: UInt64
        let start: () -> Void
        let expiry: Timer
    }
    private var pendingBroadcastStarts: [String: PendingBroadcastStart] = [:]
    private var requestingBroadcastPermission = false

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // Configure app group for database sharing
        configureAppGroup()
        
        // Initialize Napier logging BEFORE Compose starts
        // Note: Koin will be initialized by Compose's KoinApplication automatically
        App_iosKt.initializeNapierLogging()
        
        // Register Liquid Auth callback for iOS
        registerLiquidAuthCallback()
        
        window = UIWindow(frame: UIScreen.main.bounds)
        if let window = window {
            window.rootViewController = App_iosKt.MainViewController()
            window.makeKeyAndVisible()
        }
        return true
    }
    
    private func configureAppGroup() {
        let appGroupIdentifier = "group.com.michaeltchuang.walletsdk.demo"
        if let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) {
            print("✅ App Group configured: \(containerURL.path)")
            App_iosKt.setAppGroupDirectory(directory: containerURL.path)
        } else {
            print("⚠️ Warning: App Group '\(appGroupIdentifier)' not configured!")
            print("⚠️ Add App Group in Xcode: Signing & Capabilities → + Capability → App Groups")
        }
    }
    
    private func registerLiquidAuthCallback() {
        NSLog("📱 Registering Liquid Auth callback for iOS")

        App_iosKt.iosStreamingCleanupHandler = { [weak self] in
            NSLog("🧹 Streaming viewer dismissed — disconnecting LiquidAuthService")
            self?.activeStreamingService?.disconnect()
            self?.activeStreamingService = nil
        }

        App_iosKt.setViewerStopHandler { [weak self] in
            NSLog("🧹 Viewer overlay closed — disconnecting LiquidAuthService")
            self?.activeStreamingService?.disconnect()
            self?.activeStreamingService = nil
        }

        App_iosKt.registerIosNativeMediaHandlers(
            localVideoViewProvider: { SignalService.shared.makeLocalVideoRenderer() },
            remoteVideoViewProvider: { [weak self] in self?.activeStreamingService?.makeRemoteVideoRenderer() },
            setAudioEnabled: { enabled in
                DispatchQueue.main.async { SignalService.shared.setLocalAudioEnabled(enabled.boolValue) }
            },
            setVideoEnabled: { enabled in
                DispatchQueue.main.async { SignalService.shared.setLocalVideoEnabled(enabled.boolValue) }
            }
        )

        SignalService.shared.onHostMediaCreated = { [weak self] media in
            self?.startBroadcastMedia(media)
        }
        SignalService.shared.onHostStopped = { [weak self] in
            self?.cancelPendingBroadcastStarts()
            self?.stopBroadcastMedia()
        }
        SignalService.shared.onHostViewerConnectionType = { requestId, type in
            App_iosKt.notifyBroadcastViewerConnectionType(requestId: requestId, type: type)
        }

        // Request-scoped messages never pass through the singleton paid transport.
        App_iosKt.setIosBroadcastViewerStopHandler { [weak self] requestId in
            DispatchQueue.main.async {
                self?.cancelledBroadcastIds.insert(requestId)
                self?.pendingBroadcastStarts.removeValue(forKey: requestId)?.expiry.invalidate()
                SignalService.shared.disconnectHostViewer(requestId: requestId)
            }
        }

        App_iosKt.registerBroadcastHandlers(
            startHandler: { [weak self] origin, requestId in
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.withBroadcastPermissions(requestId: requestId) { [weak self] in
                    guard let self else { return }
                    let iceServers = [RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])]
                    NSLog("📡 Broadcast: connecting for origin=\(origin) requestId=\(requestId)")
                    // Add an invitation; neither start() nor the single-peer replacement
                    // API may be called here while other viewers are receiving media.
                    SignalService.shared.connectHostViewer(
                        requestId: requestId,
                        origin: origin,
                        iceServers: iceServers,
                        onMessage: { message in
                            App_iosKt.notifyBroadcastViewerMessageReceived(
                                requestId: requestId, message: message
                            )
                        },
                        onStateChange: { state in
                            NSLog("📡 Broadcast data-channel state: \(state ?? "nil")")
                            switch state {
                            case "open":
                                // Publish first-connected identity before a secondary-channel
                                // failure can retire it. Kotlin and Swift reserve the same slot.
                                App_iosKt.isBroadcastChannelOpen = true
                                App_iosKt.notifyBroadcastViewerConnected(requestId: requestId)
                                // Preserve primary payments; extras receive scoped metadata/chat.
                                if let paymentDC = SignalService.shared.createAdditionalDataChannel(
                                    label: "x402-payment-channel",
                                    requestId: requestId,
                                    onMessage: { message in
                                        App_iosKt.notifyBroadcastViewerMessageReceived(
                                            requestId: requestId, message: message
                                        )
                                    },
                                    onStateChange: { dcState in
                                        NSLog("Payment DC state: \(dcState ?? "nil")")
                                    },
                                    onOpen: { channel in
                                        App_iosKt.setIosBroadcastViewerPaymentSendHandler(
                                            requestId: requestId,
                                            handler: { [weak channel] message in
                                                DispatchQueue.main.async {
                                                    guard let channel, channel.readyState == .open,
                                                          SignalService.shared.hostViewers[requestId] != nil else { return }
                                                    // Android bootstraps its viewer from the
                                                    // liquid channel, not the payment observer.
                                                    // Routing changes transport only, never the envelope.
                                                    if self.isViewerBootstrapMessage(message) {
                                                        SignalService.shared.sendHostMessage(
                                                            message, requestId: requestId
                                                        )
                                                        return
                                                    }
                                                    channel.sendData(RTCDataBuffer(data: Data(message.utf8), isBinary: false))
                                                }
                                            }
                                        )
                                    }
                                ) {
                                    if SignalService.shared.primaryHostRequestId == requestId {
                                        App_iosKt.setIosBroadcastPaymentSendHandler { message in
                                            DispatchQueue.main.async { [weak paymentDC] in
                                                guard let paymentDC, paymentDC.readyState == .open,
                                                      SignalService.shared.hostViewers[requestId] != nil else { return }
                                                paymentDC.sendData(RTCDataBuffer(data: Data(message.utf8), isBinary: false))
                                            }
                                        }
                                    }
                                    NSLog("Payment DC created: x402-payment-channel (id=\(paymentDC.channelId))")
                                } else {
                                    NSLog("Warning: failed to create x402-payment-channel DC")
                                    App_iosKt.notifyBroadcastInvitationFailed(
                                        requestId: requestId,
                                        message: "Unable to create the viewer session channel."
                                    )
                                    SignalService.shared.disconnectHostViewer(requestId: requestId)
                                    return
                                }
                                // The keyed sender is installed only when its channel opens.
                                NSLog("Broadcast: viewer connected (requestId=\(requestId))")
                            case "failed", "expired", "pending-limit":
                                App_iosKt.isBroadcastChannelOpen = SignalService.shared.hasConnectedHostViewers
                                let reason = state == "expired" ? "The viewer invitation expired after five minutes." :
                                    state == "pending-limit" ? "At most eight viewer invitations may be pending." :
                                    "The viewer peer connection failed."
                                App_iosKt.notifyBroadcastInvitationFailed(
                                    requestId: requestId, message: reason
                                )
                            case "closed":
                                App_iosKt.isBroadcastChannelOpen = SignalService.shared.hasConnectedHostViewers
                                App_iosKt.notifyBroadcastViewerDisconnected(requestId: requestId)
                                NSLog("🔴 Broadcast: viewer disconnected (state=\(state ?? "?"))")
                            default:
                                break
                            }
                        }
                    )
                    }
                }
            },
            stopHandler: {
                DispatchQueue.main.async {
                    App_iosKt.isBroadcastChannelOpen = false
                    SignalService.shared.stopHosting()
                    NSLog("🛑 Broadcast: SignalService stopped")
                }
            },
            sendMessageHandler: { message in
                DispatchQueue.main.async {
                    SignalService.shared.sendMessage(message)
                }
            }
        )
        App_iosKt.enableIosMeshHosting()
        NSLog("✅ Broadcast handlers registered")

        App_iosKt.registerBroadcastFrameCapture(
            startCapture: {
                DispatchQueue.main.async {
                    BroadcastFrameCapture.shared.start()
                    NSLog("📷 Broadcast frame capture started")
                }
            },
            stopCapture: {
                DispatchQueue.main.async {
                    BroadcastFrameCapture.shared.stop()
                    NSLog("📷 Broadcast frame capture stopped")
                }
            }
        )
        NSLog("✅ Broadcast frame capture bridge registered")

        // ── Viewer (answer) handler ────────────────────────────────────────────
        // Register the callback using the bridge function in sharedDemoApp
        App_iosKt.setIosLiquidAuthHandler { [weak self] origin, requestId, algoAddress in
            NSLog("🔗 Liquid Auth callback triggered from Kotlin")
            NSLog("   Origin: '\(origin)'")
            NSLog("   RequestID: '\(requestId)'")
            NSLog("   RequestID count: \(requestId.count) chars")
            NSLog("   RequestID isEmpty: \(requestId.isEmpty)")
            NSLog("   AlgoAddress: '\(algoAddress)'")
            
            // Present Liquid Auth on main thread
            DispatchQueue.main.async {
                self?.presentLiquidAuthFlow(
                    origin: origin,
                    requestId: requestId,
                    algoAddress: algoAddress
                )
            }
        }
        
        NSLog("✅ Liquid Auth callback registered successfully")
    }

    /// Permission prompts precede peer creation, so both tracks are attached synchronously
    /// before SDP negotiation. All pending-start mutations and callbacks run on main.
    private func withBroadcastPermissions(requestId: String, start: @escaping () -> Void) {
        guard !cancelledBroadcastIds.contains(requestId),
              pendingBroadcastStarts[requestId] == nil,
              SignalService.shared.hostViewers[requestId] == nil else { return }
        guard pendingBroadcastStarts.count + SignalService.shared.pendingHostViewerCount <
                SignalService.maximumPendingHostViewers else {
            cancelledBroadcastIds.insert(requestId)
            App_iosKt.notifyBroadcastInvitationFailed(
                requestId: requestId, message: "At most eight viewer invitations may be pending."
            )
            return
        }
        let token = UUID()
        let expiry = Timer.scheduledTimer(
            withTimeInterval: SignalService.pendingHostViewerLifetime, repeats: false
        ) { [weak self] _ in
            guard let self, self.pendingBroadcastStarts[requestId]?.token == token else { return }
            self.pendingBroadcastStarts.removeValue(forKey: requestId)
            self.cancelledBroadcastIds.insert(requestId)
            App_iosKt.notifyBroadcastInvitationFailed(
                requestId: requestId, message: "The viewer invitation expired while waiting for media permission."
            )
        }
        pendingBroadcastStarts[requestId] = PendingBroadcastStart(
            token: token, generation: broadcastGeneration, start: start, expiry: expiry
        )
        resolveBroadcastPermissions()
    }

    private func resolveBroadcastPermissions() {
        guard !requestingBroadcastPermission, !pendingBroadcastStarts.isEmpty else { return }
        for mediaType in [AVMediaType.video, AVMediaType.audio] {
            switch AVCaptureDevice.authorizationStatus(for: mediaType) {
            case .authorized:
                continue
            case .notDetermined:
                requestingBroadcastPermission = true
                AVCaptureDevice.requestAccess(for: mediaType) { [weak self] _ in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        self.requestingBroadcastPermission = false
                        self.resolveBroadcastPermissions()
                    }
                }
                return
            default:
                let denied = pendingBroadcastStarts
                pendingBroadcastStarts.removeAll()
                for (requestId, pending) in denied {
                    pending.expiry.invalidate()
                    guard pending.generation == broadcastGeneration else { continue }
                    cancelledBroadcastIds.insert(requestId)
                    App_iosKt.notifyBroadcastInvitationFailed(
                        requestId: requestId,
                        message: "Camera and microphone permission are required. Enable them in Settings."
                    )
                }
                return
            }
        }
        let ready = pendingBroadcastStarts
        pendingBroadcastStarts.removeAll()
        for (requestId, pending) in ready {
            pending.expiry.invalidate()
            guard pending.generation == broadcastGeneration,
                  !cancelledBroadcastIds.contains(requestId) else { continue }
            pending.start()
        }
    }

    private func cancelPendingBroadcastStarts() {
        broadcastGeneration &+= 1
        for pending in pendingBroadcastStarts.values { pending.expiry.invalidate() }
        pendingBroadcastStarts.removeAll()
        cancelledBroadcastIds.removeAll()
        // An OS permission prompt cannot be cancelled. Its completion only drains
        // the current generation; it has no captured invitation-start closure.
    }

    private func startBroadcastMedia(_ media: SharedBroadcastMedia) {
        broadcastMedia = media
        // A rapid stop/start must wait for the previous camera session to release
        // the device. The newest media request wins while shutdown is in flight.
        guard !broadcastCaptureStopping, broadcastVideoCapturer == nil else { return }
        let capturer = RTCCameraVideoCapturer(delegate: media.videoSource)
        guard let camera = RTCCameraVideoCapturer.captureDevices().first(where: { $0.position == .front })
            ?? RTCCameraVideoCapturer.captureDevices().first,
            let format = RTCCameraVideoCapturer.supportedFormats(for: camera).max(by: {
                CMVideoFormatDescriptionGetDimensions($0.formatDescription).width <
                    CMVideoFormatDescriptionGetDimensions($1.formatDescription).width
            }) else {
            NSLog("❌ Broadcast: no camera available for WebRTC capture")
            return
        }

        let fps = min(format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 30, 30)
        broadcastVideoCapturer = capturer
        capturer.startCapture(with: camera, format: format, fps: Int(fps)) { error in
            if let error { NSLog("❌ Broadcast camera start failed: \(error)") }
        }
        NSLog("✅ Broadcast: shared WebRTC camera and microphone source started")
    }

    private func isViewerBootstrapMessage(_ message: String) -> Bool {
        guard let data = message.data(using: .utf8),
              let envelope = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return false }
        return envelope["reference"] as? String == "liquid:stream:info" ||
            envelope["type"] as? String == "stream:cost:update"
    }

    private func stopBroadcastMedia() {
        let capturer = broadcastVideoCapturer
        let media = broadcastMedia
        broadcastVideoCapturer = nil
        broadcastMedia = nil
        guard let capturer else { return }
        broadcastCaptureStopping = true
        // Retain both through asynchronous capture shutdown. Peer removal never calls this.
        capturer.stopCapture { [weak self, capturer, media] in
            _ = capturer
            _ = media
            DispatchQueue.main.async {
                guard let self else { return }
                self.broadcastCaptureStopping = false
                NSLog("🛑 Broadcast: shared WebRTC camera stopped")
                if let pending = self.broadcastMedia { self.startBroadcastMedia(pending) }
            }
        }
    }

    /// Present the Liquid Auth flow.
    private func presentLiquidAuthFlow(origin: String, requestId: String, algoAddress: String) {
        NSLog("🌉 Presenting Liquid Auth flow")
        NSLog("   📥 Received parameters:")
        NSLog("      origin: '\(origin)'")
        NSLog("      requestId: '\(requestId)'")
        NSLog("      requestId count: \(requestId.count) chars")
        NSLog("      requestId isEmpty: \(requestId.isEmpty)")
        NSLog("      algoAddress: '\(algoAddress)'")

        guard let rootViewController = window?.rootViewController else {
            NSLog("❌ Could not find root view controller")
            return
        }
        
        // Find the top-most view controller
        let topViewController = findTopViewController(from: rootViewController)
        
        // Build the auth view controller
        let authVC = LiquidAuthViewController(
            origin: origin,
            requestId: requestId,
            algoAddress: algoAddress,
            onCompletion: { NSLog("✅ Liquid Auth completed") }
        )
        authVC.modalPresentationStyle = .pageSheet
        if let sheet = authVC.sheetPresentationController {
            sheet.detents = [.medium()]
            sheet.prefersGrabberVisible = true
        }

        authVC.onStreamingConnected = { [weak self] connOrigin, connRequestId, connAlgoAddress, service in
            guard let self = self else { return }
            NSLog("🎥 Auth VC dismissed — Compose overlay renders the viewer")
            self.activeStreamingService = service
            service.messageForwardingHandler = { message in
                App_iosKt.forwardMessageToActiveViewer(message: message)
            }
            NSLog("🔌 Service owned by AppDelegate — channel alive; overlay drives the UI")
        }
        
        topViewController.present(authVC, animated: true)
        NSLog("✅ LiquidAuthViewController presented")
    }
    
    /// Find the top-most view controller in the hierarchy
    private func findTopViewController(from viewController: UIViewController) -> UIViewController {
        if let presentedViewController = viewController.presentedViewController {
            return findTopViewController(from: presentedViewController)
        }
        
        if let navigationController = viewController as? UINavigationController,
           let visibleViewController = navigationController.visibleViewController {
            return findTopViewController(from: visibleViewController)
        }
        
        if let tabBarController = viewController as? UITabBarController,
           let selectedViewController = tabBarController.selectedViewController {
            return findTopViewController(from: selectedViewController)
        }
        
        return viewController
    }
}
