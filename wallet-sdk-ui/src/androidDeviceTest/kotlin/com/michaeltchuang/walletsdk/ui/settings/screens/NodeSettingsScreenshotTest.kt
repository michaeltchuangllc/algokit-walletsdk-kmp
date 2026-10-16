package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.NodeSettingsViewModel
import org.junit.Test
import java.util.Locale

class NodeSettingsScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            val navController = rememberNavController()
            val viewState =
                NodeSettingsViewModel.ViewState.Content(
                    networkOptions =
                        listOf(
                            AlgorandNetwork.MAINNET,
                            AlgorandNetwork.TESTNET,
                            AlgorandNetwork.FUTURENET,
                        ),
                    currentNetwork = AlgorandNetwork.TESTNET,
                )
            ScreenContent(
                navController = navController,
                viewState = viewState,
                showMainnetWarningDialog = false,
                pendingNetwork = null,
                onNetworkSelected = { },
                onConfirmMainnetSelection = { },
                onDismissDialog = { },
            )
        }
        takeScreenshot("testContent")
    }
}
