package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class PassphraseAcknowledgeScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            PassphraseAcknowledgeScreen(
                navController = rememberNavController(),
                address = WalletSdkConstants.SAMPLE_FALCON24_ADDRESS,
            )
        }

        takeScreenshot("testContent")
    }
}
