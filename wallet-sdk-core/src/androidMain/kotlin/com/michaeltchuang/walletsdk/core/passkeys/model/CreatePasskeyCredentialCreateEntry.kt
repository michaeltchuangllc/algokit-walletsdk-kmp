package com.michaeltchuang.walletsdk.ui.passkeys.model

data class CreatePasskeyCredentialCreateEntry(
    val accountName: String,
    val passkeyCount: Int,
    val algoAddress: String
)
