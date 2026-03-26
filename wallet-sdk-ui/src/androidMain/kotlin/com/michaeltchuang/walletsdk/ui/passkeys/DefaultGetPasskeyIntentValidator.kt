package com.michaeltchuang.walletsdk.ui.passkeys

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.Passkey
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialRequestOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.GetPasskeyByCredentialId
import com.michaeltchuang.walletsdk.core.passkeys.model.GetCredentialsParams
import com.michaeltchuang.walletsdk.core.passkeys.model.GetPasskeyIntentValidationResult
import com.michaeltchuang.walletsdk.core.passkeys.model.PasskeySigningProvider
import com.michaeltchuang.walletsdk.core.passkeys.validator.AppInfoValidationResult
import com.michaeltchuang.walletsdk.core.passkeys.validator.CallingAppInfoValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.GetPasskeyIntentValidator

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class DefaultGetPasskeyIntentValidator(
    private val appInfoValidator: CallingAppInfoValidator,
    private val getPasskeyByCredentialId: GetPasskeyByCredentialId,
    private val getLocalAccount: GetLocalAccount,
) : GetPasskeyIntentValidator {
    override suspend fun validate(intent: Intent): GetPasskeyIntentValidationResult {
        val request = PendingIntentHandler.Companion.retrieveProviderGetCredentialRequest(intent)
        val requestExtras = intent.getBundleExtra(PasskeyProviderService.EXTRA_INTENT_DATA_KEY)
        val credentialIdB64 = requestExtras?.getString(PasskeyProviderService.CRED_ID_KEY)

        if (request == null || credentialIdB64.isNullOrEmpty()) {
            return GetPasskeyIntentValidationResult.UnableToExtractData
        }

        val publicKeyCredentialOption = request.credentialOptions.firstOrNull()
        val publicKeyRequest = publicKeyCredentialOption as? GetPublicKeyCredentialOption
        if (publicKeyRequest == null) {
            return GetPasskeyIntentValidationResult.InvalidRequestType
        }

        return getIntentResultValidatingAppInfo(request, publicKeyRequest, credentialIdB64)
    }

    private suspend fun getIntentResultValidatingAppInfo(
        request: ProviderGetCredentialRequest,
        publicKeyRequest: GetPublicKeyCredentialOption,
        credId: String,
    ): GetPasskeyIntentValidationResult {
        val publicKeyRequestOptions =
            PublicKeyCredentialRequestOptions(publicKeyRequest.requestJson)
        val validationResult = appInfoValidator.validateCallingApp(publicKeyRequestOptions.rpId, request.callingAppInfo)
        return when (validationResult) {
            is AppInfoValidationResult.AppInfoNotFound -> GetPasskeyIntentValidationResult.AppInfoNotFound
            is AppInfoValidationResult.FailedToValidateRP -> GetPasskeyIntentValidationResult.FailedToValidateRP
            is AppInfoValidationResult.FailedToValidateOrigin -> GetPasskeyIntentValidationResult.FailedToValidateOrigin
            is AppInfoValidationResult.Success -> {
                val passkey = getPasskeyByCredentialId(credId)
                if (passkey == null) {
                    GetPasskeyIntentValidationResult.PasskeyNotFound
                } else {
                    val params =
                        getGetCredentialsParams(
                            request,
                            publicKeyRequest,
                            publicKeyRequestOptions,
                            validationResult.callingAppInfoOrigin,
                            passkey,
                        )
                    GetPasskeyIntentValidationResult.Success(request, params)
                }
            }
        }
    }

    private suspend fun getGetCredentialsParams(
        request: ProviderGetCredentialRequest,
        publicKeyRequest: GetPublicKeyCredentialOption,
        publicKeyRequestOptions: PublicKeyCredentialRequestOptions,
        appInfoOrigin: String,
        passkey: Passkey,
    ): GetCredentialsParams {
        val signingProvider =
            when (getLocalAccount(passkey.address)) {
                is LocalAccount.SeedVault -> PasskeySigningProvider.SOLANA_SEED_VAULT
                else -> PasskeySigningProvider.BIP39_DETERMINISTIC
            }

        return GetCredentialsParams(
            address = passkey.address,
            credId = passkey.credId,
            origin = appInfoOrigin,
            request = publicKeyRequestOptions,
            userId = passkey.userId,
            username = passkey.username,
            packageName = request.callingAppInfo.packageName,
            callingAppInfo = appInfoOrigin,
            clientDataHash = publicKeyRequest.clientDataHash.takeIf { appInfoOrigin.isNotBlank() },
            signingProvider = signingProvider,
        )
    }
}
