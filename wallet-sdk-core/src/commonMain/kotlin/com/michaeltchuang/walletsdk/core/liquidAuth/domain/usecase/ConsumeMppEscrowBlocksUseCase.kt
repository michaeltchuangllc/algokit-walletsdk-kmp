package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.MppEscrowSession
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.repository.MppEscrowRepository

class ConsumeMppEscrowBlocksUseCase(
    private val repository: MppEscrowRepository,
) {
    suspend operator fun invoke(
        sessionId: String,
        blocks: Int = 1,
    ): MppEscrowSession = repository.consumeBlocks(sessionId, blocks)
}
