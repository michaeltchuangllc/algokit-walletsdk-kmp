package com.michaeltchuang.walletsdk.ui.initializeSdk

import android.content.Context
import com.michaeltchuang.walletsdk.core.network.domain.AndroidContextHolder
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.KoinApplication
import org.koin.core.logger.Level

/**
 * Android-specific Koin configuration.
 * Sets up Android context and optional logging.
 */
internal actual fun KoinApplication.platformConfiguration(
    context: Any,
    enableLogging: Boolean
) {
    val androidContext = (context as Context).applicationContext
    AndroidContextHolder.applicationContext = androidContext
    androidContext(androidContext)
    if (enableLogging) {
        androidLogger(Level.DEBUG)
    }
}
