package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class AuthIdentity(
    val address: String,
    val credentialId: String? = null,
    val provider: String,
    val meta: Map<String, Any>? = null,
)
