package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccounts

class GetAccountRegistrationTypeUseCase(
    private val getLocalAccounts: GetLocalAccounts,
) : GetAccountRegistrationType {
    override suspend fun invoke(address: String): AccountRegistrationType? =
        when (getLocalAccounts().firstOrNull { it.algoAddress == address }) {
            is LocalAccount.Algo25 -> AccountRegistrationType.Algo25
            is LocalAccount.Falcon24 -> AccountRegistrationType.Falcon24
            is LocalAccount.LedgerBle -> AccountRegistrationType.LedgerBle
            is LocalAccount.NoAuth -> AccountRegistrationType.NoAuth
            is LocalAccount.SeedVault -> AccountRegistrationType.SeedVault
            is LocalAccount.HdKey -> AccountRegistrationType.HdKey
            else -> null
        }

    override fun invoke(account: LocalAccount): AccountRegistrationType =
        when (account) {
            is LocalAccount.Algo25 -> AccountRegistrationType.Algo25
            is LocalAccount.Falcon24 -> AccountRegistrationType.Falcon24
            is LocalAccount.LedgerBle -> AccountRegistrationType.LedgerBle
            is LocalAccount.NoAuth -> AccountRegistrationType.NoAuth
            is LocalAccount.SeedVault -> AccountRegistrationType.SeedVault
            is LocalAccount.HdKey -> AccountRegistrationType.HdKey
        }
}
