package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.Falcon24WalletSelectionViewModel
import org.junit.Test
import java.util.Locale

class Falcon24WalletSelectionScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    companion object {
        private fun createSampleWalletItems(count: Int): List<Falcon24WalletSelectionViewModel.WalletItemPreview> =
            (1..count).map { index ->
                Falcon24WalletSelectionViewModel.WalletItemPreview(
                    seedId = index,
                    name = "Wallet #$index",
                    numberOfAccounts = if (index == 1) "1 account" else "$index accounts",
                    primaryValue = "0 ALGO",
                    secondaryValue = "$0.00 USD",
                    maxAccountIndex = index - 1,
                )
            }
    }

    @Test
    fun testContent() {
        setTestContent {
            Falcon24WalletSelectionScreenContent(
                viewState =
                    Falcon24WalletSelectionViewModel.ViewState.Content(
                        walletItemPreviews = createSampleWalletItems(2),
                    ),
                navController = rememberNavController(),
                createNewWalletClick = {},
                walletItemClick = {},
            )
        }

        takeScreenshot("testContent")
    }

    @Test
    fun testWithSingleWallet() {
        setTestContent {
            Falcon24WalletSelectionScreenContent(
                viewState =
                    Falcon24WalletSelectionViewModel.ViewState.Content(
                        walletItemPreviews = createSampleWalletItems(1),
                    ),
                navController = rememberNavController(),
                createNewWalletClick = {},
                walletItemClick = {},
            )
        }

        takeScreenshot("testWithSingleWallet")
    }
}
