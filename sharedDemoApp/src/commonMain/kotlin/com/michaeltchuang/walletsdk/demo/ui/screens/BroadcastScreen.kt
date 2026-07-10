package com.michaeltchuang.walletsdk.demo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.SeedVault
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackbarViewModel
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.LiquidAuthOfferScreen
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.StreamHostUiMode
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidStream.components.CameraStreamingPreviewController
import com.michaeltchuang.walletsdk.ui.liquidStream.components.createCameraStreamingPreview

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
data class BroadcastPlatformState(
    val accountsLoaded: Boolean,
    val accounts: List<LocalAccount>,
    val connectionManager: LiquidAuthConnectionManager,
)

@Composable
expect fun rememberBroadcastPlatformState(): BroadcastPlatformState

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun BroadcastScreen(
    navController: NavController,
    snackbarViewModel: SnackbarViewModel,
    tag: String,
    streamHostUiModeState: MutableState<StreamHostUiMode>,
    miniPlayerCameraPreviewState: MutableState<(@Composable () -> Unit)?>,
    miniPlayerOnCloseActionState: MutableState<(() -> Unit)?>,
) {
    val platformState = rememberBroadcastPlatformState()

    val cameraPreviewController = remember { CameraStreamingPreviewController() }
    val cameraPreview =
        remember(platformState.connectionManager, cameraPreviewController) {
            createCameraStreamingPreview(platformState.connectionManager, cameraPreviewController)
        }

    var selectedCreatorAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(platformState.accounts) {
        if (selectedCreatorAddress == null || platformState.accounts.none { it.address == selectedCreatorAddress }) {
            selectedCreatorAddress = platformState.accounts.firstOrNull()?.address
        }
    }

    val enablePaidStreaming = selectedCreatorAddress != null
    val selectedCreatorAccount =
        platformState.accounts.firstOrNull { it.address == selectedCreatorAddress }
    val paymentCurrencyLabel = if (selectedCreatorAccount is SeedVault) "SOL" else "ALGO"
    val blockChainLabel = if (selectedCreatorAccount is SeedVault) "Solana" else "Algorand"
    val balanceCurrencySymbol = if (selectedCreatorAccount is SeedVault) "S" else "A"

    LiquidAuthOfferScreen(
        origin = "https://liquid-auth-api.pg.nodely.dev/",
        onBackPressed = { navController.popBackStack() },
        cameraPreview = cameraPreview,
        connectionManager = platformState.connectionManager,
        headerContent = {
            if (platformState.accountsLoaded && platformState.accounts.isEmpty()) {
                Text(
                    text = "No account exist, Please create account",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = AlgoKitTheme.colors.snackbarError,
                    style = AlgoKitTheme.typography.body.regular.sansMedium,
                )
            } else {
                AccountSelector(
                    accounts = platformState.accounts,
                    selectedAddress = selectedCreatorAddress,
                    onAddressSelected = { selectedCreatorAddress = it },
                )
            }
        },
        creatorAddress = selectedCreatorAddress,
        enablePaidStreaming = enablePaidStreaming,
        paymentCurrencyLabel = paymentCurrencyLabel,
        blockChainLabel = blockChainLabel,
        balanceCurrencySymbol = balanceCurrencySymbol,
        onMinimise = {},
        streamHostUiModeState = streamHostUiModeState,
        miniPlayerCameraPreviewState = miniPlayerCameraPreviewState,
        miniPlayerOnCloseActionState = miniPlayerOnCloseActionState,
        cameraPreviewController = cameraPreviewController,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSelector(
    accounts: List<LocalAccount>,
    selectedAddress: String?,
    onAddressSelected: (String) -> Unit,
) {
    if (accounts.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val selectedDisplay = selectedAddress ?: accounts.first().address

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedDisplay,
            onValueChange = {},
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            readOnly = true,
            singleLine = true,
            label = { Text("Select Creator Address") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle = MaterialTheme.typography.bodySmall,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AlgoKitTheme.colors.textMain,
                    unfocusedTextColor = AlgoKitTheme.colors.textMain,
                    focusedLabelColor = AlgoKitTheme.colors.textMain,
                    unfocusedLabelColor = AlgoKitTheme.colors.textGray,
                    focusedTrailingIconColor = AlgoKitTheme.colors.textMain,
                    unfocusedTrailingIconColor = AlgoKitTheme.colors.textGray,
                ),
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AlgoKitTheme.colors.layerGray,
        ) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    colors =
                        MenuDefaults.itemColors(
                            textColor = AlgoKitTheme.colors.textMain,
                        ),
                    text = {
                        Text(
                            text = account.address,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = AlgoKitTheme.typography.body.regular.sansMedium,
                        )
                    },
                    onClick = {
                        onAddressSelected(account.address)
                        expanded = false
                    },
                )
            }
        }
    }
}
