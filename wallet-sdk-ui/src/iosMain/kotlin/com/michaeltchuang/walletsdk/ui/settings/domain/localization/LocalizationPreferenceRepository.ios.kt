package com.michaeltchuang.walletsdk.ui.settings.domain.localization

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults
import platform.Foundation.setValue

private const val LOCALIZATION_KEY = "localization_key"

/**
 * Singleton holder to ensure all LocalizationPreferenceRepository instances
 * share the same MutableStateFlow, allowing updates to propagate to all observers.
 */
private object LocalizationPreferenceRepositoryHolder {
    val stateFlow: MutableStateFlow<LocalizationPreference>

    init {
        val defaults = NSUserDefaults.standardUserDefaults
        val saved = defaults.objectForKey(LOCALIZATION_KEY) as? String
        val initialValue =
            saved?.let { name ->
                LocalizationPreference.entries.find { it.name == name }
            } ?: LocalizationPreference.ENGLISH

        stateFlow = MutableStateFlow(initialValue)
    }
}

actual class LocalizationPreferenceRepository actual constructor(
    context: Any?,
) {
    private val stateFlow: MutableStateFlow<LocalizationPreference>
        get() = LocalizationPreferenceRepositoryHolder.stateFlow

    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    actual fun getSavedLocalizationPreferenceFlow(): Flow<LocalizationPreference> = stateFlow.asStateFlow()

    actual suspend fun saveLocalizationPreference(pref: LocalizationPreference) {
        withContext(Dispatchers.Default) {
            defaults.setValue(
                value = pref.name,
                forKey = LOCALIZATION_KEY,
            )
            defaults.synchronize()
            // Update the iOS system locale immediately
            setDefaultLocale(pref)
            // Update the state flow to trigger recomposition
            stateFlow.value = pref
        }
    }
}

@Composable
actual fun provideLocalizationPreferenceRepository(): LocalizationPreferenceRepository = LocalizationPreferenceRepository()
