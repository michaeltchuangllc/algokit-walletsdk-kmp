package com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk

import com.algorand.algosdk.sdk.Sdk
import com.michaeltchuang.walletsdk.core.foundation.utils.Log

internal class SignFalcon24TransactionImpl : SignFalcon24Transaction {
    override fun signTransaction(
        transactionByteArray: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? =
        try {
            val signedBytes =
                Sdk.signFalconTransaction(
                    transactionByteArray,
                    publicKey,
                    privateKey,
                )
            signedBytes
        } catch (e: Exception) {
            Log.e(TAG, "Error signing transaction + ${e.message}")
            null
        }

    override fun signLegacyArbitraryData(
        data: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray? =
        try {
            // For Falcon24, we can use the same signing method as transactions
            // The SDK's signFalconTransaction should work for arbitrary data too
            val signedBytes =
                Sdk.signFalconTransaction(
                    data,
                    publicKey,
                    privateKey,
                )
            signedBytes
        } catch (e: Exception) {
            Log.e(TAG, "Error signing arbitrary data + ${e.message}")
            null
        }

    private val TAG = SignFalcon24TransactionImpl::class.java.simpleName
}
