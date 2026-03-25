package com.michaeltchuang.walletsdk.core.liquidAuth.data.repository

import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.MppEscrowDepositReceipt
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.MppEscrowSession
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.MppEscrowSettlement
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.repository.MppEscrowRepository
import kotlin.collections.set

/**
 * In-memory stub implementation for development and integration scaffolding.
 *
 * Replace with a real Ktor-backed implementation when backend endpoints are ready.
 */
class StubMppEscrowRepository : MppEscrowRepository {
    private val sessions = mutableMapOf<String, MppEscrowSession>()

    override suspend fun createEscrowSession(session: MppEscrowSession): MppEscrowSession {
        sessions[session.sessionId] = session
        return session
    }

    override suspend fun submitViewerDeposit(sessionId: String): MppEscrowDepositReceipt {
        val existing = requireNotNull(sessions[sessionId]) { "Session not found: $sessionId" }
        val updated = existing.markStreaming()
        sessions[sessionId] = updated

        return MppEscrowDepositReceipt(
            sessionId = sessionId,
            transactionId = "stub-deposit-$sessionId",
            escrowAddress = updated.escrowAddress,
            depositedMicroAlgos = updated.initialDepositMicroAlgos,
        )
    }

    override suspend fun consumeBlocks(
        sessionId: String,
        blocks: Int,
    ): MppEscrowSession {
        val existing = requireNotNull(sessions[sessionId]) { "Session not found: $sessionId" }
        val updated = existing.consumeBlocks(blocks)
        sessions[sessionId] = updated
        return updated
    }

    override suspend fun settleSession(sessionId: String): MppEscrowSettlement {
        val existing = requireNotNull(sessions[sessionId]) { "Session not found: $sessionId" }
        val settlement =
            MppEscrowSettlement(
                sessionId = sessionId,
                creatorPayoutMicroAlgos = existing.consumedMicroAlgos,
                viewerRefundMicroAlgos = existing.refundableMicroAlgos,
                settlementTransactionId = "stub-settlement-$sessionId",
                refundTransactionId = "stub-refund-$sessionId",
            )

        sessions[sessionId] = existing.markSettled()
        return settlement
    }

    override suspend fun getSession(sessionId: String): MppEscrowSession? = sessions[sessionId]
}
