package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.CreateWatchAccountViewModel
import org.junit.Test
import java.util.Locale

class CreateWatchAccountScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testEmptyForm() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                viewState =
                    CreateWatchAccountViewModel.ViewState.Content(
                        address = "",
                        isAddressValid = false,
                        isLoading = false,
                    ),
                onAddressChanged = {},
                onCreateWatchAccount = {},
                onInfoClick = {},
            )
        }

        takeScreenshot("testEmptyForm")
    }

    @Test
    fun testWithAddress() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                viewState =
                    CreateWatchAccountViewModel.ViewState.Content(
                        address = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        isAddressValid = true,
                        isLoading = false,
                    ),
                onAddressChanged = {},
                onCreateWatchAccount = {},
                onInfoClick = {},
            )
        }

        takeScreenshot("testWithAddress")
    }
}
