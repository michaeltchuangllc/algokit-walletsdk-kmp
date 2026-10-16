package com.michaeltchuang.walletsdk.core.railmpp.domain.repository

enum class MppWalletSignerType {
    ED25519,
    FALCON_NATIVE,
    FALCON_LSIG,
}

/** Platform-agnostic wallet signer for Algorand/Solana payment rails. */
interface MppWalletSigner {
    val address: String
    val authorizedSignerPublicKey: ByteArray

    /** Transaction authorization algorithm used by this signer. */
    val signerType: MppWalletSignerType
        get() = MppWalletSignerType.ED25519

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
