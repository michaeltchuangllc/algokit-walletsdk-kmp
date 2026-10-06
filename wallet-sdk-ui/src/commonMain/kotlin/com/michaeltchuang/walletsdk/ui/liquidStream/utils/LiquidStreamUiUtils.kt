package com.michaeltchuang.walletsdk.ui.liquidStream.utils

import androidx.compose.ui.unit.TextUnit
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

/** Number of decimals USDC supports (1 micro-USDC = 0.000001 USDC). */
const val USDC_DISPLAY_DECIMALS = 6

/** Zero USDC rendered at full micro-USDC precision. */
const val ZERO_USDC_LABEL = "0.000000"

/**
 * Formats revenue with full micro-USDC precision and a '+' prefix if positive.
 * Example: 6.0 -> "+6.000000", 0.000008 -> "+0.000008", 0.0 -> "0.000000"
 */
fun formatRevenueLabel(revenue: Double): String {
    val formatted = formatUsdcSixDecimals(revenue)
    return if (formatted != ZERO_USDC_LABEL && revenue > 0) "+$formatted" else ZERO_USDC_LABEL
}

/**
 * Formats a USDC amount with exactly six decimals (micro-USDC precision).
 * Rounds to the nearest micro-USDC to avoid floating-point artifacts.
 * Example: 0.000008 -> "0.000008", 1.5 -> "1.500000"
 */
fun formatUsdcSixDecimals(usdc: Double): String = formatMicroUsdc(kotlin.math.round(usdc * 1_000_000.0).toLong())

private const val COMPACT_METRIC_VALUE_LENGTH = 6
private const val COMPACT_METRIC_VALUE_SCALE = 0.62f

/**
 * Shrinks large metric values (e.g. "+0.000080") so two six-decimal values fit side by side.
 */
fun metricValueFontSize(
    baseFontSize: TextUnit,
    value: String,
): TextUnit = if (value.length > COMPACT_METRIC_VALUE_LENGTH) baseFontSize * COMPACT_METRIC_VALUE_SCALE else baseFontSize

/**
 * Formats a micro-USDC amount as USDC with exactly six decimals using integer math.
 * Example: 8 -> "0.000008", 1_250_000 -> "1.250000"
 */
fun formatMicroUsdc(microUsdc: Long): String {
    val sign = if (microUsdc < 0) "-" else ""
    val absolute = kotlin.math.abs(microUsdc)
    val whole = absolute / 1_000_000
    val fraction = (absolute % 1_000_000).toString().padStart(USDC_DISPLAY_DECIMALS, '0')
    return "$sign$whole.$fraction"
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
