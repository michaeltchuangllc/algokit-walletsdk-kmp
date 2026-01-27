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
        NSLog("   Origin: ${authMessage.origin}")
        NSLog("   RequestID: ${authMessage.requestId}")
        NSLog("   AlgoAddress: $algoAddress")
        
        // Call the registered handler
        val handler = iosLiquidAuthHandler
        if (handler != null) {
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
