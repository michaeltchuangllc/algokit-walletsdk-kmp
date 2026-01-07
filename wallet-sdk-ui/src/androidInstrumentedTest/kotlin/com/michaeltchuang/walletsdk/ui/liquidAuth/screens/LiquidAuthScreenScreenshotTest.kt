package com.michaeltchuang.walletsdk.ui.liquidAuth.screens

import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthViewModel
import org.junit.Test
import java.util.Locale

class LiquidAuthScreenScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            // Use sample data as the preview method
            val accounts = listOf(
                AccountLite(
                    address = "X6TTIPDLJQ2S5ARP6PEURNG4DIU6R4CPMRZ7AMCC5AHH2ME374LSCQ2GWI",
                    customName = "Account 1",
                    registrationType = AccountRegistrationType.HdKey,
                    balance = "1",
                ),
                AccountLite(
                    address = "X6TTIPDLJQ2S5ARP6PEURNG4DIU6R4CPMRZ7AMCC5AHH2ME374LSCQ2GWI",
                    customName = "Account 2",
                    registrationType = AccountRegistrationType.Falcon24,
                    balance = "100",
                ),
            )
            ScreenContentLiquidAuth(
                viewState = LiquidAuthViewModel.ViewState.Content(accounts),
                onAccountSelected = {}, // Stub
                onBack = {}, // Stub
            )
        }
        takeScreenshot("testContent")
    }
}
