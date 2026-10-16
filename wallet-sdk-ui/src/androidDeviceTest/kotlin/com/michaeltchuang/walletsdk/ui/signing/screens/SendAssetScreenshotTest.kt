package com.michaeltchuang.walletsdk.ui.signing.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.signing.viewmodels.SendAssetViewModel
import org.junit.Test
import java.util.Locale

class SendAssetScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                senderAddress = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                viewState =
                    SendAssetViewModel.ViewState.Content(
                        amount = "10.5",
                        usdValue = "$20.85",
                        balance = "25.0 ALGO",
                        assetUsdValue = "$49.75",
                        showUSDAmount = true,
                    ),
                noteText = remember { mutableStateOf("") },
                onAmountChange = {},
                onDeletePressed = {},
                onMaxPressed = {},
                onNextPressed = {},
                onBackClick = {},
                onInfoClick = {},
            )
        }

        takeScreenshot("testContent")
    }

    @Test
    fun testSendAssetScreenEmpty() {
        setTestContent {
            ScreenContent(
                senderAddress = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                viewState =
                    SendAssetViewModel.ViewState.Content(
                        amount = "",
                        usdValue = "$0.00",
                        balance = "25.0 ALGO",
                        assetUsdValue = "$49.75",
                        showUSDAmount = true,
                    ),
                noteText = remember { mutableStateOf("") },
                onAmountChange = {},
                onDeletePressed = {},
                onMaxPressed = {},
                onNextPressed = {},
                onBackClick = {},
                onInfoClick = {},
            )
        }

        takeScreenshot("SendAsset_Empty")
    }

    @Test
    fun testSendAssetScreenWithAmount() {
        setTestContent {
            ScreenContent(
                senderAddress = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                viewState =
                    SendAssetViewModel.ViewState.Content(
                        amount = "10.5",
                        usdValue = "$20.85",
                        balance = "25.0 ALGO",
                        assetUsdValue = "$49.75",
                        showUSDAmount = true,
                    ),
                noteText = remember { mutableStateOf("") },
                onAmountChange = {},
                onDeletePressed = {},
                onMaxPressed = {},
                onNextPressed = {},
                onBackClick = {},
                onInfoClick = {},
            )
        }

        takeScreenshot("SendAsset_WithAmount")
    }

    @Test
    fun testSendAssetScreenWithMaxAmount() {
        setTestContent {
            ScreenContent(
                senderAddress = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                viewState =
                    SendAssetViewModel.ViewState.Content(
                        amount = "24.999",
                        usdValue = "$49.75",
                        balance = "25.0 ALGO",
                        assetUsdValue = "$49.75",
                        showUSDAmount = true,
                    ),
                noteText = remember { mutableStateOf("") },
                onAmountChange = {},
                onDeletePressed = {},
                onMaxPressed = {},
                onNextPressed = {},
                onBackClick = {},
                onInfoClick = {},
            )
        }

        takeScreenshot("SendAsset_MaxAmount")
    }

    @Test
    fun testSendAssetScreenWithNote() {
        setTestContent {
            ScreenContent(
                senderAddress = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                viewState =
                    SendAssetViewModel.ViewState.Content(
                        amount = "5.25",
                        usdValue = "$10.44",
                        balance = "25.0 ALGO",
                        assetUsdValue = "$49.75",
                        showUSDAmount = true,
                    ),
                noteText = remember { mutableStateOf("Payment for services") },
                onAmountChange = {},
                onDeletePressed = {},
                onMaxPressed = {},
                onNextPressed = {},
                onBackClick = {},
                onInfoClick = {},
            )
        }

        takeScreenshot("SendAsset_WithNote")
    }

    @Test
    fun testSendAssetScreenLargeAmount() {
        setTestContent {
            ScreenContent(
                senderAddress = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                viewState =
                    SendAssetViewModel.ViewState.Content(
                        amount = "1000000.123456",
                        usdValue = "$1,987,654.32",
                        balance = "1500000.0 ALGO",
                        assetUsdValue = "$2,981,250.00",
                        showUSDAmount = true,
                    ),
                noteText = remember { mutableStateOf("Large transaction") },
                onAmountChange = {},
                onDeletePressed = {},
                onMaxPressed = {},
                onNextPressed = {},
                onBackClick = {},
                onInfoClick = {},
            )
        }

        takeScreenshot("SendAsset_LargeAmount")
    }

    @Test
    fun testSendAssetScreenWithoutUSD() {
        setTestContent {
            ScreenContent(
                senderAddress = "AXNQ4ZE7GWQJ4HBQZ7PMJLVBQCXQJ4ZJZQM6HWQZ7PMJLVBQCXQJ4ZJZQM6H",
                viewState =
                    SendAssetViewModel.ViewState.Content(
                        amount = "15.75",
                        usdValue = "$31.31",
                        balance = "50.0 ALGO",
                        assetUsdValue = "$99.50",
                        showUSDAmount = false,
                    ),
                noteText = remember { mutableStateOf("") },
                onAmountChange = {},
                onDeletePressed = {},
                onMaxPressed = {},
                onNextPressed = {},
                onBackClick = {},
                onInfoClick = {},
            )
        }

        takeScreenshot("SendAsset_WithoutUSD")
    }
}
