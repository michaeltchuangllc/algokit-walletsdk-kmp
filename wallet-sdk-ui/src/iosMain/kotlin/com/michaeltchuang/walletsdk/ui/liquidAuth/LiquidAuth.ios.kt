package com.michaeltchuang.walletsdk.ui.liquidAuth

import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage
import platform.Foundation.NSLog

/**
 * Global handler for iOS Liquid Auth.
 * This should be set by the iOS app during initialization.
 */
var iosLiquidAuthHandler: ((origin: String, requestId: String, accountAddress: String) -> Unit)? =
    null

actual fun connectLiquidAuth(
    authMessage: AuthMessage,
    accountAddress: String,
) {
    NSLog("🔗 iOS Liquid Auth connect() called")
    NSLog("   Origin: '${authMessage.origin}'")
    NSLog("   RequestID: '${authMessage.requestId}'")
    NSLog("   RequestID length: ${authMessage.requestId.length}")
    NSLog("   RequestID isEmpty: ${authMessage.requestId.isEmpty()}")
    NSLog("   AlgoAddress: '$accountAddress'")

    // Verify requestId is not empty
    if (authMessage.requestId.isEmpty()) {
        NSLog("❌ ERROR: RequestID is empty!")
        NSLog("   This usually means the URL wasn't parsed correctly")
        NSLog("   Expected URL format: liquid://host/?requestId=...")
    }

    // Call the registered handler
    val handler = iosLiquidAuthHandler
    if (handler != null) {
        NSLog("✅ Calling iOS handler with:")
        NSLog("   - origin: '${authMessage.origin}'")
        NSLog("   - requestId: '${authMessage.requestId}'")
        NSLog("   - address: '$accountAddress'")
        handler(authMessage.origin, authMessage.requestId, accountAddress)
    } else {
        NSLog("⚠️ No iOS Liquid Auth handler registered!")
        NSLog("📝 Call setIosLiquidAuthHandler() from Swift app initialization")
    }
}

/**
 * Set the handler for iOS Liquid Auth.
 * Call this from Swift during app initialization.
 */
fun setIosLiquidAuthHandler(handler: (String, String, String) -> Unit) {
    iosLiquidAuthHandler = handler
    NSLog("✅ iOS Liquid Auth handler registered")
}
