package com.michaeltchuang.walletsdk.core.algosdk

import com.algorand.algosdk.account.Account
import com.algorand.algosdk.sdk.Sdk
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk.AlgorandBip39WalletProvider
import com.michaeltchuang.walletsdk.core.algosdk.bip39.sdk.Bip39Wallet
import com.michaeltchuang.walletsdk.core.algosdk.domain.model.Algo25Account
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoAccountSdkImpl
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoKitBip39SdkImpl
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoSdkNumberExtensions.toUint64
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.SignFalcon24TransactionImpl
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.SignHdKeyTransactionImpl
import com.michaeltchuang.walletsdk.core.foundation.utils.SuggestedParams
import com.michaeltchuang.walletsdk.core.foundation.utils.toSuggestedParams
import com.michaeltchuang.walletsdk.core.foundation.utils.urlSafeBase64ToStandard
import com.michaeltchuang.walletsdk.core.transaction.model.OfflineKeyRegTransactionPayload
import com.michaeltchuang.walletsdk.core.transaction.model.OnlineKeyRegTransactionPayload
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

actual fun recoverAlgo25Account(mnemonic: String): Algo25Account? = AlgoAccountSdkImpl().recoverAlgo25Account(mnemonic = mnemonic)

actual fun createAlgo25Account(): Algo25Account? = AlgoAccountSdkImpl().createAlgo25Account()

actual fun isValidAlgorandAddress(accountAddress: String): Boolean = AlgoAccountSdkImpl().isValidAlgorandAddress(accountAddress)

actual fun getMnemonicFromAlgo25SecretKey(secretKey: ByteArray): String? =
    AlgoAccountSdkImpl().getMnemonicFromAlgo25SecretKey(secretKey = secretKey)

actual fun createBip39Wallet(): Bip39Wallet = AlgorandBip39WalletProvider().createBip39Wallet()

actual fun getBip39Wallet(entropy: ByteArray): Bip39Wallet = AlgorandBip39WalletProvider().getBip39Wallet(entropy)

actual fun getSeedFromEntropy(entropy: ByteArray): ByteArray? {
    val seed = AlgoKitBip39SdkImpl().getSeedFromEntropy(entropy)
    return seed
}

actual fun signHdKeyTransaction(
    transactionByteArray: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray? {
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 0)
    return SignHdKeyTransactionImpl().signTransaction(
        transactionByteArray,
        seed,
        account,
        change,
        key,
    )
}

/**
 * Signs a group of Falcon24 transactions as a bundle on Android.
 * On Android, the grouped transaction approach uses explicit dummy transactions in
 * [submitAssetTransferAndAppCallInternal]; this function signs each transaction individually
 * (group IDs are already assigned by the caller).
 */
actual fun signFalcon24GroupBundle(
    txnsByteArrays: List<ByteArray>,
    publicKey: ByteArray,
    privateKey: ByteArray,
): List<ByteArray> = txnsByteArrays.mapNotNull { txn ->
    signFalcon24Transaction(txn, publicKey, privateKey)
}

actual fun signFalcon24Transaction(
    transactionByteArray: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray,
): ByteArray? {
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 0)
    return SignFalcon24TransactionImpl().signTransaction(
        transactionByteArray,
        publicKey,
        privateKey,
    )
}

actual fun signAlgo25Transaction(
    secretKey: ByteArray,
    transactionByteArray: ByteArray,
): ByteArray {
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 0)
    val account = Account(secretKey)
    val transaction = Encoder.decodeFromMsgPack(transactionByteArray, Transaction::class.java)
    val signedTransaction: SignedTransaction = account.signTransaction(transaction)
    return Encoder.encodeToMsgPack(signedTransaction)
}

