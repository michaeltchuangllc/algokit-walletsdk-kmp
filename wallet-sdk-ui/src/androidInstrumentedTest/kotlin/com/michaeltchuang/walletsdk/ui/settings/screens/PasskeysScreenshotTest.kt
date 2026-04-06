package com.michaeltchuang.walletsdk.ui.settings.screens

import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.PasskeysViewModel
import org.junit.Test
import java.util.Locale

class PasskeysScreenshotTest(
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
                        title = "michaeltchuang",
                        domain = "github.com",
                        lastUsed = "8 days ago",
                        username = "michaeltchuang",
                        accountType = "",
                    ),
                    PasskeysViewModel.Passkey(
                        credId = "credId124",
                        title = "6ZTU...RCGY",
                        domain = "michaeltchuang.ngrok.dev",
                        lastUsed = "5d ago",
                        username = "6ZTU...RCGY",
                        accountType = "",
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
