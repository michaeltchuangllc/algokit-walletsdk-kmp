package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.CreateWatchAccountViewModel
import org.junit.Test
import java.util.Locale

class CreateWatchAccountScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {

    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                viewState =
                    CreateWatchAccountViewModel.ViewState.Content(
                        address = WalletSdkConstants.SAMPLE_FALCON24_ADDRESS,
                        isAddressValid = true,
                        isLoading = false,
                    ),
                onAddressChanged = {},
                onCreateWatchAccount = {},
                onInfoClick = {},
            )
        }

        takeScreenshot("testContent")
    }
}
