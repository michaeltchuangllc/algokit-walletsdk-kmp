package com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.MppEscrowDepositReceipt
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.repository.MppEscrowRepository

class SubmitMppViewerDepositUseCase(
    private val repository: MppEscrowRepository,
) {
    suspend operator fun invoke(sessionId: String): MppEscrowDepositReceipt = repository.submitViewerDeposit(sessionId)
}
