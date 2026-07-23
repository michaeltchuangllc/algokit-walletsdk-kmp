package com.michaeltchuang.walletsdk.core.railmpp.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PaymentRequest(
    val id: String,
    val sessionId: String,
    val segmentIndex: Int,
    val amount: String,
    val asset: String,
    val network: String,
    val payTo: String,
    val ttl: Int,
    val nonce: String,
    val meta: PaymentRequestMeta,
    val railPayload: JsonElement? = null,
    val channelId: String? = null,
    val salt: String? = null,
)
