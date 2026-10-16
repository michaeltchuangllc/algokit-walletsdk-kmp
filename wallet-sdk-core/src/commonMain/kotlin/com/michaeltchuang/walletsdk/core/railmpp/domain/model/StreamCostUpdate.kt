package com.michaeltchuang.walletsdk.core.railmpp.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class StreamCostUpdate(
    val costMicroUsdc: Long,
)
