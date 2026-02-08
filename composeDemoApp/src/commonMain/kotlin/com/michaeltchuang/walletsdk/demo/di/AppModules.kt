package com.michaeltchuang.walletsdk.demo.di

/**
 * Get platform-specific context for Koin initialization.
 * - Android: Returns Application Context
 * - iOS: Returns null (not needed)
 */
expect fun getPlatformContext(): Any?
