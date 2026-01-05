package com.michaeltchuang.walletsdk.core.passkeys.model

import androidx.credentials.provider.CallingAppInfo
import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialCreationOptions

data class CreatePasskeyParams(
    val requestOptions: PublicKeyCredentialCreationOptions,
    val callingAppInfo: CallingAppInfo,
    val clientDataHash: ByteArray?,
    val algoAddress: String,
    val appInfoOrigin: String,
) {
    val rpId: String
        get() = requestOptions.rp.id
}
