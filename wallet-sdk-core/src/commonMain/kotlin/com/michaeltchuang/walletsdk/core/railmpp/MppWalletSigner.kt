package com.michaeltchuang.walletsdk.core.railmpp

/** Platform-agnostic wallet signer for Algorand/Solana payment rails. */
interface MppWalletSigner {
    val address: String
    val authorizedSignerPublicKey: ByteArray

    /** 0 = Ed25519 (Algo25/HD), 1 = Falcon txn-auth. */
    val signerType: Long
        get() = 0L

    suspend fun signMessage(message: ByteArray): ByteArray =
        throw UnsupportedOperationException("Message signing is not supported by this signer")

    /** Signs a msgpack-encoded unsigned Algorand transaction, returns signed msgpack bytes. */
    suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray

    /** Signs a group of msgpack-encoded transactions in-order. */
    suspend fun signTransactionsBytes(txnsMsgpack: List<ByteArray>): List<ByteArray> = txnsMsgpack.map { signTransactionBytes(it) }

    suspend fun createSolanaSignedTransaction(
        recipientAddress: String,
        amount: String,
        network: String,
        mint: String? = null,
    ): ByteArray = throw UnsupportedOperationException("Solana transaction signing is not supported by this signer")
}
