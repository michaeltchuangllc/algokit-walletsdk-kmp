package com.michaeltchuang.walletsdk.ui.onboarding.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.SelectSeedViewModel
import com.solanamobile.seedvault.Wallet
import com.solanamobile.seedvault.WalletContractV1
import org.koin.compose.viewmodel.koinViewModel

/**
 * Find the Activity from a given Context by traversing up the ContextWrapper chain.
 */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private val seedVaultTestPackages =
    setOf(
        "com.solanamobile.seedvaultimpl",
    )

private fun isSeedVaultTestPackageInstalled(context: Context): Boolean =
    seedVaultTestPackages.any { packageName ->
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
    }

private fun isSupportedSeedVaultDevice(context: Context): Boolean {
    val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
    val brand = Build.BRAND.orEmpty().lowercase()
    val model = Build.MODEL.orEmpty().lowercase()

    val isSolanaManufacturer = manufacturer.contains("solana") || brand.contains("solana")
    val isSagaOrSeekerModel = model.contains("saga") || model.contains("seeker")

    return isSolanaManufacturer || isSagaOrSeekerModel || isSeedVaultTestPackageInstalled(context)
}

private fun showSeedVaultUnsupportedAlert(context: Context) {
    Toast.makeText(
        context,
        "Seed Vault is only available on Solana Saga/Seeker devices or test builds.",
        Toast.LENGTH_LONG,
    ).show()
}

/**
 * Android actual implementation of SelectSeedScreenPlatform.
 * Automatically requests Seed Vault permission when the screen is shown.
 */
@Composable
actual fun SelectSeedScreenPlatform(navController: NavController) {
    val context = LocalContext.current
    val viewModel: SelectSeedViewModel = koinViewModel()

    // Find the Activity from context
    val activity =
        remember(context) {
            context.findActivity()
        }

    val isSeedVaultSupportedDevice = remember(context) { isSupportedSeedVaultDevice(context) }

    var permissionGranted by remember(isSeedVaultSupportedDevice) {
        mutableStateOf(
            isSeedVaultSupportedDevice &&
                context.checkSelfPermission(WalletContractV1.PERMISSION_ACCESS_SEED_VAULT) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    // Permission launcher for runtime permission
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            permissionGranted = isSeedVaultSupportedDevice && isGranted
            if (permissionGranted) {
                viewModel.loadSeeds()
            }
        }

    // Activity result launcher for seed authorization
    val authorizeSeedLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val resultCode = result.resultCode
            val data = result.data

            try {
                val authToken = Wallet.onAuthorizeSeedResult(resultCode, data)
                Log.d("SelectSeedScreen", "Seed authorized successfully, authToken=$authToken")
                // Reload seeds to show the newly authorized seed
                viewModel.loadSeeds()
            } catch (e: Wallet.ActionFailedException) {
                Log.e("SelectSeedScreen", "Seed authorization failed: ${e.message}")
                // Don't reload - user might want to try again
            }
        }

    // Check permission on first launch and request if needed
    LaunchedEffect(Unit) {
        if (isSeedVaultSupportedDevice && !permissionGranted) {
            permissionLauncher.launch(WalletContractV1.PERMISSION_ACCESS_SEED_VAULT)
        }
    }

    val viewState by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is SelectSeedViewModel.ViewEvent.SeedSelected -> {
                    navController.navigate(
                        "${AlgoKitScreens.IMPORT_SEED_VAULT_ACCOUNTS_SCREEN.name}?selectedSeedIds=${event.seed.id}",
                    )
                }
                is SelectSeedViewModel.ViewEvent.AllSeedsConnected -> {
                    // Handle all seeds connected
                }
                is SelectSeedViewModel.ViewEvent.Error -> {
                    // Show error snackbar
                }
                is SelectSeedViewModel.ViewEvent.SeedAuthorized -> {
                    // Seed was authorized, reload seeds
                    viewModel.loadSeeds()
                }
                is SelectSeedViewModel.ViewEvent.RequestAuthorizeSeed -> {
                    // Launch the seed authorization intent
                    if (!isSeedVaultSupportedDevice) {
                        Log.w("SelectSeedScreen", "Seed authorization requested on unsupported device")
                        return@collect
                    }

                    val currentActivity = activity
                    if (currentActivity != null) {
                        val intent =
                            Wallet.authorizeSeed(
                                currentActivity,
                                WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION,
                            )
                        authorizeSeedLauncher.launch(intent)
                    } else {
                        Log.e("SelectSeedScreen", "Cannot authorize seed - no Activity found")
                        // Could emit an error event here
                    }
                }
            }
        }
    }

    SelectSeedScreenContent(
        navController = navController,
        viewState = viewState,
        permissionGranted = permissionGranted,
        onSeedSelected = { viewModel.onSeedSelected(it) },
        onAllSeedsConnected = { viewModel.onAllSeedsConnected() },
        onRetry = {
            if (!isSeedVaultSupportedDevice) {
                showSeedVaultUnsupportedAlert(context)
                return@SelectSeedScreenContent
            }

            if (!permissionGranted) {
                permissionLauncher.launch(WalletContractV1.PERMISSION_ACCESS_SEED_VAULT)
            } else {
                viewModel.loadSeeds()
            }
        },
        onRequestPermission = {
            if (isSeedVaultSupportedDevice) {
                permissionLauncher.launch(WalletContractV1.PERMISSION_ACCESS_SEED_VAULT)
            } else {
                showSeedVaultUnsupportedAlert(context)
            }
        },
        onAuthorizeSeed = {
            // This triggers the event that launches the authorization intent
            viewModel.authorizeNewSeed()
        },
    )
}
