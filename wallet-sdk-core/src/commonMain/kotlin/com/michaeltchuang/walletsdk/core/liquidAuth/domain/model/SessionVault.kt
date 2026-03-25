package com.michaeltchuang.walletsdk.core.liquidAuth.domain.model

/**
 * Generic session vault for block-based streaming deductions.
 *
 * This model is network/payment-rail agnostic and can be reused by any streaming flow.
 */
data class SessionVault(
    val sessionId: String,
    val initialDepositMicroUnits: Long,
    val costPerBlockMicroUnits: Long,
    val blocksConsumed: Int = 0,
    val consumedMicroUnits: Long = 0,
) {
    val remainingMicroUnits: Long
        get() = (initialDepositMicroUnits - consumedMicroUnits).coerceAtLeast(0L)

    val isDepleted: Boolean
        get() = remainingMicroUnits <= 0L

    fun consumeBlocks(blockCount: Int = 1): SessionVaultDeduction {
        require(blockCount > 0) { "blockCount must be greater than 0" }

        val requestedDeduction = costPerBlockMicroUnits * blockCount
        val actualDeduction = minOf(requestedDeduction, remainingMicroUnits)
        val updatedVault =
            copy(
                blocksConsumed = blocksConsumed + blockCount,
                consumedMicroUnits = consumedMicroUnits + actualDeduction,
            )

        return SessionVaultDeduction(
            previousVault = this,
            updatedVault = updatedVault,
            deductedMicroUnits = actualDeduction,
            blocksConsumedNow = blockCount,
        )
    }

    companion object {
        fun create(
            sessionId: String,
            initialDepositMicroUnits: Long,
            costPerBlockMicroUnits: Long,
        ): SessionVault {
            require(initialDepositMicroUnits > 0L) { "initialDepositMicroUnits must be greater than 0" }
            require(costPerBlockMicroUnits > 0L) { "costPerBlockMicroUnits must be greater than 0" }

            return SessionVault(
                sessionId = sessionId,
                initialDepositMicroUnits = initialDepositMicroUnits,
                costPerBlockMicroUnits = costPerBlockMicroUnits,
            )
        }
    }
}

data class SessionVaultDeduction(
    val previousVault: SessionVault,
    val updatedVault: SessionVault,
    val deductedMicroUnits: Long,
    val blocksConsumedNow: Int,
) {
    val totalBlocksConsumed: Int
        get() = updatedVault.blocksConsumed

    val totalConsumedMicroUnits: Long
        get() = updatedVault.consumedMicroUnits

    val isDepleted: Boolean
        get() = updatedVault.isDepleted
}
