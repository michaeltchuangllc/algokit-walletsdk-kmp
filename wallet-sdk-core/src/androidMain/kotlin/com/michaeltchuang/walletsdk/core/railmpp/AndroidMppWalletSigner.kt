package com.michaeltchuang.walletsdk.core.railmpp

import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder

/**
 * Android extension of [MppWalletSigner] for algosdk [Transaction]-based signing.
 * Implement [signTransaction]; the common [signTransactionBytes] bridge is provided automatically.
 */
interface AndroidMppWalletSigner : MppWalletSigner {
    suspend fun signTransaction(txn: Transaction): ByteArray

    suspend fun signTransactions(txns: List<Transaction>): List<ByteArray> = txns.map { signTransaction(it) }

    override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray =
        signTransaction(Encoder.decodeFromMsgPack(txnMsgpack, Transaction::class.java))

    override suspend fun signTransactionsBytes(txnsMsgpack: List<ByteArray>): List<ByteArray> =
        signTransactions(txnsMsgpack.map { Encoder.decodeFromMsgPack(it, Transaction::class.java) })
}
