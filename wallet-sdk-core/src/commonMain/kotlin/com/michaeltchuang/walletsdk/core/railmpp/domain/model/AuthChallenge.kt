package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class AuthChallenge(
    val challenge: String,
    val sessionId: String,
    val expiresAt: Long,
)
