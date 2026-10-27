package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.local.SeedVaultAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SeedVaultAccountRepository
import com.michaeltchuang.walletsdk.core.foundation.utils.Chain

internal class ImportAlgorandSeedVaultAccountsUseCase(
    private val seedVaultAccountRepository: SeedVaultAccountRepository,
) : ImportAlgorandSeedVaultAccounts {
    override suspend fun invoke(accounts: List<SeedVaultAccount>) {
        require(accounts.all { it.chainId == Chain.ALGORAND.id }) {
            "Only Algorand (chain ${Chain.ALGORAND.id}) accounts can be imported"
        }
        val newAccounts =
            accounts
                .distinctBy { it.address }
                .filterNot { seedVaultAccountRepository.isAddressExists(it.address) }
        seedVaultAccountRepository.addAccounts(newAccounts)
    }
}
