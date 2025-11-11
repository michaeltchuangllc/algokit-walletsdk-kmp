package com.michaeltchuang.walletsdk.ui.settings.screens

import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.ui.base.test.BaseScreenshotTest
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocalizationPreference
import com.michaeltchuang.walletsdk.ui.settings.viewmodels.LanguageSelectorViewModel
import org.junit.Test
import java.util.Locale

class LanguageSelectorScreenshotTest(
    locale: Locale,
    darkTheme: Boolean,
) : BaseScreenshotTest(locale, darkTheme) {
    @Test
    fun testContent() {
        setTestContent {
            val navController = rememberNavController()
            val viewState =
                LanguageSelectorViewModel.ViewState.Content(
                    languageOptions =
                        listOf(
                            LocalizationPreference.ENGLISH,
                            LocalizationPreference.ITALIAN,
                            LocalizationPreference.HINDI,
                        ),
                    currentLanguage = LocalizationPreference.ENGLISH,
                )
            ScreenContent(
                navController = navController,
                viewState = viewState,
                onLanguageSelected = { },
            )
        }
        takeScreenshot("testContent")
    }
}
