package com.michaeltchuang.walletsdk.ui.settings.domain.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

private object LocaleChangeTracker {
    private val _localeChangeCounter = MutableStateFlow(0)
    val localeChangeCounter = _localeChangeCounter.asStateFlow()

    fun notifyLocaleChange() {
        _localeChangeCounter.value += 1
    }
}

actual class LocalizationManager actual constructor(
    private val context: Any?,
) {
    actual fun actuateLocalization(localizationPreference: LocalizationPreference): Any? {
        setDefaultLocale(localizationPreference)
        LocaleChangeTracker.notifyLocaleChange()
        return Unit
    }
}

@Composable
actual fun provideLocalizationManager(): LocalizationManager = LocalizationManager(null)

@Composable
actual fun observeLocaleChanges(): Int {
    val counter by LocaleChangeTracker.localeChangeCounter.collectAsState()
    return counter
}

actual fun setDefaultLocale(localizationPreference: LocalizationPreference) {
    val languageCode =
        when (localizationPreference) {
            LocalizationPreference.ENGLISH -> "en"
            LocalizationPreference.ITALIAN -> "it"
            LocalizationPreference.HINDI -> "hi"
        }

    NSUserDefaults.standardUserDefaults.setObject(
        arrayListOf(languageCode),
        "AppleLanguages",
    )
    NSUserDefaults.standardUserDefaults.synchronize()

    LocaleChangeTracker.notifyLocaleChange()
}
