package com.michaeltchuang.walletsdk.core.passkeys.domain.model

data class Passkey(
    val credId: String,
    val site: PasskeySite,
    val address: String,
    val userId: String,
    val username: String,
    val displayName: String,
    val lastUsed: Long?,
) {
    val origin: String
        get() = site.url
}
