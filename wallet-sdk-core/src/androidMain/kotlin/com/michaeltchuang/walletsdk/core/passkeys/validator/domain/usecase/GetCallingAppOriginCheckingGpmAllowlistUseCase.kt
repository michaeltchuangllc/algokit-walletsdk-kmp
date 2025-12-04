/*
 * Copyright 2022-2025 Pera Wallet, LDA
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

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
