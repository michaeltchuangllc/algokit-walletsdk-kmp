package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import java.util.Locale

class RecoverAnAccountScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            RecoverAnAccountScreen(
                navController = rememberNavController(),
                showSnackbar = { _, _ -> },
            )
        }

        takeScreenshot("testContent")
    }
}
