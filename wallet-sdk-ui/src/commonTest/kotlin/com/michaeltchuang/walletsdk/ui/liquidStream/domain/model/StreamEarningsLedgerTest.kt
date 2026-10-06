package com.michaeltchuang.walletsdk.ui.liquidStream.domain.model

import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewerInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamEarningsLedgerTest {
    private val deposit = 1_000_000L

    /** Builds a viewer whose vault has [authorized] signed and [settled] paid out (micro-USDC). */
    private fun viewer(
        id: String,
        authorized: Long,
        settled: Long,
        vaultDeposit: Long = deposit,
    ) = ConnectedViewerInfo(
        sessionId = id,
        remainingBalanceUSDC = (vaultDeposit - settled) / 1_000_000.0,
        progressBalanceUSDC = (vaultDeposit - maxOf(settled, authorized)) / 1_000_000.0,
        lastSettledUSDC = settled / 1_000_000.0,
        connectionType = IceConnectionType.LOCAL,
    )

    @Test
    fun rateUsesAuthorizedSoItDoesNotSawtoothWithBatchedPayouts() {
        val ledger = StreamEarningsLedger()
        ledger.update(listOf(viewer("a", authorized = 0, settled = 0)), currentBlock = 1000)
        // 255 blocks in at 8/block, nothing settled yet.
        ledger.update(listOf(viewer("a", authorized = 2040, settled = 0)), currentBlock = 1255)
        assertEquals(8.0, ledger.streamRatePerBlockMicroUsdc(1255))
        // Batch settles at block 1256.
        ledger.update(listOf(viewer("a", authorized = 2048, settled = 2048)), currentBlock = 1256)
        assertEquals(8.0, ledger.streamRatePerBlockMicroUsdc(1256))
        assertEquals(2048L, ledger.totalSettledMicroUsdc())
    }

    @Test
    fun departedViewerEarningsStayInStreamTotals() {
        val ledger = StreamEarningsLedger()
        ledger.update(listOf(viewer("a", 0, 0), viewer("b", 0, 0)), currentBlock = 100)
        ledger.update(listOf(viewer("a", 800, 800), viewer("b", 800, 800)), currentBlock = 200)
        ledger.update(listOf(viewer("a", 1600, 800)), currentBlock = 300) // b left
        assertEquals(2400L, ledger.totalAuthorizedMicroUsdc())
        assertEquals(1600L, ledger.totalSettledMicroUsdc())
        assertEquals(12.0, ledger.streamRatePerBlockMicroUsdc(300))
    }

    @Test
    fun reDepositBanksPreviousVaultEarnings() {
        val ledger = StreamEarningsLedger()
        ledger.update(listOf(viewer("a", 0, 0)), currentBlock = 0)
        ledger.update(listOf(viewer("a", 1000, 1000)), currentBlock = 100)
        // Fresh vault: cumulative counters restart.
        ledger.update(listOf(viewer("a", 200, 0, vaultDeposit = 500_000)), currentBlock = 125)
        assertEquals(1200L, ledger.totalAuthorizedMicroUsdc())
        assertEquals(1000L, ledger.totalSettledMicroUsdc())
    }

    @Test
    fun superchatPaidThroughVoucherIsCountedOnce() {
        val ledger = StreamEarningsLedger()
        ledger.update(listOf(viewer("a", 0, 0)), currentBlock = 0)
        // 100 blocks at 8/block plus a 2000 micro-USDC gift folded into the same voucher.
        ledger.update(listOf(viewer("a", 800 + 2_000, 0)), currentBlock = 100)
        assertEquals(28.0, ledger.streamRatePerBlockMicroUsdc(100))
        // Revenue (signed total) counts the gift before it settles, and only once after it does.
        assertEquals(2_800L, ledger.totalAuthorizedMicroUsdc())
        ledger.update(listOf(viewer("a", 2_808, 2_808)), currentBlock = 101)
        assertEquals(2_808L, ledger.totalAuthorizedMicroUsdc())
    }

    @Test
    fun rateNeedsABlockNumber() {
        val ledger = StreamEarningsLedger()
        // No block yet (e.g. polling not running): nothing is recorded.
        ledger.update(listOf(viewer("a", 800, 0)), currentBlock = null)
        assertEquals(0.0, ledger.streamRatePerBlockMicroUsdc(100))
        // Once blocks arrive the same viewers start the clock.
        ledger.update(listOf(viewer("a", 800, 0)), currentBlock = 100)
        ledger.update(listOf(viewer("a", 1_600, 0)), currentBlock = 200)
        assertEquals(16.0, ledger.streamRatePerBlockMicroUsdc(200))
    }

    @Test
    fun viewersWithoutVaultDataAreIgnored() {
        val ledger = StreamEarningsLedger()
        ledger.update(
            listOf(ConnectedViewerInfo("free", null, null, connectionType = IceConnectionType.STUN)),
            currentBlock = 10,
        )
        assertEquals(0.0, ledger.streamRatePerBlockMicroUsdc(20))
    }
}
