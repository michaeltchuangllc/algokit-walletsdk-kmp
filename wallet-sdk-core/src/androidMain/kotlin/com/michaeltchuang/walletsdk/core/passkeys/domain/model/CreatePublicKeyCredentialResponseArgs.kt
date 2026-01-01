package com.michaeltchuang.walletsdk.core.passkeys.domain.model

import androidx.credentials.provider.CallingAppInfo
import java.security.KeyPair

data class CreatePublicKeyCredentialResponseArgs(
    val keyPair: KeyPair,
    val credentialId: ByteArray,
    val request: PublicKeyCredentialCreationOptions,
    val appInfoOrigin: String,
    val appInfo: CallingAppInfo,
    val clientDataHash: ByteArray?,
)
