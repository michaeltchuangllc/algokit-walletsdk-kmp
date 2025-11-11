package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.settings.domain.theme.ThemePreference
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.ThemePickerViewModel
import org.junit.Test
import java.util.Locale

class ThemePickerScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            val navController = rememberNavController()
            val viewState =
                ThemePickerViewModel.ViewState.Content(
                    themeOptions =
                        listOf(
                            ThemePreference.LIGHT,
                            ThemePreference.DARK,
                            ThemePreference.SYSTEM,
                        ),
                    currentTheme = ThemePreference.SYSTEM,
                )
            ScreenContent(
                navController = navController,
                viewState = viewState,
                onThemeSelected = { },
            )
        }
        takeScreenshot("testContent")
    }
}
