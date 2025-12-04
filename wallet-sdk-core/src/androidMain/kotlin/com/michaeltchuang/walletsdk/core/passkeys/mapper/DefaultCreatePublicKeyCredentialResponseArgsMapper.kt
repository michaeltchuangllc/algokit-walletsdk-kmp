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

package com.michaeltchuang.walletsdk.core.passkeys.mapper

import com.michaeltchuang.walletsdk.core.passkeys.domain.Bip39SignManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.CreatePublicKeyCredentialResponseArgs
import com.michaeltchuang.walletsdk.core.passkeys.model.CreatePasskeyParams


class DefaultCreatePublicKeyCredentialResponseArgsMapper (
    private val bip39SignManager: Bip39SignManager
) : CreatePublicKeyCredentialResponseArgsMapper {

    override suspend fun invoke(
        params: CreatePasskeyParams,
        appInfoOrigin: String
    ): CreatePublicKeyCredentialResponseArgs {
        with(params) {
            val userHandle = requestOptions.user.name

            // Derive deterministic keypair from HD seed
            // This ensures the same keypair is always generated for the same (address, origin, userHandle)
            val keyPair = bip39SignManager.deriveKeyPair(bip44Address, appInfoOrigin, userHandle)
                ?: throw IllegalStateException("Failed to derive keypair for address: $bip44Address")

            // Derive deterministic credential ID from the public key
            val credentialId = bip39SignManager.deriveCredentialId(keyPair)

            return CreatePublicKeyCredentialResponseArgs(
                keyPair = keyPair,
                credentialId = credentialId,
                request = requestOptions,
                appInfoOrigin = appInfoOrigin,
                appInfo = callingAppInfo,
                clientDataHash = clientDataHash
            )
        }
    }
}
