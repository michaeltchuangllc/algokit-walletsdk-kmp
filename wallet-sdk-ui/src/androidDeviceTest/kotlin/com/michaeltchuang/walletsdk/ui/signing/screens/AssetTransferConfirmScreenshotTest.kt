package com.michaeltchuang.walletsdk.ui.signing.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.AssetTransferConfirmViewModel
import org.junit.Test
import java.util.Locale

class AssetTransferConfirmScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            val navController = rememberNavController()
            val contentState =
                AssetTransferConfirmViewModel.ViewState.Content(
                    senderAddress = "AXNQ4ZEZ5QBVWGMGW3C7VQJHZ8NQKQY5XJVZ2WQXQY5XJVZ2WQXQY5XJVZ2W",
                    receiverAddress = "BXYZ9YUZ5QBVWGMGW3C7VQJHZ8NQKQY5XJVZ2WQXQY5XJVZ2WQXQY5XJVZ2W",
                    amount = "5.25",
                    accountBalance = "15000000",
                    note = "Payment for services rendered",
                    fee = "0.001",
                    assetId = -7L,
                    assetName = "",
                    assetLogoUrl = "",
                    assetBalance = null,
                    isAssetValid = true,
                )
            ScreenContent(
                navController = navController,
                viewState = contentState,
                onSendTransaction = { },
                onSetNote = { },
            )
        }
        takeScreenshot("testContent")
        Thread.sleep(2000)
    }

    @Test
    fun testASAContent() {
        setTestContent {
            val navController = rememberNavController()
            val contentState =
                AssetTransferConfirmViewModel.ViewState.Content(
                    senderAddress = "AXNQ4ZEZ5QBVWGMGW3C7VQJHZ8NQKQY5XJVZ2WQXQY5XJVZ2WQXQY5XJVZ2W",
                    receiverAddress = "BXYZ9YUZ5QBVWGMGW3C7VQJHZ8NQKQY5XJVZ2WQXQY5XJVZ2WQXQY5XJVZ2W",
                    amount = "100.5",
                    accountBalance = "15000000",
                    note = "ASA transfer",
                    fee = "0.001",
                    assetId = 10458941L,
                    assetName = "USDC",
                    assetLogoUrl = "https://algorand-wallet-mainnet.b-cdn.net/media/usd-coin-usdc-logo.png",
                    assetBalance = "500000000",
                    isAssetValid = true,
                )
            ScreenContent(
                navController = navController,
                viewState = contentState,
                onSendTransaction = { },
                onSetNote = { },
            )
        }
        takeScreenshot("testASAContent")
        Thread.sleep(2000)
    }

    @Test
    fun testError() {
        setTestContent {
            val navController = rememberNavController()
            val errorState =
                AssetTransferConfirmViewModel.ViewState.Error(
                    message = "Asset not found: 99999999999",
                )
            ScreenContent(
                navController = navController,
                viewState = errorState,
                onSendTransaction = { },
                onSetNote = { },
            )
        }
        takeScreenshot("testError")
        Thread.sleep(2000)
    }
}
