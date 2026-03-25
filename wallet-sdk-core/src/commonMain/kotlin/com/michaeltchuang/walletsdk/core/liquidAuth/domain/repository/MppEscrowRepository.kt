package com.michaeltchuang.walletsdk.core.liquidAuth.domain.repository

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.MppEscrowDepositReceipt
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.MppEscrowSession
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.MppEscrowSettlement

/**
 * Repository abstraction for escrow-backed MPP operations.
 *
 * Implementations can be backed by:
 * - Ktor API calls to a settlement service
 * - Direct contract calls
 * - test/fake repositories
 */
interface MppEscrowRepository {
    suspend fun createEscrowSession(session: MppEscrowSession): MppEscrowSession

    suspend fun submitViewerDeposit(sessionId: String): MppEscrowDepositReceipt

    suspend fun consumeBlocks(
        sessionId: String,
        blocks: Int,
    ): MppEscrowSession

    suspend fun settleSession(sessionId: String): MppEscrowSettlement

    suspend fun getSession(sessionId: String): MppEscrowSession?
}
