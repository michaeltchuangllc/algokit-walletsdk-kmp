package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.AccountDetailViewModel
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class AccountDetailScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                address = WalletSdkConstants.SAMPLE_FALCON24_ADDRESS,
                viewState =
                    AccountDetailViewModel.ViewState.Content(
                        currentNetwork = AlgorandNetwork.MAINNET,
                        isTestNet = false,
                        explorerBaseUrl = "https://algoexplorer.io",
                        isNoAuthAccount = false,
                        isSolanaAccount = false,
                    ),
                onDeleteAccount = {},
                showSnackBar = {},
            )
        }

        takeScreenshot("testContent")
    }

    @Test
    fun testRegularAccountMainnet() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                address = "MCRT347GYFXVLIQBCEBTEQJO6S5KFYRG2TC5CLXBHGGVNXHONP5RA7FWRLM",
                viewState =
                    AccountDetailViewModel.ViewState.Content(
                        currentNetwork = AlgorandNetwork.MAINNET,
                        isTestNet = false,
                        explorerBaseUrl = "https://algoexplorer.io",
                        isNoAuthAccount = false,
                        isSolanaAccount = false,
                    ),
                onDeleteAccount = {},
                showSnackBar = {},
            )
        }

        takeScreenshot("testRegularAccountMainnet")
    }

    @Test
    fun testWatchAccountMainnet() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                address = "MCRT347GYFXVLIQBCEBTEQJO6S5KFYRG2TC5CLXBHGGVNXHONP5RA7FWRLM",
                viewState =
                    AccountDetailViewModel.ViewState.Content(
                        currentNetwork = AlgorandNetwork.MAINNET,
                        isTestNet = false,
                        explorerBaseUrl = "https://algoexplorer.io",
                        isNoAuthAccount = true,
                        isSolanaAccount = false,
                    ),
                onDeleteAccount = {},
                showSnackBar = {},
            )
        }

        takeScreenshot("testWatchAccountMainnet")
    }
}
