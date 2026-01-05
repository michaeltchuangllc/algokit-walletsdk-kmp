package com.michaeltchuang.walletsdk.core.network.domain.repository

import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult

internal interface AccountFastLookupRepository {
    suspend fun fetchAccountFastLookup(accountAddress: String): AlgoKitResult<AccountFastLookup>
}
