package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class CreateAccountNameScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                accountName = remember { androidx.compose.runtime.mutableStateOf(WalletSdkConstants.SAMPLE_FALCON24_ADDRESS) },
                seedId = 1,
                onFinishClick = {},
            )
        }

        takeScreenshot("testContent")
    }
}
