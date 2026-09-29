package com.michaeltchuang.walletsdk.ui.liquidStream.utils

import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress

enum class TextCasing {
    UPPERCASE,
    LOWERCASE,
    ORIGINAL,
}

fun formatDisplayName(
    input: String?,
    casing: TextCasing = TextCasing.ORIGINAL,
): String {
    if (input.isNullOrBlank()) return ""
    val isAddress = input.length >= 50
    val formatted =
        if (isAddress) {
            input.toShortenedAddress().uppercase()
        } else {
            input
        }
    if (isAddress) {
        return formatted
    }
    return when (casing) {
        TextCasing.UPPERCASE -> formatted.uppercase()
        TextCasing.LOWERCASE -> formatted.lowercase()
        TextCasing.ORIGINAL -> formatted
    }
}

/**
 * Formats revenue into a string with two decimal places and a '+' prefix if positive.
 * Example: 6.0 -> "+6.00", 6.123 -> "+6.12", 0.0 -> "0.00"
 */
fun formatRevenueLabel(revenue: Double): String =
    if (revenue > 0) {
        "+${formatTwoDecimals(revenue)}"
    } else {
        "0.00"
    }

/**
 * Formats a double to a string with exactly two decimal places.
 * Example: 6.0 -> "6.00", 6.123 -> "6.12"
 */
fun formatTwoDecimals(value: Double): String {
    val rounded = (value * 100).toLong() / 100.0
    val str = rounded.toString()
    val parts = str.split(".")
    val intPart = parts[0]
    val decPart = parts.getOrNull(1)?.padEnd(2, '0')?.take(2) ?: "00"
    return "$intPart.$decPart"
}

sealed interface GiftAmountValidation {
    object Valid : GiftAmountValidation

    data class Error(
        val message: String,
    ) : GiftAmountValidation
}

fun sanitizeGiftAmountInput(
    input: String,
    maxLength: Int = 7,
): String {
    var filtered = input.filter { it.isDigit() || it == '.' }
    if (filtered.startsWith(".")) {
        filtered = "0$filtered"
    }
    val parts = filtered.split('.')
    val sanitized =
        if (parts.size > 2) {
            parts[0] + "." + parts.drop(1).joinToString("")
        } else {
            filtered
        }
    return sanitized.take(maxLength)
}

fun validateGiftAmount(
    amountToSend: String,
    balanceLabel: String,
): GiftAmountValidation {
    val sendVal = amountToSend.toDoubleOrNull()
    val availableBalance = balanceLabel.toDoubleOrNull() ?: 0.0
    return when {
        sendVal == null || sendVal <= 0.0 -> GiftAmountValidation.Error("Please enter a valid gift amount")
        sendVal > availableBalance -> GiftAmountValidation.Error("Insufficient balance ($balanceLabel USDC available)")
        else -> GiftAmountValidation.Valid
    }
}
