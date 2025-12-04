
package com.michaeltchuang.walletsdk.core.passkeys.validator.domain.usecase

import androidx.credentials.provider.CallingAppInfo
import com.michaeltchuang.walletsdk.core.foundation.utils.AlgoKitResult


internal fun interface IsAssetLinksValid {
    suspend operator fun invoke(rpId: String, callingAppInfo: CallingAppInfo): AlgoKitResult<Boolean>
}
