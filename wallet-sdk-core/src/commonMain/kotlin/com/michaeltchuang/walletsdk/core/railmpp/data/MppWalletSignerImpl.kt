package com.michaeltchuang.walletsdk.core.railmpp.data

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25Transaction
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24GroupBundle
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24Transaction
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyTransaction
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import io.github.aakira.napier.Napier

class MppWalletSignerImpl(
    override val address: String,
    override val authorizedSignerPublicKey: ByteArray,
    override val signerType: Long,
    private val localAccount: LocalAccount,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getHdSeed: GetHdSeed,
    private val logTag: String = "MppWalletSignerImpl",
) : MppWalletSigner {
    override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray {
        return try {
            when (localAccount) {
                is LocalAccount.Algo25 -> {
                    val secretKey = getAlgo25SecretKey(address)
                    if (secretKey == null) {
                        Napier.e("Missing Algo25 key for $address", tag = logTag)
                        return ByteArray(0)
                    }
                    signAlgo25Transaction(secretKey = secretKey, transactionByteArray = txnMsgpack)
                }
                is LocalAccount.HdKey -> {
                    val seed = getHdSeed(localAccount.seedId)
                    if (seed == null) {
                        Napier.e("Missing HD seed for $address", tag = logTag)
                        return ByteArray(0)
                    }
                    signHdKeyTransaction(
                        transactionByteArray = txnMsgpack,
                        seed = seed,
                        account = localAccount.account,
                        change = localAccount.change,
                        key = localAccount.keyIndex,
                    ) ?: run {
                        Napier.e("HD signing failed for $address", tag = logTag)
                        ByteArray(0)
                    }
                }
                is LocalAccount.Falcon24 -> {
                    val secretKey = getFalcon24SecretKey(address)
                    if (secretKey == null) {
                        Napier.e("Missing Falcon24 key for $address", tag = logTag)
                        return ByteArray(0)
                    }
                    signFalcon24Transaction(
                        transactionByteArray = txnMsgpack,
                        publicKey = localAccount.publicKey,
                        privateKey = secretKey,
                    ) ?: run {
                        Napier.e("Falcon24 signing failed for $address", tag = logTag)
                        ByteArray(0)
                    }
                }
                else -> {
                    Napier.e("Unsupported account type for $address", tag = logTag)
                    ByteArray(0)
                }
            }
        } catch (t: Throwable) {
            Napier.e("signTransactionBytes failed for $address: ${t.message}", t, tag = logTag)
            ByteArray(0)
        }
    }

    override suspend fun signTransactionsBytes(txnsMsgpack: List<ByteArray>): List<ByteArray> {
        if (localAccount !is LocalAccount.Falcon24 || txnsMsgpack.size <= 1) {
            return super.signTransactionsBytes(txnsMsgpack)
        }
        return try {
            val secretKey = getFalcon24SecretKey(address)
            if (secretKey == null) {
                Napier.e("Missing Falcon24 key for group bundle signing: $address", tag = logTag)
                return txnsMsgpack.map { ByteArray(0) }
            }
            val result =
                signFalcon24GroupBundle(
                    txnsByteArrays = txnsMsgpack,
                    publicKey = localAccount.publicKey,
                    privateKey = secretKey,
                )
            if (result.isEmpty()) {
                Napier.e("Falcon24 group bundle returned empty for $address", tag = logTag)
                txnsMsgpack.map { ByteArray(0) }
            } else {
                Napier.d(
                    "Falcon24 group bundle signed: ${result.size} txns (includes dummies)",
                    tag = logTag,
                )
                result
            }
        } catch (t: Throwable) {
            Napier.e("signTransactionsBytes (Falcon bundle) failed: ${t.message}", t, tag = logTag)
            txnsMsgpack.map { ByteArray(0) }
        }
    }

    override suspend fun signMessage(message: ByteArray): ByteArray {
        return try {
            when (localAccount) {
                is LocalAccount.Algo25 -> {
                    val secretKey = getAlgo25SecretKey(address)
                    if (secretKey == null) {
                        Napier.e("Missing Algo25 key for $address", tag = logTag)
                        return ByteArray(0)
                    }
                    signAlgo25ArbitraryData(data = message, secretKey = secretKey) ?: run {
                        Napier.e("Algo25 message signing failed for $address", tag = logTag)
                        ByteArray(0)
                    }
                }
                is LocalAccount.HdKey -> {
                    val seed = getHdSeed(localAccount.seedId)
                    if (seed == null) {
                        Napier.e("Missing HD seed for $address", tag = logTag)
                        return ByteArray(0)
                    }
                    signHdKeyArbitraryData(
                        data = message,
                        seed = seed,
                        account = localAccount.account,
                        change = localAccount.change,
                        key = localAccount.keyIndex,
                    ) ?: run {
                        Napier.e("HD message signing failed for $address", tag = logTag)
                        ByteArray(0)
                    }
                }
                is LocalAccount.Falcon24 -> {
                    val secretKey = getFalcon24SecretKey(address)
                    if (secretKey == null) {
                        Napier.e("Missing Falcon24 key for $address", tag = logTag)
                        return ByteArray(0)
                    }
                    signFalcon24ArbitraryData(
                        data = message,
                        publicKey = localAccount.publicKey,
                        privateKey = secretKey,
                    ) ?: run {
                        Napier.e("Falcon24 message signing failed for $address", tag = logTag)
                        ByteArray(0)
                    }
                }
                else -> {
                    Napier.e("Unsupported account type for message signing: $address", tag = logTag)
                    ByteArray(0)
                }
            }
        } catch (t: Throwable) {
            Napier.e("signMessage failed for $address: ${t.message}", t, tag = logTag)
            ByteArray(0)
        }
    }
}
