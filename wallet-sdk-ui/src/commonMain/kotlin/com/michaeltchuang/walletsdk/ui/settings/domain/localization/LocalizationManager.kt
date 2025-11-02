package com.michaeltchuang.walletsdk.ui.settings.domain.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

expect class LocalizationManager(
    context: Any? = null,
) {
    fun actuateLocalization(localizationPreference: LocalizationPreference): Any?
}

@Composable
expect fun provideLocalizationManager(): LocalizationManager

expect fun setDefaultLocale(localizationPreference: LocalizationPreference)

val LocalAppLocale = staticCompositionLocalOf { LocalizationPreference.ENGLISH }

/**
 * A wrapper around stringResource that forces recomposition when the app locale changes.
 * This is particularly important for iOS where stringResource doesn't automatically
 * observe locale changes.
 */
@Composable
fun localizedStringResource(resource: StringResource): String {
    // Read the current locale to trigger recomposition when it changes
    val currentLocale = LocalAppLocale.current
    // On iOS, also observe locale change counter to force immediate recomposition
    val localeChangeCounter = observeLocaleChanges()
    // Return the string resource - the reads above ensure recomposition
    return stringResource(resource)
}

/**
 * A wrapper around stringResource with format arguments that forces recomposition
 * when the app locale changes.
 */
@Composable
fun localizedStringResource(
    resource: StringResource,
    vararg formatArgs: Any,
): String {
    val currentLocale = LocalAppLocale.current
    val localeChangeCounter = observeLocaleChanges()
    return stringResource(resource, *formatArgs)
}

@Composable
fun LocaleAwareContent(content: @Composable () -> Unit) {
    val currentLocale = LocalAppLocale.current

    LaunchedEffect(currentLocale) {
        setDefaultLocale(currentLocale)
    }

    content()
}

@Composable
expect fun observeLocaleChanges(): Int
