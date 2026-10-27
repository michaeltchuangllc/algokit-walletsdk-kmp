package com.michaeltchuang.walletsdk.core.foundation.utils

/**
 * Blockchains supported by the SDK. [id] is the SLIP-44 coin type, which is the value stored
 * as `chainId` on Seed Vault accounts.
 */
enum class Chain(
    val id: String,
    val ticker: String,
) {
    ALGORAND("283", "ALGO"),
    SOLANA("501", "SOL"),
    ;

    companion object {
        fun fromId(id: String?): Chain? = entries.firstOrNull { it.id == id }
    }
}
