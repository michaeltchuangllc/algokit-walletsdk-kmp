package com.michaeltchuang.walletsdk.ui.settings.domain.localization

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

actual class LocalizationManager actual constructor(
    private val context: Any?,
) {
    actual fun actuateLocalization(localizationPreference: LocalizationPreference): Any? {
        val context = context as Context
        val resources = context.resources
        val config = resources.configuration

        val locale =
            when (localizationPreference) {
                LocalizationPreference.ENGLISH -> Locale.ENGLISH
                LocalizationPreference.ITALIAN -> Locale.ITALIAN
                LocalizationPreference.HINDI ->
                    Locale
                        .Builder()
                        .setLanguage("hi")
                        .setRegion("IN")
                        .build()
            }
        setDefaultLocale(localizationPreference)
        config.setLocale(locale)
        val newContext = context.createConfigurationContext(config)

        return newContext
    }
}

@Composable
actual fun provideLocalizationManager(): LocalizationManager {
    val context = LocalContext.current
    return LocalizationManager(context)
}

actual fun setDefaultLocale(localizationPreference: LocalizationPreference) {
    val locale =
        when (localizationPreference) {
            LocalizationPreference.ENGLISH -> Locale.ENGLISH
            LocalizationPreference.ITALIAN -> Locale.ITALIAN
            LocalizationPreference.HINDI ->
                Locale
                    .Builder()
                    .setLanguage("hi")
                    .setRegion("IN")
                    .build()
        }
    Locale.setDefault(locale)
}

@Composable
actual fun observeLocaleChanges(): Int {
    // On Android, stringResource automatically handles locale changes
    // so we don't need additional tracking
    return 0
}
