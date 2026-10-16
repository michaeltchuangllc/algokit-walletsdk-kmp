package com.michaeltchuang.walletsdk.core.liquidAuth.domain.model

/**
 * Data class representing a Liquid Auth offer
 */
data class LiquidAuthOffer(
    val requestId: String,
    val liquidAuthUrl: String,
    val origin: String,
)
