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
    SignFalcon24TransactionImpl().signLegacyArbitraryData(
        data,
        publicKey,
        privateKey,
    )

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

actual fun getReceiverMinBalanceFee(
    receiverAlgoAmount: String,
    receiverMinBalanceAmount: String,
): Long =
    Sdk.getReceiverMinBalanceFee(
        receiverAlgoAmount.toBigInteger().toUint64(),
        receiverMinBalanceAmount.toBigInteger().toUint64(),
    )

actual fun makeAssetTransferTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    assetId: Long,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
): ByteArray =
    Sdk.makeAssetTransferTxn(
        senderAddress,
        receiverAddress,
        "",
        amount.toBigInteger().toUint64(),
        noteInByteArray,
        suggestedParams,
        assetId,
    )

actual fun makePaymentTxn(
    senderAddress: String,
    receiverAddress: String,
    amount: String,
    isMax: Boolean,
    noteInByteArray: ByteArray?,
    suggestedParams: SuggestedParams,
): ByteArray =
    Sdk.makePaymentTxn(
        senderAddress,
        receiverAddress,
        amount.toBigInteger().toUint64(),
        noteInByteArray,
        if (isMax) receiverAddress else "",
        suggestedParams,
    )

actual fun makeAssetAcceptanceTxn(
    publicKey: String,
    assetId: Long,
    suggestedParams: SuggestedParams,
): ByteArray =
    Sdk.makeAssetAcceptanceTxn(
        publicKey,
        null,
        suggestedParams,
        assetId,
    )
