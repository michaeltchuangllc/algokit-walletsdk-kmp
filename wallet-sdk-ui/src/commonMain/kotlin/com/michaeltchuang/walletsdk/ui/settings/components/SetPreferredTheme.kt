package com.michaeltchuang.walletsdk.ui.settings.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.LocalThemeIsDark
import com.michaeltchuang.walletsdk.ui.settings.domain.theme.ThemePreference
import com.michaeltchuang.walletsdk.ui.settings.domain.theme.provideThemePreferenceRepository

@Composable
fun SetPreferredTheme() {
    val repository = provideThemePreferenceRepository()
    val currentThemePreference by repository
        .getSavedThemePreferenceFlow()
        .collectAsState(initial = ThemePreference.SYSTEM)
    val systemDark = isSystemInDarkTheme()
    val isDark = LocalThemeIsDark.current
    LaunchedEffect(currentThemePreference) {
        currentThemePreference.let {
            when (it) {
                ThemePreference.LIGHT -> {
                    isDark.value = false
                }

                ThemePreference.DARK -> {
                    isDark.value = true
                }

                ThemePreference.SYSTEM -> {
                    isDark.value = systemDark
                }
            }
        }
    }
}
