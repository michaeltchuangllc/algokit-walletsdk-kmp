package com.michaeltchuang.walletsdk.ui.liquidAuth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AuthMessage
import platform.Foundation.NSLog

/**
 * Global handler for iOS Liquid Auth.
 * This should be set by the iOS app during initialization.
 */
var iosLiquidAuthHandler: ((origin: String, requestId: String, algoAddress: String) -> Unit)? = null

@Composable
actual fun connect(
    authMessage: AuthMessage,
    algoAddress: String,
) {
    LaunchedEffect(authMessage, algoAddress) {
        NSLog("🔗 iOS Liquid Auth connect() called")
        NSLog("   Origin: '${authMessage.origin}'")
        NSLog("   RequestID: '${authMessage.requestId}'")
        NSLog("   RequestID length: ${authMessage.requestId.length}")
        NSLog("   RequestID isEmpty: ${authMessage.requestId.isEmpty()}")
        NSLog("   AlgoAddress: '$algoAddress'")
        
        // Verify requestId is not empty
        if (authMessage.requestId.isEmpty()) {
            NSLog("❌ ERROR: RequestID is empty!")
            NSLog("   This usually means the URL wasn't parsed correctly")
            NSLog("   Expected URL format: liquid://host/?requestId=...")
            return@LaunchedEffect
        }

        // Call the registered handler
        val handler = iosLiquidAuthHandler
        if (handler != null) {
            NSLog("✅ Calling iOS handler with:")
            NSLog("   - origin: '${authMessage.origin}'")
            NSLog("   - requestId: '${authMessage.requestId}'")
            NSLog("   - algoAddress: '$algoAddress'")
            handler(authMessage.origin, authMessage.requestId, algoAddress)
        } else {
            NSLog("⚠️ No iOS Liquid Auth handler registered!")
            NSLog("📝 Call setIosLiquidAuthHandler() from Swift app initialization")
        }
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
