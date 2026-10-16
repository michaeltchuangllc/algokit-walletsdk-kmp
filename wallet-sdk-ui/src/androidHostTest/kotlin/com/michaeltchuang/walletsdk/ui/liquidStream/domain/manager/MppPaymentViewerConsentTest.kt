package com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager

import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamViewer
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelState
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BillingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.sessionVaultMinimumBalanceMicroUsdc
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MppPaymentViewerConsentTest {
    @Test
    fun `EXPECT the gated popup to be suppressed WHEN the balance is above minimum`() =
        scenario {
            balance = connectionType.sessionVaultMinimumBalanceMicroUsdc() + 1L
            gated()
            runCurrent()
            assertEquals(0, prompts)
            assertEquals(listOf(balance), progress)
        }

    @Test
    fun `EXPECT a popup WHEN the vault balance is positive but below minimum`() =
        scenario {
            balance = 5L
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns
                MppPayments.SessionDynamicData(5, 0, 0, 1)
            val approval = consent().requestConsent(terms)
            assertFalse(approval.approved)
            assertEquals(1, prompts)
        }

    @Test
    fun `EXPECT initial and gated prompts at or below verified remaining minimum regardless of latest vouchers`() {
        IceConnectionType.entries.forEach { type ->
            val minimum = type.sessionVaultMinimumBalanceMicroUsdc()
            // 0.000020 USDC is above LOCAL's minimum, but below STUN/RELAY's.
            listOf(0L, 20L, minimum - 1L, minimum, minimum + 1L).forEach { remaining ->
                scenario {
                    connectionType = type
                    balance = remaining
                    coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns
                        MppPayments.SessionDynamicData(1000, 1000 - remaining, 1000, 1)
                    val expectedPrompts = if (remaining <= minimum) 1 else 0
                    // Popup policy does not grant a spendable budget: all funds are reserved.
                    assertFalse(consent().requestConsent(terms).approved)
                    assertEquals(expectedPrompts, prompts, "initial: $type balance=$remaining")
                    gated()
                    runCurrent()
                    assertEquals(expectedPrompts * 2, prompts, "gated: $type balance=$remaining")
                }
            }
        }
    }

    @Test
    fun `EXPECT initial popup to wait for settlement while funded approval still uses spendable`() =
        scenario {
            balance = 100L
            var latestVoucher = 0L
            var settled = 0L
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers {
                MppPayments.SessionDynamicData(100, settled, latestVoucher, 1)
            }
            listOf(0L, 84L, 91L, 100L).forEach { claimed ->
                latestVoucher = claimed
                val result = consent().requestConsent(terms)
                val spendable = 100L - claimed
                assertEquals(spendable >= 10L, result.approved, "latest voucher=$claimed")
                if (result.approved) {
                    assertEquals(BudgetCap(spendable.toString(), "USDC"), result.budgetCap)
                } else {
                    assertFalse(result.autoPaySegments)
                }
                assertEquals(0, prompts)
            }
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(0, prompts) // Polling must also ignore the exhausted spendable budget.

            settled = 83L
            balance = 17L
            assertFalse(consent().requestConsent(terms).approved)
            runCurrent()
            assertEquals(0, prompts)
            settled = 84L
            balance = 16L
            assertFalse(consent().requestConsent(terms).approved)
            assertEquals(1, prompts)
            assertTrue(processing.none { it })
        }

    @Test
    fun `EXPECT initial and gated decisions to use latest transport and retain it through transient detection`() {
        listOf(false, true).forEach { gatedFlow ->
            scenario {
                balance = IceConnectionType.STUN.sessionVaultMinimumBalanceMicroUsdc()
                coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns
                    MppPayments.SessionDynamicData(1000, 1000 - balance, 1000, 1)
                listOf(
                    IceConnectionType.LOCAL to 0,
                    IceConnectionType.STUN to 1,
                    IceConnectionType.RELAY to 2,
                    IceConnectionType.UNKNOWN to 3,
                    IceConnectionType.FAILED to 4,
                    IceConnectionType.LOCAL to 4,
                ).forEach { (type, expectedPrompts) ->
                    connectionType = type
                    if (gatedFlow) {
                        gated()
                        runCurrent()
                    } else {
                        assertFalse(consent().requestConsent(terms).approved)
                        runCurrent()
                    }
                    assertEquals(expectedPrompts, prompts, "gated=$gatedFlow transport=$type")
                }
            }
        }
    }

    @Test
    fun `EXPECT retries without a popup WHEN the balance is unknown`() =
        scenario {
            coEvery { balanceReader(any()) } returns Result.failure(IllegalStateException("offline"))
            repeat(5) { gated() }
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(0, prompts)
            assertTrue(progress.isEmpty())
        }

    @Test
    fun `EXPECT gated events and the initial consent to share one prompt`() =
        scenario {
            val answer = CompletableDeferred<ConsentApproval>()
            approval = { answer.await() }
            gated()
            runCurrent()
            val initial = async { consent().requestConsent(terms) }
            repeat(10) { gated() }
            runCurrent()
            assertEquals(1, prompts)
            balance = 100L
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns
                MppPayments.SessionDynamicData(100, 0, 0, 1)
            answer.complete(ConsentApproval(true, true, BudgetCap("100", "USDC")))
            runCurrent()
            assertTrue(initial.await().approved)
            assertEquals(1, prompts)
            assertTrue(processing.none { it })
        }

    @Test
    fun `EXPECT gated events to stay out WHILE funding and confirmation are in progress`() =
        scenario {
            val transaction = CompletableDeferred<Unit>()
            var deposits = 0
            val payment =
                async {
                    manager.topUpViewerSessionVault("viewer", 100, fund = {
                        deposits++
                        transaction.await()
                    }, readBalance = { balance })
                }
            runCurrent()
            assertEquals(listOf(true), processing)
            balance = 50L
            repeat(5) { gated() }
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(0, prompts)
            assertTrue(progress.isEmpty())
            balance = 0L
            transaction.complete(Unit)
            runCurrent()
            repeat(5) { gated() }
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(0, prompts)
            assertEquals(listOf(true), processing)
            balance = 100L
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(100L, payment.await())
            assertEquals(1, deposits)
            assertEquals(listOf(true, false), processing)
        }

    @Test
    fun `EXPECT reads to retry without another deposit WHEN confirmation times out`() =
        scenario {
            var deposits = 0
            val payment =
                async {
                    runCatching {
                        manager.topUpViewerSessionVault(
                            "viewer",
                            100,
                            fund = { deposits++ },
                            readBalance = { balance },
                        )
                    }
                }
            advanceTimeBy(3000)
            runCurrent()
            assertTrue(payment.await().isFailure)
            repeat(5) { gated() }
            advanceTimeBy(3000)
            runCurrent()
            assertEquals(1, deposits)
            assertEquals(0, prompts)
            assertTrue(progress.isEmpty())
            balance = 100L
            advanceTimeBy(3000)
            runCurrent()
            assertTrue(progress.contains(100L))
            assertEquals(1, deposits)
            assertEquals(0, prompts)
        }

    @Test
    fun `EXPECT the old prompt to cancel without funding or progress WHEN restarting`() =
        scenario {
            val answer = CompletableDeferred<ConsentApproval>()
            approval = { answer.await() }
            gated()
            runCurrent()
            assertEquals(1, prompts)
            manager.start(params)
            runCurrent()
            answer.complete(ConsentApproval(true, true, BudgetCap("100", "USDC")))
            runCurrent()
            assertTrue(processing.none { it })
            assertTrue(progress.isEmpty())
        }

    private fun scenario(block: suspend Fixture.() -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            mockkObject(MppPayments)
            val fixture = Fixture(this)
            try {
                fixture.manager.start(fixture.params)
                runCurrent()
                fixture.block()
            } finally {
                fixture.manager.stop()
                unmockkObject(MppPayments)
                Dispatchers.resetMain()
            }
        }

    private class Fixture(
        val scope: TestScope,
    ) {
        var balance = 0L
        var connectionType = IceConnectionType.LOCAL
        var prompts = 0
        val progress = mutableListOf<Long>()
        val processing = mutableListOf<Boolean>()
        var approval: suspend () -> ConsentApproval = { ConsentApproval(false, false) }
        val balanceReader = mockk<GetRemainingSessionVaultBalanceUseCase>()
        val manager = MppPaymentViewerManager(balanceReader)
        val terms =
            ConsentTerms(
                gatingMode = GatingMode.PARTIAL_TIME,
                amount = "10",
                asset = "USDC",
                network = MppNetworks.ALGORAND_TESTNET,
                billingMode = BillingMode.SESSION_VAULT,
            )
        val params: MppPaymentViewerManager.StartParams

        init {
            coEvery { balanceReader(any()) } answers { Result.success(balance) }
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers {
                MppPayments.SessionDynamicData(balance, 0, 0, 1)
            }
            val signer = mockk<MppWalletSigner>(relaxed = true)
            every { signer.authorizedSignerPublicKey } returns byteArrayOf(1, 2, 3)
            val channel = mockk<RtcDataChannel>(relaxed = true)
            every { channel.state() } returns RtcDataChannelState.OPEN
            params =
                MppPaymentViewerManager.StartParams(
                    dataChannel = channel,
                    viewerAddress = "viewer",
                    scope = scope,
                    signer = signer,
                    mppNetwork = MppNetworks.ALGORAND_TESTNET,
                    sessionVaultAppId = 123,
                    requestMppConsent = {
                        prompts++
                        approval()
                    },
                    setViewerSessionVaultProgress = { remaining, _ -> progress += remaining },
                    signFido2Challenge = { _, _ -> null },
                    channelIdProvider = { ByteArray(32) { 9 } },
                    setViewerPaymentProcessing = { processing += it },
                    getConnectionType = { connectionType },
                )
        }

        private fun viewer(): LiquidStreamViewer =
            manager.javaClass
                .getDeclaredField("liquidStreamViewer")
                .apply { isAccessible = true }
                .get(manager) as LiquidStreamViewer

        fun consent(): ConsentHandler {
            val client = viewer().rtcClient
            return client.javaClass
                .getDeclaredField("consent")
                .apply { isAccessible = true }
                .get(client) as ConsentHandler
        }

        fun gated() = viewer().rtcClient.onStreamGated?.invoke("Session balance exhausted")

        fun runCurrent() = scope.runCurrent()

        fun advanceTimeBy(milliseconds: Long) = scope.advanceTimeBy(milliseconds)

        fun <T> async(block: suspend () -> T) = scope.async { block() }
    }
}
