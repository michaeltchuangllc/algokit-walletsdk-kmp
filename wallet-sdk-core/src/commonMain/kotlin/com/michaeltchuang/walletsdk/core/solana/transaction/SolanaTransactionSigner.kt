package com.michaeltchuang.walletsdk.core.solana.transaction

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.internal.signEd25519
import com.michaeltchuang.walletsdk.core.solana.crypto.solanaEd25519PublicKey
import com.michaeltchuang.walletsdk.core.solana.utils.Base58

/**
 * Signs compiled Solana transaction messages. Implementations:
 * - **Seed Vault payer** (UI layer): forwards the message to Seed Vault's sign-transaction
 *   request and returns the signature, so every on-chain payer action is user-approved.
 * - **Linked session key**: [MppWalletSigner.asSolanaTransactionSigner], signs silently.
 * - **Hot key**: [SolanaKeypairSigner].
 */
interface SolanaTransactionSigner {
    /** Base58 public key. */
    val address: String

    /** Returns the 64-byte Ed25519 signature over the raw [message] bytes. */
    suspend fun signMessage(message: ByteArray): ByteArray
}

/** Adapts an MPP signer (e.g. the Seed Vault-linked session key) for Solana transactions. */
fun MppWalletSigner.asSolanaTransactionSigner(): SolanaTransactionSigner {
    val delegate = this
    return object : SolanaTransactionSigner {
        override val address: String = delegate.address

        override suspend fun signMessage(message: ByteArray): ByteArray = delegate.signMessage(message)
    }
}

/** In-memory Ed25519 signer from a 32-byte private seed. */
class SolanaKeypairSigner(
    private val privateSeed: ByteArray,
) : SolanaTransactionSigner {
    init {
        require(privateSeed.size == 32) { "Expected a 32-byte Ed25519 seed" }
    }

    override val address: String = Base58.encode(solanaEd25519PublicKey(privateSeed))

    override suspend fun signMessage(message: ByteArray): ByteArray = signEd25519(privateSeed, message) ?: error("Ed25519 signing failed")
}
