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
        // Configure app group for database sharing BEFORE Compose initializes Koin
        configureAppGroup()
        
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
}
