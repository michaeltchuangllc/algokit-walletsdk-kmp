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

object SolanaClusters {
    const val USDC_MINT_MAINNET = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDC_MINT_DEVNET = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    const val LAMPORTS_PER_SIGNATURE = 5_000L

    /** Seed Vault accounts follow the app-wide network toggle: TestNet -> devnet, MainNet -> mainnet-beta. */
    fun clusterFor(network: AlgorandNetwork): String =
        when (network) {
            AlgorandNetwork.MAINNET -> "mainnet-beta"
            AlgorandNetwork.TESTNET, AlgorandNetwork.FUTURENET -> "devnet"
        }

    fun usdcMintFor(cluster: String): String =
        if (cluster.contains("mainnet", ignoreCase = true)) USDC_MINT_MAINNET else USDC_MINT_DEVNET
}
