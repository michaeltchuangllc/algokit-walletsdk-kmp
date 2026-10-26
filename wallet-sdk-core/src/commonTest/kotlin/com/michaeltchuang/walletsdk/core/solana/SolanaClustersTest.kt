package com.michaeltchuang.walletsdk.core.solana

import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetTransactionHistoryUrlUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.solana.domain.SolanaClusters
import kotlin.test.Test
import kotlin.test.assertEquals

class SolanaClustersTest {
    @Test
    fun mainnetMapsToMainnetBeta() {
        val cluster = SolanaClusters.clusterFor(AlgorandNetwork.MAINNET)
        assertEquals(SolanaClusters.MAINNET, cluster)
        assertEquals(SolanaClusters.MAINNET_RPC_URL, SolanaClusters.rpcUrlFor(cluster))
        assertEquals(SolanaClusters.USDC_MINT_MAINNET, SolanaClusters.usdcMintFor(cluster))
    }

    @Test
    fun testnetAndFuturenetMapToDevnet() {
        listOf(AlgorandNetwork.TESTNET, AlgorandNetwork.FUTURENET).forEach { network ->
            val cluster = SolanaClusters.clusterFor(network)
            assertEquals(SolanaClusters.DEVNET, cluster)
            assertEquals(SolanaClusters.DEVNET_RPC_URL, SolanaClusters.rpcUrlFor(cluster))
            assertEquals(SolanaClusters.USDC_MINT_DEVNET, SolanaClusters.usdcMintFor(cluster))
        }
    }

    @Test
    fun mppNetworkIdsResolveToMatchingRpc() {
        assertEquals(SolanaClusters.MAINNET_RPC_URL, SolanaClusters.rpcUrlFor(MppNetworks.SOLANA_MAINNET))
        assertEquals(SolanaClusters.DEVNET_RPC_URL, SolanaClusters.rpcUrlFor(MppNetworks.SOLANA_DEVNET))
        // Solana testnet cluster is never used; anything that isn't mainnet goes to devnet.
        assertEquals(SolanaClusters.DEVNET_RPC_URL, SolanaClusters.rpcUrlFor("testnet"))
    }

    @Test
    fun explorerLinkMatchesCluster() {
        val useCase = GetTransactionHistoryUrlUseCase()
        assertEquals(
            "https://explorer.solana.com/address/abc",
            useCase("abc", AlgorandNetwork.MAINNET, isSolanaAccount = true),
        )
        listOf(AlgorandNetwork.TESTNET, AlgorandNetwork.FUTURENET).forEach { network ->
            assertEquals(
                "https://explorer.solana.com/address/abc?cluster=devnet",
                useCase("abc", network, isSolanaAccount = true),
            )
        }
    }
}
