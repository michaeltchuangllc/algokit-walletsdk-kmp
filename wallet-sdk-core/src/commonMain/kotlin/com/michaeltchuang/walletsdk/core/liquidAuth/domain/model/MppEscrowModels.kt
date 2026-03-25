package com.michaeltchuang.walletsdk.core.liquidAuth.domain.model

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Escrow-backed MPP session model.
 *
 * Note: This is protocol/domain state only. Real fund custody should live in
 * an escrow contract/account or backend signer infra, not in-app private keys.
 */
data class MppEscrowSession(
    val sessionId: String,
    val viewerAddress: String,
    val creatorAddress: String,
    val escrowAddress: String,
    val network: String,
    val initialDepositMicroAlgos: Long,
    val costPerBlockMicroAlgos: Long,
    val consumedMicroAlgos: Long = 0L,
    val blocksConsumed: Int = 0,
    val status: Status = Status.PendingDeposit,
) {
    val refundableMicroAlgos: Long
        get() = (initialDepositMicroAlgos - consumedMicroAlgos).coerceAtLeast(0L)

    val isDepleted: Boolean
        get() = refundableMicroAlgos <= 0L

    fun consumeBlocks(blocks: Int = 1): MppEscrowSession {
        require(blocks > 0) { "blocks must be greater than 0" }

        val requestedConsume = costPerBlockMicroAlgos * blocks
        val actualConsume = minOf(requestedConsume, refundableMicroAlgos)

        return copy(
            consumedMicroAlgos = consumedMicroAlgos + actualConsume,
            blocksConsumed = blocksConsumed + blocks,
            status = if (actualConsume >= refundableMicroAlgos) Status.ReadyToSettle else Status.Streaming,
        )
    }

    fun markStreaming(): MppEscrowSession = copy(status = Status.Streaming)

    fun markReadyToSettle(): MppEscrowSession = copy(status = Status.ReadyToSettle)

    fun markSettled(): MppEscrowSession = copy(status = Status.Settled)

    enum class Status {
        PendingDeposit,
        Streaming,
        ReadyToSettle,
        Settled,
    }

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            viewerAddress: String,
            creatorAddress: String,
            escrowAddress: String,
            network: String,
            initialDepositMicroAlgos: Long,
            costPerBlockMicroAlgos: Long,
        ): MppEscrowSession {
            require(initialDepositMicroAlgos > 0L) { "initialDepositMicroAlgos must be greater than 0" }
            require(costPerBlockMicroAlgos > 0L) { "costPerBlockMicroAlgos must be greater than 0" }

            return MppEscrowSession(
                sessionId = Uuid.random().toString(),
                viewerAddress = viewerAddress,
                creatorAddress = creatorAddress,
                escrowAddress = escrowAddress,
                network = network,
                initialDepositMicroAlgos = initialDepositMicroAlgos,
                costPerBlockMicroAlgos = costPerBlockMicroAlgos,
            )
        }
    }
}

data class MppEscrowSettlement(
    val sessionId: String,
    val creatorPayoutMicroAlgos: Long,
    val viewerRefundMicroAlgos: Long,
    val settlementTransactionId: String? = null,
    val refundTransactionId: String? = null,
)

data class MppEscrowDepositRequest(
    val sessionId: String,
    val fromViewerAddress: String,
    val toEscrowAddress: String,
    val amountMicroAlgos: Long,
    val note: String = "MPP Escrow Deposit",
)

data class MppEscrowDepositReceipt(
    val sessionId: String,
    val transactionId: String,
    val escrowAddress: String,
    val depositedMicroAlgos: Long,
)
