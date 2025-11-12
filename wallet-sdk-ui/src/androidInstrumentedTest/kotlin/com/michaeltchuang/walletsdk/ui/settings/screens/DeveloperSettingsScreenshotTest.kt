package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class DeveloperSettingsScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            val navController = rememberNavController()
            ScreenContent(
                navController = navController,
                onCreateAlgoAccount = { },
                onNavigateToNodeSettings = { },
                onNavigateToHDWalletSelection = { },
            )
        }
        takeScreenshot("testContent")
    }
}
