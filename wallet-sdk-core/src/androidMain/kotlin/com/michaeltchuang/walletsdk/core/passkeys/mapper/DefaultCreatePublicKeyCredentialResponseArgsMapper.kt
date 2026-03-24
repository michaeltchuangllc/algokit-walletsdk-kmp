package com.michaeltchuang.walletsdk.core.passkeys.mapper

import com.michaeltchuang.walletsdk.core.passkeys.domain.AndroidKeyStorePasskeyManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.Bip39SignManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.WebAuthnUtils
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.CreatePublicKeyCredentialResponseArgs
import com.michaeltchuang.walletsdk.core.passkeys.model.CreatePasskeyParams
import com.michaeltchuang.walletsdk.core.passkeys.model.PasskeySigningProvider

class DefaultCreatePublicKeyCredentialResponseArgsMapper(
    private val bip39SignManager: Bip39SignManager,
    private val androidKeyStorePasskeyManager: AndroidKeyStorePasskeyManager,
) : CreatePublicKeyCredentialResponseArgsMapper {
    override suspend fun invoke(
        params: CreatePasskeyParams,
        appInfoOrigin: String,
    ): CreatePublicKeyCredentialResponseArgs {
        with(params) {
            val userHandle = requestOptions.user.name

            val (keyPair, credentialId) =
                when (signingProvider) {
                    PasskeySigningProvider.BIP39_DETERMINISTIC -> {
                        val keyPair =
                            bip39SignManager.deriveKeyPair(address, appInfoOrigin, userHandle)
                                ?: throw IllegalStateException("Failed to derive keypair for address: $address")
                        keyPair to bip39SignManager.deriveCredentialId(keyPair)
                    }
                    PasskeySigningProvider.SOLANA_SEED_VAULT -> {
                        val credentialId = androidKeyStorePasskeyManager.generateRandomCredentialId()
                        val credentialIdBase64 = WebAuthnUtils.b64Encode(credentialId)
                        val keyPair =
                            androidKeyStorePasskeyManager.createOrGetKeyPair(credentialIdBase64)
                                ?: throw IllegalStateException("Failed to create Android Keystore keypair for credential: $credentialIdBase64")
                        keyPair to credentialId
                    }
                }

            return CreatePublicKeyCredentialResponseArgs(
                keyPair = keyPair,
                credentialId = credentialId,
                request = requestOptions,
                appInfoOrigin = appInfoOrigin,
                appInfo = callingAppInfo,
                clientDataHash = clientDataHash,
            )
        }
    }
}
