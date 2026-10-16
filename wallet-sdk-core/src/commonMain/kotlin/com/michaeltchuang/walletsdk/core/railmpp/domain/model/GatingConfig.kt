package com.michaeltchuang.walletsdk.core.railmpp.domain.model

data class GatingConfig(
    val mode: GatingMode,
    val amount: String,
    val asset: String,
    val network: String,
    val payTo: String,
    val segmentDuration: Int? = null,
    val leadTime: Int? = null,
    val segmentBytes: Long? = null,
    val leadBytes: Long? = null,
)
