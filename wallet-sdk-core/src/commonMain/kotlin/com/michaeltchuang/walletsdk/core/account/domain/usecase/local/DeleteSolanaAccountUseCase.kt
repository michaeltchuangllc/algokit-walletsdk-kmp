package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SolanaAccountRepository

class DeleteSolanaAccountUseCase(
    private val solanaAccountRepository: SolanaAccountRepository,
) {
    suspend operator fun invoke(address: String): Result<Unit> =
        try {
            solanaAccountRepository.deleteAccountByAddress(address)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
}
