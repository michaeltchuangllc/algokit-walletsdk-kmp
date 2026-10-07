package com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager

import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamViewer
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelObserver
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelState
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BillingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class MppPaymentViewerVaultOnlyTest {
    @Test
    fun `EXPECT blocked receipt replay after topup confirmation to exhaust 171 with final partial 3`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val oldChannel = EscrowSessionVaultHybridManagerClient.channelId
            val oldSalt = EscrowSessionVaultHybridManagerClient.salt
            val oldHost = EscrowSessionVaultHybridManagerClient.hostAddress
            val balanceReader = mockk<GetRemainingSessionVaultBalanceUseCase>()
            val manager = MppPaymentViewerManager(balanceReader)
            mockkObject(MppPayments)
            try {
                val channel = ByteArray(32) { 9 }
                val dc = Channel()
                val signer = mockk<MppWalletSigner>(relaxed = true)
                every { signer.authorizedSignerPublicKey } returns byteArrayOf(1, 2, 3)
                var data = MppPayments.SessionDynamicData(80, 0, 0, 1)
                coEvery { balanceReader(any()) } answers { Result.success(data.totalDeposit - data.lastSettled) }
                coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } answers { data }
                coEvery { MppPayments.getSessionProgressSnapshotFromVault(any()) } answers {
                    MppPayments.computeSessionProgressSnapshot(data)
                }
                every { MppPayments.voucherSettleWindowMicroUsdc() } returns 8L
                var deposits = 0
                coEvery { MppPayments.topUpSessionVault(any(), any()) } answers {
                    assertEquals(91L, secondArg<Long>())
                    deposits++
                    // Transaction submission succeeds before the deposit is visible.
                    Result.success("top-up")
                }
                coEvery { MppPayments.setAuthorizedSignerForSession(any(), any(), any(), any()) } returns Result.success("auth")
                coEvery { MppPayments.registerSettlementLogicSig(any(), any(), any()) } returns Result.success("logic")
                val cumulative = mutableListOf<Long>()
                every { MppPayments.buildLogicSigSettlementVoucher(any(), any(), any()) } answers {
                    assertTrue(firstArg<ByteArray>().contentEquals(channel))
                    cumulative += secondArg<Long>()
                    secondArg<Long>().toString().encodeToByteArray()
                }
                val signed = mutableListOf<Long>()
                val processing = mutableListOf<Boolean>()
                val progress = mutableListOf<Long>()
                var prompts = 0
                manager.start(
                    MppPaymentViewerManager.StartParams(
                        dataChannel = dc,
                        viewerAddress = "viewer",
                        scope = this,
                        signer = signer,
                        mppNetwork = MppNetworks.ALGORAND_TESTNET,
                        sessionVaultAppId = 123,
                        requestMppConsent = {
                            prompts++
                            ConsentApproval(true, autoPaySegments = true, budgetCap = BudgetCap("91", "USDC"))
                        },
                        setViewerSessionVaultProgress = { _, available -> progress += available },
                        signFido2Challenge = { challenge, _ ->
                            signed += challenge.decodeToString().toLong()
                            byteArrayOf(7, 8, 9)
                        },
                        channelIdProvider = { channel },
                        setViewerPaymentProcessing = { processing += it },
                        getConnectionType = { IceConnectionType.LOCAL },
                    ),
                )
                runCurrent()
                // Access the actual viewer, as in MppPaymentViewerConsentTest. Only seed
                // the initial cap here; the UI's confirmation must perform the next extension.
                val viewer =
                    manager.javaClass
                        .getDeclaredField("liquidStreamViewer")
                        .apply { isAccessible = true }
                        .get(manager) as LiquidStreamViewer
                val rtcClient = viewer.rtcClient
                rtcClient.extendBudget(80L, "USDC")

                fun deliver(segment: Int, amount: String = "8") {
                    val receipt =
                        PaymentReceipt(
                            txId = "",
                            sessionId = "topup-session",
                            segmentIndex = segment,
                            amount = amount,
                            asset = "USDC",
                            payTo = "creator",
                            payFrom = "viewer",
                            network = MppNetworks.ALGORAND_TESTNET,
                            timestamp = 1,
                            channelId = Base64.encode(channel),
                            salt = Base64.encode(ByteArray(32) { 1 }),
                            billingMode = BillingMode.SESSION_VAULT,
                            settlementDeferred = true,
                        )
                    dc.receive(
                        buildJsonObject {
                            put("type", DCMessageType.SEGMENT_ACCEPTED.value)
                            put("payload", Json.encodeToJsonElement(receipt))
                        },
                    )
                }

                repeat(10) {
                    deliver(it)
                    runCurrent()
                }
                assertEquals("80", rtcClient.spend.totalAmount)
                assertEquals((8L..80L step 8).toList(), cumulative)
                // Local vouchers exhaust the spendable budget, not the popup balance.
                deliver(10)
                runCurrent()
                assertEquals(0, prompts)
                assertEquals(0, deposits)
                assertEquals("80", rtcClient.spend.totalAmount)
                assertEquals(10, signed.size)
                assertEquals(80L, cumulative.last())
                manager.startViewerOnChainRefresh(
                    scope = this,
                    viewerAddress = "viewer",
                    sessionVaultAppId = 123,
                    setViewerSessionVaultProgress = { _, available -> progress += available },
                )
                advanceTimeBy(3.seconds)
                runCurrent()
                assertEquals(0, prompts) // Polling must also wait while only local vouchers are exhausted.
                assertEquals(0, deposits)
                assertEquals("80", rtcClient.spend.totalAmount)
                assertEquals(10, signed.size)

                fun gate() =
                    dc.receive(
                        buildJsonObject {
                            put("type", DCMessageType.SEGMENT_REJECTED.value)
                            put("payload", buildJsonObject { put("reason", "exhausted") })
                        },
                    )

                data = MppPayments.SessionDynamicData(80, 0, 80, 1)
                gate()
                runCurrent()
                assertEquals(0, prompts) // Latest on-chain vouchers must not trigger either.
                assertEquals(0, deposits)
                assertEquals("80", rtcClient.spend.totalAmount)
                assertEquals(10, signed.size)

                // Only settlement reaches the inclusive LOCAL popup minimum of 16.
                data = MppPayments.SessionDynamicData(80, 64, 80, 1)
                progress.clear()
                gate()
                runCurrent()
                assertEquals(1, prompts)
                assertEquals(1, deposits)
                assertEquals(listOf(true), processing)
                assertEquals("80", rtcClient.spend.totalAmount)
                assertEquals(10, signed.size)
                assertEquals(80L, cumulative.last())
                assertTrue(progress.isEmpty())

                // The 91-unit confirmation must extend cap 80 to 171 and replay segment
                // 10 without retransmission, before any subsequent receipt is delivered.
                data = MppPayments.SessionDynamicData(171, 80, 80, 1)
                advanceTimeBy(1.seconds)
                runCurrent()
                assertTrue(91L in progress)
                assertEquals(listOf(true, false), processing)
                assertEquals("88", rtcClient.spend.totalAmount)
                assertEquals(88L, cumulative.last())
                assertEquals(11, signed.size)
                assertEquals(1, rtcClient.spend.transactions.count { it.segmentIndex == 10 })

                for (segment in 11..20) {
                    deliver(segment)
                    runCurrent()
                }
                assertEquals("168", rtcClient.spend.totalAmount)
                deliver(21, amount = "3")
                runCurrent()
                // Late duplicate deliveries must not debit or sign again.
                deliver(10)
                deliver(21, amount = "3")
                runCurrent()
                val expected = (8L..168L step 8).toList() + 171L
                val vouchers = dc.sent.filter { it["type"]?.jsonPrimitive?.content == DCMessageType.SEGMENT_VOUCHER.value }
                assertEquals(expected, cumulative)
                assertEquals(expected, signed)
                assertEquals(expected, vouchers.map { it.getValue("totalAmountClaimedMicroUsdc").jsonPrimitive.long })
                assertEquals("171", rtcClient.spend.totalAmount)
                assertEquals(22, rtcClient.spend.segmentsPaid)
                assertEquals((0..21).toList(), rtcClient.spend.transactions.map { it.segmentIndex })
                assertEquals("3", rtcClient.spend.transactions.last().amount)
                assertEquals(3L, cumulative.last() - cumulative[cumulative.lastIndex - 1])
                assertEquals(0L, data.totalDeposit - cumulative.last()) // No stranded 8.
                assertEquals(1, prompts)
                assertEquals(1, deposits)
                assertTrue(dc.sent.none { it["type"]?.jsonPrimitive?.content == DCMessageType.SEGMENT_PAYMENT.value })
            } finally {
                manager.stop()
                runCurrent()
                unmockkObject(MppPayments)
                EscrowSessionVaultHybridManagerClient.channelId = oldChannel
                EscrowSessionVaultHybridManagerClient.salt = oldSalt
                EscrowSessionVaultHybridManagerClient.hostAddress = oldHost
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `EXPECT deferred receipts to sign cumulative vouchers without rail receipts WHEN billing mode is SESSION_VAULT`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val oldChannel = EscrowSessionVaultHybridManagerClient.channelId
            val oldSalt = EscrowSessionVaultHybridManagerClient.salt
            val oldHost = EscrowSessionVaultHybridManagerClient.hostAddress
            val balanceReader = mockk<GetRemainingSessionVaultBalanceUseCase>()
            val manager = MppPaymentViewerManager(balanceReader)
            mockkObject(MppPayments)
            try {
                val channel = ByteArray(32) { 9 }
                val dc = Channel()
                val signer = mockk<MppWalletSigner>(relaxed = true)
                every { signer.authorizedSignerPublicKey } returns byteArrayOf(1, 2, 3)
                val data = MppPayments.SessionDynamicData(45, 20, 20, 1)
                coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns data
                coEvery { MppPayments.getSessionProgressSnapshotFromVault(any()) } returns MppPayments.computeSessionProgressSnapshot(data)
                val cumulative = mutableListOf<Long>()
                every { MppPayments.buildLogicSigSettlementVoucher(any(), any(), any()) } answers {
                    assertTrue(firstArg<ByteArray>().contentEquals(channel))
                    cumulative += secondArg<Long>()
                    byteArrayOf(secondArg<Long>().toByte())
                }
                val signed = mutableListOf<ByteArray>()
                var prompts = 0
                manager.start(
                    MppPaymentViewerManager.StartParams(
                        dataChannel = dc,
                        viewerAddress = "viewer",
                        scope = this,
                        signer = signer,
                        mppNetwork = MppNetworks.ALGORAND_TESTNET,
                        sessionVaultAppId = 123,
                        requestMppConsent = {
                            prompts++
                            error("Funded receipts should not prompt")
                        },
                        setViewerSessionVaultProgress = { _, _ -> },
                        signFido2Challenge = { challenge, _ ->
                            signed += challenge
                            byteArrayOf(7, 8, 9)
                        },
                        channelIdProvider = { channel },
                    ),
                )
                runCurrent()

                fun deliver(segment: Int, amount: String = "10") {
                    val receipt =
                        PaymentReceipt(
                            txId = "",
                            sessionId = "ordinary-session",
                            segmentIndex = segment,
                            amount = amount,
                            asset = "USDC",
                            payTo = "creator",
                            payFrom = "viewer",
                            network = MppNetworks.ALGORAND_TESTNET,
                            timestamp = 1,
                            channelId = Base64.encode(channel),
                            salt = Base64.encode(ByteArray(32) { 1 }),
                            billingMode = BillingMode.SESSION_VAULT,
                            settlementDeferred = true,
                        )
                    dc.receive(
                        buildJsonObject {
                            put("type", DCMessageType.SEGMENT_ACCEPTED.value)
                            put("payload", Json.encodeToJsonElement(receipt))
                        },
                    )
                }
                deliver(0)
                runCurrent()
                deliver(1)
                deliver(1)
                runCurrent()
                assertEquals(listOf(30L, 40L), cumulative)
                assertEquals(2, signed.size)
                // Only 5 micro-USDC remain after two full 10 micro-USDC receipts.
                // Duplicate delivery both before processing and after sending must not
                // reserve, sign or send the final partial amount twice.
                deliver(2, amount = "5")
                deliver(2, amount = "5")
                runCurrent()
                deliver(2, amount = "5")
                runCurrent()
                assertEquals(listOf(30L, 40L, 45L), cumulative)
                assertEquals(listOf(30, 40, 45), signed.map { it.single().toInt() })
                val vouchers = dc.sent.filter { it["type"]?.jsonPrimitive?.content == DCMessageType.SEGMENT_VOUCHER.value }
                assertEquals(3, vouchers.size)
                assertEquals(
                    cumulative,
                    vouchers.map { it.getValue("totalAmountClaimedMicroUsdc").jsonPrimitive.long },
                )
                assertTrue(vouchers.all { it["channelId"]?.jsonPrimitive?.content == Base64.encode(channel) })
                assertTrue(vouchers.all { it["billingMode"]?.jsonPrimitive?.content == BillingMode.SESSION_VAULT })
                assertTrue(vouchers.all { !it["signature"]?.jsonPrimitive?.content.isNullOrBlank() })
                assertTrue(dc.sent.none { it["type"]?.jsonPrimitive?.content == DCMessageType.SEGMENT_PAYMENT.value })
                // A known funded vault becomes unavailable after a receipt, just as in
                // the device log. A failed read represented as zero must not prompt.
                coEvery { balanceReader(any()) } returns Result.success(0L)
                coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns null
                dc.receive(
                    buildJsonObject {
                        put("type", DCMessageType.SEGMENT_REJECTED.value)
                        put("payload", buildJsonObject { put("reason", "balance lookup failed") })
                    },
                )
                advanceTimeBy(3.seconds)
                runCurrent()
                assertEquals(0, prompts)
            } finally {
                manager.stop()
                unmockkObject(MppPayments)
                EscrowSessionVaultHybridManagerClient.channelId = oldChannel
                EscrowSessionVaultHybridManagerClient.salt = oldSalt
                EscrowSessionVaultHybridManagerClient.hostAddress = oldHost
                Dispatchers.resetMain()
            }
        }

    private class Channel : RtcDataChannel {
        val sent = mutableListOf<JsonObject>()
        private lateinit var observer: RtcDataChannelObserver

        override fun state() = RtcDataChannelState.OPEN

        override fun send(bytes: ByteArray) {
            sent += Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        }

        override fun registerObserver(observer: RtcDataChannelObserver) {
            this.observer = observer
        }

        override fun close() = Unit

        fun receive(message: JsonObject) = observer.onMessage(message.toString().encodeToByteArray())
    }
}
