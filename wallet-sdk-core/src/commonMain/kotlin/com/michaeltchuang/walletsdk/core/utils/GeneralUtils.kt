package com.michaeltchuang.walletsdk.core.utils

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.network.model.TransactionParams

const val MIN_FEE = 1000L
const val DATA_SIZE_FOR_MAX = 270
const val ROUND_THRESHOLD = 1000L

val minBalancePerAssetAsBigInteger = 100_000L

fun TransactionParams.getTxFee(signedTxData: ByteArray? = null): Long =
    ((signedTxData?.size ?: DATA_SIZE_FOR_MAX) * fee).coerceAtLeast(minFee ?: MIN_FEE)

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
        val formattedIntPart = intPart
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
