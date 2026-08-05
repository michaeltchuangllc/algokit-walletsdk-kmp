package com.michaeltchuang.walletsdk.core.railmpp.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PaymentReceipt(
    val txId: String,
    val sessionId: String,
    val segmentIndex: Int,
    val amount: String,
    val asset: String,
    val payTo: String,
    val payFrom: String = "",
    val feePayer: String? = null,
    val facilitator: String? = null,
    val network: String,
    val timestamp: Long,
    val channelId: String? = null,
)
