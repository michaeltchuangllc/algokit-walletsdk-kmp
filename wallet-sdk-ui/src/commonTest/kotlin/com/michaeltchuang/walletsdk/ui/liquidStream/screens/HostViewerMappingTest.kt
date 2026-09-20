package com.michaeltchuang.walletsdk.ui.liquidStream.screens

import com.michaeltchuang.walletsdk.core.foundation.utils.toShortenedAddress
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerDetails
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerProgress
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.components.ConnectedViewerInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostViewerMappingTest {
    @Test
    fun firstFundedSnapshotStartsFullDespitePreviousSettlementAndThenDecreases() {
        val progress = HostViewerProgress()
        assertNull(progress.update(HostViewerDetails()).progressCapacityMicroUsdc)
        val snapshot = HostViewerDetails(
            viewerAddress = "secondary-wallet",
            totalDepositMicroUsdc = 2_000_000,
            remainingBalanceMicroUsdc = 800_000,
            lastSettledMicroUsdc = 1_200_000,
            progressBalanceMicroUsdc = 600_000,
        )
        fun map(details: HostViewerDetails) = mapHostViewers(
            primary,
            listOf("primary-request", "secondary-request"),
            mapOf("secondary-request" to progress.update(details)),
        ).also { assertEquals(primary, it.first()) }.last()

        val first = map(snapshot)
        assertEquals(1.0, first.progressBalanceUSDC!! / first.progressCapacityUSDC!!)
        assertEquals(0.8, first.remainingBalanceUSDC)
        assertEquals(1.2, first.lastSettledUSDC)
        assertEquals(2.0, first.revenueCapacityUSDC)
        val settled = map(snapshot.copy(progressBalanceMicroUsdc = 300_000))
        assertEquals(0.5, settled.progressBalanceUSDC!! / settled.progressCapacityUSDC!!)
        assertEquals(first.progressCapacityUSDC, settled.progressCapacityUSDC)
    }

    @Test
    fun emptyInitialVaultStaysEmptyUntilFunded() {
        val progress = HostViewerProgress()
        val empty = progress.update(HostViewerDetails(totalDepositMicroUsdc = 0, progressBalanceMicroUsdc = 0))
        assertEquals(0L, empty.progressCapacityMicroUsdc)
        val funded = progress.update(empty.copy(totalDepositMicroUsdc = 1_000_000, progressBalanceMicroUsdc = 1_000_000))
        assertEquals(1_000_000L, funded.progressCapacityMicroUsdc)
    }

    @Test
    fun repeatedTopUpsResetOnlySecondaryProgressWithoutResettingRevenue() {
        val progress = HostViewerProgress()
        fun update(total: Long, settled: Long, available: Long = total - settled): ConnectedViewerInfo {
            val details = progress.update(
                HostViewerDetails(
                    viewerAddress = "secondary-wallet",
                    totalDepositMicroUsdc = total,
                    remainingBalanceMicroUsdc = total - settled,
                    lastSettledMicroUsdc = settled,
                    progressBalanceMicroUsdc = available,
                ),
            )
            val viewers = mapHostViewers(
                primary,
                listOf("primary-request", "secondary-request"),
                mapOf("secondary-request" to details),
            )
            assertEquals(primary, viewers.first())
            return viewers.last()
        }
        fun fraction(viewer: ConnectedViewerInfo) = viewer.progressBalanceUSDC!! / viewer.progressCapacityUSDC!!

        assertEquals(1.0, fraction(update(1_000_000, 0)))
        assertEquals(0.2, fraction(update(1_000_000, 800_000)))
        val secondDeposit = update(2_000_000, 800_000)
        assertEquals(1.0, fraction(secondDeposit))
        assertEquals(1.2, secondDeposit.progressCapacityUSDC)
        assertEquals(2.0, secondDeposit.revenueCapacityUSDC)
        assertEquals(0.8, secondDeposit.lastSettledUSDC)
        assertEquals(0.5, fraction(update(2_000_000, 1_400_000)))
        assertEquals(1.0, fraction(update(3_000_000, 1_400_000)))
        assertEquals(0.5, fraction(update(3_000_000, 2_200_000)))
        assertEquals(0.0, fraction(update(3_000_000, 3_000_000)))
    }

    @Test
    fun unavailableReadAndRepeatedSnapshotsDoNotResetTopUpBaseline() {
        val progress = HostViewerProgress()
        val initial = HostViewerDetails(
            totalDepositMicroUsdc = 1_000_000,
            progressBalanceMicroUsdc = 200_000,
        )
        progress.update(initial)
        val toppedUp = initial.copy(totalDepositMicroUsdc = 2_000_000, progressBalanceMicroUsdc = 1_200_000)
        assertEquals(1_200_000L, progress.update(toppedUp).progressCapacityMicroUsdc)
        assertNull(progress.update(HostViewerDetails()).progressCapacityMicroUsdc)
        val settled = toppedUp.copy(progressBalanceMicroUsdc = 600_000)
        assertEquals(1_200_000L, progress.update(settled).progressCapacityMicroUsdc)
        assertEquals(1_200_000L, progress.update(settled).progressCapacityMicroUsdc)
        assertEquals(
            600_000L,
            progress.update(settled.copy(viewerAddress = "another-wallet")).progressCapacityMicroUsdc,
        )
    }

    private val primary =
        ConnectedViewerInfo(
            sessionId = "primary-request",
            viewerAddress = "primary-wallet",
            remainingBalanceUSDC = 0.9,
            progressBalanceUSDC = 0.8,
            lastSettledUSDC = 0.1,
            progressCapacityUSDC = 1.0,
            revenueCapacityUSDC = 1.0,
            connectionType = IceConnectionType.LOCAL,
            currentBlockNumber = 123L,
        )

    @Test
    fun primaryKeepsExistingDataAndSecondaryUsesOnlyItsOwnDetailsRegardlessOfOrder() {
        val secondary =
            HostViewerDetails(
                viewerAddress = "secondary-wallet",
                connectionType = IceConnectionType.RELAY,
                remainingBalanceMicroUsdc = 500_000L,
                lastSettledMicroUsdc = 200_000L,
                progressBalanceMicroUsdc = 400_000L,
                totalDepositMicroUsdc = 700_000L,
            )
        val viewers =
            mapHostViewers(
                primary,
                listOf("secondary-request", "primary-request"),
                mapOf("secondary-request" to secondary, "primary-request" to secondary),
            )

        assertEquals(primary, viewers[1])
        assertEquals("secondary-wallet", viewers[0].viewerAddress)
        assertEquals(IceConnectionType.RELAY, viewers[0].connectionType)
        assertEquals(0.5, viewers[0].remainingBalanceUSDC)
        assertEquals(0.4, viewers[0].progressBalanceUSDC)
        assertEquals(0.2, viewers[0].lastSettledUSDC)
        assertEquals(0.7, viewers[0].progressCapacityUSDC)
        assertEquals(0.7, viewers[0].revenueCapacityUSDC)
    }

    @Test
    fun missingSecondaryDetailsStayUnknownEvenWhenPrimaryLeaves() {
        val viewer = mapHostViewers(primary, listOf("secondary-request"), emptyMap()).single()

        assertEquals("secondary-request", viewer.sessionId)
        assertNull(viewer.viewerAddress)
        assertNull(viewer.remainingBalanceUSDC)
        assertNull(viewer.progressBalanceUSDC)
        assertNull(viewer.lastSettledUSDC)
        assertNull(viewer.progressCapacityUSDC)
        assertNull(viewer.revenueCapacityUSDC)
        assertEquals(IceConnectionType.UNKNOWN, viewer.connectionType)
        assertEquals("N/A", hostViewerDisplayAddress(viewer.viewerAddress, emptyMap()))
        assertTrue(mapHostViewers(primary, emptyList(), emptyMap()).isEmpty())
        assertEquals(listOf(primary), mapHostViewers(primary, null, emptyMap()))
    }

    @Test
    fun settlementKeepsSecondaryDepositCapacityInsteadOfRefillingProgress() {
        val details =
            HostViewerDetails(
                remainingBalanceMicroUsdc = 2_000_000L,
                progressBalanceMicroUsdc = 1_000_000L,
                totalDepositMicroUsdc = 2_000_000L,
            )
        fun map(details: HostViewerDetails) =
            mapHostViewers(primary, listOf("secondary-request"), mapOf("secondary-request" to details)).single()

        val before = map(details)
        val after = map(details.copy(remainingBalanceMicroUsdc = 1_000_000L, lastSettledMicroUsdc = 1_000_000L))

        assertEquals(2.0, before.progressCapacityUSDC)
        assertEquals(before.progressCapacityUSDC, after.progressCapacityUSDC)
        assertEquals(before.revenueCapacityUSDC, after.revenueCapacityUSDC)
        assertEquals(0.5, after.progressBalanceUSDC!! / after.progressCapacityUSDC!!)

        val unavailable = map(details.copy(totalDepositMicroUsdc = null))
        assertNull(unavailable.progressCapacityUSDC)
        assertNull(unavailable.revenueCapacityUSDC)
        val zero = map(details.copy(totalDepositMicroUsdc = 0L))
        assertEquals(0.0, zero.progressCapacityUSDC)
        assertEquals(0.0, zero.revenueCapacityUSDC)
    }

    @Test
    fun zeroBalancesAreKnownAndPrimaryCanFillMissingFactsFromItsOwnSnapshot() {
        val details =
            HostViewerDetails(
                viewerAddress = "real-wallet",
                connectionType = IceConnectionType.STUN,
                remainingBalanceMicroUsdc = 0L,
                lastSettledMicroUsdc = 0L,
                progressBalanceMicroUsdc = 0L,
            )
        val unknownPrimary =
            primary.copy(
                viewerAddress = "",
                connectionType = IceConnectionType.UNKNOWN,
                remainingBalanceUSDC = null,
                progressBalanceUSDC = null,
                lastSettledUSDC = null,
            )
        val viewers =
            mapHostViewers(
                unknownPrimary,
                listOf("primary-request", "secondary-request"),
                mapOf("primary-request" to details, "secondary-request" to details),
            )

        viewers.forEach {
            assertEquals(0.0, it.remainingBalanceUSDC)
            assertEquals(0.0, it.progressBalanceUSDC)
            assertEquals(0.0, it.lastSettledUSDC)
            assertEquals("real-wallet", it.viewerAddress)
            assertEquals(IceConnectionType.STUN, it.connectionType)
        }
        val knownZeroPrimary = primary.copy(remainingBalanceUSDC = 0.0)
        assertEquals(
            0.0,
            mapHostViewers(
                knownZeroPrimary,
                listOf("primary-request"),
                mapOf("primary-request" to details.copy(remainingBalanceMicroUsdc = 1_000_000L)),
            ).single().remainingBalanceUSDC,
        )
    }

    @Test
    fun nfdUsesRealAddressAndOnlyUnknownWalletsDisplayNotAvailable() {
        val address = "6Z4BAS2WIVUXW4DLEVTTQHFRUMGQZZFZQ4OTIUUZCOGIJH3MEPJHMAYX3U"
        val names = mapOf(address to "viewer.algo", "secondary-request" to "wrong.algo")

        assertEquals("viewer.algo", hostViewerDisplayAddress(address, names))
        assertEquals(address.toShortenedAddress(), hostViewerDisplayAddress(address, emptyMap()))
        assertEquals("N/A", hostViewerDisplayAddress(null, names))
        assertEquals("N/A", hostViewerDisplayAddress("  ", names))
    }
}
