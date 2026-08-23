package com.michaeltchuang.walletsdk.ui.liquidStream.utils

/**
 * Formats revenue into a string with two decimal places and a '+' prefix if positive.
 * Example: 6.0 -> "+6.00", 6.123 -> "+6.12", 0.0 -> "0.00"
 */
fun formatRevenueLabel(revenue: Double): String {
    return if (revenue > 0) {
        "+${formatTwoDecimals(revenue)}"
    } else {
        "0.00"
    }
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
