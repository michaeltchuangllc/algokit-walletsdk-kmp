package com.michaeltchuang.walletsdk.core.network.domain.usecase

import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.EXPLORER_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.EXPLORER_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.EXPLORER_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaClusters

class GetTransactionHistoryUrlUseCase {
    operator fun invoke(
        address: String,
        network: AlgorandNetwork,
        isSolanaAccount: Boolean,
    ): String =
        if (isSolanaAccount) {
            SolanaClusters.explorerAddressUrl(address, SolanaClusters.clusterFor(network))
        } else {
            when (network) {
                AlgorandNetwork.FUTURENET -> "$EXPLORER_FUTURENET_BASE_URL/fnet/account/$address"
                AlgorandNetwork.TESTNET ->
                    "$EXPLORER_TESTNET_BASE_URL/transactions/?transaction_list_address=$address"
                AlgorandNetwork.MAINNET ->
                    "$EXPLORER_MAINNET_BASE_URL/transactions/?transaction_list_address=$address"
            }
        }
}
