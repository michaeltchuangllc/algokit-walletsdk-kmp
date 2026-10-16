package com.michaeltchuang.walletsdk.core.network.usecase

import com.michaeltchuang.walletsdk.core.network.model.ApiResult
import com.michaeltchuang.walletsdk.core.network.model.NfdProfile
import com.michaeltchuang.walletsdk.core.network.service.NfdApiService

/**
 * Looks up the NFD (Non-Fungible Domain) profile - name and avatar URL - associated with an
 * Algorand address, if any.
 *
 * NFDs are human readable names (e.g. "michaeltchuang.algo") that map to Algorand addresses,
 * similar to DNS. Not every address has an NFD, so this returns `null` when none is registered,
 * as well as when the lookup fails, since this is meant for best-effort display purposes (e.g.
 * showing an NFD username/avatar next to an address on a liquid stream screen).
 */
fun interface GetNfdProfileForAddress {
    suspend operator fun invoke(address: String): NfdProfile?
}

internal class GetNfdProfileForAddressUseCase(
    private val nfdApiService: NfdApiService,
) : GetNfdProfileForAddress {
    override suspend fun invoke(address: String): NfdProfile? =
        when (val result = nfdApiService.getNfdForAddress(address)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error, is ApiResult.NetworkError -> null
        }
}
