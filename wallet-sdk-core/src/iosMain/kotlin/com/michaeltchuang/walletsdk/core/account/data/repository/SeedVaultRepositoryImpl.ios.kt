package com.michaeltchuang.walletsdk.core.account.data.repository

import com.michaeltchuang.walletsdk.core.account.domain.model.solana.SolanaSeedInfo
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SolanaAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository

/**
 * iOS implementation of SeedVaultRepository.
 * Since Seed Vault is Android-specific, this implementation returns empty lists.
 */
class SeedVaultRepositoryImpl(
    private val solanaAccountRepository: SolanaAccountRepository,
) : SeedVaultRepository {
    override suspend fun getSolanaSeeds(): List<SolanaSeedInfo> {
        // Seed Vault is Android-specific, return empty list on iOS
        return emptyList()
    }

    override suspend fun getImportedAddresses(addresses: List<String>): Set<String> {
        val importedAddresses = mutableSetOf<String>()
        for (address in addresses) {
            if (solanaAccountRepository.isAddressExists(address)) {
                importedAddresses.add(address)
            }
        }
        return importedAddresses
    }

    override fun hasUnauthorizedSeeds(): Boolean {
        // Seed Vault is Android-specific, always return false on iOS
        return false
    }
}
