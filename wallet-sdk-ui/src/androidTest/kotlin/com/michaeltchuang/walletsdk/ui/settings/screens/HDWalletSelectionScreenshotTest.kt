package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.HDWalletSelectionViewModel
import org.junit.Test
import java.util.Locale

class HDWalletSelectionScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            val navController = rememberNavController()
            val walletItems =
                listOf(
                    HDWalletSelectionViewModel.WalletItemPreview(
                        seedId = 1,
                        name = "My Primary Wallet",
                        numberOfAccounts = "3 accounts",
                        primaryValue = "AXNQ4ZE...",
                        secondaryValue = "123.45 ALGO",
                        maxAccountIndex = 2,
                    ),
                    HDWalletSelectionViewModel.WalletItemPreview(
                        seedId = 2,
                        name = "Secondary Wallet",
                        numberOfAccounts = "1 account",
                        primaryValue = "BXYZ9YU...",
                        secondaryValue = "67.89 ALGO",
                        maxAccountIndex = 0,
                    ),
                    HDWalletSelectionViewModel.WalletItemPreview(
                        seedId = 3,
                        name = "Trading Wallet",
                        numberOfAccounts = "5 accounts",
                        primaryValue = "CDEF1QW...",
                        secondaryValue = "234.56 ALGO",
                        maxAccountIndex = 4,
                    ),
                )
            val viewState =
                HDWalletSelectionViewModel.ViewState.Content(
                    walletItemPreviews = walletItems,
                )
            ScreenContent(
                viewState = viewState,
                navController = navController,
                createNewWalletClick = { },
                walletItemClick = { },
            )
        }
        takeScreenshot("testContent")
    }

    @Test
    fun testEmptyWalletList() {
        setTestContent {
            val navController = rememberNavController()
            val viewState =
                HDWalletSelectionViewModel.ViewState.Content(
                    walletItemPreviews = emptyList(),
                )
            ScreenContent(
                viewState = viewState,
                navController = navController,
                createNewWalletClick = { },
                walletItemClick = { },
            )
        }
        takeScreenshot("testEmptyWalletList")
    }

    @Test
    fun testWithMultipleWallets() {
        setTestContent {
            val navController = rememberNavController()
            val walletItems =
                listOf(
                    HDWalletSelectionViewModel.WalletItemPreview(
                        seedId = 1,
                        name = "My Primary Wallet",
                        numberOfAccounts = "3 accounts",
                        primaryValue = "AXNQ4ZE...",
                        secondaryValue = "123.45 ALGO",
                        maxAccountIndex = 2,
                    ),
                    HDWalletSelectionViewModel.WalletItemPreview(
                        seedId = 2,
                        name = "Secondary Wallet",
                        numberOfAccounts = "1 account",
                        primaryValue = "BXYZ9YU...",
                        secondaryValue = "67.89 ALGO",
                        maxAccountIndex = 0,
                    ),
                    HDWalletSelectionViewModel.WalletItemPreview(
                        seedId = 3,
                        name = "Trading Wallet",
                        numberOfAccounts = "5 accounts",
                        primaryValue = "CDEF1QW...",
                        secondaryValue = "234.56 ALGO",
                        maxAccountIndex = 4,
                    ),
                )
            val viewState =
                HDWalletSelectionViewModel.ViewState.Content(
                    walletItemPreviews = walletItems,
                )
            ScreenContent(
                viewState = viewState,
                navController = navController,
                createNewWalletClick = { },
                walletItemClick = { },
            )
        }
        takeScreenshot("testWithMultipleWallets")
    }

    @Test
    fun testWithSingleWallet() {
        setTestContent {
            val navController = rememberNavController()
            val walletItems =
                listOf(
                    HDWalletSelectionViewModel.WalletItemPreview(
                        seedId = 1,
                        name = "My Wallet",
                        numberOfAccounts = "1 account",
                        primaryValue = "AXNQ4ZE...",
                        secondaryValue = "123.45 ALGO",
                        maxAccountIndex = 0,
                    ),
                )
            val viewState =
                HDWalletSelectionViewModel.ViewState.Content(
                    walletItemPreviews = walletItems,
                )
            ScreenContent(
                viewState = viewState,
                navController = navController,
                createNewWalletClick = { },
                walletItemClick = { },
            )
        }
        takeScreenshot("testWithSingleWallet")
    }
}
