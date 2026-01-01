
package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.account.domain.model.local.ActiveHdAccount
import com.michaeltchuang.walletsdk.core.algosdk.bip39.model.HdKeyAddressLite

internal class DefaultHdAccountAddressMapper : HdAccountAddressMapper {
    override fun invoke(
        hdKeyDetails: List<HdKeyAddressLite>,
        accountFastLookupBatch: Map<String, AccountFastLookup?>,
    ): List<ActiveHdAccount.HdAccountAddress> =
        hdKeyDetails.map { hdKeyDetail ->
            ActiveHdAccount.HdAccountAddress(
                address = hdKeyDetail.address,
                accountIndex = hdKeyDetail.index.accountIndex,
                changeIndex = hdKeyDetail.index.changeIndex,
                keyIndex = hdKeyDetail.index.keyIndex,
                fastLookup = accountFastLookupBatch[hdKeyDetail.address],
            )
        }
}
