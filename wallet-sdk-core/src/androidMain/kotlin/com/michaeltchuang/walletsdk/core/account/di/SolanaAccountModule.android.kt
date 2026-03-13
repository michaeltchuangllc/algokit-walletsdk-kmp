package com.michaeltchuang.walletsdk.core.account.di

import com.michaeltchuang.walletsdk.core.account.data.repository.SeedVaultRepositoryImpl
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Android-specific module for Solana/SeedVault dependencies.
 */
val solanaAccountModule = module {
    singleOf(::SeedVaultRepositoryImpl) { bind<SeedVaultRepository>() }
}
