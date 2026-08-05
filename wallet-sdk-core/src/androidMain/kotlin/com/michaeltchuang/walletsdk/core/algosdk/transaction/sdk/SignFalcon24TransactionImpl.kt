package com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk

import android.util.Base64
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import io.github.algorandecosystem.sdk.BytesArray
import io.github.algorandecosystem.sdk.Sdk
import java.math.BigInteger

private val TX_PREFIX = "TX".encodeToByteArray()

private fun ByteArray.withoutTxPrefix(): ByteArray =
    if (size >= TX_PREFIX.size && this[0] == TX_PREFIX[0] && this[1] == TX_PREFIX[1]) {
        copyOfRange(TX_PREFIX.size, size)
    } else {
        this
    }

private fun List<ByteArray>.flattenToByteArray(): ByteArray {
    val totalSize = sumOf { it.size }
    val result = ByteArray(totalSize)
    var offset = 0
    for (bytes in this) {
        bytes.copyInto(result, destinationOffset = offset)
        offset += bytes.size
    }
    return result
}

internal class SignFalcon24TransactionImpl : SignFalcon24Transaction {
    fun signLogicSigTransaction(
        transactionByteArray: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? =
        try {
            require(transactionByteArray.isNotEmpty()) { "transactionByteArray must not be empty" }
            require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
            require(privateKey.isNotEmpty()) { "privateKey must not be empty" }

            val unsignedTxnBytes = transactionByteArray.withoutTxPrefix()
            val expectedTxn = Encoder.decodeFromMsgPack(unsignedTxnBytes, Transaction::class.java)
            val resultCsv =
                GoMobileDispatcher.runOnGoThread {
                    val transactionList = BytesArray()
                    transactionList.append(unsignedTxnBytes.copyOf())
                    Sdk.signFalconLsigBundle(
                        transactionList,
                        publicKey.copyOf(),
                        privateKey.copyOf(),
                    )
                }
            val decodedResults =
                resultCsv
                    .split(",")
                    .filter { it.isNotBlank() }
                    .map { Base64.decode(it, Base64.DEFAULT) }

            if (decodedResults.isEmpty()) {
                throw IllegalStateException("Falcon24 LogicSig signer returned no signed transaction")
            }

            val containsExpectedTxn =
                decodedResults.any { bytes ->
                    try {
                        val signed = Encoder.decodeFromMsgPack(bytes, SignedTransaction::class.java)
                        val transaction = signed.tx ?: return@any false
                        matchesExpectedTransaction(expectedTxn, transaction)
                    } catch (_: Exception) {
                        false
                    }
                }
            if (!containsExpectedTxn) {
                throw IllegalStateException("Falcon24 LogicSig signer did not return a matching SignedTransaction")
            }

            if (decodedResults.size == 1) decodedResults.first() else decodedResults.flattenToByteArray()
        } catch (throwable: Throwable) {
            Log.e(tag = TAG, message = "Error signing Falcon24 LogicSig transaction: ${throwable.message}, cause: ${throwable.cause}")
            null
        }

    override fun signTransaction(
        transactionByteArray: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? = signLogicSigTransaction(transactionByteArray, publicKey, privateKey)

    override fun signArbitraryData(
        data: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? =
        try {
            require(data.isNotEmpty()) { "data must not be empty" }
            require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
            require(privateKey.isNotEmpty()) { "privateKey must not be empty" }

            GoMobileDispatcher.runOnGoThread {
                Sdk.rawSignFalconLsig(
                    data.copyOf(),
                    publicKey.copyOf(),
                    privateKey.copyOf(),
                )
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "Error signing Falcon24 arbitrary data: ${throwable.message}, cause: ${throwable.cause}")
            null
        }

    private fun matchesExpectedTransaction(
        expected: Transaction,
        actual: Transaction,
    ): Boolean {
        if (expected.type != actual.type) return false
        if (expected.sender?.toString() != actual.sender?.toString()) return false
        return when (expected.type?.toString()) {
            "pay" -> {
                expected.receiver?.toString() == actual.receiver?.toString() &&
                    (expected.amount ?: BigInteger.ZERO) == (actual.amount ?: BigInteger.ZERO)
            }
            "axfer" -> {
                expected.assetReceiver?.toString() == actual.assetReceiver?.toString() &&
                    (expected.assetAmount ?: BigInteger.ZERO) == (actual.assetAmount ?: BigInteger.ZERO) &&
                    expected.assetIndex.toLong() == actual.assetIndex.toLong()
            }
            else -> true
        }
    }

    private companion object {
        private val TAG = SignFalcon24TransactionImpl::class.java.simpleName
    }
}
