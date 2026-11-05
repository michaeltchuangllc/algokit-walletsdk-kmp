package com.michaeltchuang.walletsdk.core.passkeys

import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import app.perawallet.deterministicP256.DeterministicP256
import com.michaeltchuang.walletsdk.core.foundation.utils.date.TimeProvider
import com.michaeltchuang.walletsdk.core.passkeys.domain.AlgorandSeedVaultPasskeyKeyManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.AndroidKeyStorePasskeyManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.Bip39SignManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.SolanaSeedVaultPasskeyKeyManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.WebAuthnUtils
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.AuthenticatorAssertionResponse
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.AuthenticatorFlags
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.FidoPublicKeyCredential
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.SetPasskeyLastUsedTime
import com.michaeltchuang.walletsdk.core.passkeys.model.GetCredentialsParams
import com.michaeltchuang.walletsdk.core.passkeys.model.PasskeySigningProvider
import io.github.aakira.napier.Napier
import java.security.KeyPair

internal class DefaultGetCredentialResponseProcessor(
    private val bip39SignManager: Bip39SignManager,
    private val algorandSeedVaultPasskeyKeyManager: AlgorandSeedVaultPasskeyKeyManager,
    private val solanaSeedVaultPasskeyKeyManager: SolanaSeedVaultPasskeyKeyManager,
    private val androidKeyStorePasskeyManager: AndroidKeyStorePasskeyManager,
    private val setPasskeyLastUsedTime: SetPasskeyLastUsedTime,
    private val timeProvider: TimeProvider,
) : GetCredentialResponseProcessor {
    override suspend fun getResponseWithSignature(params: GetCredentialsParams): GetCredentialResponse {
        var callingOrigin: String? = params.origin
        if (params.callingAppInfo != null) {
            callingOrigin = params.callingAppInfo
        }

        Napier.d(
            "sign-in provider=${params.signingProvider}, address=${params.address}, credId=${params.credId}, " +
                "origin=${params.origin}, user=${params.username}",
            tag = "SeedVaultPasskey",
        )
        val authAssertionResponse =
            getAuthAssertionResponse(params, callingOrigin).apply {
                signature =
                    when (params.signingProvider) {
                        PasskeySigningProvider.BIP39_DETERMINISTIC -> {
                            bip39SignManager.sign(params.address, params.origin, params.username, dataToSign())
                        }
                        PasskeySigningProvider.ALGORAND_SEED_VAULT_DERIVED -> {
                            signWithSeedVault(params, dataToSign()) {
                                algorandSeedVaultPasskeyKeyManager.deriveKeyPair(params.address, params.origin, params.username)
                            }
                        }
                        PasskeySigningProvider.SOLANA_SEED_VAULT_DERIVED -> {
                            signWithSeedVault(params, dataToSign()) {
                                solanaSeedVaultPasskeyKeyManager.deriveKeyPair(params.address, params.origin, params.username)
                            }
                        }
                        PasskeySigningProvider.ANDROID_KEYSTORE_SECP256R1 -> {
                            androidKeyStorePasskeyManager.sign(params.credId, dataToSign())
                        }
                    } ?: byteArrayOf()
            }
        setPasskeyLastUsedTime(params.credId, timeProvider.getCurrentTimeMillis())
        val fidoResponse = FidoPublicKeyCredential(params.credId, authAssertionResponse)
        return GetCredentialResponse(PublicKeyCredential(fidoResponse.json()))
    }

    /**
     * Derives the key again and checks it is the key the passkey was registered with (the credential id
     * is the hash of its public key), so a changed derivation fails with a clear error and not as a bad
     * signature on the website.
     */
    private suspend fun signWithSeedVault(
        params: GetCredentialsParams,
        payload: ByteArray,
        derive: suspend () -> KeyPair?,
    ): ByteArray {
        val keyPair = derive() ?: error("Seed Vault did not derive a key")
        val derivedCredId = WebAuthnUtils.b64Encode(bip39SignManager.deriveCredentialId(keyPair))
        Napier.d(
            "stored credId=${params.credId}, derived credId=$derivedCredId, origin=${params.origin}, user=${params.username}",
            tag = "SeedVaultPasskey",
        )
        check(derivedCredId == params.credId) {
            "The Seed Vault key no longer matches this passkey. Remove the passkey and register it again."
        }
        return DeterministicP256().signWithDomainSpecificKeyPair(keyPair, payload)
    }

    private fun getAuthAssertionResponse(
        params: GetCredentialsParams,
        origin: String?,
    ): AuthenticatorAssertionResponse =
        AuthenticatorAssertionResponse(
            requestOptions = params.request,
            origin = origin,
            authFlags = AuthenticatorFlags(),
            userHandle = params.userId,
            packageName = params.packageName,
            clientDataHash = params.clientDataHash,
        )
}
