package com.michaeltchuang.walletsdk.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.createLiquidAuthConnectionManager

@Composable
actual fun rememberBroadcastConnectionManager(): LiquidAuthConnectionManager = remember { createLiquidAuthConnectionManager(Unit) }
