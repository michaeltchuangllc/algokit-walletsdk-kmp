package com.michaeltchuang.walletsdk.ui.settings.utils.debug

import androidx.compose.runtime.Composable
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager

@Composable
expect fun rememberLiveDebugConnectionManager(): LiquidAuthConnectionManager

@Composable
expect fun LiveDebugPermissionGate(content: @Composable () -> Unit)
