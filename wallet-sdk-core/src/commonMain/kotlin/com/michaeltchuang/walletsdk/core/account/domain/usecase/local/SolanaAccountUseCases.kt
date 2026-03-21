package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.SolanaAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.solana.SolanaSeedInfo
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SolanaAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository

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
    suspend operator fun invoke(): List<SolanaSeedInfo> = seedVaultRepository.getSolanaSeeds()
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
    suspend operator fun invoke(addresses: List<String>): Set<String> = seedVaultRepository.getImportedAddresses(addresses)
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
        val newAccounts =
            accounts.filter { account ->
                !solanaAccountRepository.isAddressExists(account.address)
            }
        solanaAccountRepository.addAccounts(newAccounts)
    }
}

/**
 * Use case for fully syncing Solana accounts from Seed Vault into local database.
 * Local Solana accounts are selectively updated to match the latest Seed Vault content.
 */
class SyncSolanaAccountsFromSeedVaultUseCase(
    private val seedVaultRepository: SeedVaultRepository,
    private val solanaAccountRepository: SolanaAccountRepository,
) {
    suspend operator fun invoke() {
        val seeds = seedVaultRepository.getSolanaSeeds()
        val latestSolanaAccounts =
            seeds
                .flatMap { it.accounts }
                .map { account ->
                    SolanaAccount(
                        publicKey = account.address,
                        address = account.address,
                        chainId = extractChainIdFromDerivationPath(account.derivationPath),
                        accountName = account.accountName,
                    )
                }.distinctBy { it.address }
        val latestAddresses = latestSolanaAccounts.map { it.address }.toSet()
        val localAccounts = solanaAccountRepository.getAll()
        val localAccountsByAddress = localAccounts.associateBy { it.address }

        localAccounts
            .filter { it.address !in latestAddresses }
            .forEach { solanaAccountRepository.deleteAccountByAddress(it.address) }

        val accountsToRename =
            latestSolanaAccounts.filter { latestAccount ->
                val localAccount = localAccountsByAddress[latestAccount.address]
                localAccount != null && localAccount.accountName != latestAccount.accountName
            }
        accountsToRename.forEach { account ->
            solanaAccountRepository.updateAccountNameByAddress(account.address, account.accountName)
        }
    }

    private fun extractChainIdFromDerivationPath(derivationPath: String): String =
        derivationPath
            .split("/")
            .getOrNull(2)
            ?.replace("'", "")
            ?: "501"
}
