package com.michaeltchuang.walletsdk.core.railmpp.data.repository

import com.michaeltchuang.walletsdk.core.railmpp.data.local.RailMppDataStore
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.DebugAddressSelections
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.RailMppDataRepository

class RailMppDataRepositoryImpl : RailMppDataRepository {
    override suspend fun getOrCreateChannelSalt(): ByteArray = RailMppDataStore.getOrCreateChannelSalt()

    override suspend fun getDebugAddressSelections(): DebugAddressSelections = RailMppDataStore.getDebugAddressSelections()

    override suspend fun saveDebugAddressSelections(selections: DebugAddressSelections) {
        RailMppDataStore.saveDebugAddressSelections(selections)
    }
}
