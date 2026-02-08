package com.michaeltchuang.walletsdk.demo

import androidx.compose.runtime.Composable
import com.michaeltchuang.walletsdk.demo.ui.navigation.AppNavigation
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.LocaleAwareContent

/**
 * Main composable for the demo app.
 * 
 * Note: WalletSDK.initialize() and loadKoinModules() must be called BEFORE
 * this composable is rendered:
 * - Android: In AndroidApp.onCreate()
 * - iOS: In MainViewController() before creating ComposeUIViewController
 */
@Composable
internal fun App() {
    AlgoKitTheme {
        LocaleAwareContent {
            AppNavigation()
        }
    }
}

