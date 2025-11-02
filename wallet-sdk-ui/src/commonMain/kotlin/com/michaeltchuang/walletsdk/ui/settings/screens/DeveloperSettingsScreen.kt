package com.michaeltchuang.walletsdk.ui.settings.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_legacy_algo25_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_legacy_hd_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.developer_settings
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_node
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.node_settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.AlgoKitTopBar
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.settings.components.SettingsItem
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.DeveloperSettingsViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

private const val TAG = "DeveloperSettingsScreen"

@Composable
fun DeveloperSettingsScreen(
    navController: NavController,
    onClick: (message: String) -> Unit,
) {
    val viewModel: DeveloperSettingsViewModel = koinViewModel()
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect {
            when (it) {
                is DeveloperSettingsViewModel.ViewEvent.AccountCreated -> {
                    navController.navigate(AlgoKitScreens.CREATE_ACCOUNT_NAME.name)
                    Log.d(TAG, it.accountCreation.address)
                }

                is DeveloperSettingsViewModel.ViewEvent.Error -> {
                    Log.d(TAG, it.message)
                }
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color = AlgoKitTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        AlgoKitTopBar(
            title = localizedStringResource(Res.string.developer_settings),
            onClick = { navController.popBackStack() },
        )

        SettingsItem(
            Res.drawable.ic_node,
            localizedStringResource(Res.string.node_settings),
        ) {
            navController.navigate(AlgoKitScreens.NODE_SETTINGS_SCREEN.name)
        }

        SettingsItem(
            Res.drawable.ic_wallet,
            localizedStringResource(Res.string.create_legacy_algo25_account),
        ) {
            viewModel.createAlgoAccount()
        }

        SettingsItem(
            Res.drawable.ic_wallet,
            localizedStringResource(Res.string.create_legacy_hd_account),
        ) {
            navController.navigate(AlgoKitScreens.HD_WALLET_SELECTION_SCREEN.name)
        }
    }
}

@Preview
@Composable
fun DeveloperSettingsScreenPreview() {
    AlgoKitTheme {
        DeveloperSettingsScreen(navController = rememberNavController()) {
        }
    }
}
