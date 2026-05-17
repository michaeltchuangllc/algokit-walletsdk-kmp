package com.michaeltchuang.walletsdk.demo.ui.screens

import androidx.compose.runtime.Composable
import com.michaeltchuang.walletsdk.ui.test.PaymentTestScreen

/**
 * Android implementation of DiscoverScreen.
 *
 * Hosts the manual Payment Test UI so developers can exercise Session Vault
 * operations (deposit, balance fetch, settlement) without a live stream.
 */
@Composable
actual fun PaymentTestScreen() {
    PaymentTestScreen()
}
