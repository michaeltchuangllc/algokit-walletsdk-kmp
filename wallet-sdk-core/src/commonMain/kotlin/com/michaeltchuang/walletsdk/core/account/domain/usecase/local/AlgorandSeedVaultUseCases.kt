package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.algorand.AlgorandSeedVaultSeed
import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SeedVaultAccountRepository
import com.michaeltchuang.walletsdk.core.foundation.utils.Chain
import kotlinx.coroutines.CancellationException

fun interface GetAlgorandSeedVaultSeeds {
    suspend operator fun invoke(): List<AlgorandSeedVaultSeed>
}

fun interface ImportAlgorandSeedVaultAccounts {
    suspend operator fun invoke(accounts: List<SeedVaultAccount>)
}

class SyncAlgorandAccountsFromSeedVaultUseCase(
    private val getAlgorandSeedVaultSeeds: GetAlgorandSeedVaultSeeds,
    private val seedVaultAccountRepository: SeedVaultAccountRepository,
) {
    suspend operator fun invoke() {
        try {
            val seeds = getAlgorandSeedVaultSeeds()
            val latestAddresses = seeds.flatMap { it.accounts }.map { it.address }.toSet()
            val localAccounts = seedVaultAccountRepository.getAll().filter { it.chainId == Chain.ALGORAND.id }

            localAccounts
                .filter { it.address !in latestAddresses }
                .forEach { seedVaultAccountRepository.deleteAccountByAddress(it.address) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Seed Vault might be unavailable on this device or permission not granted
        }
    }
}
