package com.michaeltchuang.walletsdk.core.passkeys.validator

import androidx.credentials.provider.CallingAppInfo

interface CallingAppInfoValidator {
    suspend fun validateCallingApp(
        rpId: String,
        callingAppInfo: CallingAppInfo?,
    ): AppInfoValidationResult
}
