package com.michaeltchuang.walletsdk.core.railmpp.data.repository

import com.michaeltchuang.walletsdk.core.railmpp.data.local.RailMppDataStore
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.RailMppDataRepository

class RailMppDataRepositoryImpl : RailMppDataRepository {
    override suspend fun getOrCreateChannelSalt(): ByteArray = RailMppDataStore.getOrCreateChannelSalt()
}
