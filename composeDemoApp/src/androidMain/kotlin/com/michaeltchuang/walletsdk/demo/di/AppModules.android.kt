package com.michaeltchuang.walletsdk.demo.di

import com.michaeltchuang.walletsdk.demo.AndroidApp

/**
 * Returns the Android application context for Koin initialization.
 */
actual fun getPlatformContext(): Any? {
    return AndroidApp.instance.applicationContext
}
