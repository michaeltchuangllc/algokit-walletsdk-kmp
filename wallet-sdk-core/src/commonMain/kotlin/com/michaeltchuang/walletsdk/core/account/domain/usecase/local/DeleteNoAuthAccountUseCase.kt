package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.repository.local.NoAuthAccountRepository

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
