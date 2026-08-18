package com.michaeltchuang.walletsdk.core.railmpp.utils

/**
 * Shared timing/threshold constants and staleness rules for voucher settlement, used by both the
 * Android (`LiquidStreamBlockConsumptionManager`) and iOS (`LiquidAuthConnectionManager.ios.kt`)
 * block-consumption/settlement flows.
 *
 * Note: the two platforms still execute settlement differently (Android settles on-chain
 * directly in Kotlin; iOS delegates to native Swift via a callback), but the policy for *when* a
 * voucher is too stale to bother settling is identical and shouldn't drift between them.
 */
object VoucherSettlementPolicy {
    const val CHAIN_READ_TIMEOUT_MS = 10_000L
    const val CHAIN_WRITE_TIMEOUT_MS = 15_000L
    const val MAX_BLOCK_DIFF_FOR_SETTLEMENT = 888

    /**
     * Returns true if the voucher's recorded block number is too far from [currentBlock] to be
     * worth settling (e.g. a stale pending voucher from a long-since-ended session). When either
     * block number is unknown, settlement is never skipped on this basis.
     */
    fun isTooStaleToSettle(
        currentBlock: Long?,
        voucherBlockNumber: Long?,
    ): Boolean {
        if (currentBlock == null || voucherBlockNumber == null || voucherBlockNumber <= 0) return false
        val diff = (currentBlock - voucherBlockNumber).let { if (it < 0) -it else it }
        return diff > MAX_BLOCK_DIFF_FOR_SETTLEMENT
    }
}
