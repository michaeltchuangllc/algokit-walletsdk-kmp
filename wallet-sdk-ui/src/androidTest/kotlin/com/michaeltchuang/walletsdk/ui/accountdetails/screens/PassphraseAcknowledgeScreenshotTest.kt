package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class PassphraseAcknowledgeScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testInitialState() {
        setTestContent {
            PassphraseAcknowledgeScreen(
                navController = rememberNavController(),
                address = "MCRT347GYFXVLIQBCEBTEQJO6S5KFYRG2TC5CLXBHGGVNXHONP5RA7FWRLM",
            )
        }

        takeScreenshot("testInitialState")
    }
}
