package com.michaeltchuang.walletsdk.demo.di

/**
 * Returns null for iOS as it doesn't require a context for Koin initialization.
 */
actual fun getPlatformContext(): Any? = null
