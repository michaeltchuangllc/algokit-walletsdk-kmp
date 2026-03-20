package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.SelectSeedViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * iOS actual implementation of SelectSeedScreenPlatform.
 * Seed Vault is Android-only, so this shows the regular SelectSeedScreen
 * with permissionGranted = false (will show "Not Available" message).
 */
@Composable
actual fun SelectSeedScreenPlatform(navController: NavController) {
    val viewModel: SelectSeedViewModel = koinViewModel()

    // Seed Vault is not available on iOS, so we always show permission denied state
    SelectSeedScreen(
        navController = navController,
        viewModel = viewModel,
        permissionGranted = false,
        onRequestPermission = {
            // No-op: Seed Vault is Android-only
        },
    )
}
