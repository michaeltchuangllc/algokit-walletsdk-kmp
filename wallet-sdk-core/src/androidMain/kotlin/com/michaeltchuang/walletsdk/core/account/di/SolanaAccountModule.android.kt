package com.michaeltchuang.walletsdk.core.account.di

import com.michaeltchuang.walletsdk.core.account.data.repository.AlgorandSeedVaultRepositoryImpl
import com.michaeltchuang.walletsdk.core.account.data.repository.SeedVaultRepositoryImpl
import com.michaeltchuang.walletsdk.core.account.domain.repository.algorand.AlgorandSeedVaultRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Android-specific module for Solana/SeedVault dependencies.
 */
val solanaAccountModule =
    module {
        single<AlgorandSeedVaultRepository> { AlgorandSeedVaultRepositoryImpl(androidContext()) }
        singleOf(::SeedVaultRepositoryImpl) { bind<SeedVaultRepository>() }
    }
