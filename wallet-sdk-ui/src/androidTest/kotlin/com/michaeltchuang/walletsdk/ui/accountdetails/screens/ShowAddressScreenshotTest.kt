package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.accountdetails.viewmodels.QRCodeViewModel
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class ShowAddressScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                viewState =
                    QRCodeViewModel.ViewState.Content(
                        address = "MCRT347GYFXVLIQBCEBTEQJO6S5KFYRG2TC5CLXBHGGVNXHONP5RA7FWRLM",
                        displayAddress = "MCRT...FWRLM",
                    ),
                onCopyAddress = {},
            )
        }

        takeScreenshot("testContent")
    }
}
