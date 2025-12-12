package com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase

import androidx.credentials.provider.CallingAppInfo
import com.michaeltchuang.walletsdk.core.passkeys.validator.domain.repository.AppInfoValidationRepository
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult
import com.google.gson.JsonElement


internal class GetCallingAppOriginCheckingGpmAllowlistUseCase constructor(
    private val appInfoValidationRepository: AppInfoValidationRepository
) : GetCallingAppOriginCheckingGpmAllowlist {

    override suspend fun invoke(callingAppInfo: CallingAppInfo): AlgoKitResult<String> {
        return when (val result = appInfoValidationRepository.getGpmPrivilegedAppAllowlist()) {
            is AlgoKitResult.Success -> getOrigin(callingAppInfo, result.data)
            is AlgoKitResult.Error -> AlgoKitResult.Error(result.exception, result.code)
        }
    }

    private fun getOrigin(callingAppInfo: CallingAppInfo, allowlist: JsonElement): AlgoKitResult<String> {
        return try {
            AlgoKitResult.Success(callingAppInfo.getOrigin(allowlist.toString()).orEmpty().removeSuffix("/"))
        } catch (e: Exception) {
            AlgoKitResult.Error(e)
        }
    }
}
