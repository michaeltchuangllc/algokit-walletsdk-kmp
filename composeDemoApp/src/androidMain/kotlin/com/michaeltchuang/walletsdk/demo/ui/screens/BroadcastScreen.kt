package com.michaeltchuang.walletsdk.demo.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.SeedVault
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetLocalAccountsUseCase
import com.michaeltchuang.walletsdk.core.utils.AppId
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackbarViewModel
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.CameraStreamingPreviewController
import com.michaeltchuang.walletsdk.ui.liquidAuth.components.createCameraStreamingPreview
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.LiquidAuthOfferScreen
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.StreamHostUiMode
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.createLiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.getSupportedLocalAccountsByAppId
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
    streamHostUiModeState: MutableState<StreamHostUiMode>,
    miniPlayerCameraPreviewState: MutableState<(@Composable () -> Unit)?>,
    miniPlayerOnCloseActionState: MutableState<(() -> Unit)?>,
) {
    // Get Android Context for creating the connection manager
    val context = LocalContext.current

    // Create Android-specific connection manager that binds to SignalService
    val connectionManager =
        remember(context) {
            createLiquidAuthConnectionManager(context)
        }
    val cameraPreviewController = remember { CameraStreamingPreviewController() }
    val cameraPreview =
        remember(connectionManager, cameraPreviewController) { createCameraStreamingPreview(connectionManager, cameraPreviewController) }

    // Get accounts for X402 creator address (using produceState for suspend function)
    val getLocalAccountsUseCase = koinInject<GetLocalAccountsUseCase>()
    val accountResult by produceState<Pair<Boolean, List<LocalAccount>>>(initialValue = false to emptyList()) {
        value = true to
            getSupportedLocalAccountsByAppId(
                appId = AppId.LIQUID_AUTH_STREAM.name,
                localAccount = getLocalAccountsUseCase(),
            )
    }
    val accountsLoaded = accountResult.first
    val accounts = accountResult.second

    // Keep user-selected creator address, defaulting to first available account.
    var selectedCreatorAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(accounts) {
        if (selectedCreatorAddress == null || accounts.none { it.address == selectedCreatorAddress }) {
            selectedCreatorAddress = accounts.firstOrNull()?.address
        }
    }

    // Log for debugging
    android.util.Log.d(
        "BroadcastScreen",
        "Accounts loaded: ${accounts.size}, creatorAddress=$selectedCreatorAddress",
    )

    // Only enable paid streaming when we have a valid creator address
    val enablePaidStreaming = selectedCreatorAddress != null

    val selectedCreatorAccount = accounts.firstOrNull { it.address == selectedCreatorAddress }
    val paymentCurrencyLabel = if (selectedCreatorAccount is SeedVault) "SOL" else "ALGO"
    val blockChainLabel = if (selectedCreatorAccount is SeedVault) "Solana" else "Algorand"
    val balanceCurrencySymbol = if (selectedCreatorAccount is SeedVault) "S" else "A"

    // Don't show the screen until we know if there are accounts or not
    // This ensures enablePaidStreaming is stable on first composition
    if (accounts.isEmpty()) {
        // Still loading accounts - show loading or proceed with null address
        // For now, proceed but paid streaming will be disabled
        android.util.Log.d(
            "BroadcastScreen",
            "No accounts yet, proceeding with enablePaidStreaming=false",
        )
    } else {
        android.util.Log.d(
            "BroadcastScreen",
            "Accounts loaded, enablePaidStreaming=$enablePaidStreaming",
        )
    }

    // The LiquidAuthOfferScreen now receives the connection manager
    // which handles SignalService binding and peer detection
    LiquidAuthOfferScreen(
        origin = "https://liquid-auth-api.pg.nodely.dev/",
        onBackPressed = { navController.popBackStack() },
        cameraPreview = cameraPreview,
        connectionManager = connectionManager, // Enables WebRTC connection!
        headerContent = {
            if (accountsLoaded && accounts.isEmpty()) {
                Text(
                    text = "No account exist, Please create account",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = AlgoKitTheme.colors.snackbarError,
                    style = AlgoKitTheme.typography.body.regular.sansMedium,
                )
            } else {
                AccountSelector(
                    accounts = accounts,
                    selectedAddress = selectedCreatorAddress,
                    onAddressSelected = { selectedCreatorAddress = it },
                )
            }
        },
        creatorAddress = selectedCreatorAddress, // For X402 paid streaming
        enablePaidStreaming = enablePaidStreaming, // Enable if we have an account
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
                    .padding(horizontal = 16.dp),
            readOnly = true,
            singleLine = true,
            label = { Text("Select Creator Address") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall,
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
