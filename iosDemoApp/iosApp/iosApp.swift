import UIKit
import composeDemoApp
import Foundation

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

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
    
    /// Present the Liquid Auth flow
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
        
        // Use the static present method from LiquidAuthViewController
        LiquidAuthViewController.present(
            from: topViewController,
            origin: origin,
            requestId: requestId,
            algoAddress: algoAddress,
            onCompletion: {
                NSLog("✅ Liquid Auth completed")
            }
        )
        
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
