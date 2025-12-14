package com.michaeltchuang.walletsdk.core.passkeys.model

import androidx.credentials.provider.ProviderGetCredentialRequest

sealed interface GetPasskeyIntentValidationResult {
    data object UnableToExtractData : GetPasskeyIntentValidationResult
    data object InvalidRequestType : GetPasskeyIntentValidationResult
    data object AppInfoNotFound : GetPasskeyIntentValidationResult
    data object FailedToValidateRP : GetPasskeyIntentValidationResult
    data object FailedToValidateOrigin : GetPasskeyIntentValidationResult
    data class Success(
        val request: ProviderGetCredentialRequest,
        val params: GetCredentialsParams
    ) : GetPasskeyIntentValidationResult

    data object PasskeyNotFound : GetPasskeyIntentValidationResult
}
