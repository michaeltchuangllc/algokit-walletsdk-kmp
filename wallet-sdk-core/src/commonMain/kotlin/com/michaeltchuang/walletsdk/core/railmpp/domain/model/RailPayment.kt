package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class RailPayment(
    val railId: String,
    val version: Int,
    val nonce: String,
    val paymentPayload: Any,
    val paymentRequirements: Any,
)