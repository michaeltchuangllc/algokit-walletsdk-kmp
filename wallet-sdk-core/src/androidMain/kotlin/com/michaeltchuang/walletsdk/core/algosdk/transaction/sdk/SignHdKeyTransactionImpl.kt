package com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk

import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.sdk.Sdk
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import foundation.algorand.xhdwalletapi.Bip32DerivationType
import foundation.algorand.xhdwalletapi.KeyContext
import foundation.algorand.xhdwalletapi.XHDWalletAPIAndroid
import foundation.algorand.xhdwalletapi.XHDWalletAPIBase.Companion.getBIP44PathFromContext
import java.nio.charset.StandardCharsets

internal class SignHdKeyTransactionImpl : SignHdKeyTransaction {
    override fun signTransaction(
        transactionByteArray: ByteArray,
        seed: ByteArray,
        account: Int,
        change: Int,
        key: Int,
    ): ByteArray? {
        return try {
            val tx = Encoder.decodeFromMsgPack(transactionByteArray, Transaction::class.java)

            val xHDWalletAPI = XHDWalletAPIAndroid(seed)
            val (accountIndex, changeIndex, keyIndex) =
                listOf(
                    account.toUInt(),
                    change.toUInt(),
                    key.toUInt(),
                )

            val signedTxn =
                xHDWalletAPI.signAlgoTransaction(
                    KeyContext.Address,
                    accountIndex,
                    changeIndex,
                    keyIndex,
                    rawTransactionBytesToSign(transactionByteArray),
                )

            val pkAddress =
                Address(
                    xHDWalletAPI.keyGen(
                        KeyContext.Address,
                        accountIndex,
                        changeIndex,
                        keyIndex,
                    ),
                )

            return if (tx.sender != pkAddress) {
                Sdk.attachSignatureWithSigner(signedTxn, transactionByteArray, pkAddress.toString())
            } else {
                Sdk.attachSignature(signedTxn, transactionByteArray)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun rawTransactionBytesToSign(tx: ByteArray): ByteArray {
        val txIdPrefix = "TX".toByteArray(StandardCharsets.UTF_8)
        return txIdPrefix + tx
    }

    override fun signLegacyArbitraryData(
        transactionByteArray: ByteArray,
        seed: ByteArray,
        account: Int,
        change: Int,
        key: Int,
    ): ByteArray? {
        return try {
            val prefixedData = prefixData(transactionByteArray)

            val xHDWalletAPI = XHDWalletAPIAndroid(seed)
            val (accountIndex, changeIndex, keyIndex) =
                listOf(
                    account.toUInt(),
                    change.toUInt(),
                    key.toUInt(),
                )

            val stx =
                xHDWalletAPI.rawSign(
                    getBIP44PathFromContext(
                        context = KeyContext.Address,
                        account = accountIndex,
                        change = changeIndex,
                        keyIndex = keyIndex,
                    ),
                    prefixedData,
                    Bip32DerivationType.Peikert,
                )

            return stx
        } catch (e: Exception) {
            null
        }
    }

    private fun prefixData(data: ByteArray): ByteArray {
        val prefix = "MX".toByteArray(Charsets.UTF_8)
        return prefix + data
    }

    override fun signArbitraryData(
        data: ByteArray,
        seed: ByteArray,
        account: Int,
        change: Int,
        key: Int,
    ): ByteArray? {
        return try {
            val xHDWalletAPI = XHDWalletAPIAndroid(seed)
            val (accountIndex, changeIndex, keyIndex) =
                listOf(
                    account.toUInt(),
                    change.toUInt(),
                    key.toUInt(),
                )

            val stx =
                xHDWalletAPI.rawSign(
                    getBIP44PathFromContext(
                        context = KeyContext.Address,
                        account = accountIndex,
                        change = changeIndex,
                        keyIndex = keyIndex,
                    ),
                    data, // Sign the raw data without any prefix
                    Bip32DerivationType.Peikert,
                )

            return stx
        } catch (e: Exception) {
            null
        }
    }
}
