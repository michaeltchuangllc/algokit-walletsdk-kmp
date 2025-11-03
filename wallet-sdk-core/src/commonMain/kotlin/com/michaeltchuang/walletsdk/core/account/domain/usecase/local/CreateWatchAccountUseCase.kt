package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.model.core.CreateAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.CustomAccountInfo
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.repository.local.NoAuthAccountRepository
import com.michaeltchuang.walletsdk.core.account.domain.usecase.custom.SetAccountCustomInfo

class CreateWatchAccountUseCase(
    private val setCustomInfo: SetAccountCustomInfo,
    private val noAuthAccountRepository: NoAuthAccountRepository,
    private val getLocalAccounts: GetLocalAccounts,
) {
    suspend operator fun invoke(createAccount: CreateAccount): Result<Unit> {
        return try {
            // Check if address already exists in ANY account type
            val existingAccounts = getLocalAccounts()
            val addressExists = existingAccounts.any { it.algoAddress == createAccount.address }

            if (addressExists) {
                Result.failure(Exception("An account with this address already exists"))
            } else {
                // Create and add the watch account
                val watchAccount = LocalAccount.NoAuth(algoAddress = createAccount.address)
                setCustomInfo(
                    CustomAccountInfo(
                        createAccount.address,
                        createAccount.customName,
                        createAccount.orderIndex,
                        isBackedUp = true
                    )
                )
                noAuthAccountRepository.addAccount(watchAccount)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class DeleteNoAuthAccountUseCase(
    private val noAuthAccountRepository: NoAuthAccountRepository,
) {
    suspend operator fun invoke(address: String): Result<Unit> {
        return try {
            noAuthAccountRepository.deleteAccount(address)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}