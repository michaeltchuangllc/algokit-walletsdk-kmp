package com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk

import android.util.Base64
import com.algorand.algosdk.sdk.BytesArray
import com.algorand.algosdk.sdk.Sdk
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import io.github.aakira.napier.Napier
import java.math.BigInteger

private fun List<ByteArray>.flattenToByteArray(): ByteArray {
    val totalSize = this.sumOf { it.size }
    val result = ByteArray(totalSize)
    var offset = 0
    for (bytes in this) {
        bytes.copyInto(result, destinationOffset = offset)
        offset += bytes.size
    }
    return result
}

internal class SignFalcon24TransactionImpl : SignFalcon24Transaction {
    override fun signTransaction(
        transactionByteArray: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? =
        try {
            require(transactionByteArray.isNotEmpty()) { "transactionByteArray must not be empty" }
            require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
            require(privateKey.isNotEmpty()) { "privateKey must not be empty" }

            Napier.d(tag = TAG, message = "Signing Falcon24 transaction, input bytes: ${transactionByteArray.size}")
            val expectedTxn = Encoder.decodeFromMsgPack(transactionByteArray, Transaction::class.java)

            val txnList = BytesArray()
            txnList.append(transactionByteArray.copyOf())

            val resultCsv =
                Sdk.signFalconBundle(
                    txnList,
                    publicKey.copyOf(),
                    privateKey.copyOf(),
                )
            Napier.d(tag = TAG, message = "signFalconBundle returned CSV with length: ${resultCsv.length}")

            val signedResults = resultCsv.split(",").filter { it.isNotBlank() }
            val decodedResults =
                signedResults.map { encodedTxn ->
                    Base64.decode(encodedTxn, Base64.DEFAULT)
                }

            if (decodedResults.isEmpty()) {
                throw IllegalStateException("Falcon signer returned no signed transaction")
            }

            val containsExpectedTxn =
                decodedResults.any { bytes ->
                    try {
                        val signed = Encoder.decodeFromMsgPack(bytes, SignedTransaction::class.java)
                        val tx = signed.tx ?: return@any false
                        matchesExpectedTransaction(expectedTxn, tx)
                    } catch (_: Exception) {
                        false
                    }
                }

            if (!containsExpectedTxn) {
                throw IllegalStateException("Falcon signer did not return a matching SignedTransaction")
            }

            // Falcon bundle signing may return additional dummy transactions for verification budget.
            // Return the full signed payload so algod receives the complete group.
            if (decodedResults.size == 1) {
                decodedResults.first()
            } else {
                decodedResults.flattenToByteArray()
            }
        } catch (t: Throwable) {
            Log.e(tag = TAG, message = "Error signing transaction: ${t.message}, cause: ${t.cause}")
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

    override fun signArbitraryData(
        data: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? =
        try {
            require(data.isNotEmpty()) { "data must not be empty" }
            require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
            require(privateKey.isNotEmpty()) { "privateKey must not be empty" }

            val signedBytes =
                Sdk.rawSign(
                    data.copyOf(),
                    publicKey.copyOf(),
                    privateKey.copyOf(),
                )
            signedBytes
        } catch (t: Throwable) {
            Log.e(TAG, "Error signing arbitrary data: ${t.message}, cause: ${t.cause}")
            null
        }

    companion object {
        private val TAG = SignFalcon24TransactionImpl::class.java.simpleName
    }
}
