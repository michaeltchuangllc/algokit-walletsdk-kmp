package com.michaeltchuang.walletsdk.core.railmpp.domain.usecase

import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants

class GetSessionVaultConfigUseCase {
    operator fun invoke(network: AlgorandNetwork): SessionVaultConfig =
        when (network) {
            AlgorandNetwork.MAINNET ->
                SessionVaultConfig(
                    appId = RailMppConstants.MAINNET_MPP_SESSION_VAULT_APP_ID,
                    appAddress = RailMppConstants.MAINNET_MPP_SESSION_VAULT_APP_ADDRESS,
                )
            AlgorandNetwork.TESTNET ->
                SessionVaultConfig(
                    appId = RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ID,
                    appAddress = RailMppConstants.TESTNET_MPP_SESSION_VAULT_APP_ADDRESS,
                )
            AlgorandNetwork.FUTURENET ->
                SessionVaultConfig(
                    appId = RailMppConstants.FUTURENET_MPP_SESSION_VAULT_APP_ID,
                    appAddress = RailMppConstants.FUTURENET_MPP_SESSION_VAULT_APP_ADDRESS,
                )
        }

    data class SessionVaultConfig(
        val appId: Long,
        val appAddress: String,
    )
}
