package com.michaeltchuang.walletsdk.demo.ui.screens

import algokit_walletsdk_kmp.composedemoapp.generated.resources.Res
import algokit_walletsdk_kmp.composedemoapp.generated.resources.nav_broadcast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackbarViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.LiquidAuthOfferScreen
import org.jetbrains.compose.resources.stringResource

/**
 * Broadcast Screen
 *
 * This screen generates a QR code that dApps can scan to initiate
 * a Liquid Auth connection with the wallet. Once connected,
 * it can stream video back to the client.
 * 
 * Uses the self-contained LiquidAuthOfferScreen from wallet-sdk-ui
 * which internally manages WebRTC SignalService binding (Android).
 *
 * @param navController The navigation controller
 * @param snackbarViewModel The snackbar view model (unused but kept for API compatibility)
 * @param tag The tag for this screen (unused)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
expect fun BroadcastScreen(
    navController: NavController,
    snackbarViewModel: SnackbarViewModel,
    tag: String,
)
