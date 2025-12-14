package com.michaeltchuang.walletsdk.ui.passkeys.model

import androidx.credentials.provider.BeginGetPublicKeyCredentialOption

data class GetPasskeyCredentialEntry(
    val option: BeginGetPublicKeyCredentialOption,
    val credentialId: String,
    val username: String?,
    val userDisplayName: String?
)
