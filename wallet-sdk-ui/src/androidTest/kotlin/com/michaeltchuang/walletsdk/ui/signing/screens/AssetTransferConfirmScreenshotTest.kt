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
                )
            ScreenContent(
                navController = navController,
                viewState = contentState,
                onSendTransaction = { },
                onSetNote = { },
            )
        }
        takeScreenshot("testContent")
    }
}
