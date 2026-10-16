package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.michaeltchuang.walletsdk.core.account.domain.model.custom.AccountLite

private val minimumUsdcBalance = BigDecimal.parseString("1.00")
private val zeroBalance = BigDecimal.fromInt(0)

fun checkMinimumBalanceRequired(account: AccountLite): String? {
    val usdcBalance = parseBalance(account.usdcBalance)
    if (usdcBalance < minimumUsdcBalance) {
        return "Insufficient USDC balance. Minimum required is 1.00 USDC."
    }

    val accountBalance = parseBalance(account.balance)
    if (accountBalance <= zeroBalance) {
        return "Insufficient account balance. Please select an account with balance greater than 0."
    }

    return null
}

private fun parseBalance(balance: String?): BigDecimal =
    if (balance.isNullOrBlank()) {
        zeroBalance
    } else {
        try {
            BigDecimal.parseString(balance)
        } catch (_: Exception) {
            zeroBalance
        }
    }
