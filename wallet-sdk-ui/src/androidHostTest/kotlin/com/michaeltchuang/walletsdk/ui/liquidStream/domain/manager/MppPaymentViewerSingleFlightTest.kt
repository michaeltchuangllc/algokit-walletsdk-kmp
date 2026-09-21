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
import io.mockk.coEvery
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
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class MppPaymentViewerSingleFlightTest {
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
    fun `EXPECT gated and vault-only prompts to be suppressed WHEN balance is positive even below segment cost`() =
        scenario {
            balance = 1L
            channel.gate()
            runCurrent()
            channel.request()
            runCurrent()
            assertEquals(0, prompts)
            assertEquals(0, deposits)
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
        var reads = 0
        var prompts = 0
        var deposits = 0
        val processing = mutableListOf<Boolean>()
        val progress = mutableListOf<Long>()
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
            )

        fun start() {
            every { signer.authorizedSignerPublicKey } returns byteArrayOf(1, 2, 3)
            every { MppPayments.voucherSettleWindowMicroUsdc() } returns 10L
            coEvery { balanceUseCase(any()) } coAnswers {
                reads++
                readBalance()
            }
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns
                MppPayments.SessionDynamicData(100, 0, 0, 1)
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
