package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.account.domain.model.local.ActiveHdAccount
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressLite

internal interface HdAccountAddressMapper {
    operator fun invoke(
        hdKeyDetails: List<HdKeyAddressLite>,
        accountFastLookupBatch: Map<String, AccountFastLookup?>,
    ): List<ActiveHdAccount.HdAccountAddress>
}
