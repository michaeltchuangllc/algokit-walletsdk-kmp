package com.michaeltchuang.walletsdk.core.railmpp.domain.usecase

import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import kotlinx.coroutines.flow.first

class GetSessionVaultContextUseCase(
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase,
) {
    suspend operator fun invoke(): SessionVaultContext {
        val network = getCurrentNetworkUseCase().first()
        val config = getSessionVaultConfigUseCase(network)
        return SessionVaultContext(
            network = network,
            appId = config.appId,
            appAddress = config.appAddress,
            usdcAssetId = MppPayments.usdcAssetIdForAppId(config.appId),
        )
    }
}

data class SessionVaultContext(
    val network: AlgorandNetwork,
    val appId: Long,
    val appAddress: String,
    val usdcAssetId: Long,
) {
    val networkLabel: String
        get() = network.displayName
}
