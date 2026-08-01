package com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk

import android.util.Base64
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import io.github.algorandecosystem.sdk.BytesArray
import io.github.algorandecosystem.sdk.Sdk

private val TX_PREFIX = "TX".encodeToByteArray()

private fun ByteArray.withoutTxPrefix(): ByteArray =
    if (size >= TX_PREFIX.size && this[0] == TX_PREFIX[0] && this[1] == TX_PREFIX[1]) {
        copyOfRange(TX_PREFIX.size, size)
    } else {
        this
    }

internal class SignFalcon25TransactionImpl {
    fun signTransaction(
        transactionByteArray: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? =
        try {
            require(transactionByteArray.isNotEmpty()) { "transactionByteArray must not be empty" }
            require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
            require(privateKey.isNotEmpty()) { "privateKey must not be empty" }

            val unsignedTransaction = transactionByteArray.withoutTxPrefix()
            val signedTransactionCsv =
                GoMobileDispatcher.runOnGoThread {
                    val transactionList = BytesArray()
                    transactionList.append(unsignedTransaction.copyOf())
                    Sdk.signFalconBundle(
                        transactionList,
                        publicKey.copyOf(),
                        privateKey.copyOf(),
                    )
                }
            val signedTransactions =
                signedTransactionCsv
                    .split(",")
                    .filter { it.isNotBlank() }
                    .map { Base64.decode(it, Base64.DEFAULT) }

            require(signedTransactions.size == 1) {
                "Falcon25 native signer returned ${signedTransactions.size} transactions; expected one"
            }
            signedTransactions.single()
        } catch (throwable: Throwable) {
            Log.e(tag = TAG, message = "Error signing Falcon25 native transaction: ${throwable.message}, cause: ${throwable.cause}")
            null
        }

    fun signArbitraryData(
        data: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? =
        try {
            require(data.isNotEmpty()) { "data must not be empty" }
            require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
            require(privateKey.isNotEmpty()) { "privateKey must not be empty" }

            GoMobileDispatcher.runOnGoThread {
                Sdk.rawSign(
                    data.copyOf(),
                    publicKey.copyOf(),
                    privateKey.copyOf(),
                )
            }
        } catch (throwable: Throwable) {
            Log.e(tag = TAG, message = "Error signing Falcon25 arbitrary data: ${throwable.message}, cause: ${throwable.cause}")
            null
        }

    private companion object {
        private val TAG = SignFalcon25TransactionImpl::class.java.simpleName
    }
}
