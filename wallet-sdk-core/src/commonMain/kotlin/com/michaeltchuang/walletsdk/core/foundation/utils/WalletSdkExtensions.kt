package com.michaeltchuang.walletsdk.core.foundation.utils

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import com.ionspin.kotlin.bignum.decimal.BigDecimal
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

const val AMOUNT_FORMAT_DECIMAL_PLACES: Int = 6

fun String.formatAmount(convertToMicroAmount: Boolean = true): String =
    try {
        val amount = BigDecimal.parseString(this)
        val divisor = if (convertToMicroAmount) BigDecimal.parseString(MICRO_ALGO_DIVISOR) else BigDecimal.ONE
        amount.divide(divisor).toStringExpanded().roundDecimalString(AMOUNT_FORMAT_DECIMAL_PLACES)
    } catch (e: Exception) {
        this
    }

private fun String.roundDecimalString(decimalPlaces: Int): String {
    val parts = split(".")
    var intPart = parts[0]
    val decimalPart = parts.getOrNull(1).orEmpty()
    val roundedDecimalPart = decimalPart.take(decimalPlaces).padEnd(decimalPlaces, '0').toCharArray()
    val shouldRoundUp = decimalPart.getOrNull(decimalPlaces)?.digitToIntOrNull()?.let { it >= 5 } == true

    if (shouldRoundUp) {
        var index = roundedDecimalPart.lastIndex
        var carry = true
        while (index >= 0 && carry) {
            if (roundedDecimalPart[index] == '9') {
                roundedDecimalPart[index] = '0'
                index--
            } else {
                roundedDecimalPart[index] = roundedDecimalPart[index] + 1
                carry = false
            }
        }
        if (carry) {
            intPart = intPart.incrementDecimalIntegerString()
        }
    }

    return "$intPart.${roundedDecimalPart.concatToString()}"
}

private fun String.incrementDecimalIntegerString(): String {
    val digits = toCharArray()
    var index = digits.lastIndex
    var carry = true
    while (index >= 0 && carry) {
        if (digits[index] == '9') {
            digits[index] = '0'
            index--
        } else {
            digits[index] = digits[index] + 1
            carry = false
        }
    }
    return if (carry) "1${digits.concatToString()}" else digits.concatToString()
}

private const val MICRO_ALGO_DIVISOR = "1000000"

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
