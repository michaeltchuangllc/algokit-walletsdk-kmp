package com.michaeltchuang.walletsdk.core.solana.crypto

import com.michaeltchuang.walletsdk.core.solana.utils.Base58

/** A Solana Ed25519 keypair. [privateSeed] is the raw 32-byte seed; never log it. */
class SolanaKeypair internal constructor(
    val privateSeed: ByteArray,
    val publicKey: ByteArray,
) {
    val address: String get() = Base58.encode(publicKey)
}

/** Creates the device-generated session keys linked to Seed Vault accounts. */
object SolanaKeyDerivation {
    fun keypairFromSeed(privateSeed: ByteArray): SolanaKeypair {
        require(privateSeed.size == 32) { "Solana private seed must be 32 bytes" }
        return SolanaKeypair(privateSeed = privateSeed, publicKey = solanaEd25519PublicKey(privateSeed))
    }

    /** Generates a fresh random keypair (not recoverable from a phrase). */
    fun generateEphemeralKeypair(): SolanaKeypair = keypairFromSeed(secureRandomBytes(32))
}
