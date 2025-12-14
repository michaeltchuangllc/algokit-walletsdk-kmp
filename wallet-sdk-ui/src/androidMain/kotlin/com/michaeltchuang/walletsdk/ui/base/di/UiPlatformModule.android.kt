package com.michaeltchuang.walletsdk.ui.base.di

import com.michaeltchuang.walletsdk.ui.passkeys.di.passkeysUiModule
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun uiPlatformModule(): Module =
    module {
        includes(passkeysUiModule)
    }
