import UIKit
import composeDemoApp
import Foundation
import WebRTC

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    private var activeStreamingService: LiquidAuthService?

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

        App_iosKt.registerBroadcastHandlers(
            startHandler: { origin, requestId in
                DispatchQueue.main.async {
                    let iceServers = [RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])]
                    NSLog("📡 Broadcast: connecting for origin=\(origin) requestId=\(requestId)")
                    // Note: no separate start() call — connectToPeer handles socket setup internally.
                    SignalService.shared.connectToPeer(
                        requestId: requestId,
                        type: "offer",
                        origin: origin,
                        iceServers: iceServers,
                        onMessage: { message in
                            App_iosKt.notifyBroadcastMessageReceived(message: message)
                        },
                        onStateChange: { state in
                            NSLog("📡 Broadcast data-channel state: \(state ?? "nil")")
                            switch state {
                            case "open":
                                // 1. Create the "x402-payment-channel" DC on the same peerConnection
                                //    BEFORE notifying Kotlin, so the send handler is ready when
                                //    PaywalledRTCServer fires its first segment:request.
                                if let paymentDC = SignalService.shared.createAdditionalDataChannel(
                                    label: "x402-payment-channel",
                                    onMessage: { message in
                                        // Payment DC messages (segment:payment, etc.) go to Kotlin
                                        App_iosKt.notifyBroadcastMessageReceived(message: message)
                                    },
                                    onStateChange: { dcState in
                                        NSLog("Payment DC state: \(dcState ?? "nil")")
                                    }
                                ) {
                                    // 2. Override the Kotlin send handler to use the payment DC
                                    //    (PaywalledRTCServer sends segment:request on it).
                                    App_iosKt.setIosBroadcastPaymentSendHandler { message in
                                        if let data = message.data(using: .utf8) {
                                            paymentDC.sendData(RTCDataBuffer(data: data, isBinary: false))
                                        }
                                    }
                                    NSLog("Payment DC created: x402-payment-channel (id=\(paymentDC.channelId))")
                                } else {
                                    NSLog("Warning: failed to create x402-payment-channel DC")
                                }
                                // 3. Now notify Kotlin — PaywalledRTCServer can start immediately.
                                App_iosKt.isBroadcastChannelOpen = true
                                App_iosKt.notifyBroadcastClientConnected(requestId: requestId)
                                NSLog("Broadcast: viewer connected (requestId=\(requestId))")
                            case "closed", "failed":
                                App_iosKt.isBroadcastChannelOpen = false
                                App_iosKt.notifyBroadcastClientDisconnected()
                                NSLog("🔴 Broadcast: viewer disconnected (state=\(state ?? "?"))")
                            default:
                                break
                            }
                        }
                    )
                }
            },
            stopHandler: {
                DispatchQueue.main.async {
                    App_iosKt.isBroadcastChannelOpen = false
                    SignalService.shared.stop()
                    NSLog("🛑 Broadcast: SignalService stopped")
                }
            },
            sendMessageHandler: { message in
                // Called on Kotlin's background thread — sendMessage is thread-safe.
                SignalService.shared.sendMessage(message)
            }
        )
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
        // Register the callback using the bridge function in composeDemoApp
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
