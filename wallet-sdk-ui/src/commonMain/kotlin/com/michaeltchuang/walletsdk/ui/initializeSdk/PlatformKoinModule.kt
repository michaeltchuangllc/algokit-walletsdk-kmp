package com.michaeltchuang.walletsdk.ui.initializeSdk

import org.koin.core.KoinApplication

/**
 * Platform-specific configuration for Koin initialization.
 *
 * @param context Platform-specific context (e.g., Android Context)
 * @param enableLogging Enable platform-specific logging
 */
internal expect fun KoinApplication.platformConfiguration(
    context: Any,
    enableLogging: Boolean,
)
