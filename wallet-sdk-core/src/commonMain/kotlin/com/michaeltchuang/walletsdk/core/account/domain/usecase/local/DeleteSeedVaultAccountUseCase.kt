package com.michaeltchuang.walletsdk.core.account.domain.usecase.local

import com.michaeltchuang.walletsdk.core.account.domain.repository.local.SeedVaultAccountRepository
import com.michaeltchuang.walletsdk.core.solana.domain.ReturnSolanaSessionKeyFundsUseCase
import kotlinx.coroutines.CancellationException

/**
 * Deletes a Seed Vault account. Its session key is cascade-deleted with it, so any funds sent to
 * the key by mistake are returned to the Seed Vault account first. If that can't be done (offline,
 * not enough SOL for fees, transaction failed), the account is kept and the error is returned.
 */
class DeleteSeedVaultAccountUseCase(
    private val seedVaultAccountRepository: SeedVaultAccountRepository,
    private val returnSessionKeyFunds: ReturnSolanaSessionKeyFundsUseCase,
) {
    suspend operator fun invoke(address: String): Result<Unit> =
        try {
            val account = seedVaultAccountRepository.getAll().firstOrNull { it.address == address }
            if (account?.chainId == "501") {
                returnSessionKeyFunds(address)
            }
            seedVaultAccountRepository.deleteAccountByAddress(address)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
