@file:Suppress("TooManyFunctions")

package com.michaeltchuang.walletsdk.core.foundation.utils

import android.util.Base64
import com.algorand.algosdk.sdk.BytesArray
import com.algorand.algosdk.sdk.Sdk
import com.algorand.algosdk.sdk.SuggestedParams
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.algosdk.makeAssetTransferTxn
import com.michaeltchuang.walletsdk.core.algosdk.makePaymentTxn
import com.michaeltchuang.walletsdk.core.algosdk.transaction.sdk.AlgoSdkNumberExtensions.toUint64
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.ALGO_ID
import com.michaeltchuang.walletsdk.core.network.model.TransactionParams
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray

const val ROUND_THRESHOLD = 1000L

actual typealias SuggestedParams = SuggestedParams
actual typealias TransactionParams = TransactionParams
actual typealias BytesArray = BytesArray

actual fun TransactionParams.toSuggestedParams(addGenesisId: Boolean): SuggestedParams =
    SuggestedParams().apply {
        fee = this@toSuggestedParams.fee
        genesisID = if (addGenesisId) genesisId else ""
        firstRoundValid = lastRound
        lastRoundValid = lastRound + ROUND_THRESHOLD
        genesisHash = Base64.decode(this@toSuggestedParams.genesisHash, Base64.DEFAULT)
    }

fun String.urlSafeBase64ToStandard(): String =
    this
        .replace('-', '+')
        .replace('_', '/')
        .let {
            // Add padding if needed (base64 strings should be multiples of 4)
            val padding = (4 - it.length % 4) % 4
            it + "=".repeat(padding)
        }

actual fun ByteArray.signTransaction(secretKey: ByteArray): ByteArray =
    GoMobileDispatcher.runOnGoThread { Sdk.signTransaction(secretKey, this) }

actual fun ByteArray.signTx(secretKey: ByteArray): ByteArray = GoMobileDispatcher.runOnGoThread { Sdk.signTransaction(secretKey, this) }

actual fun TransactionParams.makeAlgoTx(
    senderAddress: String,
    receiverAddress: String,
    amount: BigInteger,
    isMax: Boolean,
    noteInByteArray: ByteArray?,
): ByteArray =
    makePaymentTxn(
        senderAddress = senderAddress,
        receiverAddress = receiverAddress,
        amount = amount.toString(),
        isMax = isMax,
        noteInByteArray = noteInByteArray,
        toSuggestedParams(),
    )

actual fun TransactionParams.makeAssetTx(
    senderAddress: String,
    receiverAddress: String,
    amount: BigInteger,
    assetId: Long,
    noteInByteArray: ByteArray?,
): ByteArray =
    makeAssetTransferTxn(
        senderAddress = senderAddress,
        receiverAddress = receiverAddress,
        amount = amount.toString(),
        assetId = assetId,
        noteInByteArray = noteInByteArray,
        toSuggestedParams(addGenesisId = false),
    )

actual fun TransactionParams.makeTx(
    senderAddress: String,
    receiverAddress: String,
    amount: BigInteger,
    assetId: Long,
    isMax: Boolean,
    note: String?,
): ByteArray {
    val noteInByteArray = note?.toByteArray(charset = Charsets.UTF_8)

    return if (assetId == ALGO_ID) {
        makeAlgoTx(senderAddress, receiverAddress, amount, isMax, noteInByteArray)
    } else {
        makeAssetTx(senderAddress, receiverAddress, amount, assetId, noteInByteArray)
    }
}

actual fun TransactionParams.makeAddAssetTx(
    publicKey: String,
    assetId: Long,
): ByteArray =
    GoMobileDispatcher.runOnGoThread {
        Sdk.makeAssetAcceptanceTxn(
            publicKey,
            null,
            toSuggestedParams(),
            assetId,
        )
    }

actual fun TransactionParams.makeRemoveAssetTx(
    senderAddress: String,
    creatorPublicKey: String,
    assetId: Long,
): ByteArray =
    GoMobileDispatcher.runOnGoThread {
        Sdk.makeAssetTransferTxn(
            senderAddress,
            creatorPublicKey,
            creatorPublicKey,
            0L.toUint64(),
            null,
            toSuggestedParams(addGenesisId = false),
            assetId,
        )
    }

actual fun TransactionParams.makeSendAndRemoveAssetTx(
    senderAddress: String,
    receiverAddress: String,
    assetId: Long,
    amount: BigInteger,
    noteInByteArray: ByteArray?,
): ByteArray =
    GoMobileDispatcher.runOnGoThread {
        Sdk.makeAssetTransferTxn(
            senderAddress,
            receiverAddress,
            receiverAddress,
            amount.toString().toBigInteger().toUint64(),
            noteInByteArray,
            toSuggestedParams(addGenesisId = false),
            assetId,
        )
    }

actual fun TransactionParams.makeRekeyTx(
    rekeyAddress: String,
    rekeyAdminAddress: String,
): ByteArray =
    GoMobileDispatcher.runOnGoThread {
        Sdk.makeRekeyTxn(
            rekeyAddress,
            rekeyAdminAddress,
            toSuggestedParams(),
        )
    }
