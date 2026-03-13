package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.SolanaAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SolanaAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository
import com.michaeltchuang.walletsdk.core.account.domain.model.solana.SolanaSeedInfo

/**
 * Use case for fetching Solana accounts from Seed Vault.
 */
class GetSolanaAccountsFromSeedVaultUseCase(
    private val seedVaultRepository: SeedVaultRepository,
) {
    /**
     * Fetches all Solana accounts from Seed Vault.
     * @return List of SolanaSeedInfo containing seeds and their accounts
     */
    suspend operator fun invoke(): List<SolanaSeedInfo> {
        return seedVaultRepository.getSolanaSeeds()
    }
}

/**
 * Use case for getting imported Solana accounts.
 */
class GetImportedSolanaAddressesUseCase(
    private val seedVaultRepository: SeedVaultRepository,
) {
    /**
     * Checks which addresses are already imported.
     * @param addresses List of addresses to check
     * @return Set of addresses that are already imported
     */
    suspend operator fun invoke(addresses: List<String>): Set<String> {
        return seedVaultRepository.getImportedAddresses(addresses)
    }
}

/**
 * Use case for importing Solana accounts to local database.
 */
class ImportSolanaAccountsUseCase(
    private val solanaAccountRepository: SolanaAccountRepository,
) {
    /**
     * Imports Solana accounts to the local database.
     * @param accounts List of SolanaAccount to import
     */
    suspend operator fun invoke(accounts: List<SolanaAccount>) {
        // Filter out already imported accounts
        val newAccounts = accounts.filter { account ->
            !solanaAccountRepository.isAddressExists(account.address)
        }
        solanaAccountRepository.addAccounts(newAccounts)
    }
}
