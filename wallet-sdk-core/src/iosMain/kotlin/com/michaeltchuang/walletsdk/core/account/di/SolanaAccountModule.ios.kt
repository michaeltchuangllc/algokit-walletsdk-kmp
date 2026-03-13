package com.michaeltchuang.walletsdk.core.account.di

import com.michaeltchuang.walletsdk.core.account.domain.model.solana.SolanaSeedInfo
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * No-op implementation of SeedVaultRepository for iOS.
 * Since Seed Vault is Android-specific, this provides an empty implementation.
 */
class NoOpSeedVaultRepository : SeedVaultRepository {
    override suspend fun getSolanaSeeds(): List<SolanaSeedInfo> = emptyList()

    override suspend fun getImportedAddresses(addresses: List<String>): Set<String> = emptySet()
}

/**
 * iOS-specific module for Solana/SeedVault dependencies.
 * Since Seed Vault is Android-specific, this provides a no-op implementation.
 */
val solanaAccountModule = module {
    singleOf(::NoOpSeedVaultRepository) { bind<SeedVaultRepository>() }
}
