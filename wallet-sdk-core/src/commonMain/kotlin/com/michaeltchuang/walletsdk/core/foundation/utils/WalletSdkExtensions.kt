package com.michaeltchuang.walletsdk.core.foundation.utils

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.network.model.TransactionParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

fun ByteArray.clearFromMemory(): ByteArray {
    // Overwrite the byte array contents with zeros
    this.fill(0)
    return ByteArray(0)
}

val jsonConfig =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

inline fun <reified T> SavedStateHandle.setObject(value: T) {
    this["data"] = jsonConfig.encodeToString(serializer<T>(), value)
}

inline fun <reified T> SavedStateHandle.getObject(): T? {
    val json = this.get<String>("data") ?: return null
    return jsonConfig.decodeFromString(serializer<T>(), json)
}

inline fun <reified T> NavController.navigateWithArgument(
    route: String,
    bundle: T,
) {
    setData(bundle)
    navigate(route)
}

inline fun <reified T> NavController.setData(data: T) {
    currentBackStackEntry
        ?.savedStateHandle
        ?.setObject(data)
}

inline fun <reified T> NavController.getData(): T? =
    this.previousBackStackEntry
        ?.savedStateHandle
        ?.getObject()

val precision: Int = 3

fun String.formatAmount(convertToMicroAmount: Boolean = true): String =

    try {
        val microalgos = BigDecimal.parseString(this)
        var divisor = BigDecimal.parseString("1")
        if (convertToMicroAmount) {
            divisor = BigDecimal.parseString("1000000")
        }
        val micros = microalgos.divide(divisor)

        // Round to 6 decimal places
        val rounded =
            micros.roundToDigitPosition(
                digitPosition = precision.toLong(),
                roundingMode = RoundingMode.ROUND_HALF_AWAY_FROM_ZERO,
            )

        // Format with exactly 6 decimal places
        val str = rounded.toStringExpanded()
        val parts = str.split(".")
        val intPart = parts[0]
        val decPart = parts.getOrNull(1)?.take(precision)?.padEnd(precision, '0') ?: "000"

        "$intPart.$decPart"
    } catch (e: Exception) {
        this
    }

expect class BytesArray

expect class SuggestedParams

expect class TransactionParams

expect fun TransactionParams.toSuggestedParams(addGenesisId: Boolean = true): SuggestedParams

expect fun ByteArray.signTransaction(secretKey: ByteArray): ByteArray

expect fun ByteArray.signTx(secretKey: ByteArray): ByteArray

expect fun TransactionParams.makeAlgoTx(
    senderAddress: String,
    receiverAddress: String,
    amount: BigInteger,
    isMax: Boolean,
    noteInByteArray: ByteArray? = null,
): ByteArray

expect fun TransactionParams.makeAssetTx(
    senderAddress: String,
    receiverAddress: String,
    amount: BigInteger,
    assetId: Long,
    noteInByteArray: ByteArray? = null,
): ByteArray

expect fun TransactionParams.makeTx(
    senderAddress: String,
    receiverAddress: String,
    amount: BigInteger,
    assetId: Long,
    isMax: Boolean,
    note: String? = null,
): ByteArray

expect fun TransactionParams.makeAddAssetTx(
    publicKey: String,
    assetId: Long,
): ByteArray

expect fun TransactionParams.makeRemoveAssetTx(
    senderAddress: String,
    creatorPublicKey: String,
    assetId: Long,
): ByteArray

expect fun TransactionParams.makeSendAndRemoveAssetTx(
    senderAddress: String,
    receiverAddress: String,
    assetId: Long,
    amount: BigInteger,
    noteInByteArray: ByteArray? = null,
): ByteArray

expect fun TransactionParams.makeRekeyTx(
    rekeyAddress: String,
    rekeyAdminAddress: String,
): ByteArray
