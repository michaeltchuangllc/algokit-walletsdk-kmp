package com.michaeltchuang.walletsdk.ui.initializeSdk

import org.koin.core.KoinApplication

/**
 * iOS-specific Koin configuration.
 * Currently, iOS doesn't require additional configuration.
 */
internal actual fun KoinApplication.platformConfiguration(
    context: Any,
    enableLogging: Boolean
) {
    // iOS doesn't require additional context configuration
    // Logging can be added here if needed in the future
}