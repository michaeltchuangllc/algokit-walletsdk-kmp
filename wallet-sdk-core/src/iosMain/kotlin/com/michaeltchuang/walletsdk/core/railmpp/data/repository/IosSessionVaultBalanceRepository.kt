package com.michaeltchuang.walletsdk.core.railmpp.data.repository

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.SessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments

class IosSessionVaultBalanceRepository : SessionVaultBalanceRepository {
    override suspend fun getRemainingBalance(params: SessionVaultBalanceRepository.GetRemainingBalanceParams): Result<Long> =
        runCatching {
            MppPayments.getRemainingBalanceFromSessionVault(
                viewerAddress = params.viewerAddress,
                hostAddress = params.hostAddress,
                appId = params.appId,
                algodUrl = params.algodUrl
            )
        }
}
