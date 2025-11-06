package com.michaeltchuang.walletsdk.core

import com.michaeltchuang.walletsdk.core.foundation.di.walletSdkCoreModules
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.KoinAppDeclaration

object WalletSDK {
    private var koinApp: KoinApplication? = null

    fun init(config: KoinAppDeclaration? = null): WalletSDKManager {
        if (koinApp != null) {
            throw IllegalStateException("WalletSDK is already initialized. Call shutdown() first if you need to reinitialize.")
        }

        koinApp =
            startKoin {
                modules(walletSdkCoreModules)
                config?.invoke(this)
            }

        return WalletSDKManagerImpl()
    }

    fun shutdown() {
        koinApp?.close()
        koinApp = null
        try {
            stopKoin()
        } catch (e: Exception) {
            // Koin might have been initialized externally
        }
    }

    fun isInitialized(): Boolean = koinApp != null
}
