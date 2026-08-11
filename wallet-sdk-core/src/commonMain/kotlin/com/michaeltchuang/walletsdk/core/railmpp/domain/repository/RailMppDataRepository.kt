package com.michaeltchuang.walletsdk.core.railmpp.domain.repository

interface RailMppDataRepository {
    suspend fun getOrCreateChannelSalt(): ByteArray

    suspend fun getDebugAddressSelections(): DebugAddressSelections

    suspend fun saveDebugAddressSelections(selections: DebugAddressSelections)
}

data class DebugAddressSelections(
    val creatorAddress: String = "",
    val viewerAddress: String = "",
    val viewerAddress2: String = "",
    val viewerAddress3: String = "",
)
