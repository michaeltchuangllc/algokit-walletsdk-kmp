package com.michaeltchuang.walletsdk.demo.di

import com.michaeltchuang.walletsdk.core.foundation.di.walletSdkCoreModules
import com.michaeltchuang.walletsdk.ui.base.di.walletSdkUiModules
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.includes
import org.koin.dsl.koinConfiguration

expect fun nativeConfig(): KoinAppDeclaration

val initKoinConfig =
    koinConfiguration {
        includes(nativeConfig())
        // Include SDK core modules and demo app modules
        modules(walletSdkCoreModules + provideViewModelModules + walletSdkUiModules)
    }
