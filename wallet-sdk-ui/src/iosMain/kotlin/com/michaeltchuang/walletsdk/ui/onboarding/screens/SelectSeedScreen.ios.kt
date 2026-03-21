package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val showUnsupportedAlert = remember { mutableStateOf(false) }

    // Seed Vault is not available on iOS, so we always show permission denied state
    SelectSeedScreen(
        navController = navController,
        viewModel = viewModel,
        permissionGranted = false,
        onRequestPermission = {
            showUnsupportedAlert.value = true
        },
    )

    if (showUnsupportedAlert.value) {
        AlertDialog(
            onDismissRequest = { showUnsupportedAlert.value = false },
            title = { Text("Not supported on this device") },
            text = { Text("Seed Vault is only available on Solana Saga/Seeker devices or test builds.") },
            confirmButton = {
                TextButton(onClick = { showUnsupportedAlert.value = false }) {
                    Text("OK")
                }
            },
        )
    }
}
