package com.michaeltchuang.walletsdk.core.railmpp.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val sender: String,
    val text: String,
    val timestamp: Long,
    val amount: String? = null,
    val asset: String? = null
)
