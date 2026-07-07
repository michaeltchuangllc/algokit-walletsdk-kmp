package com.michaeltchuang.walletsdk.core.railmpp.domain.repository

interface RailMppDataRepository {
    suspend fun getOrCreateChannelSalt(): ByteArray
}
