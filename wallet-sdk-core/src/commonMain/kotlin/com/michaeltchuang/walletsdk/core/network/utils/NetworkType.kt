package com.michaeltchuang.walletsdk.core.network.utils

import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.EXPLORER_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.EXPLORER_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.EXPLORER_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.INDEXER_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.INDEXER_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.INDEXER_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.NODE_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.PERA_API_FUTURENET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.PERA_API_MAINNET_BASE_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.PERA_API_TESTNET_BASE_URL
import com.michaeltchuang.walletsdk.core.network.domain.provideNodePreferenceRepository
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

suspend fun getIndexerBaseUrl(): String =
    CoroutineScope(Dispatchers.IO)
        .async {
            val networkType = provideNodePreferenceRepository().getSavedNodePreferenceFlow().first()
            when (networkType) {
                AlgorandNetwork.FUTURENET -> INDEXER_FUTURENET_BASE_URL
                AlgorandNetwork.TESTNET -> INDEXER_TESTNET_BASE_URL
                AlgorandNetwork.MAINNET -> INDEXER_MAINNET_BASE_URL
            }
        }.await()

suspend fun getNodeBaseUrl(): String =
    CoroutineScope(Dispatchers.IO)
        .async {
            val networkType = provideNodePreferenceRepository().getSavedNodePreferenceFlow().first()
            when (networkType) {
                AlgorandNetwork.FUTURENET -> NODE_FUTURENET_BASE_URL
                AlgorandNetwork.TESTNET -> NODE_TESTNET_BASE_URL
                AlgorandNetwork.MAINNET -> NODE_MAINNET_BASE_URL
            }
        }.await()

suspend fun getExplorerBaseUrl(): String =
    CoroutineScope(Dispatchers.IO)
        .async {
            val networkType = provideNodePreferenceRepository().getSavedNodePreferenceFlow().first()
            when (networkType) {
                AlgorandNetwork.FUTURENET -> EXPLORER_FUTURENET_BASE_URL
                AlgorandNetwork.TESTNET -> EXPLORER_TESTNET_BASE_URL
                AlgorandNetwork.MAINNET -> EXPLORER_MAINNET_BASE_URL
            }
        }.await()

suspend fun getPeraWalletBaseUrl(): String =
    CoroutineScope(Dispatchers.IO)
        .async {
            val networkType = provideNodePreferenceRepository().getSavedNodePreferenceFlow().first()
            when (networkType) {
                AlgorandNetwork.FUTURENET -> PERA_API_FUTURENET_BASE_URL
                AlgorandNetwork.TESTNET -> PERA_API_TESTNET_BASE_URL
                AlgorandNetwork.MAINNET -> PERA_API_MAINNET_BASE_URL
            }
        }.await()
