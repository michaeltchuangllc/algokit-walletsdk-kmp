package com.michaeltchuang.walletsdk.core.railmpp.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RailPayment(
    val railId: String,
    val version: Int,
    val nonce: String,
    val paymentPayload: JsonElement,
    val paymentRequirements: JsonElement,
)
