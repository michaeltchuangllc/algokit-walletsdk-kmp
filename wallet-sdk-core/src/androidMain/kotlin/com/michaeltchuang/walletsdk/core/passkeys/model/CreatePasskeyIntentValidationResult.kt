package com.michaeltchuang.walletsdk.core.passkeys.model

import androidx.credentials.provider.ProviderCreateCredentialRequest


 sealed interface CreatePasskeyIntentValidationResult {
    data object UnableToExtractData : CreatePasskeyIntentValidationResult
    data class BiometricError(val code: Int, val message: String) : CreatePasskeyIntentValidationResult
    data object InvalidRequestType : CreatePasskeyIntentValidationResult
    data object AppInfoNotFound : CreatePasskeyIntentValidationResult
    data object FailedToValidateRP : CreatePasskeyIntentValidationResult
    data object FailedToValidateOrigin : CreatePasskeyIntentValidationResult
    data object ExistingPasskey : CreatePasskeyIntentValidationResult
    data class Success(
        val request: ProviderCreateCredentialRequest,
        val params: CreatePasskeyParams
    ) : CreatePasskeyIntentValidationResult
}
