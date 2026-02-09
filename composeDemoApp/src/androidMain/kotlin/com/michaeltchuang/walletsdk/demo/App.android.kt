package com.michaeltchuang.walletsdk.demo

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.michaeltchuang.walletsdk.demo.di.provideViewModelModules
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocalizationManager
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocalizationPreference
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocalizationPreferenceRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.koin.core.context.loadKoinModules

class AndroidApp : Application() {
    companion object {
        lateinit var instance: AndroidApp
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        WalletSDK.initialize(
            context = applicationContext,
            enableLogging = false,
        )

        // Load demo app's ViewModel modules
        loadKoinModules(provideViewModelModules)
    }
}

class AppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
        observeLanguageChanges()
    }

    override fun attachBaseContext(newBase: Context) {
        val savedLanguageCode: LocalizationPreference =
            runBlocking {
                LocalizationPreferenceRepository(newBase).getSavedLocalizationPreferenceFlow().first()
            }
        val localeAwareContext = LocalizationManager(newBase).actuateLocalization(savedLanguageCode)
        super.attachBaseContext(localeAwareContext as Context)
    }

    private fun observeLanguageChanges() {
        LocalizationPreferenceRepository(this)
            .getSavedLocalizationPreferenceFlow()
            .distinctUntilChanged()
            .drop(1)
            .onEach { newLanguageCode ->
                recreate()
            }.launchIn(lifecycleScope)
    }
}
