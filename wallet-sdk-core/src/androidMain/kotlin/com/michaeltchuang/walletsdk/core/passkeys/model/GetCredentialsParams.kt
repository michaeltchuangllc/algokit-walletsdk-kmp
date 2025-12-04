package com.michaeltchuang.walletsdk.core.passkeys.model

import com.michaeltchuang.walletsdk.core.passkeys.domain.model.PublicKeyCredentialRequestOptions

data class GetCredentialsParams(
    val bip44Address: String,
    val credId: String,
    val origin: String,
    val request: PublicKeyCredentialRequestOptions,
    val userId: String,
    val username: String,
    val packageName: String,
    val callingAppInfo: String?,
    val clientDataHash: ByteArray?,
)