package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.SolanaAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.solana.SolanaSeedInfo
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SolanaAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.repository.solana.SeedVaultRepository
import com.michaeltchuang.walletsdk.core.solana.domain.EnsureSolanaSessionKeyUseCase
import com.michaeltchuang.walletsdk.core.solana.domain.ReturnSolanaSessionKeyFundsUseCase
import kotlinx.coroutines.CancellationException

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
    private val ensureSolanaSessionKeyUseCase: EnsureSolanaSessionKeyUseCase,
) {
    /**
     * Imports Solana accounts to the local database. Every Seed Vault account gets a session
     * signing key at import time so Liquid Stream / MPP vouchers can be signed without prompts.
     * @param accounts List of SolanaAccount to import
     */
    suspend operator fun invoke(accounts: List<SolanaAccount>) {
        // Filter out already imported accounts
        val newAccounts =
            accounts.filter { account ->
                !solanaAccountRepository.isAddressExists(account.address)
            }
        solanaAccountRepository.addAccounts(newAccounts)
        accounts.forEach { ensureSolanaSessionKeyUseCase(it.address) }
    }
}

/**
 * Use case for fully syncing Solana accounts from Seed Vault into local database.
 * Local Solana accounts are selectively updated to match the latest Seed Vault content.
 */
class SyncSolanaAccountsFromSeedVaultUseCase(
    private val seedVaultRepository: SeedVaultRepository,
    private val solanaAccountRepository: SolanaAccountRepository,
    private val ensureSolanaSessionKeyUseCase: EnsureSolanaSessionKeyUseCase,
    private val returnSessionKeyFunds: ReturnSolanaSessionKeyFundsUseCase,
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

        // Deleting the account cascade-deletes its session key. The account is gone from Seed Vault
        // (its seed may be wiped), so sweeping to it isn't safe; keep any account whose key still
        // holds funds (or whose balance can't be checked) and retry on the next sync.
        localAccounts
            .filter { it.address !in latestAddresses }
            .filterNot { sessionKeyMayHoldFunds(it.address) }
            .forEach { solanaAccountRepository.deleteAccountByAddress(it.address) }

        val accountsToRename =
            latestSolanaAccounts.filter { latestAccount ->
                val localAccount = localAccountsByAddress[latestAccount.address]
                localAccount != null && localAccount.accountName != latestAccount.accountName
            }
        accountsToRename.forEach { account ->
            solanaAccountRepository.updateAccountNameByAddress(account.address, account.accountName)
        }

        // Backfill session keys for Seed Vault accounts added before keys were auto-generated.
        localAccounts
            .filter { it.address in latestAddresses }
            .forEach { ensureSolanaSessionKeyUseCase(it.address) }
    }

    private suspend fun sessionKeyMayHoldFunds(address: String): Boolean =
        try {
            returnSessionKeyFunds.hasFunds(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }

    private fun extractChainIdFromDerivationPath(derivationPath: String): String =
        derivationPath
            .split("/")
            .getOrNull(2)
            ?.replace("'", "")
            ?: "501"
}
