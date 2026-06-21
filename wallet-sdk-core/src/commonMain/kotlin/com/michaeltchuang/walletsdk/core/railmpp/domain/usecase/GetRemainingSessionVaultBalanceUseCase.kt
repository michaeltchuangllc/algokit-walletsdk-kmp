package com.michaeltchuang.walletsdk.core.railmpp.domain.usecase

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.SessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants

/**
 * Reads the remaining Session Vault balance.
 */
class GetRemainingSessionVaultBalanceUseCase(
    private val sessionVaultBalanceRepository: SessionVaultBalanceRepository,
) {
    suspend operator fun invoke(params: Params): Result<Long> =
        sessionVaultBalanceRepository.getRemainingBalance(
            SessionVaultBalanceRepository.GetRemainingBalanceParams(
                viewerAddress = params.viewerAddress,
                hostAddress = params.hostAddress,
                appId = params.appId,
                algodUrl = params.algodUrl,
            ),
        )

    class Params(
        val viewerAddress: String,
        val hostAddress: String,
        val appId: Long = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
        val algodUrl: String? = null,
        val authorizedSignerPublicKey: ByteArray? = null,
    )
}
