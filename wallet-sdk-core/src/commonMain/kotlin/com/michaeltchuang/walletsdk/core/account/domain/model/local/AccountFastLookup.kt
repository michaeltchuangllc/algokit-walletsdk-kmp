package com.michaeltchuang.walletsdk.core.account.domain.model.local

import com.ionspin.kotlin.bignum.decimal.BigDecimal


data class AccountFastLookup(
    val algoValue: BigDecimal,
    val usdValue: BigDecimal,
    val accountExists: Boolean
)
