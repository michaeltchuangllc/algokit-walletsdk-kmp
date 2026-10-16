package com.michaeltchuang.walletsdk.core.account.domain.repository.solana

import com.michaeltchuang.walletsdk.core.account.domain.model.solana.SolanaSeedInfo

/**
 * Repository for fetching Solana accounts from the Seed Vault.
 * Platform-specific implementations handle the actual Seed Vault access.
 */
interface SeedVaultRepository {
    /**
     * Fetches all authorized Solana seeds and their accounts from Seed Vault.
     * @return List of SolanaSeedInfo containing seeds and their associated accounts
     */
    suspend fun getSolanaSeeds(): List<SolanaSeedInfo>

    /**
     * Checks if any Solana accounts are already imported (exist in local database).
     * @param addresses List of addresses to check
     * @return Set of addresses that are already imported
     */
    suspend fun getImportedAddresses(addresses: List<String>): Set<String>

    /**
     * Checks if there are any unauthorized seeds in the Seed Vault.
     * This helps determine if we need to show an "Authorize Seed" button.
     * @return true if there are unauthorized seeds available
     */
    fun hasUnauthorizedSeeds(): Boolean
}
