package com.michaeltchuang.walletsdk.core.passkeys

import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import com.michaeltchuang.walletsdk.core.foundation.utils.date.TimeProvider
import com.michaeltchuang.walletsdk.core.passkeys.domain.AndroidKeyStorePasskeyManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.Bip39SignManager
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.AuthenticatorAssertionResponse
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.AuthenticatorFlags
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.FidoPublicKeyCredential
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.SetPasskeyLastUsedTime
import com.michaeltchuang.walletsdk.core.passkeys.model.GetCredentialsParams
import com.michaeltchuang.walletsdk.core.passkeys.model.PasskeySigningProvider

internal class DefaultGetCredentialResponseProcessor(
    private val bip39SignManager: Bip39SignManager,
    private val androidKeyStorePasskeyManager: AndroidKeyStorePasskeyManager,
    private val setPasskeyLastUsedTime: SetPasskeyLastUsedTime,
    private val timeProvider: TimeProvider,
) : GetCredentialResponseProcessor {
    override suspend fun getResponseWithSignature(params: GetCredentialsParams): GetCredentialResponse {
        var callingOrigin: String? = params.origin
        if (params.callingAppInfo != null) {
            callingOrigin = params.callingAppInfo
        }

        val authAssertionResponse =
            getAuthAssertionResponse(params, callingOrigin).apply {
                signature =
                    when (params.signingProvider) {
                        PasskeySigningProvider.BIP39_DETERMINISTIC -> {
                            bip39SignManager.sign(params.address, params.origin, params.username, dataToSign())
                        }
                        PasskeySigningProvider.SOLANA_SEED_VAULT -> {
                            androidKeyStorePasskeyManager.sign(params.credId, dataToSign())
                        }
                    } ?: byteArrayOf()
            }
        setPasskeyLastUsedTime(params.credId, timeProvider.getCurrentTimeMillis())
        val fidoResponse = FidoPublicKeyCredential(params.credId, authAssertionResponse)
        return GetCredentialResponse(PublicKeyCredential(fidoResponse.json()))
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
