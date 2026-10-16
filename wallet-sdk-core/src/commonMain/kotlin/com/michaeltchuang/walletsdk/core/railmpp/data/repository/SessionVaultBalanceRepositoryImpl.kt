package com.michaeltchuang.walletsdk.core.railmpp.data.repository

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.SessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class SessionVaultBalanceRepositoryImpl : SessionVaultBalanceRepository {
    override suspend fun getRemainingBalance(params: SessionVaultBalanceRepository.GetRemainingBalanceParams): Result<Long> =
        runCatching {
            withContext(Dispatchers.IO) {
                MppPayments.getRemainingBalanceFromSessionVault(
                    viewerAddress = params.viewerAddress,
                )
            }
        }
}
