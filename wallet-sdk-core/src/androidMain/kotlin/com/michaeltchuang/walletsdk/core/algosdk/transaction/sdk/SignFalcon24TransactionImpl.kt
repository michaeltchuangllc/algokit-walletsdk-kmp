package com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk

import android.util.Base64
import com.algorand.algosdk.sdk.BytesArray
import com.algorand.algosdk.sdk.Sdk
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import io.github.aakira.napier.Napier

internal class SignFalcon24TransactionImpl : SignFalcon24Transaction {
    override fun signTransaction(
        transactionByteArray: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? =
        try {
            Napier.d(tag = TAG, message = "Signing Falcon24 transaction, input bytes: ${transactionByteArray.size}")

            val txnList = BytesArray()
            txnList.append(transactionByteArray)

            val resultCsv =
                Sdk.signFalconBundle(
                    txnList,
                    publicKey.copyOf(),
                    privateKey.copyOf(),
                )
            Napier.d(tag = TAG, message = "signFalconBundle returned CSV with length: ${resultCsv.length}")

            // Parse CSV and decode Base64 transactions, then concatenate into raw bytes
            val signedResults = resultCsv.split(",")
            val outputStream = java.io.ByteArrayOutputStream()
            for (encodedTxn in signedResults) {
                val decodedBytes = Base64.decode(encodedTxn, Base64.DEFAULT)
                outputStream.write(decodedBytes)
            }
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(tag = TAG, message = "Error signing transaction: ${e.message}, cause: ${e.cause}")
            null
        }

    override fun signArbitraryData(
        data: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? =
        try {
            val signedBytes =
                Sdk.rawSign(
                    data,
                    publicKey,
                    privateKey,
                )
            signedBytes
        } catch (e: Exception) {
            Log.e(TAG, "Error signing arbitrary data + ${e.message}")
            null
        }

    companion object {
        private val TAG = SignFalcon24TransactionImpl::class.java.simpleName
    }
}
