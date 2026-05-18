package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.ui.test.PaymentTestScreen

@Composable
actual fun PaymentTestScreen(navController: Any) {
    PaymentTestScreen(navController = navController as NavController)
}
