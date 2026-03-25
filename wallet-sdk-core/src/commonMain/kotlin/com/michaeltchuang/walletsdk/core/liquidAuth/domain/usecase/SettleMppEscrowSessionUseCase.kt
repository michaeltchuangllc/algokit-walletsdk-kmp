package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.MppEscrowSettlement
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.repository.MppEscrowRepository

class SettleMppEscrowSessionUseCase(
    private val repository: MppEscrowRepository,
) {
    suspend operator fun invoke(sessionId: String): MppEscrowSettlement = repository.settleSession(sessionId)
}
