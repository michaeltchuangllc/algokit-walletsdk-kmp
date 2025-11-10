package com.michaeltchuang.walletsdk.ui.settings.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.cancel
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mainnet_warning
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.mainnet_warning_desc
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.node_settings
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ok
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.NodeSettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

private const val TAG = "NodeSettingsScreen"

val networkNodeSettings = MutableStateFlow<AlgorandNetwork?>(null)

@Composable
fun NodeSettingsScreen(navController: NavController) {
    val viewModel: NodeSettingsViewModel = koinViewModel()
    val viewState by viewModel.state.collectAsState()
    var showMainnetWarningDialog by remember { mutableStateOf(false) }
    var pendingNetwork by remember { mutableStateOf<AlgorandNetwork?>(null) }

    // Handle ViewEvents
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect { event ->
            when (event) {
                is NodeSettingsViewModel.ViewEvent.NetworkChanged -> {
                    Log.d(TAG, "Network changed to: ${event.network}")
                    networkNodeSettings.value = event.network
                }

                is NodeSettingsViewModel.ViewEvent.ShowMainnetWarning -> {
                    Log.d(TAG, "Showing mainnet warning for: ${event.network}")
                    pendingNetwork = event.network
                    showMainnetWarningDialog = true
                }

                is NodeSettingsViewModel.ViewEvent.Error -> {
                    Log.e(TAG, "Error: ${event.message}")
                }
            }
        }
    }

    ScreenContent(
        navController = navController,
        viewState = viewState,
        showMainnetWarningDialog = showMainnetWarningDialog,
        pendingNetwork = pendingNetwork,
        onNetworkSelected = { viewModel.onNetworkSelected(it) },
        onConfirmMainnetSelection = {
            pendingNetwork?.let { network ->
                viewModel.confirmMainnetSelection(network)
            }
        },
        onDismissDialog = {
            showMainnetWarningDialog = false
            pendingNetwork = null
        },
    )
}

@Composable
internal fun ScreenContent(
    navController: NavController,
    viewState: NodeSettingsViewModel.ViewState,
    showMainnetWarningDialog: Boolean = false,
    pendingNetwork: AlgorandNetwork? = null,
    onNetworkSelected: (AlgorandNetwork) -> Unit = {},
    onConfirmMainnetSelection: () -> Unit = {},
    onDismissDialog: () -> Unit = {},
) {
    if (showMainnetWarningDialog && pendingNetwork != null) {
        AlertDialog(
            onDismissRequest = onDismissDialog,
            title = {
                Text(
                    text = localizedStringResource(Res.string.mainnet_warning),
                    color = AlgoKitTheme.colors.textMain,
                    style = AlgoKitTheme.typography.body.large.sansMedium,
                )
            },
            text = {
                Text(
                    text = localizedStringResource(Res.string.mainnet_warning_desc),
                    color = AlgoKitTheme.colors.textMain,
                    style = AlgoKitTheme.typography.body.regular.sansMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmMainnetSelection()
                        onDismissDialog()
                    },
                ) {
                    Text(
                        text = localizedStringResource(Res.string.ok),
                        color = AlgoKitTheme.colors.positive,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissDialog,
                ) {
                    Text(
                        text = localizedStringResource(Res.string.cancel),
                        color = AlgoKitTheme.colors.textMain,
                    )
                }
            },
            containerColor = AlgoKitTheme.colors.background,
            titleContentColor = AlgoKitTheme.colors.textMain,
            textContentColor = AlgoKitTheme.colors.textMain,
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color = AlgoKitTheme.colors.background)
                .padding(horizontal = 16.dp),
    ) {
        AlgoKitTopBar(
            title = localizedStringResource(Res.string.node_settings),
        ) {
            navController.popBackStack()
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (val state = viewState) {
            is NodeSettingsViewModel.ViewState.Loading -> {
                // Could add a loading indicator here if needed
            }

            is NodeSettingsViewModel.ViewState.Idle -> {
                // Initial state
            }

            is NodeSettingsViewModel.ViewState.Content -> {
                state.networkOptions.forEach { network ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onNetworkSelected(network) }
                                .padding(vertical = 16.dp),
                    ) {
                        Text(
                            text = network.displayName,
                            color = AlgoKitTheme.colors.textMain,
                            modifier = Modifier.weight(1f),
                            style = AlgoKitTheme.typography.body.regular.sansMedium,
                        )
                        RadioButton(
                            selected = network == state.currentNetwork,
                            onClick = { onNetworkSelected(network) },
                            colors =
                                RadioButtonDefaults.colors(
                                    selectedColor = AlgoKitTheme.colors.positive,
                                    unselectedColor = Color.LightGray,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun NodeSettingsScreenPreview() {
    AlgoKitTheme {
        NodeSettingsScreen(navController = rememberNavController())
    }
}
