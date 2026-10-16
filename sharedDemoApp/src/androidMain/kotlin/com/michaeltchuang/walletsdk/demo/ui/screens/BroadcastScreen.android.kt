package com.michaeltchuang.walletsdk.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.createLiquidAuthConnectionManager

@Composable
actual fun rememberBroadcastConnectionManager(): LiquidAuthConnectionManager {
    val context = LocalContext.current
    return remember(context) { createLiquidAuthConnectionManager(context) }
}
