package com.michaeltchuang.walletsdk.core.account.domain.usecase.recoverypassphrase

import com.michaeltchuang.walletsdk.core.account.domain.model.local.AccountFastLookup
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountFastLookup
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAccountFastLookupBatch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal class GetAccountFastLookupBatchUseCase(
    private val getAccountFastLookup: GetAccountFastLookup,
) : GetAccountFastLookupBatch {
    override suspend fun invoke(addresses: List<String>): Map<String, AccountFastLookup?> =
        coroutineScope {
            addresses
                .map { address ->
                    async {
                        address to getAccountFastLookup(address).getDataOrNull()
                    }
                }.awaitAll()
                .associate { it }
        }
}
