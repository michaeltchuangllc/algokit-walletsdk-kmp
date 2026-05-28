import UIKit
import composeDemoApp
import Foundation
import WebRTC

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    /// Keeps the LiquidAuthService alive after LiquidAuthViewController is dismissed
    /// so the open WebRTC data channel continues forwarding video frames to the viewer.
    /// Set to nil when the viewer screen is dismissed.
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

        // ── Viewer cleanup ─────────────────────────────────────────────────────
        // Called by the Compose viewer's DisposableEffect when the viewer screen is dismissed.
        // Disconnect + release the service that was transferred from the auth VC.
        App_iosKt.iosStreamingCleanupHandler = { [weak self] in
            NSLog("🧹 Streaming viewer dismissed — disconnecting LiquidAuthService")
            self?.activeStreamingService?.disconnect()
            self?.activeStreamingService = nil
        }

        // ── Broadcast (creator/host) bridge handlers ───────────────────────────
        // Called by IOSLiquidAuthConnectionManager when the broadcast QR screen
        // opens and startListening() is triggered from the KMP ViewModel.
        //
        // The "offer" side uses SignalService.shared (same singleton as the
        // viewer/answer side — safe because creator & viewer are different devices).
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
                                App_iosKt.isBroadcastChannelOpen = true
                                App_iosKt.notifyBroadcastClientConnected(requestId: requestId)
                                NSLog("✅ Broadcast: viewer connected (requestId=\(requestId))")
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

        // ── Broadcast frame capture bridge ────────────────────────────────────
        // BroadcastFrameCapture.shared uses the AVCaptureSession that the Kotlin
        // camera composable creates, so preview and capture share one pipeline.
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
    ///
    /// After the WebRTC channel opens and the credential is accepted the auth sheet
    /// auto-dismisses and the full-screen streaming viewer is presented. Video frames
    /// arriving on the still-open channel are forwarded via
    /// `LiquidAuthService.messageForwardingHandler` →
    /// `IOSLiquidStreamViewerConnectionManager.notifyMessageReceived`.
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
        
        // ── Streaming viewer transition ────────────────────────────────────────
        // When the data channel opens and the credential is accepted, the auth VC
        // fires onStreamingConnected, then dismisses itself.  We then:
        //   1. Forward all subsequent channel messages to the Kotlin viewer manager
        //   2. Present the KMP Compose LiquidStreamViewerScreen full-screen
        // ── Streaming viewer transition ────────────────────────────────────────
        // Fires AFTER the auth sheet has fully dismissed (in its dismiss completion
        // block).  The service has already been transferred out of the auth VC before
        // dismiss was called, so the WebRTC channel is still alive.
        authVC.onStreamingConnected = { [weak self] connOrigin, connRequestId, connAlgoAddress, service in
            guard let self = self else { return }
            NSLog("🎥 Auth VC dismissed — presenting viewer screen")

            // Own the service so it is not ARC-released.
            // Wire message forwarding: LiquidAuthService → IOSLiquidStreamViewerConnectionManager
            self.activeStreamingService = service
            service.messageForwardingHandler = { message in
                App_iosKt.forwardMessageToActiveViewer(message: message)
            }
            NSLog("🔌 Service owned by AppDelegate — channel alive")

            // Create the KMP Compose viewer screen wrapped in a UIViewController.
            // Auth VC is fully gone now so findTopViewController() returns the root VC
            // and viewerVC lives independently (no cascade-dismiss risk).
            let viewerVC = App_iosKt.LiquidStreamViewerViewController(
                viewerAddress: connAlgoAddress,
                originUrl: connOrigin,
                networkLabel: "TESTNET"
            )
            viewerVC.modalPresentationStyle = .fullScreen

            // Register the minimize/close handler so the Compose "minimize" button
            // can dismiss the UIViewController from the Kotlin side.
            App_iosKt.iosViewerMinimizeHandler = { [weak viewerVC] in
                DispatchQueue.main.async {
                    viewerVC?.dismiss(animated: true)
                }
            }

            let presenter = self.findTopViewController(from: self.window?.rootViewController ?? UIViewController())
            presenter.present(viewerVC, animated: true) {
                NSLog("✅ LiquidStreamViewerScreen presented")
            }
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
