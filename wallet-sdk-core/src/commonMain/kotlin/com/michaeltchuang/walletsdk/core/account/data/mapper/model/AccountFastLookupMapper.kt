package com.michaeltchuang.walletsdk.core.account.data.mapper.model

import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.network.model.AccountFastLookupResponse

internal interface AccountFastLookupMapper {
    operator fun invoke(response: AccountFastLookupResponse): AccountFastLookup
}
