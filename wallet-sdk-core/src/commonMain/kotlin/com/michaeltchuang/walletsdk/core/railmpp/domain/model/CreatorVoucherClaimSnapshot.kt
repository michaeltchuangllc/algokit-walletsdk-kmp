package com.michaeltchuang.walletsdk.core.railmpp.domain.model

/**
 * Snapshot of the latest payment voucher claimed by a viewer, captured by the creator side
 * (host) of a Liquid Stream session. Shared between platform-specific
 * `LiquidAuthConnectionManager` implementations (Android/iOS) so the shape of a "claim" stays
 * consistent even though the settlement mechanism itself differs per platform.
 */
data class CreatorVoucherClaimSnapshot(
    val sessionId: String,
    val viewerAddress: String,
    val viewerPublicKeyBase64: String,
    val signatureBase64: String,
    val totalAmountClaimedMicroUsdc: Long,
    val channelIdBase64: String? = null,
    val blockNumber: Long? = null,
)
