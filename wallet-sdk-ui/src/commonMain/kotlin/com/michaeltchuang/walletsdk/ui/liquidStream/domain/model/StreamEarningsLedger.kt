package com.michaeltchuang.walletsdk.ui.liquidStream.domain.model

import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewerInfo
import kotlin.math.roundToLong

private const val MICRO_PER_USDC = 1_000_000.0

/**
 * Stream-lifetime earnings for the host, kept per viewer session.
 *
 * - **Authorized** = what the viewer has signed for (cumulative voucher). It grows every block,
 *   so it yields smooth averages. Superchats are paid by bumping the same voucher, so they are
 *   already included. Derived from on-chain fields: `remaining + settled − progress`
 *   (since `remaining = deposit − settled` and `progress = deposit − max(settled, voucher)`).
 * - **Settled** = what has actually been paid out on-chain (moves in payout batches).
 *
 * Sessions are retained after a viewer leaves, and a re-deposit (cumulative counters dropping)
 * banks the previous vault's totals, so stream totals never go backwards.
 */
internal class StreamEarningsLedger {
    private class Entry {
        var bankedAuthorized = 0L
        var authorized = 0L
        var bankedSettled = 0L
        var settled = 0L

        val totalAuthorized get() = bankedAuthorized + authorized
        val totalSettled get() = bankedSettled + settled
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** First block at which any paid viewer session was seen. */
    private var streamStartBlock: Long? = null

    /** Records the latest vault values for currently connected viewers at [currentBlock]. */
    fun update(
        viewers: List<ConnectedViewerInfo>,
        currentBlock: Long?,
    ) {
        if (currentBlock == null) return
        viewers.forEach { viewer ->
            val settled = viewer.lastSettledUSDC?.toMicro() ?: return@forEach
            val entry = entries.getOrPut(viewer.sessionId) { Entry() }
            if (streamStartBlock == null) streamStartBlock = currentBlock
            val authorized = viewer.authorizedMicroUsdc(settled)
            if (authorized < entry.authorized || settled < entry.settled) {
                // New vault for the same session: keep the previous vault's earnings.
                entry.bankedAuthorized += entry.authorized
                entry.bankedSettled += entry.settled
            }
            entry.authorized = authorized
            entry.settled = settled
        }
    }

    fun totalAuthorizedMicroUsdc(): Long = entries.values.sumOf { it.totalAuthorized }

    fun totalSettledMicroUsdc(): Long = entries.values.sumOf { it.totalSettled }

    /** All signed vouchers (streaming + superchats) ÷ blocks since the first paid session. */
    fun streamRatePerBlockMicroUsdc(currentBlock: Long?): Double {
        val start = streamStartBlock ?: return 0.0
        val blocks = ((currentBlock ?: return 0.0) - start).coerceAtLeast(1L)
        return totalAuthorizedMicroUsdc() / blocks.toDouble()
    }
}

private fun Double.toMicro(): Long = (this * MICRO_PER_USDC).roundToLong()

/** Cumulative signed voucher amount; never below what has already settled. */
private fun ConnectedViewerInfo.authorizedMicroUsdc(settledMicro: Long): Long {
    val remaining = remainingBalanceUSDC?.toMicro() ?: return settledMicro
    val progress = progressBalanceUSDC?.toMicro() ?: return settledMicro
    return (remaining + settledMicro - progress).coerceAtLeast(settledMicro)
}
