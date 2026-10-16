package com.michaeltchuang.walletsdk.core.railmpp.domain.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
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
    @SerialName("mppNetwork")
    @JsonNames("network")
    val network: String,
    val timestamp: Long,
    val channelId: String? = null,
    val salt: String? = null,
    val billingMode: String? = null,
    /** A budget/consumption acknowledgement, not proof of on-chain settlement. */
    val settlementDeferred: Boolean = false,
)
