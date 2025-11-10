package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class OnboardingIntroScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testOnboardingIntro() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                onCreateNewWallet = {},
                onImportAccount = {},
            )
        }

        takeScreenshot("OnboardingIntro")
    }
}
