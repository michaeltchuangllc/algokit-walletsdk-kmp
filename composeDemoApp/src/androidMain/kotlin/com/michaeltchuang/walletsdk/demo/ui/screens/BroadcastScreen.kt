package com.michaeltchuang.walletsdk.demo.ui.screens

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetLocalAccountsUseCase
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackbarViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.createCameraStreamingPreview
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.LiquidAuthOfferScreen
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.createLiquidAuthConnectionManager
import org.koin.compose.koinInject

/**
 * Android-specific Broadcast Screen
 *
 * Creates the LiquidAuthConnectionManager with Android Context
 * and passes it to LiquidAuthOfferScreen for WebRTC SignalService binding.
 *
 * This enables the QR code screen to detect when a peer connects
 * and transition to the streaming UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun BroadcastScreen(
    navController: NavController,
    snackbarViewModel: SnackbarViewModel,
    tag: String,
) {
    // Get Android Context for creating the connection manager
    val context = LocalContext.current

    // Create Android-specific connection manager that binds to SignalService
    val connectionManager =
        remember(context) {
            createLiquidAuthConnectionManager(context)
        }

    // Get accounts for MPP creator address (using produceState for suspend function)
    val getLocalAccountsUseCase = koinInject<GetLocalAccountsUseCase>()
    val accounts by produceState<List<LocalAccount>>(initialValue = emptyList()) {
        value = getLocalAccountsUseCase()
    }

    // Use first account address for MPP payments (or null if no accounts)
    val creatorAddress = accounts.firstOrNull()?.address

    // Log for debugging
    android.util.Log.d("BroadcastScreen", "Accounts loaded: ${accounts.size}, creatorAddress=$creatorAddress")

    // Only enable paid streaming when we have a valid creator address
    val enablePaidStreaming = creatorAddress != null

    // Don't show the screen until we know if there are accounts or not
    // This ensures enablePaidStreaming is stable on first composition
    if (accounts.isEmpty()) {
        // Still loading accounts - show loading or proceed with null address
        // For now, proceed but paid streaming will be disabled
        android.util.Log.d("BroadcastScreen", "No accounts yet, proceeding with enablePaidStreaming=false")
    } else {
        android.util.Log.d("BroadcastScreen", "Accounts loaded, enablePaidStreaming=$enablePaidStreaming")
    }

    // The LiquidAuthOfferScreen now receives the connection manager
    // which handles SignalService binding and peer detection
    LiquidAuthOfferScreen(
        origin = "https://michaeltchuang.ngrok.dev/", // "https://liquid-auth-api.pg.nodely.dev/",
        onBackPressed = { navController.popBackStack() },
        cameraPreview = createCameraStreamingPreview(connectionManager),
        connectionManager = connectionManager, // Enables WebRTC connection!
        creatorAddress = creatorAddress, // For MPP paid streaming
        enablePaidStreaming = creatorAddress != null, // Enable if we have an account
    )
}
