package com.michaeltchuang.walletsdk.core.network.service

import com.michaeltchuang.walletsdk.core.network.model.ApiResult
import com.michaeltchuang.walletsdk.core.network.model.AssetDetailResponse

/**
 * API service interface for retrieving asset details from Algorand node/indexer
 */
interface AssetDetailApiService {
    /**
     * Get asset detail information for a given asset ID
     *
     * @param assetId The asset ID to fetch details for
     * @return ApiResult containing AssetDetailResponse or error information
     */
    suspend fun getAssetDetail(assetId: Long): ApiResult<AssetDetailResponse>
}
