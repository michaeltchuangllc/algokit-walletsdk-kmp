package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.account.domain.model.local.ActiveHdAccount
import com.michaeltchuang.walletsdk.core.account.domain.model.local.RegisteredHdKey


internal interface RegisteredHdKeyMapper {
    operator fun invoke(
        hdAccountAddress: ActiveHdAccount.HdAccountAddress,
        fastLookupAccount: AccountFastLookup?,
        isAlreadyImported: Boolean
    ): RegisteredHdKey
}
