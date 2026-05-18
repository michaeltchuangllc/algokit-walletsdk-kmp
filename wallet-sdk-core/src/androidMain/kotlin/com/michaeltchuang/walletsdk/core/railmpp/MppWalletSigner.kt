package com.michaeltchuang.walletsdk.core.railmpp

import com.algorand.algosdk.account.Account
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder

/**
 * Consumer-side wallet signer used by [MppPaymentRail] in client mode.
 *
 * Mirrors the rail-x402 signer interface — apps with an in-process Algorand
 * keypair can use [AccountMppSigner] for zero-boilerplate setup.
 */
interface MppWalletSigner {
    /** Transaction sender address. */
    val address: String

    /** Full authorized signer public key bytes used by Session Vault. */
    val authorizedSignerPublicKey: ByteArray

    /** Signer mode for Session Vault contract: 0=Ed25519 (Algo25/HD), 1=Falcon txn-auth. */
    val signerType: Long
        get() = 0L

    suspend fun signMessage(message: ByteArray): ByteArray =
        throw UnsupportedOperationException("Message signing is not supported by this signer")

    /**
     * Sign the given Algorand [Transaction] and return the raw msgpack bytes
     * of the signed transaction (as produced by `Encoder.encodeToMsgPack`).
     * Must not mutate the input transaction other than attaching a signature.
     */
    suspend fun signTransaction(txn: Transaction): ByteArray

    /**
     * Sign a full Algorand group in-order.
     * Default behavior signs each transaction independently via [signTransaction].
     */
    suspend fun signTransactions(txns: List<Transaction>): List<ByteArray> = txns.map { signTransaction(it) }

    /**
     * Build and sign a full Solana transaction for MPP charge, returning serialized signed bytes.
     * Default is unsupported for non-Solana signers.
     */
    suspend fun createSolanaSignedTransaction(
        recipientAddress: String,
        amount: String,
        network: String,
        mint: String? = null,
    ): ByteArray = throw UnsupportedOperationException("Solana transaction signing is not supported by this signer")
}

/**
 * Convenience [MppWalletSigner] that signs with an in-process
 * [com.algorand.algosdk.account.Account].
 */
class AccountMppSigner(
    private val account: Account,
) : MppWalletSigner {
    override val address: String
        get() = account.address.toString()

    override val authorizedSignerPublicKey: ByteArray
        get() = account.address.getBytes()

    override suspend fun signTransaction(txn: Transaction): ByteArray {
        val signed = account.signTransaction(txn)
        return Encoder.encodeToMsgPack(signed)
    }
}
