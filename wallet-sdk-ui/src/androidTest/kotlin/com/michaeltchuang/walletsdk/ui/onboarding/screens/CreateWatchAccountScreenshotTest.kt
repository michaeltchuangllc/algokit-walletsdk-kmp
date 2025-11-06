package com.michaeltchuang.walletsdk.ui.onboarding.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import com.karumi.shot.ScreenshotTest
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalThemeIsDark
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.CreateWatchAccountViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocalizationPreference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

@RunWith(Parameterized::class)
class CreateWatchAccountScreenshotTest(
    private val locale: Locale,
    private val darkTheme: Boolean,
) : ScreenshotTest {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "locale={0}, darkTheme={1}")
        fun data(): Collection<Array<Any>> =
            LocalizationPreference.entries.flatMap { localizationPref ->
                val locale = localizationPref.toLocale()
                listOf(
                    arrayOf(locale, false),
                    arrayOf(locale, true),
                )
            }

        private fun LocalizationPreference.toLocale(): Locale =
            when (this) {
                LocalizationPreference.ENGLISH -> Locale.ENGLISH
                LocalizationPreference.ITALIAN -> Locale.ITALIAN
                LocalizationPreference.HINDI ->
                    Locale
                        .Builder()
                        .setLanguage("hi")
                        .setRegion("IN")
                        .build()
            }
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testEmptyForm() {
        Locale.setDefault(locale)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalThemeIsDark provides mutableStateOf(darkTheme)) {
                AlgoKitTheme {
                    ScreenContent(
                        navController = rememberNavController(),
                        viewState =
                            CreateWatchAccountViewModel.ViewState.Content(
                                address = "",
                                isAddressValid = false,
                                isLoading = false,
                            ),
                        onAddressChanged = {},
                        onCreateWatchAccount = {},
                        onInfoClick = {},
                    )
                }
            }
        }

        val themeDir = if (darkTheme) "dark" else "light"
        compareScreenshot(
            composeTestRule,
            name = "${locale.language}_${themeDir}_testEmptyForm",
        )
    }

    @Test
    fun testWithAddress() {
        Locale.setDefault(locale)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalThemeIsDark provides mutableStateOf(darkTheme)) {
                AlgoKitTheme {
                    ScreenContent(
                        navController = rememberNavController(),
                        viewState =
                            CreateWatchAccountViewModel.ViewState.Content(
                                address = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                                isAddressValid = true,
                                isLoading = false,
                            ),
                        onAddressChanged = {},
                        onCreateWatchAccount = {},
                        onInfoClick = {},
                    )
                }
            }
        }

        val themeDir = if (darkTheme) "dark" else "light"
        compareScreenshot(
            composeTestRule,
            name = "${locale.language}_${themeDir}_testWithAddress",
        )
    }
}
