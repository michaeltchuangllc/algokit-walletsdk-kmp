package com.michaeltchuang.walletsdk.core.passkeys.validator.domain.repository

import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.model.AssetLinkCheckResult
import kotlinx.serialization.json.JsonElement

internal interface AppInfoValidationRepository {
    suspend fun getGpmPrivilegedAppAllowlist(): AlgoKitResult<JsonElement>

    suspend fun getAssetLinkCheckResult(
        url: String,
        pkgName: String,
        certId: String,
    ): AlgoKitResult<AssetLinkCheckResult>
}
