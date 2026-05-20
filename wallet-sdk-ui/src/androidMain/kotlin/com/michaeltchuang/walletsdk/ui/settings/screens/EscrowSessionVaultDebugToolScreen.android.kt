package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.ui.test.EscrowSessionVaultDebugToolScreen

@Composable
actual fun EscrowSessionVaultDebugToolScreen(navController: Any) {
    EscrowSessionVaultDebugToolScreen(navController = navController as NavController)
}
