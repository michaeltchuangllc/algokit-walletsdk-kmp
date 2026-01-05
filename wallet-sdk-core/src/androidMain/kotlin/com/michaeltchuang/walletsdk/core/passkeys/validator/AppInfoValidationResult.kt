package com.michaeltchuang.walletsdk.core.passkeys.validator

sealed interface AppInfoValidationResult {
    data object AppInfoNotFound : AppInfoValidationResult

    data object FailedToValidateRP : AppInfoValidationResult

    data class FailedToValidateOrigin(
        val exception: Exception,
    ) : AppInfoValidationResult

    data class Success(
        val callingAppInfoOrigin: String,
    ) : AppInfoValidationResult
}
