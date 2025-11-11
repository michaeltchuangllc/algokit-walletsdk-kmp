package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

@RunWith(Parameterized::class)
class CreateAccountNameScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            ScreenContent(
                navController = rememberNavController(),
                accountName = remember { androidx.compose.runtime.mutableStateOf("My Main Account") },
                seedId = 1,
                onFinishClick = {},
            )
        }

        takeScreenshot("testContent")
    }
}
