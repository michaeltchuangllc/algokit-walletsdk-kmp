package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.runtime.Composable

/**
 * No-op on iOS. iOS Liquid Auth uses the native [LiquidAuthViewController]
 * via the [com.michaeltchuang.walletsdk.ui.liquidAuth.iosLiquidAuthHandler] registered in [LiquidAuth.ios.kt].
 */
@Composable
actual fun AnswerScreenOverlay() {
    // Intentionally empty — iOS uses the native handler path.
}
