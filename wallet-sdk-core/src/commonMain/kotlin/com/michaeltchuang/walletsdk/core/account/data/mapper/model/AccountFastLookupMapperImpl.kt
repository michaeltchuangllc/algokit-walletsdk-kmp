package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.toBigDecimal
import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.network.model.AccountFastLookupResponse

internal class AccountFastLookupMapperImpl : AccountFastLookupMapper {
    override fun invoke(response: AccountFastLookupResponse): AccountFastLookup =
        AccountFastLookup(
            algoValue = response.algoValue?.toBigDecimal() ?: BigDecimal.ZERO,
            usdValue = response.usdValue?.toBigDecimal() ?: BigDecimal.ZERO,
            accountExists = response.accountExists ?: false,
        )
}
