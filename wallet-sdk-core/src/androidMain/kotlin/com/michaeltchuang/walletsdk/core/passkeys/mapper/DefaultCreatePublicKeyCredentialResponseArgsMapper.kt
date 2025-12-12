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
