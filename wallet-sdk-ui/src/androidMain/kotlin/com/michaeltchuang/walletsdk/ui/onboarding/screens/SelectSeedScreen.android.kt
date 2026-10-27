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
import com.michaeltchuang.seedvault.sdk.SeedVaultServiceConstants
import com.michaeltchuang.seedvault.sdk.client.SeedVaultServiceClient
import com.michaeltchuang.walletsdk.core.foundation.utils.Chain
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
        SeedVaultServiceConstants.PACKAGE,
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
    Toast
        .makeText(
            context,
            "Seed Vault is only available on supported devices or test builds.",
            Toast.LENGTH_LONG,
        ).show()
}

/**
 * Android actual implementation of SelectSeedScreenPlatform.
 * Automatically requests Seed Vault permission when the screen is shown.
 */
@Composable
actual fun SelectSeedScreenPlatform(
    navController: NavController,
    accountType: Chain,
) {
    val context = LocalContext.current
    val viewModel: SelectSeedViewModel = koinViewModel()
    val isAlgorand = accountType == Chain.ALGORAND

    val activity = remember(context) { context.findActivity() }
    val isSeedVaultSupportedDevice = remember(context) { isSupportedSeedVaultDevice(context) }

    val requiredPermission =
        if (isAlgorand) {
            SeedVaultServiceConstants.PERMISSION_ACCESS_SEED_VAULT
        } else {
            WalletContractV1.PERMISSION_ACCESS_SEED_VAULT
        }

    var permissionGranted by remember(isSeedVaultSupportedDevice, requiredPermission) {
        mutableStateOf(
            context.checkSelfPermission(requiredPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    fun loadSeeds() {
        viewModel.loadSeeds(accountType)
    }

    // Permission launcher for runtime permission
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            permissionGranted = isGranted
        }

    // Activity result launcher for seed authorization
    val authorizeSeedLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val resultCode = result.resultCode
            val data = result.data

            if (isAlgorand) {
                Log.d("SelectSeedScreen", "Algorand seed authorize result code=$resultCode")
                loadSeeds()
            } else {
                try {
                    val authToken = Wallet.onAuthorizeSeedResult(resultCode, data)
                    Log.d("SelectSeedScreen", "Solana seed authorized successfully, authToken=$authToken")
                    loadSeeds()
                } catch (e: Wallet.ActionFailedException) {
                    Log.e("SelectSeedScreen", "Solana seed authorization failed: ${e.message}")
                }
            }
        }

    // Check permission on first launch and request if needed, or load seeds if granted
    LaunchedEffect(accountType, permissionGranted) {
        if (!permissionGranted) {
            permissionLauncher.launch(requiredPermission)
        } else {
            loadSeeds()
        }
    }

    val viewState by viewModel.state.collectAsState()

    LaunchedEffect(accountType) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is SelectSeedViewModel.ViewEvent.SeedSelected -> {
                    navController.navigate(
                        "${AlgoKitScreens.IMPORT_SEED_VAULT_ACCOUNTS_SCREEN.name}?accountType=${accountType.name}&selectedSeedIds=${event.seed.id}",
                    )
                }
                is SelectSeedViewModel.ViewEvent.AllSeedsConnected -> {
                    // Handle all seeds connected
                }
                is SelectSeedViewModel.ViewEvent.Error -> {
                    // Show error snackbar
                }
                is SelectSeedViewModel.ViewEvent.SeedAuthorized -> {
                    loadSeeds()
                }
                is SelectSeedViewModel.ViewEvent.RequestAuthorizeSeed -> {
                    if (isAlgorand) {
                        try {
                            val client = SeedVaultServiceClient(context)
                            val intent = client.authorizeAppIntent()
                            authorizeSeedLauncher.launch(intent)
                        } catch (e: Exception) {
                            Log.e("SelectSeedScreen", "Cannot authorize Algorand seed", e)
                        }
                    } else {
                        if (!isSeedVaultSupportedDevice) {
                            Log.w("SelectSeedScreen", "Solana seed authorization requested on unsupported device")
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
                        }
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
            if (!permissionGranted) {
                permissionLauncher.launch(requiredPermission)
            } else {
                loadSeeds()
            }
        },
        onRequestPermission = {
            permissionLauncher.launch(requiredPermission)
        },
        onAuthorizeSeed = {
            viewModel.authorizeNewSeed()
        },
    )
}
