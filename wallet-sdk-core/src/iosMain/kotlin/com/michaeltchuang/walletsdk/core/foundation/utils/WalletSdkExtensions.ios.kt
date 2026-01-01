package com.michaeltchuang.walletsdk.core.foundation.utils

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.algosdk.makeAssetTransferTxn
import com.michaeltchuang.walletsdk.core.algosdk.makePaymentTxn
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.ALGO_ID
import com.michaeltchuang.walletsdk.core.network.model.TransactionParams
import io.ktor.util.decodeBase64Bytes
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray

const val ROUND_THRESHOLD = 1000L

actual fun ByteArray.signTransaction(secretKey: ByteArray): ByteArray = ByteArray(0)

actual data class SuggestedParams(
    var fee: Long = 0,
    var genesisID: String = "",
    var firstRoundValid: Long = 0,
    var lastRoundValid: Long = 0,
    var genesisHash: ByteArray = ByteArray(0),
    var flatFee: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SuggestedParams

        if (fee != other.fee) return false
        if (genesisID != other.genesisID) return false
        if (firstRoundValid != other.firstRoundValid) return false
        if (lastRoundValid != other.lastRoundValid) return false
        if (!genesisHash.contentEquals(other.genesisHash)) return false
        if (flatFee != other.flatFee) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fee.hashCode()
        result = 31 * result + genesisID.hashCode()
        result = 31 * result + firstRoundValid.hashCode()
        result = 31 * result + lastRoundValid.hashCode()
        result = 31 * result + genesisHash.contentHashCode()
        result = 31 * result + flatFee.hashCode()
        return result
    }
}

actual class TransactionParams

actual class BytesArray

actual fun TransactionParams.toSuggestedParams(addGenesisId: Boolean): SuggestedParams {
    // Cast to the actual TransactionParams type
    val params = this as com.michaeltchuang.walletsdk.core.network.model.TransactionParams

    return SuggestedParams(
        fee = params.fee,
        genesisID = if (addGenesisId) params.genesisId else "",
        firstRoundValid = params.lastRound,
        lastRoundValid = params.lastRound + ROUND_THRESHOLD,
        genesisHash =
            try {
                params.genesisHash.decodeBase64Bytes()
            } catch (e: Exception) {
                println("Error decoding genesis hash: ${e.message}")
                ByteArray(0)
            },
    )
}

actual fun ByteArray.signTx(secretKey: ByteArray): ByteArray = ByteArray(0)

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
): ByteArray = ByteArray(0)

actual fun TransactionParams.makeRemoveAssetTx(
    senderAddress: String,
    creatorPublicKey: String,
    assetId: Long,
): ByteArray = ByteArray(0)

actual fun TransactionParams.makeSendAndRemoveAssetTx(
    senderAddress: String,
    receiverAddress: String,
    assetId: Long,
    amount: BigInteger,
    noteInByteArray: ByteArray?,
): ByteArray = ByteArray(0)

actual fun TransactionParams.makeRekeyTx(
    rekeyAddress: String,
    rekeyAdminAddress: String,
): ByteArray = ByteArray(0)
