package com.michaeltchuang.walletsdk.ui.base.utils

import com.michaeltchuang.walletsdk.ui.BuildInfo

/**
 * Android implementation: checks if debuggable flag is set in the app.
 */
actual fun isDebugBuild(): Boolean {
    // Use the generated BuildInfo.DEBUG for Android
    return BuildInfo.DEBUG
}
