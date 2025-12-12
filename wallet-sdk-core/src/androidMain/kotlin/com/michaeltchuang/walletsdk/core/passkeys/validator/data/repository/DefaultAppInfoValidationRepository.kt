package com.michaeltchuang.walletsdk.core.passkeys.validator.data.repository

import com.michaeltchuang.walletsdk.core.passkeys.validator.data.model.AssetLinkCheckResultResponse
import com.michaeltchuang.walletsdk.core.passkeys.validator.data.network.AssetLinksApiService
import com.michaeltchuang.walletsdk.core.passkeys.validator.data.network.GStaticApiService
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.model.AssetLinkCheckResult
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.repository.AppInfoValidationRepository
import com.google.gson.JsonElement
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult
import kotlinx.serialization.json.Json


internal class DefaultAppInfoValidationRepository constructor(
    private val gStaticApiService: GStaticApiService,
    private val assetLinksApiService: AssetLinksApiService
) : AppInfoValidationRepository {

    override suspend fun getGpmPrivilegedAppAllowlist(): AlgoKitResult<JsonElement> {
        return try {
            val allowlist = gStaticApiService.getPrivilegedAppAllowlist().body()
            if (allowlist == null) AlgoKitResult.Error(Exception()) else AlgoKitResult.Success(allowlist)
        } catch (e: Exception) {
            AlgoKitResult.Error(e)
        }
    }

    override suspend fun getAssetLinkCheckResult(
        url: String,
        pkgName: String,
        certId: String
    ): AlgoKitResult<AssetLinkCheckResult> {
        return try {
            val responseJson = assetLinksApiService.getAssetLinksCheckResult(url, pkgName, certId)
            val response = Json.decodeFromString<AssetLinkCheckResultResponse>(responseJson)
            val result = AssetLinkCheckResult(response.linked)
            AlgoKitResult.Success(result)
        } catch (e: Exception) {
            AlgoKitResult.Error(e)
        }
    }
}
