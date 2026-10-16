package com.michaeltchuang.walletsdk.ui.settings.utils.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.createLiquidAuthConnectionManager

@Composable
actual fun rememberLiveDebugConnectionManager(): LiquidAuthConnectionManager = remember { createLiquidAuthConnectionManager(Unit) }

@Composable
actual fun LiveDebugPermissionGate(content: @Composable () -> Unit) {
    // Native capture owns camera and microphone authorization on iOS.
    content()
}
