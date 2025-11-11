package com.michaeltchuang.walletsdk.ui.onboarding.screens

import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class OnboardingAccountTypeScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {

    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                hasWalletWithNoAccounts = true,
                onCreateNewAccount = {},
                onCreateWallet = {},
                onImportAccount = {},
                onWatchAddress = {},
            )
        }

        takeScreenshot("testContent")
    }

    @Test
    fun testWithWalletButNoAccounts() {
        setTestContent {
            ScreenContent(
                hasWalletWithNoAccounts = true,
                onCreateNewAccount = {},
                onCreateWallet = {},
                onImportAccount = {},
                onWatchAddress = {},
            )
        }

        takeScreenshot("testWithWalletButNoAccounts")
    }

    @Test
    fun testWithoutWalletAccounts() {
        setTestContent {
            ScreenContent(
                hasWalletWithNoAccounts = false,
                onCreateNewAccount = {},
                onCreateWallet = {},
                onImportAccount = {},
                onWatchAddress = {},
            )
        }

        takeScreenshot("testWithoutWalletAccounts")
    }
}
