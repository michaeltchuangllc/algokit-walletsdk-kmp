package com.michaeltchuang.walletsdk.ui.base.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.karumi.shot.ScreenshotTest
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalCustomColors
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalThemeIsDark
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.ThemedColors
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocalAppLocale
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocalizationPreference
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

@RunWith(Parameterized::class)
abstract class BaseScreenshotTest(
    protected val locale: Locale,
    protected val darkTheme: Boolean,
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

    /**
     * Helper method to set up the test content with proper theme and locale configuration
     */
    protected fun setTestContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        Locale.setDefault(locale)

        composeTestRule.setContent {
            // Provide theme directly without AlgoKitTheme to avoid override
            val themeState = mutableStateOf(darkTheme)
            val customColors = ThemedColors.getColorsByMode(darkTheme)
            val localePreference =
                when (locale.language) {
                    "en" -> LocalizationPreference.ENGLISH
                    "it" -> LocalizationPreference.ITALIAN
                    "hi" -> LocalizationPreference.HINDI
                    else -> LocalizationPreference.ENGLISH
                }

            CompositionLocalProvider(
                LocalThemeIsDark provides themeState,
                LocalCustomColors provides customColors,
                LocalAppLocale provides localePreference,
            ) {
                content()
            }
        }
    }

    /**
     * Helper method to generate the theme directory name for screenshots
     */
    protected fun getThemeDir(): String = if (darkTheme) "dark" else "light"

    /**
     * Helper method to generate the screenshot name with locale and theme
     */
    protected fun getScreenshotName(testName: String): String = "${locale.language}_${getThemeDir()}_$testName"

    /**
     * Helper method to take a screenshot with the standard naming convention
     */
    protected fun takeScreenshot(testName: String) {
        compareScreenshot(
            composeTestRule,
            name = getScreenshotName(testName),
        )
    }
}
