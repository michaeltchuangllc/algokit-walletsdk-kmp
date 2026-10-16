package com.michaeltchuang.walletsdk.core.railmpp.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class HelloMessage(
    val reference: String,
    val viewer: String,
    val viewerPublicKey: String,
)
