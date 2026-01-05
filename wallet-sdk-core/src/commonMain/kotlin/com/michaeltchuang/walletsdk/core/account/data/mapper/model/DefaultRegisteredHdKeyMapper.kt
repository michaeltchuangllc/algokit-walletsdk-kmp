package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.account.domain.model.local.ActiveHdAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKey

internal class DefaultRegisteredHdKeyMapper : RegisteredHdKeyMapper {
    override fun invoke(
        hdAccountAddress: ActiveHdAccount.HdAccountAddress,
        fastLookupAccount: AccountFastLookup?,
        isAlreadyImported: Boolean,
    ): RegisteredHdKey =
        RegisteredHdKey(
            address = hdAccountAddress.address,
            algoValue = fastLookupAccount?.algoValue ?: BigDecimal.ZERO,
            usdValue = fastLookupAccount?.usdValue ?: BigDecimal.ZERO,
            accountExists = fastLookupAccount?.accountExists ?: false,
            account = hdAccountAddress.accountIndex,
            change = hdAccountAddress.changeIndex,
            keyIndex = hdAccountAddress.keyIndex,
            isImportedToDB = isAlreadyImported,
        )
}
