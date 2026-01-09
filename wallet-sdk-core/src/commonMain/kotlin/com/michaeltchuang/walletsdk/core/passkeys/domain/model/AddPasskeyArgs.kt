package com.michaeltchuang.walletsdk.core.passkeys.domain.model

data class AddPasskeyArgs(
    val siteUrl: String,
    val siteName: String,
    val algoAddress: String,
    val uid: String,
    val username: String,
    val displayName: String,
    val credId: String,
)
