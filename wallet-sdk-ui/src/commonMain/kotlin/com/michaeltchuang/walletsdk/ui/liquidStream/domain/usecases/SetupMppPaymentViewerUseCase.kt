package com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases

import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import kotlinx.coroutines.CoroutineScope

expect class SetupMppPaymentViewerUseCase(
    getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
    getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
) {
    fun startViewerOnChainRefresh(
        scope: CoroutineScope,
        viewerAddress: String,
        hostAddress: String?,
        sessionVaultAppId: Long,
        authorizedSignerPublicKey: ByteArray? = null,
        setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
    )

    fun stop()
}
