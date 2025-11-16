package com.michaeltchuang.walletsdk.ui.base.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.michaeltchuang.walletsdk.ui.base.designsystem.typography.AlgoKitTypography
import com.michaeltchuang.walletsdk.ui.settings.components.SetPreferredTheme
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocalAppLocale
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocalizationPreference
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.provideLocalizationPreferenceRepository

val LocalCustomColors =
    staticCompositionLocalOf {
        ThemedColors.defaultColor
    }

val LocalThemeIsDark = compositionLocalOf { mutableStateOf(true) }

@Composable
fun AlgoKitTheme(content: @Composable () -> Unit) {
    val systemIsDark = isSystemInDarkTheme()
    val isDarkState = remember { mutableStateOf(systemIsDark) }
    val customColors = ThemedColors.getColorsByMode(isDarkState.value)

    // Get current locale preference
    val localizationRepository = provideLocalizationPreferenceRepository()
    val currentLocale by localizationRepository.getSavedLocalizationPreferenceFlow().collectAsState(
        initial = LocalizationPreference.ENGLISH,
    )

    CompositionLocalProvider(
        LocalThemeIsDark provides isDarkState,
        LocalCustomColors provides customColors,
        LocalAppLocale provides currentLocale,
    ) {
        val isDark by isDarkState
        SystemAppearance(!isDark)
        content()
        SetPreferredTheme()
    }
}

@Composable
internal expect fun SystemAppearance(isDark: Boolean)

object AlgoKitTheme {
    val colors: AlgoKitColor
        @Composable
        get() = LocalCustomColors.current

    val typography
        @Composable
        get() = AlgoKitTypography()
}
