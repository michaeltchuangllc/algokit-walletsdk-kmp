package com.michaeltchuang.walletsdk.core.railmpp.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PaymentVoucher(
    val type: String,
    val id: String,
    val appId: Long,
    val viewer: String,
    val viewerPublicKey: String,
    val creator: String,
    val blocksWatched: Int,
    val costPerBlockMicroUsdc: Long,
    val totalAmountClaimedMicroUsdc: Long,
    val remainingMicroUsdc: Long,
    val signature: String? = null,
    val channelId: String? = null,
)
