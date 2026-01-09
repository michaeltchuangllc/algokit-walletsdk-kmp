package com.michaeltchuang.walletsdk.core.passkeys.model

data class CreatePasskeyCredentialCreateEntry(
    val accountName: String,
    val passkeyCount: Int,
    val algoAddress: String,
)
