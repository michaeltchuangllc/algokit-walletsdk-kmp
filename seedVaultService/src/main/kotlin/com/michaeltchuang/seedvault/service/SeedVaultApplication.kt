package com.michaeltchuang.seedvault.service

import android.app.Application
import com.michaeltchuang.seedvault.service.di.seedVaultModule
import com.michaeltchuang.walletsdk.core.encryption.di.encryptionModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class SeedVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SeedVaultApplication)
            // wallet-sdk-core looks up its Android Keystore encryption through Koin
            modules(encryptionModule, seedVaultModule)
        }
    }
}
