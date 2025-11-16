package com.michaeltchuang.walletsdk.core.network.service

import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult

internal interface AccountFastLookupApiService {
    suspend fun getAccountFastLookup(
        address: String
    ): AlgoKitResult<AccountFastLookup>
}

