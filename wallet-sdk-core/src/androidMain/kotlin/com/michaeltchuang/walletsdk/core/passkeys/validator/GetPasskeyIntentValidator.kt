package com.michaeltchuang.walletsdk.core.passkeys.validator

import android.content.Intent
import com.michaeltchuang.walletsdk.core.passkeys.model.GetPasskeyIntentValidationResult

interface GetPasskeyIntentValidator {
    suspend fun validate(intent: Intent): GetPasskeyIntentValidationResult
}
