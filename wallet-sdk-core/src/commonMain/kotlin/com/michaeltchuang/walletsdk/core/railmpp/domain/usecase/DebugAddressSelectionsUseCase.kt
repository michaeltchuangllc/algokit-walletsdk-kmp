package com.michaeltchuang.walletsdk.core.railmpp.domain.usecase

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.DebugAddressSelections
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.RailMppDataRepository

class DebugAddressSelectionsUseCase(
    private val railMppDataRepository: RailMppDataRepository,
) {
    suspend fun get(): DebugAddressSelections = railMppDataRepository.getDebugAddressSelections()

    suspend fun save(selections: DebugAddressSelections) {
        railMppDataRepository.saveDebugAddressSelections(selections)
    }
}
