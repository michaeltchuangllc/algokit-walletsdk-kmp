package com.michaeltchuang.walletsdk.demo.ui.screens

import algokit_walletsdk_kmp.composedemoapp.generated.resources.Res
import algokit_walletsdk_kmp.composedemoapp.generated.resources.nav_broadcast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackbarViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.LiquidAuthOfferScreen
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.createLiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.createCameraStreamingPreview

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
    val connectionManager = remember(context) {
        createLiquidAuthConnectionManager(context)
    }

    // The LiquidAuthOfferScreen now receives the connection manager
    // which handles SignalService binding and peer detection
    LiquidAuthOfferScreen(
        origin = "https://liquid-auth-api.pg.nodely.dev/",
        onBackPressed = { navController.popBackStack() },
        cameraPreview = createCameraStreamingPreview(connectionManager),
        connectionManager = connectionManager  // Enables WebRTC connection!
    )
}
