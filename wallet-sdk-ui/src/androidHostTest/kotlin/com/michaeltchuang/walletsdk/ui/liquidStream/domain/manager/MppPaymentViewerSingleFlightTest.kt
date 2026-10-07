package com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager

import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelObserver
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelState
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BillingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.EnforcementMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequestMeta
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.sessionVaultMinimumBalanceMicroUsdc
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class MppPaymentViewerSingleFlightTest {
    @Test
    fun `EXPECT initial held consent to keep the card refreshing from twelve to zero`() =
        assertCardRefreshesWhileConsentIsHeld(gated = false)

    @Test
    fun `EXPECT gated held consent to keep the card refreshing from twelve to zero`() =
        assertCardRefreshesWhileConsentIsHeld(gated = true)

    private fun assertCardRefreshesWhileConsentIsHeld(gated: Boolean) =
        scenario {
            val thresholdData = MppPayments.SessionDynamicData(260, 248, 254, 7)
            val thresholdSnapshot = MppPayments.SessionProgressSnapshot(260, 12, 6, 248, 254, 7)
            val exhaustedSnapshot = MppPayments.SessionProgressSnapshot(260, 0, 0, 260, 260, 7)
            var chainSnapshot = thresholdSnapshot
            balance = 12L
            // Keep the independent eligibility reads stale: only the UI snapshot RPC advances.
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns thresholdData
            coEvery { MppPayments.getSessionProgressSnapshotFromVault(any()) } answers { chainSnapshot }
            val consent = CompletableDeferred<ConsentApproval>()
            var snapshotAtPrompt: MppPayments.SessionProgressSnapshot? = null
            requestConsent = {
                snapshotAtPrompt = vaultSnapshots.lastOrNull()
                consent.await()
            }

            // Do not call refresh(): the initial/gated handle must start polling before awaiting consent.
            if (gated) channel.gate() else channel.request()
            runCurrent()
            assertEquals(thresholdSnapshot, snapshotAtPrompt)
            assertEquals(thresholdSnapshot, vaultSnapshots.lastOrNull())
            assertEquals(1, prompts)
            assertFalse(consent.isCompleted)
            val progressAtPrompt = progress.toList()
            val readsAtPrompt = reads
            val beforeSettlement = vaultSnapshots.size

            chainSnapshot = exhaustedSnapshot
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(exhaustedSnapshot, vaultSnapshots.lastOrNull())
            assertEquals(0L, vaultSnapshots.last().remainingSettledMicroUsdc)
            assertEquals(listOf(exhaustedSnapshot), vaultSnapshots.drop(beforeSettlement).distinct())

            // A lagging node reports less settlement in the same round, with the same voucher.
            // Neither that RPC nor repeated gating may restore twelve or reopen consent.
            chainSnapshot = MppPayments.SessionProgressSnapshot(260, 12, 0, 248, 260, 7)
            repeat(5) { channel.gate() }
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(listOf(exhaustedSnapshot), vaultSnapshots.drop(beforeSettlement).distinct())
            assertFalse(consent.isCompleted)
            assertEquals(1, prompts)
            assertEquals(0, deposits)
            assertEquals(emptyList(), processing)
            assertEquals(progressAtPrompt, progress, "Consent-time polling must be UI-only")
            assertEquals(readsAtPrompt, reads, "Consent-time polling must not retry payment eligibility")
            coVerify(exactly = 0) { MppPayments.topUpSessionVault(any(), any()) }
            coVerify(exactly = 0) { MppPayments.openSessionAndDeposit(any(), any(), any(), any()) }
        }

    @Test
    fun `EXPECT stale lower settled snapshots not to restore higher remaining in the same round`() =
        assertStaleSnapshotRejected(MppPayments.SessionDynamicData(260, 190, 210, 7))

    @Test
    fun `EXPECT stale lower total deposit snapshots not to overwrite the same round`() =
        assertStaleSnapshotRejected(MppPayments.SessionDynamicData(259, 202, 210, 7))

    @Test
    fun `EXPECT stale lower latest voucher snapshots not to overwrite the same round`() =
        assertStaleSnapshotRejected(MppPayments.SessionDynamicData(260, 202, 209, 7))

    private fun assertStaleSnapshotRejected(staleData: MppPayments.SessionDynamicData) {
        // Exercise both publication paths, not just the separate full-snapshot refresh.
        listOf(false, true).forEach { staleEligibilityRead ->
            scenario {
                val newestData = MppPayments.SessionDynamicData(260, 202, 210, 7)
                val newest = MppPayments.SessionProgressSnapshot(260, 58, 50, 202, 210, 7)
                var eligibilityData = newestData
                var chainSnapshot = newest
                balance = 58L
                coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers { eligibilityData }
                coEvery { MppPayments.getSessionProgressSnapshotFromVault(any()) } answers { chainSnapshot }
                refresh()
                runCurrent()
                assertEquals(newest, vaultSnapshots.lastOrNull())
                val beforeStaleRead = vaultSnapshots.size

                if (staleEligibilityRead) {
                    eligibilityData = staleData
                } else {
                    chainSnapshot = MppPayments.computeSessionProgressSnapshot(staleData)
                }
                advanceTimeBy(3000)
                runCurrent()
                assertEquals(newest, vaultSnapshots.lastOrNull())
                assertTrue(
                    vaultSnapshots.drop(beforeStaleRead).all { it == newest },
                    "No transient stale publication: eligibility=$staleEligibilityRead stale=$staleData",
                )

                // A real top-up may increase remaining; the guard tracks cumulative fields, not balance.
                eligibilityData = MppPayments.SessionDynamicData(290, 210, 220, 7)
                chainSnapshot = MppPayments.SessionProgressSnapshot(290, 80, 70, 210, 220, 7)
                balance = 80L
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(chainSnapshot, vaultSnapshots.lastOrNull())

                // A new vault round may reset all cumulative fields.
                eligibilityData = MppPayments.SessionDynamicData(100, 0, 0, 8)
                chainSnapshot = MppPayments.SessionProgressSnapshot(100, 100, 100, 0, 0, 8)
                balance = 100L
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(chainSnapshot, vaultSnapshots.lastOrNull())
                assertEquals(0, prompts)
                assertEquals(0, deposits)
            }
        }
    }

    @Test
    fun `EXPECT gated popup to publish the exhausted threshold snapshot before consent suspends`() =
        assertSnapshotPublishedBeforeConsent(gated = true, remaining = 0L)

    @Test
    fun `EXPECT initial popup to publish the exhausted threshold snapshot before consent suspends`() =
        assertSnapshotPublishedBeforeConsent(gated = false, remaining = 0L)

    @Test
    fun `EXPECT gated popup to publish the low positive threshold snapshot before consent suspends`() =
        assertSnapshotPublishedBeforeConsent(gated = true, remaining = 12L)

    @Test
    fun `EXPECT initial popup to publish the low positive threshold snapshot before consent suspends`() =
        assertSnapshotPublishedBeforeConsent(gated = false, remaining = 12L)

    private fun assertSnapshotPublishedBeforeConsent(gated: Boolean, remaining: Long) =
        scenario {
            val stale = MppPayments.SessionProgressSnapshot(260, 58, 50, 202, 210, 7)
            var dynamicData = MppPayments.SessionDynamicData(260, 202, 210, 7)
            balance = 58L
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers { dynamicData }
            // This independent RPC deliberately stays stale, including during consent.
            coEvery { MppPayments.getSessionProgressSnapshotFromVault(any()) } returns stale
            refresh()
            runCurrent()
            assertEquals(stale, vaultSnapshots.lastOrNull())
            assertEquals(0, prompts)
            val beforePrompt = vaultSnapshots.size

            dynamicData = MppPayments.SessionDynamicData(260, 260 - remaining, 260 - remaining / 2, 7)
            val expected =
                MppPayments.SessionProgressSnapshot(
                    totalDepositMicroUsdc = 260,
                    remainingSettledMicroUsdc = remaining,
                    progressBalanceMicroUsdc = remaining / 2,
                    lastSettledMicroUsdc = 260 - remaining,
                    latestVoucherAmountMicroUsdc = 260 - remaining / 2,
                    startRound = 7,
                )
            val consent = CompletableDeferred<ConsentApproval>()
            var snapshotAtPrompt: MppPayments.SessionProgressSnapshot? = null
            requestConsent = {
                // Capture at popup entry, before suspending, not after a later refresh.
                snapshotAtPrompt = vaultSnapshots.lastOrNull()
                consent.await()
            }
            if (gated) channel.gate() else channel.request()
            runCurrent()
            assertEquals(1, prompts)
            assertEquals(expected, snapshotAtPrompt, "at prompt: gated=$gated remaining=$remaining")
            assertEquals(expected, vaultSnapshots.lastOrNull())
            assertEquals(listOf(expected), vaultSnapshots.drop(beforePrompt).distinct())
            assertFalse(consent.isCompleted)
            assertEquals(0, deposits)
            val whileSuspended = vaultSnapshots.toList()

            refresh()
            repeat(5) { channel.gate() }
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(whileSuspended, vaultSnapshots)
            assertEquals(expected, vaultSnapshots.lastOrNull())
            assertFalse(consent.isCompleted)
            assertEquals(1, prompts)
            assertEquals(0, deposits)
            coVerify(exactly = 0) { MppPayments.topUpSessionVault(any(), any()) }
            coVerify(exactly = 0) { MppPayments.openSessionAndDeposit(any(), any(), any(), any()) }
        }

    @Test
    fun `EXPECT missing known vault data to suppress initial gated and polling prompts until recovery`() =
        scenario {
            balance = 9684L
            var data: MppPayments.SessionDynamicData? = MppPayments.SessionDynamicData(10140, 456, 456, 1)
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers { data }
            channel.gate()
            runCurrent()
            assertEquals(0, prompts)
            progress.clear()
            // Legacy remaining reader collapses an RPC failure into zero.
            balance = 0L
            data = null
            channel.request()
            channel.gate()
            refresh()
            advanceTimeBy(7000)
            runCurrent()
            assertEquals(0, prompts)
            assertEquals(0, deposits)
            assertEquals(emptyList(), progress)
            data = MppPayments.SessionDynamicData(10140, 472, 472, 1)
            balance = 9668L
            advanceTimeBy(4000)
            runCurrent()
            channel.gate()
            runCurrent()
            assertEquals(0, prompts)
            assertEquals(9668L, progress.last())
            // A verified low balance must still prompt after recovery.
            balance = 12L
            data = MppPayments.SessionDynamicData(10140, 10128, 10128, 1)
            requestConsent = { CompletableDeferred<ConsentApproval>().await() }
            advanceTimeBy(4000)
            runCurrent()
            assertEquals(1, prompts)
            assertEquals(0, deposits)
        }

    @Test
    fun `EXPECT a successful vault snapshot to override stale low remaining for initial gated and polling popups`() {
        listOf(0L, 1L, 16L).forEach { staleRemaining ->
            scenario {
                balance = staleRemaining
                coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns
                    MppPayments.SessionDynamicData(100, 0, 0, 1)
                // Verified on-chain remaining is 100, even when the legacy reader says 1.
                // Funded approval may still reject; popup eligibility is independent.
                channel.request()
                runCurrent()
                channel.gate()
                runCurrent()
                refresh()
                advanceTimeBy(3000)
                runCurrent()
                assertEquals(0, prompts, "stale remaining=$staleRemaining")
                assertEquals(0, deposits)
            }
        }
    }

    @Test
    fun `EXPECT no repeated popup or deposit while a positive old balance awaits the top-up`() {
        listOf(false, true).forEach { gated ->
            scenario {
                var totalDeposit = 260L
                var settled = 248L
                var snapshotAvailable = true
                balance = 12L
                coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers {
                    if (snapshotAvailable) MppPayments.SessionDynamicData(totalDeposit, settled, settled, 1) else null
                }
                requestConsent = { approved.copy(budgetCap = BudgetCap("30", "USDC")) }
                if (gated) channel.gate() else channel.request()
                runCurrent()
                assertEquals(1, deposits)
                assertEquals(1, prompts)
                assertEquals(emptyList(), progress) // Old positive 12 must not confirm.
                snapshotAvailable = false
                advanceTimeBy(6000)
                repeat(5) { channel.gate() }
                runCurrent()
                assertEquals(emptyList(), progress)
                assertEquals(1, prompts)
                assertEquals(1, deposits)
                snapshotAvailable = true
                totalDeposit = 289L // A partial increase still does not confirm the full 30.
                advanceTimeBy(3000)
                runCurrent()
                assertEquals(emptyList(), progress)
                assertEquals(1, prompts)
                totalDeposit = 290L
                settled = 256L // Settlement can advance while the deposit becomes visible.
                // The separate balance reader deliberately still returns stale 12.
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(34L, progress.last())
                balance = 34L
                advanceTimeBy(3000)
                channel.gate()
                runCurrent()
                assertEquals(1, prompts)
                assertEquals(1, deposits)
            }
        }
    }

    @Test
    fun `EXPECT manual retries to reconcile the original total deposit target`() =
        scenario {
            var totalDeposit = 260L
            balance = 12L
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers {
                MppPayments.SessionDynamicData(totalDeposit, 248, 248, 1)
            }
            val first = async {
                runCatching { manager.topUpViewerSessionVault("viewer", 30L, { deposits++ }, { balance }) }
            }
            runCurrent()
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(true, first.await().isFailure)
            assertEquals(emptyList(), progress)
            val retry = async {
                runCatching { manager.topUpViewerSessionVault("viewer", 30L, { deposits++ }, { balance }) }
            }
            runCurrent()
            channel.gate()
            runCurrent()
            assertEquals(0, prompts)
            totalDeposit = 290L
            balance = 42L
            advanceTimeBy(3000)
            runCurrent()
            retry.await()
            assertEquals(42L, progress.last())
            assertEquals(1, deposits)
            assertEquals(0, prompts)
        }

    @Test
    fun `EXPECT no deposit WHEN the existing positive balance has no funding baseline`() =
        scenario {
            balance = 12L
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns null
            channel.gate()
            runCurrent()
            assertEquals(0, deposits)
            assertEquals(emptyList(), progress)
        }

    @Test
    fun `EXPECT a new session to wait for its full initial deposit`() =
        scenario {
            var snapshot: MppPayments.SessionDynamicData? = null
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers { snapshot }
            coEvery { MppPayments.openSessionAndDeposit(any(), any(), any(), any()) } answers {
                deposits++
                Result.success("open")
            }
            channel.gate()
            runCurrent()
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(1, deposits)
            assertEquals(emptyList(), progress)
            snapshot = MppPayments.SessionDynamicData(100, 0, 0, 1)
            balance = 100L
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(100L, progress.last())
            assertEquals(1, deposits)
            assertEquals(1, prompts)
        }

    @Test
    fun `EXPECT gated and polling popups to wait for settlement rather than latest vouchers`() {
        listOf(false, true).forEach { gated ->
            scenario {
                balance = 100L
                var settled = 0L
                coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers {
                    MppPayments.SessionDynamicData(100, settled, 100, 1)
                }
                requestConsent = { CompletableDeferred<ConsentApproval>().await() }
                if (gated) channel.gate() else refresh()
                runCurrent()
                advanceTimeBy(3000)
                runCurrent()
                assertEquals(0, prompts)
                assertEquals(0, deposits)

                settled = 83L // Verified 17 remains, despite zero spendable.
                balance = 17L
                if (gated) channel.gate() else advanceTimeBy(1000)
                runCurrent()
                assertEquals(0, prompts)

                settled = 84L // LOCAL minimum is inclusive: 100 - 84 == 16.
                balance = 16L
                if (gated) channel.gate() else advanceTimeBy(1000)
                runCurrent()
                assertEquals(1, prompts)
                advanceTimeBy(3000)
                repeat(5) { channel.gate() }
                runCurrent()
                assertEquals(1, prompts)
                assertEquals(0, deposits)
            }
        }
    }

    @Test
    fun `EXPECT polling and queued consent to block until a manual top-up confirms`() =
        scenario {
            val transaction = CompletableDeferred<Unit>()
            val topUp =
                async {
                    manager.topUpViewerSessionVault(
                        viewerAddress = "viewer",
                        depositMicroUsdc = 100L,
                        fund = {
                            deposits++
                            transaction.await()
                        },
                        readBalance = { error("Active session must use captured balance reader") },
                    )
                }
            runCurrent()
            channel.request()
            channel.gate()
            balance = 100L
            refresh()
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(listOf(true), processing)
            assertEquals(emptyList(), progress)
            assertEquals(0, prompts)
            balance = 0L
            transaction.complete(Unit)
            runCurrent()
            assertEquals(listOf(true), processing)
            balance = 100L
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(100L, topUp.await())
            assertEquals(listOf(true, false), processing)
            assertEquals(0, prompts)
            assertEquals(1, deposits)
        }

    @Test
    fun `EXPECT no additional deposit WHEN a manual top-up overlaps another`() =
        scenario {
            val transaction = CompletableDeferred<Unit>()
            val first =
                async {
                    manager.topUpViewerSessionVault("viewer", 100L, {
                        deposits++
                        transaction.await()
                    }, { balance })
                }
            runCurrent()
            val second =
                async {
                    runCatching {
                        manager.topUpViewerSessionVault("viewer", 100L, { deposits++ }, { balance })
                    }
                }
            runCurrent()
            assertEquals(true, second.await().isFailure)
            assertEquals(1, deposits)
            balance = 100L
            transaction.complete(Unit)
            runCurrent()
            assertEquals(100L, first.await())
        }

    @Test
    fun `EXPECT retry to only reconcile the unconfirmed deposit WHEN a manual top-up is retried`() =
        scenario {
            val first =
                async {
                    runCatching {
                        manager.topUpViewerSessionVault("viewer", 100L, { deposits++ }, { balance })
                    }
                }
            runCurrent()
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(true, first.await().isFailure)
            balance = 100L
            val retry =
                async {
                    runCatching {
                        manager.topUpViewerSessionVault("viewer", 100L, { deposits++ }, { balance })
                    }
                }
            runCurrent()
            retry.await()
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(100L, progress.last())
            assertEquals(1, deposits)
            assertEquals(0, prompts)
        }

    @Test
    fun `EXPECT gated and vault-only prompts to be suppressed WHEN balance is above minimum`() =
        scenario {
            balance = connectionType.sessionVaultMinimumBalanceMicroUsdc() + 1L
            channel.gate()
            runCurrent()
            channel.request()
            runCurrent()
            assertEquals(0, prompts)
            assertEquals(0, deposits)
        }

    @Test
    fun `EXPECT an approved low positive balance to top up in both initial and gated flows`() {
        listOf(false, true).forEach { gated ->
            scenario {
                balance = connectionType.sessionVaultMinimumBalanceMicroUsdc()
                fund = {
                    balance += 100L
                    Result.success("top-up")
                }
                if (gated) channel.gate() else channel.request()
                runCurrent()
                assertEquals(1, prompts)
                assertEquals(1, deposits)
                assertEquals(116L, progress.last())
                assertEquals(false, processing.last())
            }
        }
    }

    @Test
    fun `EXPECT polling to prompt at the minimum without duplicate consent`() {
        IceConnectionType.entries.forEach { type ->
            scenario {
                connectionType = type
                val totalDeposit = type.sessionVaultMinimumBalanceMicroUsdc() + 1L
                balance = totalDeposit
                var settled = 0L
                coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers {
                    MppPayments.SessionDynamicData(totalDeposit, settled, totalDeposit, 1)
                }
                requestConsent = { CompletableDeferred<ConsentApproval>().await() }
                refresh()
                runCurrent()
                assertEquals(0, prompts)
                balance--
                settled++ // Advance the snapshot too, not just the legacy balance reader.
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(1, prompts)
                advanceTimeBy(3000)
                repeat(5) { channel.gate() }
                runCurrent()
                assertEquals(1, prompts)
                assertEquals(0, deposits)
            }
        }
    }

    @Test
    fun `EXPECT no popup for free content and retained relay minimum after transient detection`() =
        scenario {
            balance = 100L
            connectionType = IceConnectionType.RELAY
            requestConsent = { ConsentApproval(false, false) }
            channel.gate()
            runCurrent()
            assertEquals(1, prompts)
            connectionType = IceConnectionType.UNKNOWN
            channel.gate()
            runCurrent()
            assertEquals(2, prompts)
            manager.updateStreamCost(0L)
            refresh()
            channel.gate()
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(2, prompts)
            assertEquals(0, deposits)
        }

    @Test
    fun `EXPECT polling to use the latest transport boundary on verified remaining`() {
        listOf(IceConnectionType.STUN, IceConnectionType.RELAY).forEach { type ->
            scenario {
                val totalDeposit = type.sessionVaultMinimumBalanceMicroUsdc() + 1L
                var settled = 0L
                balance = totalDeposit
                coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers {
                    MppPayments.SessionDynamicData(totalDeposit, settled, totalDeposit, 1)
                }
                requestConsent = { CompletableDeferred<ConsentApproval>().await() }
                refresh()
                runCurrent()
                assertEquals(0, prompts)
                connectionType = type
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(0, prompts, "$type minimum + 1")
                settled = 1L
                // Keep the separate reader stale and above the new minimum.
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(1, prompts, "$type minimum")
                assertEquals(0, deposits)
            }
        }
    }

    @Test
    fun `EXPECT retries without prompting or funding WHEN the balance is unknown`() =
        scenario {
            readBalance = { Result.failure(IllegalStateException("offline")) }
            channel.gate()
            runCurrent()
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(3, reads)
            assertEquals(0, prompts)
            assertEquals(0, deposits)
        }

    @Test
    fun `EXPECT gated and initial consent to share one funding and confirmation flight`() =
        scenario {
            val consent = CompletableDeferred<ConsentApproval>()
            val transaction = CompletableDeferred<Result<String>>()
            requestConsent = { consent.await() }
            fund = { transaction.await() }
            channel.gate()
            runCurrent()
            channel.request()
            repeat(5) { channel.gate() }
            runCurrent()
            assertEquals(1, prompts)
            assertEquals(emptyList(), processing)
            consent.complete(approved)
            runCurrent()
            assertEquals(1, deposits)
            assertEquals(listOf(true), processing)
            balance = 100L
            refresh()
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(emptyList(), progress)
            balance = 0L
            transaction.complete(Result.success("tx"))
            runCurrent()
            repeat(5) { channel.gate() }
            runCurrent()
            assertEquals(listOf(true), processing)
            balance = 100L
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(listOf(true, false), processing)
            assertEquals(1, deposits)
            assertEquals(1, prompts)
            assertEquals(100L, progress.last())
        }

    @Test
    fun `EXPECT the initial prompt to drop a gated overlap and recheck external funding before depositing`() =
        scenario {
            val consent = CompletableDeferred<ConsentApproval>()
            requestConsent = { consent.await() }
            channel.request()
            runCurrent()
            channel.gate()
            runCurrent()
            balance = 100L
            consent.complete(approved)
            runCurrent()
            assertEquals(1, prompts)
            assertEquals(0, deposits)
            assertEquals(listOf(false), processing)
        }

    @Test
    fun `EXPECT processing to clear before funding WHEN the read fails after gated consent is approved`() =
        scenario {
            requestConsent = {
                processing += true
                readBalance = { Result.failure(IllegalStateException("offline after approval")) }
                approved
            }
            channel.gate()
            runCurrent()
            assertEquals(listOf(true), processing)
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(listOf(true, false), processing)
            assertEquals(0, deposits)
        }

    @Test
    fun `EXPECT processing to clear before funding WHEN the read fails after initial consent is approved`() =
        scenario {
            requestConsent = {
                processing += true
                readBalance = { Result.failure(IllegalStateException("offline after approval")) }
                approved
            }
            channel.request()
            runCurrent()
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(listOf(true, false), processing)
            assertEquals(0, deposits)
        }

    @Test
    fun `EXPECT new processing to survive WHEN restart happens during an approved consent's read`() =
        scenario {
            val read = CompletableDeferred<Result<Long>>()
            requestConsent = {
                processing += true
                readBalance = { read.await() }
                approved
            }
            channel.gate()
            runCurrent()
            assertEquals(listOf(true), processing)
            manager.start(params)
            processing += true
            runCurrent()
            read.complete(Result.success(0L))
            runCurrent()
            assertEquals(listOf(true, false, true), processing)
            assertEquals(0, deposits)
            assertEquals(emptyList(), progress)
        }

    @Test
    fun `EXPECT approved processing to clear atomically WHEN cancellation occurs inside consent`() =
        scenario {
            requestConsent = {
                processing += true
                throw kotlinx.coroutines.CancellationException("approved request cancelled")
            }
            channel.gate()
            runCurrent()
            assertEquals(listOf(true, false), processing)
            assertEquals(0, deposits)
        }

    @Test
    fun `EXPECT unconfirmed funding and read errors to only retry reconciliation`() =
        scenario {
            channel.gate()
            runCurrent()
            assertEquals(1, deposits)
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(listOf(true, false), processing)
            readBalance = { Result.failure(IllegalStateException("offline")) }
            repeat(5) { channel.gate() }
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(1, deposits)
            assertEquals(1, prompts)
            assertEquals(emptyList(), progress)
            readBalance = { Result.success(100L) }
            balance = 100L
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(100L, progress.last())
            assertEquals(1, deposits)
        }

    @Test
    fun `EXPECT the external pending payment to remain WHEN a stale positive poll arrives`() =
        scenario {
            val poll = CompletableDeferred<Result<Long>>()
            readBalance = { poll.await() }
            refresh()
            runCurrent()
            manager.markPaymentPending()
            poll.complete(Result.success(100L))
            runCurrent()
            readBalance = { Result.success(0L) }
            channel.gate()
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(emptyList(), progress)
            assertEquals(0, prompts)
            manager.clearPendingPayment()
            channel.gate()
            runCurrent()
            assertEquals(1, prompts)
        }

    @Test
    fun `EXPECT the old consent and queued initial request to cancel WHEN restarting`() =
        scenario {
            val consent = CompletableDeferred<ConsentApproval>()
            requestConsent = { consent.await() }
            channel.gate()
            runCurrent()
            channel.request()
            runCurrent()
            manager.start(params)
            runCurrent()
            balance = 100L
            consent.complete(approved)
            runCurrent()
            assertEquals(0, deposits)
            assertEquals(emptyList(), progress)
            channel.gate()
            runCurrent()
            assertEquals(1, prompts)
            assertEquals(listOf(100L), progress)
        }

    @Test
    fun `EXPECT processing to clear without late progress WHEN stop happens during funding`() =
        scenario {
            val transaction = CompletableDeferred<Result<String>>()
            fund = { transaction.await() }
            channel.gate()
            runCurrent()
            assertEquals(listOf(true), processing)
            manager.stop()
            runCurrent()
            balance = 100L
            transaction.complete(Result.success("tx"))
            runCurrent()
            assertEquals(listOf(true, false), processing)
            assertEquals(emptyList(), progress)
            assertEquals(1, deposits)
        }

    @Test
    fun `EXPECT another consent and deposit to be allowed WHEN funding fails`() =
        scenario {
            fund = {
                requestConsent = { ConsentApproval(false, autoPaySegments = false) }
                Result.failure(IllegalStateException("transaction rejected"))
            }
            channel.gate()
            runCurrent()
            assertEquals(1, deposits)
            requestConsent = { approved }
            fund = {
                balance = 100L
                Result.success("retry")
            }
            channel.gate()
            runCurrent()
            assertEquals(2, deposits)
            assertEquals(100L, progress.last())
            assertEquals(false, processing.last())
        }

    @Test
    fun `EXPECT the pending deposit to not get stuck WHEN funding throws`() =
        scenario {
            fund = {
                requestConsent = { ConsentApproval(false, autoPaySegments = false) }
                error("signing failed")
            }
            channel.gate()
            runCurrent()
            assertEquals(1, deposits)
            requestConsent = { approved }
            fund = {
                balance = 100L
                Result.success("retry")
            }
            channel.gate()
            runCurrent()
            assertEquals(2, deposits)
            assertEquals(100L, progress.last())
            assertEquals(false, processing.last())
        }

    @Test
    fun `EXPECT a fresh positive confirmation to be required WHEN external funding completes`() =
        scenario {
            manager.markPaymentPending()
            balance = 100L
            refresh()
            channel.gate()
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(emptyList(), progress)
            assertEquals(0, prompts)
            balance = 0L
            manager.completePendingPayment(fundingSucceeded = true)
            advanceTimeBy(4000)
            runCurrent()
            assertEquals(0, prompts)
            assertEquals(0, deposits)
            balance = 100L
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(100L, progress.last())
            assertEquals(0, prompts)
        }

    @Test
    fun `EXPECT gated consent to be allowed again WHEN external funding fails`() =
        scenario {
            manager.markPaymentPending()
            channel.gate()
            runCurrent()
            assertEquals(0, prompts)
            manager.completePendingPayment(fundingSucceeded = false)
            channel.gate()
            runCurrent()
            assertEquals(1, prompts)
        }

    @Test
    fun `EXPECT the old zero poll to be discarded WHEN restarting`() =
        scenario {
            val poll = CompletableDeferred<Result<Long>>()
            readBalance = { poll.await() }
            refresh()
            runCurrent()
            manager.start(params)
            runCurrent()
            readBalance = { Result.success(100L) }
            balance = 100L
            poll.complete(Result.success(0L))
            runCurrent()
            assertEquals(0, prompts)
            assertEquals(emptyList(), progress)
            channel.gate()
            runCurrent()
            assertEquals(listOf(100L), progress)
        }

    private fun scenario(block: suspend Fixture.() -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val oldChannel = EscrowSessionVaultHybridManagerClient.channelId
            val oldSalt = EscrowSessionVaultHybridManagerClient.salt
            val oldHost = EscrowSessionVaultHybridManagerClient.hostAddress
            mockkObject(MppPayments)
            val fixture = Fixture(this)
            try {
                fixture.start()
                fixture.block()
            } finally {
                fixture.manager.stop()
                runCurrent()
                unmockkObject(MppPayments)
                EscrowSessionVaultHybridManagerClient.channelId = oldChannel
                EscrowSessionVaultHybridManagerClient.salt = oldSalt
                EscrowSessionVaultHybridManagerClient.hostAddress = oldHost
                Dispatchers.resetMain()
            }
        }

    private class Fixture(
        val testScope: TestScope,
    ) : CoroutineScope by testScope {
        val balanceUseCase = mockk<GetRemainingSessionVaultBalanceUseCase>()
        val manager = MppPaymentViewerManager(balanceUseCase)
        val channel = Channel()
        val signer = mockk<MppWalletSigner>(relaxed = true)
        val approved = ConsentApproval(true, autoPaySegments = true, budgetCap = BudgetCap("100", "USDC"))
        var balance = 0L
        var connectionType = IceConnectionType.LOCAL
        var reads = 0
        var prompts = 0
        var deposits = 0
        val processing = mutableListOf<Boolean>()
        val progress = mutableListOf<Long>()
        val vaultSnapshots = mutableListOf<MppPayments.SessionProgressSnapshot>()
        var readBalance: suspend () -> Result<Long> = { Result.success(balance) }
        var requestConsent: suspend (ConsentTerms) -> ConsentApproval = { approved }
        var fund: suspend () -> Result<String> = { Result.success("tx") }
        val params =
            MppPaymentViewerManager.StartParams(
                dataChannel = channel,
                viewerAddress = "viewer",
                scope = testScope,
                signer = signer,
                mppNetwork = MppNetworks.ALGORAND_TESTNET,
                sessionVaultAppId = 123L,
                requestMppConsent = {
                    prompts++
                    requestConsent(it)
                },
                setViewerSessionVaultProgress = { remaining, _ -> progress += remaining },
                signFido2Challenge = { _, _ -> null },
                channelIdProvider = { ByteArray(32) { 9 } },
                setViewerPaymentProcessing = { processing += it },
                onVaultSnapshot = { vaultSnapshots += it },
                getConnectionType = { connectionType },
            )

        fun start() {
            every { signer.authorizedSignerPublicKey } returns byteArrayOf(1, 2, 3)
            every { MppPayments.voucherSettleWindowMicroUsdc() } returns 10L
            every { MppPayments.computeSessionProgressSnapshot(any()) } answers { callOriginal() }
            coEvery { balanceUseCase(any()) } coAnswers {
                reads++
                readBalance()
            }
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers {
                MppPayments.SessionDynamicData(balance, 0, 0, 1)
            }
            coEvery { MppPayments.getSessionProgressSnapshotFromVault(any()) } coAnswers {
                MppPayments.getSessionDynamicDataFromVault(firstArg())?.let {
                    MppPayments.computeSessionProgressSnapshot(it)
                }
            }
            coEvery { MppPayments.topUpSessionVault(any(), any()) } coAnswers {
                deposits++
                fund()
            }
            coEvery { MppPayments.setAuthorizedSignerForSession(any(), any(), any(), any()) } returns Result.success("auth")
            coEvery { MppPayments.registerSettlementLogicSig(any(), any(), any()) } returns Result.success("logic")
            manager.start(params)
        }

        fun refresh() =
            manager.startViewerOnChainRefresh(
                scope = testScope,
                viewerAddress = params.viewerAddress,
                sessionVaultAppId = params.sessionVaultAppId,
                setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
            )

        fun runCurrent() = testScope.runCurrent()

        fun advanceTimeBy(time: Long) = testScope.advanceTimeBy(time.milliseconds)
    }

    private class Channel : RtcDataChannel {
        private lateinit var observer: RtcDataChannelObserver

        override fun state() = RtcDataChannelState.OPEN

        override fun send(bytes: ByteArray) = Unit

        override fun registerObserver(observer: RtcDataChannelObserver) {
            this.observer = observer
        }

        override fun close() = Unit

        fun gate() =
            receive(
                buildJsonObject {
                    put("type", DCMessageType.SEGMENT_REJECTED.value)
                    put("payload", buildJsonObject { put("reason", "exhausted") })
                },
            )

        fun request() =
            receive(
                buildJsonObject {
                    put("type", DCMessageType.SEGMENT_REQUEST.value)
                    put(
                        "payload",
                        Json.encodeToJsonElement(
                            PaymentRequest(
                                id = "request",
                                sessionId = "session",
                                segmentIndex = 0,
                                amount = "10",
                                asset = "USDC",
                                network = MppNetworks.ALGORAND_TESTNET,
                                payTo = "creator",
                                ttl = 60,
                                nonce = "nonce",
                                meta = PaymentRequestMeta(GatingMode.PARTIAL_TIME, EnforcementMode.TRACK),
                                channelId = Base64.encode(ByteArray(32) { 9 }),
                                salt = Base64.encode(ByteArray(32) { 1 }),
                                billingMode = BillingMode.SESSION_VAULT,
                            ),
                        ),
                    )
                },
            )

        private fun receive(message: JsonObject) = observer.onMessage(message.toString().encodeToByteArray())
    }
}
