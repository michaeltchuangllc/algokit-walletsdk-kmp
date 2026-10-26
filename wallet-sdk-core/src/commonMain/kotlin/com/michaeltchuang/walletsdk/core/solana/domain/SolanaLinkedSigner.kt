package com.michaeltchuang.walletsdk.core.solana.domain

import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork

enum class SolanaLinkedSignerSource {
    /** Random key generated on-device. Not recoverable; deleted with its Seed Vault account. */
    GENERATED,

    /** Legacy value from the removed recovery-phrase import; kept so older rows still map. */
    IMPORTED,
}

data class SolanaLinkedSigner(
    val ownerAddress: String,
    val signerAddress: String,
    val source: SolanaLinkedSignerSource,
    val derivationPath: String?,
    val createdAtMs: Long,
)

data class SolanaSignerBalance(
    val lamports: Long,
    val usdcBaseUnits: Long,
) {
    /** True if anything was sent to the session key (it isn't meant to hold funds). */
    val hasFunds: Boolean get() = usdcBaseUnits > 0 || lamports > SolanaClusters.LAMPORTS_PER_SIGNATURE

    /** The key can pay its own sweep fee, so funds can be returned to the Seed Vault. */
    val canSweep: Boolean get() = lamports > SolanaClusters.LAMPORTS_PER_SIGNATURE
}

/**
 * Single source of truth for which Solana cluster the app talks to. Only two clusters are used:
 * Algorand MainNet -> Solana mainnet-beta, everything else (TestNet, FutureNet) -> Solana devnet.
 */
object SolanaClusters {
    const val MAINNET = "mainnet-beta"
    const val DEVNET = "devnet"
    const val MAINNET_RPC_URL = "https://api.mainnet-beta.solana.com"
    const val DEVNET_RPC_URL = "https://api.devnet.solana.com"
    const val USDC_MINT_MAINNET = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDC_MINT_DEVNET = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    const val LAMPORTS_PER_SIGNATURE = 5_000L
    const val LAMPORTS_PER_SOL = 1_000_000_000L

    /**
     * Rent-exempt minimum for a 0-data system account. The runtime rejects any transaction that
     * leaves a system account with a balance in (0, this), so an empty account can't be funded
     * with less, and a funded account can't pay fees that would drop it below this.
     */
    const val RENT_EXEMPT_MINIMUM_LAMPORTS = 890_880L

    /** Seed Vault accounts follow the app-wide network toggle: MainNet -> mainnet-beta, TestNet/FutureNet -> devnet. */
    fun clusterFor(network: AlgorandNetwork): String =
        when (network) {
            AlgorandNetwork.MAINNET -> MAINNET
            AlgorandNetwork.TESTNET, AlgorandNetwork.FUTURENET -> DEVNET
        }

    /** Accepts bare cluster names and MPP CAIP-style ids (`solana:mainnet-beta`); anything not mainnet is devnet. */
    fun isMainnet(cluster: String): Boolean = cluster.contains("mainnet", ignoreCase = true)

    fun rpcUrlFor(cluster: String): String = if (isMainnet(cluster)) MAINNET_RPC_URL else DEVNET_RPC_URL

    fun usdcMintFor(cluster: String): String = if (isMainnet(cluster)) USDC_MINT_MAINNET else USDC_MINT_DEVNET

    /** Solana Explorer defaults to mainnet, so only non-mainnet clusters need the query. */
    fun explorerAddressUrl(
        address: String,
        cluster: String,
    ): String = "https://explorer.solana.com/address/$address" + if (isMainnet(cluster)) "" else "?cluster=$DEVNET"
}
