package com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk

import com.algorand.algosdk.crypto.Address
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import io.github.algorandecosystem.sdk.Sdk
import uniffi.algokit_crypto_ffi.XhdDerivedAccount
import uniffi.algokit_crypto_ffi.XhdKeyContext
import uniffi.algokit_crypto_ffi.xhdDerive
import uniffi.algokit_crypto_ffi.xhdRawSign
import uniffi.algokit_crypto_ffi.xhdRootKeyFromSeed
import java.nio.charset.StandardCharsets

private val TX_PREFIX = "TX".toByteArray(StandardCharsets.UTF_8)

private fun ByteArray.withoutTxPrefix(): ByteArray =
    if (size >= TX_PREFIX.size && this[0] == TX_PREFIX[0] && this[1] == TX_PREFIX[1]) {
        copyOfRange(TX_PREFIX.size, size)
    } else {
        this
    }

internal class SignHdKeyTransactionImpl : SignHdKeyTransaction {
    override fun signTransaction(
        transactionByteArray: ByteArray,
        seed: ByteArray,
        account: Int,
        change: Int,
        key: Int,
    ): ByteArray? {
        return try {
            val unsignedTxnBytes = transactionByteArray.withoutTxPrefix()
            val tx = Encoder.decodeFromMsgPack(unsignedTxnBytes, Transaction::class.java)

            val derivedAccount = deriveAccount(seed, account, change, key)
            val signedTxn = xhdRawSign(derivedAccount.extendedPrivateKey, rawTransactionBytesToSign(unsignedTxnBytes))
            val pkAddress = Address(derivedAccount.publicKey)

            // attachSignature/attachSignatureWithSigner must run on the dedicated Go-mobile
            // OS thread to prevent concurrent GC races with signFalconBundle
            // ("bulkBarrierPreWrite: unaligned arguments").
            return GoMobileDispatcher.runOnGoThread {
                if (tx.sender != pkAddress) {
                    Sdk.attachSignatureWithSigner(signedTxn, unsignedTxnBytes, pkAddress.toString())
                } else {
                    Sdk.attachSignature(signedTxn, unsignedTxnBytes)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun rawTransactionBytesToSign(tx: ByteArray): ByteArray = TX_PREFIX + tx

    override fun signLegacyArbitraryData(
        transactionByteArray: ByteArray,
        seed: ByteArray,
        account: Int,
        change: Int,
        key: Int,
    ): ByteArray? {
        return try {
            val prefixedData = prefixData(transactionByteArray)

            val derivedAccount = deriveAccount(seed, account, change, key)
            return xhdRawSign(derivedAccount.extendedPrivateKey, prefixedData)
        } catch (_: Exception) {
            null
        }
    }

    private fun prefixData(data: ByteArray): ByteArray {
        val prefix = "MX".toByteArray(Charsets.UTF_8)
        return prefix + data
    }

    private fun deriveAccount(
        seed: ByteArray,
        account: Int,
        change: Int,
        key: Int,
    ): XhdDerivedAccount {
        require(change == 0) {
            "AlgoKit Crypto xHD derivation only supports change index 0. Requested: $change"
        }
        return xhdDerive(
            rootKey = xhdRootKeyFromSeed(seed),
            keyContext = XhdKeyContext.ADDRESS,
            account = account.toUInt(),
            keyIndex = key.toUInt(),
        )
    }

    override fun signArbitraryData(
        data: ByteArray,
        seed: ByteArray,
        account: Int,
        change: Int,
        key: Int,
    ): ByteArray? {
        return try {
            val derivedAccount = deriveAccount(seed, account, change, key)
            return xhdRawSign(derivedAccount.extendedPrivateKey, data)
        } catch (_: Exception) {
            null
        }
    }
}