actual fun signHdKeyArbitraryData(
    data: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray? =
    SignHdKeyTransactionImpl().signLegacyArbitraryData(
        data,
        seed,
        account,
        change,
        key,
    )

actual fun signHdKeyData(
    data: ByteArray,
    seed: ByteArray,
    account: Int,
    change: Int,
    key: Int,
): ByteArray? =
    SignHdKeyTransactionImpl().signArbitraryData(
        data,
        seed,
        account,
        change,
        key,
    )

actual fun signFalcon24ArbitraryData(
    data: ByteArray,
    publicKey: ByteArray,
    privateKey: ByteArray,
): ByteArray? =
    SignFalcon24TransactionImpl().signArbitraryData(
        data,
        publicKey,
        privateKey,
    )

actual fun signAlgo25ArbitraryData(
    data: ByteArray,
    secretKey: ByteArray,
): ByteArray? {
    println("DEBUG: signAlgo25ArbitraryData called with secretKey size: ${secretKey.size}")

    // Extract private key (first 32 bytes) from expanded secret key
    // For 64-byte expanded key: [32-byte seed/private key][32-byte public key]
    val privateKey =
        when (secretKey.size) {
            64 -> secretKey.copyOfRange(0, 32)
            32 -> secretKey.copyOf()
            else -> {
                println("DEBUG: Unexpected key size: ${secretKey.size}")
                return null
            }
        }

    return try {
        // Use BouncyCastle Ed25519 directly to avoid Android Conscrypt bug
        val privateKeyParams = Ed25519PrivateKeyParameters(privateKey, 0)
        val signer = Ed25519Signer()
        signer.init(true, privateKeyParams)
        signer.update(data, 0, data.size)
        val signature = signer.generateSignature()
        println("DEBUG: Signature generated successfully, size: ${signature.size}")
        signature
    } catch (e: Exception) {
        println("DEBUG: BouncyCastle Ed25519 signing failed: ${e.message}")
        e.printStackTrace()

        // Fallback to Account class if BouncyCastle fails
        try {
            println("DEBUG: Trying Account class fallback")
            val account = Account(secretKey)
            val signature = account.signBytes(data)
            signature?.bytes
        } catch (e2: Exception) {
            println("DEBUG: Account fallback also failed: ${e2.message}")
            null
        }
    }
}

actual fun createTransaction(payload: OfflineKeyRegTransactionPayload): ByteArray =
    with(payload) {
        val suggestedParams = txnParams.toSuggestedParams()
        if (flatFee != null) {
            suggestedParams.fee = flatFee.toString().toLong()
            suggestedParams.flatFee = true
        }

        val defaultVoteValue =
            java.math.BigInteger.ZERO
                .toUint64()

        GoMobileDispatcher.runOnGoThread {
            Sdk.makeKeyRegTxnWithStateProofKey(
                senderAddress,
                note?.toByteArray(),
                suggestedParams,
                null,
                null,
                null,
                defaultVoteValue,
                defaultVoteValue,
                defaultVoteValue,
                false,
            )
        }
    }

actual fun createTransaction(payload: OnlineKeyRegTransactionPayload): ByteArray =
    with(payload) {
        val suggestedParams = txnParams.toSuggestedParams()
        if (flatFee != null) {
            suggestedParams.fee = flatFee.toString().toLong()
            suggestedParams.flatFee = true
        }

        val voteFirst = voteFirstRound.toLongOrNull() ?: 0L
        val voteLast = voteLastRound.toLongOrNull() ?: 0L
        val voteDilution = voteKeyDilution.toLongOrNull() ?: 0L

        GoMobileDispatcher.runOnGoThread {
            Sdk.makeKeyRegTxnWithStateProofKey(
                senderAddress,
                note?.toByteArray(),
                suggestedParams,
                voteKey.urlSafeBase64ToStandard(),
                selectionPublicKey.urlSafeBase64ToStandard(),
                stateProofKey.urlSafeBase64ToStandard(),
                voteFirst.toUint64(),
                voteLast.toUint64(),
                voteDilution.toUint64(),
                false,
            )
        }
    }

actual fun getReceiverMinBalanceFee(
    receiverAlgoAmount: String,
    receiverMinBalanceAmount: String,
): Long =
    GoMobileDispatcher.runOnGoThread {
        Sdk.getReceiverMinBalanceFee(
            receiverAlgoAmount.toBigInteger().toUint64(),
            receiverMinBalanceAmount.toBigInteger().toUint64(),
        )
    }

actual fun makeAssetTransferTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    assetId: Long,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
): ByteArray =
    GoMobileDispatcher.runOnGoThread {
        Sdk.makeAssetTransferTxn(
            senderAddress,
            receiverAddress,
            "",
            amount.toBigInteger().toUint64(),
            noteInByteArray,
            suggestedParams,
            assetId,
        )
    }

actual fun makePaymentTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    isMax: Boolean,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
): ByteArray =
    GoMobileDispatcher.runOnGoThread {
        Sdk.makePaymentTxn(
            senderAddress,
            receiverAddress,
            amount.toBigInteger().toUint64(),
            noteInByteArray,
            if (isMax) receiverAddress else "",
            suggestedParams,
        )
    }

actual fun makeAssetAcceptanceTxn(
    publicKey: String,
    assetId: Long,
    suggestedParams: SuggestedParams,
): ByteArray =
    GoMobileDispatcher.runOnGoThread {
        Sdk.makeAssetAcceptanceTxn(
            publicKey,
            null,
            suggestedParams,
            assetId,
        )
    }
