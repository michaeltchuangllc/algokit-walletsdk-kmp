package com.michaeltchuang.walletsdk.ui.qrscanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

@Composable
actual fun launchIntentWithUri(uri: String) {
    LaunchedEffect(uri) {
        val url = NSURL.URLWithString(uri)
        if (url != null) {
            val application = UIApplication.sharedApplication

            // Check if the URL can be opened
            if (application.canOpenURL(url)) {
                // Use modern openURL:options:completionHandler: API
                application.openURL(
                    url = url,
                    options = emptyMap<Any?, Any>(),
                    completionHandler = { success ->
                        if (success) {
                            println("✅ iOS: Successfully opened URL: $uri")
                        } else {
                            println("❌ iOS: Failed to open URL: $uri")
                        }
                    }
                )
            } else {
                println("⚠️ iOS: Cannot open URL (app needs to register URL scheme in Info.plist): $uri")
                println("   For FIDO deeplinks, add 'fido' to LSApplicationQueriesSchemes in Info.plist")
            }
        } else {
            println("❌ iOS: Invalid URL format: $uri")
        }
    }
}
