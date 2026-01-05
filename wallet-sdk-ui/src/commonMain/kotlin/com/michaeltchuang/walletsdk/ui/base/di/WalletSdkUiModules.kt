package com.michaeltchuang.walletsdk.ui.base.di

import com.michaeltchuang.walletsdk.core.foundation.di.walletSdkCoreModules
import com.michaeltchuang.walletsdk.ui.accountdetails.di.accountDetailModules
import com.michaeltchuang.walletsdk.ui.liquidAuth.di.liquidAuthModules
import com.michaeltchuang.walletsdk.ui.onboarding.di.onboardingModules
import com.michaeltchuang.walletsdk.ui.qrscanner.di.qrScannerModules
import com.michaeltchuang.walletsdk.ui.settings.di.settingsModules
import com.michaeltchuang.walletsdk.ui.signing.di.signingModules

val walletSdkUiModules =
    buildList {
        addAll(walletSdkCoreModules)
        add(uiPlatformModule())
        addAll(onboardingModules)
        addAll(qrScannerModules)
        addAll(accountDetailModules)
        addAll(settingsModules)
        addAll(signingModules)
        addAll(liquidAuthModules)
    }
