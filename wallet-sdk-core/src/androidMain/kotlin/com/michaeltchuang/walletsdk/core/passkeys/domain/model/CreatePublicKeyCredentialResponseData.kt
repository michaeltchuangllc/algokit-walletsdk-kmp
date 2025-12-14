package com.michaeltchuang.walletsdk.core.passkeys.domain.model

import androidx.credentials.CreatePublicKeyCredentialResponse

data class CreatePublicKeyCredentialResponseData(
    val credentialId: ByteArray,
    val response: CreatePublicKeyCredentialResponse
)
