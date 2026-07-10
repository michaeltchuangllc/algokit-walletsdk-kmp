package com.michaeltchuang.walletsdk.demo.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount.SeedVault
import com.michaeltchuang.walletsdk.demo.ui.viewmodel.BroadcastViewModel
import com.michaeltchuang.walletsdk.demo.ui.widgets.snackbar.SnackbarViewModel
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.LiquidAuthOfferScreen
import com.michaeltchuang.walletsdk.ui.liquidAuth.screens.StreamHostUiMode
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager
import com.michaeltchuang.walletsdk.ui.liquidStream.components.CameraStreamingPreviewController
import com.michaeltchuang.walletsdk.ui.liquidStream.components.createCameraStreamingPreview
import org.koin.compose.viewmodel.koinViewModel

/**
 * Broadcast screen platform dependencies.
 *
 * The connection manager remains UI-owned because its Android implementation requires a Context
 * and must be scoped to the composable lifecycle rather than the ViewModel lifecycle.
 */
data class BroadcastPlatformState(
    val connectionManager: LiquidAuthConnectionManager,
)

@Composable
expect fun rememberBroadcastConnectionManager(): LiquidAuthConnectionManager

@Composable
fun rememberBroadcastPlatformState(): BroadcastPlatformState =
    BroadcastPlatformState(
        connectionManager = rememberBroadcastConnectionManager(),
    )

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
    val viewModel: BroadcastViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val cameraPreviewController = remember { CameraStreamingPreviewController() }
    val cameraPreview =
        remember(platformState.connectionManager, cameraPreviewController) {
            createCameraStreamingPreview(platformState.connectionManager, cameraPreviewController)
        }

    val enablePaidStreaming = state.selectedCreatorAddress != null
    val selectedCreatorAccount =
        state.accounts.firstOrNull { it.address == state.selectedCreatorAddress }
    val paymentCurrencyLabel = if (selectedCreatorAccount is SeedVault) "SOL" else "ALGO"
    val blockChainLabel = if (selectedCreatorAccount is SeedVault) "Solana" else "Algorand"
    val balanceCurrencySymbol = if (selectedCreatorAccount is SeedVault) "S" else "A"

    LiquidAuthOfferScreen(
        origin = "https://liquid-auth-api.pg.nodely.dev/",
        onBackPressed = { navController.popBackStack() },
        cameraPreview = cameraPreview,
        connectionManager = platformState.connectionManager,
        headerContent = {
            if (state.accountsLoaded && state.accounts.isEmpty()) {
                Text(
                    text = "No account exist, Please create account",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = AlgoKitTheme.colors.snackbarError,
                    style = AlgoKitTheme.typography.body.regular.sansMedium,
                )
            } else {
                AccountSelector(
                    accounts = state.accounts,
                    selectedAddress = state.selectedCreatorAddress,
                    onAddressSelected = {
                        viewModel.onEvent(BroadcastViewModel.BroadcastEvent.CreatorAddressSelected(it))
                    },
                )
            }
        },
        creatorAddress = state.selectedCreatorAddress,
        creatorAssetId = state.creatorAssetId,
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
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
