package com.michaeltchuang.walletsdk.core.account.domain.usecase.core

import com.michaeltchuang.walletsdk.core.account.domain.model.core.AccountCreation
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.custom.GetAccountsCustomInfo
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.DeleteNoAuthAccountUseCase

class NameRegistrationUseCase(
    private val accountAdditionUseCase: AccountAdditionUseCase,
    /*   private val getLocalAccountsUseCase: GetAlgo25AccountUseCase,
       private val getHdKeyAccountUseCase: GetHdKeyAccountUseCase,*/
    private val getAccountsCustomInfo: GetAccountsCustomInfo,
    private val getAccountRegistrationTypeUseCase: GetAccountRegistrationTypeUseCase,
    private val getLocalAccountsUseCase: GetLocalAccountsUseCase,
    private val deleteHdKeyAccountUseCase: DeleteHdKeyAccountUseCase,
    private val deleteAlgo25AccountUseCase: DeleteAlgo25AccountUseCase,
    private val deleteFalcon24AccountUseCase: DeleteFalcon24AccountUseCase,
    private val deleteNoAuthAccountUseCase: DeleteNoAuthAccountUseCase,
) {
    suspend fun addNewAccount(accountCreation: AccountCreation) {
        accountAdditionUseCase.addNewAccount(accountCreation)
    }

    suspend fun getAccount(): List<LocalAccount> = getLocalAccountsUseCase()

    suspend fun getAccountLite(): List<AccountLite> {
        val localAccounts = getLocalAccountsUseCase()
        val customInfoMap = getAccountsCustomInfo(localAccounts.map { it.algoAddress })
        return localAccounts.map { account ->
            AccountLite(
                account.algoAddress,
                customInfoMap[account.algoAddress]?.customName ?: "",
                getAccountRegistrationTypeUseCase(account),
            )
        }
    }

    suspend fun deleteAccount(address: String) {
        deleteAlgo25AccountUseCase(address)
        deleteHdKeyAccountUseCase(address)
        deleteFalcon24AccountUseCase(address)
        deleteNoAuthAccountUseCase(address)
    }
}
