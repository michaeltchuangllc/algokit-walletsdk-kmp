package com.michaeltchuang.walletsdk.core.network.service

import com.michaeltchuang.walletsdk.core.network.model.ApiResult
import com.michaeltchuang.walletsdk.core.network.model.NfdProfile

/**
 * API service interface for resolving NFDs (Non-Fungible Domains) - human readable names (e.g.
 * "michaeltchuang.algo") that map to Algorand addresses, similar to DNS.
 *
 * NFD only supports Algorand MainNet and TestNet. Lookups made while on FutureNet are routed to
 * the TestNet NFD API.
 */
interface NfdApiService {
    /**
     * Reverse-looks up the NFD profile registered for a given Algorand address, if any.
     *
     * @param address The Algorand address to look up
     * @return ApiResult containing the [NfdProfile] or error information. A 404 [ApiResult.Error]
     * means no NFD is registered for the address, which is a normal outcome since most addresses
     * don't have one.
     */
    suspend fun getNfdForAddress(address: String): ApiResult<NfdProfile>
}
