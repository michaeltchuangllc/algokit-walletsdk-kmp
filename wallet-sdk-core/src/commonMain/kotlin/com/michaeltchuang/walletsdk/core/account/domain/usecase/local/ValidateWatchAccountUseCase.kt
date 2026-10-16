package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

class ValidateWatchAccountUseCase(
    private val getLocalAccounts: GetLocalAccounts,
) {
    suspend operator fun invoke(address: String): Result<Unit> =
        try {
            // Check if address already exists in ANY account type
            val existingAccounts = getLocalAccounts()
            val addressExists = existingAccounts.any { it.address == address }

            if (addressExists) {
                Result.failure(AccountAlreadyExistsException())
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
}

class AccountAlreadyExistsException : Exception("ACCOUNT_ALREADY_EXISTS")
