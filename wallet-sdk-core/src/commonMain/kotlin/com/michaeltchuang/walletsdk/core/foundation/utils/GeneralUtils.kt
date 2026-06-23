package com.michaeltchuang.walletsdk.core.foundation.utils

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.network.model.TransactionParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

const val MIN_FEE = 1000L
const val DATA_SIZE_FOR_MAX = 270
const val AMOUNT_FORMAT_DECIMAL_PLACES: Int = 6

private const val MICRO_ALGO_DIVISOR = "1000000"

val minBalancePerAssetAsBigInteger = 100_000L

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

fun TransactionParams.getTxFee(signedTxData: ByteArray? = null): Long =
    ((signedTxData?.size ?: DATA_SIZE_FOR_MAX) * fee).coerceAtLeast(getMinimumFee())

fun TransactionParams.getMinimumFee(): Long = (minFee ?: MIN_FEE).coerceAtLeast(MIN_FEE)

infix fun BigInteger?.isLesserThan(other: BigInteger): Boolean = this?.compareTo(other) == -1

fun List<ByteArray>.flatten(): ByteArray {
    val totalSize = this.sumOf { it.size }
    val result = ByteArray(totalSize)
    var position = 0
    for (array in this) {
        array.copyInto(result, position)
        position += array.size
    }
    return result
}

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

fun String.toAlgoAmount(): String {
    if (this.isEmpty()) return "0"
    if (this == "0") return "0"

    return try {
        // Validate it's a valid number using BigDecimal
        BigDecimal.parseString(this)

        // Split into integer and decimal parts
        val parts = this.split(".")
        val intPart = parts[0]
        val decPart = parts.getOrNull(1)

        // Format integer part with thousands separators
        val formattedIntPart =
            intPart
                .reversed()
                .chunked(3)
                .joinToString(",")
                .reversed()

        // Add decimal part if it exists
        if (decPart != null) {
            if (decPart.isEmpty()) {
                "$formattedIntPart." // User just typed "123."
            } else {
                "$formattedIntPart.$decPart"
            }
        } else {
            formattedIntPart
        }
    } catch (e: Exception) {
        this // Return original if parsing fails
    }
}
