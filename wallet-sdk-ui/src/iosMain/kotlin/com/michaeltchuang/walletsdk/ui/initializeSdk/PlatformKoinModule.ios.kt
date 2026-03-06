package com.michaeltchuang.walletsdk.ui.initializeSdk

import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import org.koin.core.KoinApplication

/**
 * iOS-specific Koin configuration.
 * Sets up iOS logging via Napier.
 */
internal actual fun KoinApplication.platformConfiguration(
    context: Any,
    enableLogging: Boolean,
) {
    // iOS doesn't require additional context configuration

    // Initialize Napier for logging
    Napier.base(DebugAntilog())
}
