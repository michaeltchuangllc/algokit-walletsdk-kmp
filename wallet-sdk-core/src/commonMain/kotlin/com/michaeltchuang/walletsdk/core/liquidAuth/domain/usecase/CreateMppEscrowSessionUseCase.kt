package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.MppEscrowSession
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.repository.MppEscrowRepository

class CreateMppEscrowSessionUseCase(
    private val repository: MppEscrowRepository,
) {
    suspend operator fun invoke(session: MppEscrowSession): MppEscrowSession = repository.createEscrowSession(session)
}
