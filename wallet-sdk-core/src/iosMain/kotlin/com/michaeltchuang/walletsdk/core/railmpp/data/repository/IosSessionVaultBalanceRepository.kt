package com.michaeltchuang.walletsdk.core.railmpp.data.repository

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.SessionVaultBalanceRepository

class IosSessionVaultBalanceRepository(
    private val mockRemainingBalanceMicroUsdc: Long = DEFAULT_MOCK_REMAINING_BALANCE_MICRO_USDC,
) : SessionVaultBalanceRepository {
    override suspend fun getRemainingBalance(params: SessionVaultBalanceRepository.GetRemainingBalanceParams): Result<Long> =
        runCatching {
            mockRemainingBalanceMicroUsdc.coerceAtLeast(0L)
        }

    companion object Companion {
        const val DEFAULT_MOCK_REMAINING_BALANCE_MICRO_USDC: Long = 1_000_000L
    }
}
