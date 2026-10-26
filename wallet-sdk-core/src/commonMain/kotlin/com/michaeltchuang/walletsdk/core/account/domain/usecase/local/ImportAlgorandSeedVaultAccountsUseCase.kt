package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SeedVaultAccountRepository

internal class ImportAlgorandSeedVaultAccountsUseCase(
    private val seedVaultAccountRepository: SeedVaultAccountRepository,
) : ImportAlgorandSeedVaultAccounts {
    override suspend fun invoke(accounts: List<SeedVaultAccount>) {
        require(accounts.all { it.chainId == "283" }) { "Only Algorand (chain 283) accounts can be imported" }
        val newAccounts =
            accounts
                .distinctBy { it.address }
                .filterNot { seedVaultAccountRepository.isAddressExists(it.address) }
        seedVaultAccountRepository.addAccounts(newAccounts)
    }
}
