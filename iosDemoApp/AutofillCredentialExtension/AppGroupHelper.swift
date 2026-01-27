import Foundation
import composeDemoApp

/// Helper to configure the app group for database sharing
struct AppGroupHelper {
    /// Your app group identifier (configure this in Xcode capabilities)
    static let appGroupIdentifier = "group.com.michaeltchuang.walletsdk.demo"
    
    /// Get the shared container URL for the app group
    static func getSharedContainerURL() -> URL? {
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier)
    }
    
    /// Configure the app group directory before initializing Koin
    static func configureAppGroup() {
        if let containerURL = getSharedContainerURL() {
            print("✅ App Group configured: \(containerURL.path)")
            App_iosKt.setAppGroupDirectory(directory: containerURL.path)
        } else {
            print("⚠️ Warning: App Group not configured. Using default directory.")
            print("⚠️ Data will NOT be shared between app and extensions!")
        }
    }
}
