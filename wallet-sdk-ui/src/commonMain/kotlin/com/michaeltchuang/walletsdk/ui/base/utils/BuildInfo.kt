package com.michaeltchuang.walletsdk.ui.base.utils

/**
 * Returns true if the app is running in debug mode.
 * This is determined at runtime and works correctly for both Android and iOS.
 */
expect fun isDebugBuild(): Boolean
