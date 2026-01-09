package com.michaeltchuang.walletsdk.ui.settings.screens

import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.PasskeysViewModel
import org.junit.Test
import java.util.Locale

class PasskeysScreenScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            val fakePasskeys =
                listOf(
                    PasskeysViewModel.Passkey(
                        credId = "credId123",
                        title = "Liquid Auth Passkey",
                        domain = "liquid-auth.onrender.com",
                        lastUsed = "Today",
                        username = "kyle007",
                    ),
                    PasskeysViewModel.Passkey(
                        credId = "credId124",
                        title = "Pera Passkey",
                        domain = "pera-wallet.com",
                        lastUsed = "5d ago",
                        username = "ru12345",
                    ),
                )
            val fakeViewState = PasskeysViewModel.ViewState.Content(passkeys = fakePasskeys)
            ScreenContentPasskeys(
                viewState = fakeViewState,
            )
        }
        takeScreenshot("testContent")
    }
}
