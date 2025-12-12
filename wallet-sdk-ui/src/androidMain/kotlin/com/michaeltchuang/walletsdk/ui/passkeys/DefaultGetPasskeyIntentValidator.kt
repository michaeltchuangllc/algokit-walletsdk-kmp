package com.michaeltchuang.walletsdk.ui.passkeys

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialRequestOptions
import com.michaeltchuang.walletsdk.core.passkeys.domain.usecase.GetPasskeyByCredentialId
import com.michaeltchuang.walletsdk.core.passkeys.model.GetCredentialsParams
import com.michaeltchuang.walletsdk.core.passkeys.model.GetPasskeyIntentValidationResult
import com.michaeltchuang.walletsdk.core.passkeys.model.Passkey
import com.michaeltchuang.walletsdk.core.passkeys.validator.AppInfoValidationResult
import com.michaeltchuang.walletsdk.core.passkeys.validator.CallingAppInfoValidator
import com.michaeltchuang.walletsdk.core.passkeys.validator.GetPasskeyIntentValidator

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class DefaultGetPasskeyIntentValidator(
    private val appInfoValidator: CallingAppInfoValidator,
    private val getPasskeyByCredentialId: GetPasskeyByCredentialId,
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

    private fun getGetCredentialsParams(
        request: ProviderGetCredentialRequest,
        publicKeyRequest: GetPublicKeyCredentialOption,
        publicKeyRequestOptions: PublicKeyCredentialRequestOptions,
        appInfoOrigin: String,
        passkey: Passkey,
    ): GetCredentialsParams =
        GetCredentialsParams(
            bip44Address = passkey.algoAddress,
            credId = passkey.credId,
            origin = appInfoOrigin,
            request = publicKeyRequestOptions,
            userId = passkey.userId,
            username = passkey.username,
            packageName = request.callingAppInfo.packageName,
            callingAppInfo = appInfoOrigin,
            clientDataHash = publicKeyRequest.clientDataHash.takeIf { appInfoOrigin.isNotBlank() },
        )
}
