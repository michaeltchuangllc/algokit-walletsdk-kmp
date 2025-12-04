
package com.michaeltchuang.walletsdk.core.passkeys.validator

import android.content.Intent
import com.michaeltchuang.walletsdk.core.passkeys.model.CreatePasskeyIntentValidationResult

interface CreatePasskeyIntentValidator {
    suspend fun validate(intent: Intent): CreatePasskeyIntentValidationResult
}
