package com.michaeltchuang.walletsdk.core.account.domain.model.local

import com.ionspin.kotlin.bignum.decimal.BigDecimal

data class RegisteredHdKey(
    val address: String,
    val algoValue: BigDecimal,
    val usdValue: BigDecimal,
    val accountExists: Boolean,
    val isImportedToDB: Boolean,
    val account: Int,
    val change: Int,
    val keyIndex: Int,
)
