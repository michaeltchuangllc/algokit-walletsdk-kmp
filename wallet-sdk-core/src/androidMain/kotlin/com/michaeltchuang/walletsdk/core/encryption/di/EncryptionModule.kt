package com.michaeltchuang.walletsdk.core.encryption.di

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.builtins.serializer
import com.michaeltchuang.walletsdk.core.encryption.data.manager.Base64ManagerImpl
import com.michaeltchuang.walletsdk.core.encryption.data.repository.StrongBoxRepositoryImpl
import com.michaeltchuang.walletsdk.core.encryption.domain.manager.AESPlatformManager
import com.michaeltchuang.walletsdk.core.encryption.domain.manager.AESPlatformManagerImpl
import com.michaeltchuang.walletsdk.core.encryption.domain.manager.AndroidEncryptionManager
import com.michaeltchuang.walletsdk.core.encryption.domain.manager.AndroidEncryptionManagerImpl
import com.michaeltchuang.walletsdk.core.encryption.domain.manager.Base64Manager
import com.michaeltchuang.walletsdk.core.encryption.domain.repository.StrongBoxRepository
import com.michaeltchuang.walletsdk.core.encryption.domain.usecase.GetEncryptionSecretKey
import com.michaeltchuang.walletsdk.core.encryption.domain.usecase.GetStrongBoxUsedCheck
import com.michaeltchuang.walletsdk.core.encryption.domain.usecase.SaveStrongBoxUsedCheck
import com.michaeltchuang.walletsdk.core.foundation.cache.PersistentCacheProvider
import com.michaeltchuang.walletsdk.core.foundation.cache.PersistentCacheProviderImpl
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

const val SETTINGS = "algorand_settings"

val encryptionModule =
    module {

        single<SharedPreferences> {
            androidContext().getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        }

        single {
            PersistentCacheProviderImpl(
                sharedPreferences = get(),
            )
        }

        single<PersistentCacheProvider> {
            get<PersistentCacheProviderImpl>()
        }

        // StrongBoxRepository with constructor param from PersistentCacheProvider

        single {
            StrongBoxRepositoryImpl(
                strongBoxUsedStorage =
                    get<PersistentCacheProvider>()
                        .getPersistentCache(Boolean.serializer(), key = "strongbox_used"),
            )
        }

        single<StrongBoxRepository> {
            get<StrongBoxRepositoryImpl>()
        }

    /* single { StrongBoxRepositoryImpl(get()) }
     single<StrongBoxRepository> { get<StrongBoxRepositoryImpl>() }*/

        // Use cases and functional wrappers
        factory {
            GetStrongBoxUsedCheck(get<StrongBoxRepository>()::getStrongBoxUsed)
        }

        factory {
            SaveStrongBoxUsedCheck(get<StrongBoxRepository>()::saveStrongBoxUsed)
        }

        single { AndroidEncryptionManagerImpl(get(), get()) }
        // AndroidEncryptionManager binding
        factory<AndroidEncryptionManager> { get<AndroidEncryptionManagerImpl>() }

        single {
            GetEncryptionSecretKey(get<AndroidEncryptionManager>()::getSecretKey)
        }
        single<Base64Manager> { Base64ManagerImpl() }

        single { AESPlatformManagerImpl(get()) }
        single<AESPlatformManager> { AESPlatformManagerImpl(get()) }
    }
