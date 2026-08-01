package com.michaeltchuang.walletsdk.core.railmpp.data

import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon25PrivateKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25Transaction
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24GroupBundle
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24Transaction
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon25Transaction
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
    private val getFalcon25PrivateKey: GetFalcon25PrivateKey,
    private val getHdSeed: GetHdSeed,
    private val logTag: String = "MppWalletSignerImpl",
) : MppWalletSigner {
    override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray =
        signWithLocalAccount(
            bytes = txnMsgpack,
            operation = SigningOperation.TRANSACTION,
        ) ?: ByteArray(0)

    override suspend fun signTransactionsBytes(txnsMsgpack: List<ByteArray>): List<ByteArray> {
        if (localAccount is LocalAccount.Falcon25) return super.signTransactionsBytes(txnsMsgpack)
        val falconPublicKey =
            when (localAccount) {
                is LocalAccount.Falcon24 -> localAccount.publicKey
                else -> return super.signTransactionsBytes(txnsMsgpack)
            }
        if (txnsMsgpack.size <= 1) return super.signTransactionsBytes(txnsMsgpack)
        return try {
            val privateKey = getFalcon24SecretKey(address)
            if (privateKey == null) {
                Napier.e("Missing Falcon key for group bundle signing: $address", tag = logTag)
                return txnsMsgpack.map { ByteArray(0) }
            }
            val result =
                signFalcon24GroupBundle(
                    txnsByteArrays = txnsMsgpack,
                    publicKey = falconPublicKey,
                    privateKey = privateKey,
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

    override suspend fun signMessage(message: ByteArray): ByteArray =
        signWithLocalAccount(
            bytes = message,
            operation = SigningOperation.MESSAGE,
        ) ?: ByteArray(0)

    private suspend fun signWithLocalAccount(
        bytes: ByteArray,
        operation: SigningOperation,
    ): ByteArray? =
        try {
            when (localAccount) {
                is LocalAccount.Algo25 -> signAlgo25(bytes, operation)
                is LocalAccount.HdKey -> signHdKey(bytes, operation, localAccount)
                is LocalAccount.Falcon24 -> signFalcon24(bytes, operation, localAccount)
                is LocalAccount.Falcon25 -> signFalcon25(bytes, operation, localAccount)
                else -> {
                    Napier.e("Unsupported account type for ${operation.logName}: $address", tag = logTag)
                    null
                }
            }?.also { signedBytes ->
                if (signedBytes.isEmpty()) {
                    Napier.e("${operation.logName} signing returned empty bytes for $address", tag = logTag)
                }
            }
        } catch (t: Throwable) {
            Napier.e("${operation.logName} signing failed for $address: ${t.message}", t, tag = logTag)
            null
        }

    private suspend fun signAlgo25(
        bytes: ByteArray,
        operation: SigningOperation,
    ): ByteArray? {
        val secretKey = getAlgo25SecretKey(address)
        if (secretKey == null) {
            Napier.e("Missing Algo25 key for $address", tag = logTag)
            return null
        }

        return when (operation) {
            SigningOperation.TRANSACTION -> signAlgo25Transaction(secretKey = secretKey, transactionByteArray = bytes)
            SigningOperation.MESSAGE -> signAlgo25ArbitraryData(data = bytes, secretKey = secretKey)
        }
    }

    private suspend fun signHdKey(
        bytes: ByteArray,
        operation: SigningOperation,
        account: LocalAccount.HdKey,
    ): ByteArray? {
        val seed = getHdSeed(account.seedId)
        if (seed == null) {
            Napier.e("Missing HD seed for $address", tag = logTag)
            return null
        }

        return when (operation) {
            SigningOperation.TRANSACTION ->
                signHdKeyTransaction(
                    transactionByteArray = bytes,
                    seed = seed,
                    account = account.account,
                    change = account.change,
                    key = account.keyIndex,
                )
            SigningOperation.MESSAGE ->
                signHdKeyArbitraryData(
                    data = bytes,
                    seed = seed,
                    account = account.account,
                    change = account.change,
                    key = account.keyIndex,
                )
        }
    }

    private suspend fun signFalcon24(
        bytes: ByteArray,
        operation: SigningOperation,
        account: LocalAccount.Falcon24,
    ): ByteArray? {
        val secretKey = getFalcon24SecretKey(address)
        if (secretKey == null) {
            Napier.e("Missing Falcon24 key for $address", tag = logTag)
            return null
        }

        return when (operation) {
            SigningOperation.TRANSACTION ->
                signFalcon24Transaction(
                    transactionByteArray = bytes,
                    publicKey = account.publicKey,
                    privateKey = secretKey,
                )
            SigningOperation.MESSAGE ->
                signFalcon24ArbitraryData(
                    data = bytes,
                    publicKey = account.publicKey,
                    privateKey = secretKey,
                )
        }
    }

    private suspend fun signFalcon25(
        bytes: ByteArray,
        operation: SigningOperation,
        account: LocalAccount.Falcon25,
    ): ByteArray? {
        val privateKey = getFalcon25PrivateKey(address)
        if (privateKey == null) {
            Napier.e("Missing Falcon25 key for $address", tag = logTag)
            return null
        }
        return when (operation) {
            SigningOperation.TRANSACTION ->
                signFalcon25Transaction(
                    transactionByteArray = bytes,
                    publicKey = account.publicKey,
                    privateKey = privateKey,
                )
            SigningOperation.MESSAGE ->
                signFalcon25ArbitraryData(
                    data = bytes,
                    publicKey = account.publicKey,
                    privateKey = privateKey,
                )
        }
    }

    private enum class SigningOperation(
        val logName: String,
    ) {
        TRANSACTION("transaction"),
        MESSAGE("message"),
    }
}
