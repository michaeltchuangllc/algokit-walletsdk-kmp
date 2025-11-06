package com.michaeltchuang.walletsdk.core.foundation.di

import com.michaeltchuang.walletsdk.core.WalletSDKManager
import com.michaeltchuang.walletsdk.core.WalletSDKManagerImpl
import com.michaeltchuang.walletsdk.core.foundation.commonModule
import com.michaeltchuang.walletsdk.core.foundation.delegateModule
import com.michaeltchuang.walletsdk.core.foundation.json.jsonModule
import com.michaeltchuang.walletsdk.core.network.di.networkModule
import org.koin.dsl.module

private val walletSdkManagerModule =
    module {
        single<WalletSDKManager> { WalletSDKManagerImpl() }
    }

val foundationModules =
    listOf(
        delegateModule,
        commonModule,
        platformKoinModule(),
        jsonModule,
        networkModule,
        walletSdkManagerModule,
    )
