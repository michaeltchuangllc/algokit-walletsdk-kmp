package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountRegistrationType
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.custom.GetAccountsCustomInfo
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.DeleteNoAuthAccountUseCase
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.DeleteSolanaAccountUseCase
import com.michaeltchuang.walletsdk.core.passkeys.domain.repository.PasskeyRepository

class NameRegistrationUseCase(
    private val accountAdditionUseCase: AccountAdditionUseCase,
    private val getAccountsCustomInfo: GetAccountsCustomInfo,
    private val getAccountRegistrationTypeUseCase: GetAccountRegistrationTypeUseCase,
    private val getLocalAccountsUseCase: GetLocalAccountsUseCase,
    private val deleteHdKeyAccountUseCase: DeleteHdKeyAccountUseCase,
    private val deleteAlgo25AccountUseCase: DeleteAlgo25AccountUseCase,
    private val deleteFalcon24AccountUseCase: DeleteFalcon24AccountUseCase,
    private val deleteFalcon25AccountUseCase: DeleteFalcon25AccountUseCase,
    private val deleteNoAuthAccountUseCase: DeleteNoAuthAccountUseCase,
    private val deleteSolanaAccountUseCase: DeleteSolanaAccountUseCase,
    private val passkeyRepository: PasskeyRepository,
) {
    suspend fun addNewAccount(accountCreation: AccountCreation) {
        accountAdditionUseCase.addNewAccount(accountCreation)
    }

    suspend fun getAccount(): List<LocalAccount> = getLocalAccountsUseCase()

    suspend fun getAccountLite(): List<AccountLite> {
        val localAccounts = getLocalAccountsUseCase()
        val customInfoMap = getAccountsCustomInfo(localAccounts.map { it.address })
        return localAccounts.map { account ->
            val fallbackName =
                when (account) {
                    is LocalAccount.SeedVault -> account.accountName ?: ""
                    else -> ""
                }
            AccountLite(
                account.address,
                customInfoMap[account.address]?.customName ?: fallbackName,
                getAccountRegistrationTypeUseCase(account),
            )
        }
    }

    suspend fun deleteAccount(address: String) {
        when (getAccountRegistrationTypeUseCase(address)) {
            AccountRegistrationType.Algo25 -> deleteAlgo25AccountUseCase(address)
            AccountRegistrationType.HdKey -> deleteHdKeyAccountUseCase(address)
            AccountRegistrationType.Falcon25 -> deleteFalcon25AccountUseCase(address)
            AccountRegistrationType.Falcon24 -> deleteFalcon24AccountUseCase(address)
            AccountRegistrationType.NoAuth -> deleteNoAuthAccountUseCase(address).getOrThrow()
            AccountRegistrationType.SeedVault -> deleteSolanaAccountUseCase(address).getOrThrow()
            AccountRegistrationType.LedgerBle -> error("Delete is not supported for Ledger BLE accounts.")
            null -> error("Account not found for address: $address")
        }
        passkeyRepository.removePasskeyByAddress(address)
    }
}
