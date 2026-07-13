package com.michaeltchuang.walletsdk.ui.signing.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.AddAssetViewModel
import org.junit.Test
import java.util.Locale

class AddAssetScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            val navController = rememberNavController()
            val contentState =
                AddAssetViewModel.ViewState.Content(
                    assetId = "10458941",
                    assetName = "USDC",
                    logoUri = "https://algorand-wallet-mainnet.b-cdn.net/media/usd-coin-usdc-logo.png",
                    accountAddress = "N44MXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX7CWM",
                    fee = "0.001",
                    isVerified = true,
                )
            ScreenContent(
                navController = navController,
                viewState = contentState,
                onApproveClick = { },
                onCloseClick = { },
                onCopyIdClick = { },
            )
        }
        takeScreenshot("testContent")
        Thread.sleep(1000)
    }

    @Test
    fun testError() {
        setTestContent {
            val navController = rememberNavController()
            val errorState =
                AddAssetViewModel.ViewState.Error(
                    message = "Asset not found: 99999999999",
                )
            ScreenContent(
                navController = navController,
                viewState = errorState,
                onApproveClick = { },
                onCloseClick = { },
                onCopyIdClick = { },
            )
        }
        takeScreenshot("testError")
        Thread.sleep(1000)
    }
}
