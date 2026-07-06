package com.michaeltchuang.walletsdk.core.railmpp.domain.usecase

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.RailMppDataRepository

class GetRailMppChannelSaltUseCase(
    private val railMppDataRepository: RailMppDataRepository,
) {
    suspend operator fun invoke(): ByteArray = railMppDataRepository.getOrCreateChannelSalt()
}
