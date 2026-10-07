package com.michaeltchuang.walletsdk.core.railmpp.core

import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BillingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.EnforcementMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequestMeta
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.RailPayment
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.internal.encodeAlgorandAddress
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class VaultOnlyProtocolTest {
    private val dispatcher = StandardTestDispatcher()
    private val rail = mockk<PaymentRail>()
    private val consent = mockk<ConsentHandler>()
    private val balance = mockk<GetRemainingSessionVaultBalanceUseCase>()
    private val salt = ByteArray(32) { 7 }
    private val signer = byteArrayOf(1, 2, 3)
    private val viewer = encodeAlgorandAddress(ByteArray(32) { 1 })
    private val creator = encodeAlgorandAddress(ByteArray(32) { 2 })
    private var originalSalt: ByteArray? = null
    private var originalChannel: ByteArray? = null
    private var originalViewerSalt: ByteArray? = null
    private var originalHost: String? = null

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
        originalSalt = EscrowSessionVaultHybridManagerClient.defaultSalt
        originalChannel = EscrowSessionVaultHybridManagerClient.channelId
        originalViewerSalt = EscrowSessionVaultHybridManagerClient.salt
        originalHost = EscrowSessionVaultHybridManagerClient.hostAddress
        EscrowSessionVaultHybridManagerClient.defaultSalt = salt.copyOf()
        coEvery { rail.createPaymentRequest(any()) } coAnswers {
            val p = firstArg<PaymentRailRequestParams>()
            request().copy(
                sessionId = p.sessionId,
                segmentIndex = p.segmentIndex,
                amount = p.amount,
                asset = p.asset,
                network = p.network,
                payTo = p.payTo,
                meta = p.meta,
                billingMode = null,
                channelId = null,
                salt = null,
            )
        }
        coEvery { consent.requestConsent(any()) } returns
            ConsentApproval(
                approved = true,
                autoPaySegments = true,
                budgetCap = BudgetCap("100", "USDC"),
            )
    }

    @AfterTest
    fun teardown() {
        EscrowSessionVaultHybridManagerClient.defaultSalt = originalSalt
        EscrowSessionVaultHybridManagerClient.channelId = originalChannel
        EscrowSessionVaultHybridManagerClient.salt = originalViewerSalt
        EscrowSessionVaultHybridManagerClient.hostAddress = originalHost
        Dispatchers.resetMain()
    }

    private fun config() =
        ServerConfig(
            sessionId = "ordinary-session", // Intentionally not a mesh-name heuristic.
            gating =
                GatingConfig(
                    mode = GatingMode.WHOLE_STREAM,
                    amount = "10",
                    asset = "USDC",
                    network = MppNetworks.ALGORAND_TESTNET,
                    payTo = creator,
                ),
            viewerAddress = viewer,
            viewerAuthorizedSignerPublicKey = signer,
            vaultOnlyBilling = true,
        )

    private fun request() =
        PaymentRequest(
            id = "request",
            sessionId = "ordinary-session",
            segmentIndex = 0,
            amount = "10",
            asset = "USDC",
            network = MppNetworks.ALGORAND_TESTNET,
            payTo = creator,
            ttl = 30,
            nonce = "nonce",
            meta = PaymentRequestMeta(GatingMode.WHOLE_STREAM, EnforcementMode.TRACK),
            channelId = Base64.encode(HostViewerVaultReader.deriveChannelId(viewer, creator, signer, MppNetworks.ALGORAND_TESTNET, salt)),
            salt = Base64.encode(salt),
            billingMode = BillingMode.SESSION_VAULT,
        )

    private fun receipt() =
        PaymentReceipt(
            txId = "",
            sessionId = "ordinary-session",
            segmentIndex = 0,
            amount = "10",
            asset = "USDC",
            payTo = creator,
            payFrom = viewer,
            network = MppNetworks.ALGORAND_TESTNET,
            timestamp = 1,
            channelId = request().channelId,
            salt = request().salt,
            billingMode = BillingMode.SESSION_VAULT,
            settlementDeferred = true,
        )

    @Test
    fun `EXPECT blocks to consume 8 8 3 from 19 then pause and recover without back billing`() =
        runTest(dispatcher) {
            val rounds = MutableSharedFlow<Long>(extraBufferCapacity = 10)
            var deposit = 19L
            var readFails = false
            var reads = 0
            val dc = Channel()
            val track = Track()
            val server =
                PaywalledRTCServer(
                    rail,
                    config().copy(
                        gating = config().gating.copy(mode = GatingMode.PARTIAL_TIME, amount = "8"),
                        blockDrivenBilling = true,
                    ),
                    balance,
                    vaultReader = { _, _, _, _, _ ->
                        reads++
                        if (readFails) Result.failure(IllegalStateException("offline"))
                        else Result.success(HostViewerVaultReader.Snapshot(deposit, 0, deposit, deposit))
                    },
                    workDispatcher = dispatcher,
                    blockRounds = { rounds },
                )
            try {
                server.listen(dc, listOf(track))
                advanceTimeBy(101)
                runCurrent()
                rounds.emit(100)
                runCurrent()
                assertTrue(dc.sent.isEmpty())
                for (round in 101L..103L) {
                    rounds.emit(round)
                    runCurrent()
                    assertTrue(track.enabled)
                }
                // Unsettled snapshots still show 19: local reservations must cap the last charge.
                assertEquals(listOf("8", "8", "3"), dc.receipts().map { it.amount })
                assertEquals(listOf(0, 1, 2), dc.receipts().map { it.segmentIndex })
                assertEquals(19L, dc.receipts().sumOf { it.amount.toLong() })
                assertEquals(3, server.paidBlockCount)
                assertEquals(0, server.freeBlockCount)
                assertEquals(0L, server.voucherCoveredBlockCount(7, 0))
                assertEquals(1L, server.voucherCoveredBlockCount(8, 0))
                assertEquals(2L, server.voucherCoveredBlockCount(16, 0))
                assertEquals(2L, server.voucherCoveredBlockCount(18, 0))
                assertEquals(3L, server.voucherCoveredBlockCount(19, 0))
                rounds.emit(104)
                runCurrent()
                assertVaultPaused(dc.sent.last())
                assertFalse(track.enabled)
                var vouchers = 0
                server.onVoucherReceived = { vouchers++ }
                dc.receive(buildJsonObject { put("type", DCMessageType.SEGMENT_VOUCHER.value) })
                runCurrent()
                assertEquals(1, vouchers)

                readFails = true
                val readsBeforeRetry = reads
                rounds.emit(110)
                runCurrent()
                assertTrue(reads > readsBeforeRetry)
                assertVaultPaused(dc.sent.last())
                assertFalse(track.enabled)
                assertEquals(3, server.paidBlockCount)
                assertEquals(3, dc.receipts().size)

                readFails = false
                deposit += 24L
                rounds.emit(120)
                runCurrent()
                assertEquals(4, server.paidBlockCount) // Only this round, not the paused gap.
                assertTrue(track.enabled)
                assertEquals(listOf("8", "8", "3", "8"), dc.receipts().map { it.amount })
                assertEquals(3L, server.voucherCoveredBlockCount(26, 0))
                assertEquals(4L, server.voucherCoveredBlockCount(27, 0))
                rounds.emit(121)
                runCurrent()
                assertEquals(5, server.paidBlockCount)
                assertEquals(listOf(0, 1, 2, 3, 4), dc.receipts().map { it.segmentIndex })
                assertEquals(35L, dc.receipts().sumOf { it.amount.toLong() })
                assertEquals(5L, server.voucherCoveredBlockCount(35, 3))
                assertTrue(dc.sent.none { it["type"]?.jsonPrimitive?.content == DCMessageType.SEGMENT_REQUEST.value })
                coVerify(exactly = 0) { rail.createPaymentRequest(any()) }
                coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
                coVerify(exactly = 0) { balance(any()) }
            } finally {
                server.terminate()
            }
        }

    @Test
    fun `EXPECT timer billing to consume positive balances and retry zero or failed reads without a request`() =
        runTest(dispatcher) {
            for (initial in listOf(0L, 1L, 7L, 8L, 19L)) {
                var deposit = initial
                var readFails = false
                var reads = 0
                val hostDc = Channel()
                val viewerDc = Channel()
                val track = Track()
                val server =
                    PaywalledRTCServer(
                        rail,
                        config().copy(
                            gating = config().gating.copy(mode = GatingMode.PARTIAL_TIME, amount = "8", segmentDuration = 1, leadTime = 0),
                        ),
                        balance,
                        vaultReader = { _, _, _, _, _ ->
                            reads++
                            if (readFails) Result.failure(IllegalStateException("offline"))
                            else Result.success(HostViewerVaultReader.Snapshot(deposit, 0, deposit, deposit))
                        },
                        workDispatcher = dispatcher,
                    )
                val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
                try {
                    var gatedNotifications = 0
                    client.onStreamGated = { gatedNotifications++ }
                    client.connect(viewerDc)
                    server.listen(hostDc, listOf(track))
                    advanceTimeBy(101)
                    runCurrent()
                    val message = hostDc.sent.single()
                    viewerDc.receive(message)
                    runCurrent()
                    assertEquals(if (initial == 0L) 1 else 0, gatedNotifications)
                    assertEquals(
                        if (initial == 0L) emptyList() else listOf(minOf(8L, initial).toString()),
                        hostDc.receipts().map { it.amount },
                    )
                    assertEquals(minOf(8L, initial).toString(), client.spend.totalAmount)
                    assertTrue(viewerDc.sent.isEmpty())
                    assertEquals(request().channelId, Base64.encode(requireNotNull(EscrowSessionVaultHybridManagerClient.channelId)))
                    // Consume every remaining micro-USDC, then reach a paused timer tick.
                    repeat(((initial + 7L) / 8L).toInt()) {
                        val sentBeforeTick = hostDc.sent.size
                        advanceTimeBy(1000)
                        runCurrent()
                        hostDc.sent.drop(sentBeforeTick).forEach { viewerDc.receive(it) }
                        runCurrent()
                    }
                    assertEquals(initial, hostDc.receipts().sumOf { it.amount.toLong() })
                    assertEquals(initial.toString(), client.spend.totalAmount)
                    if (initial == 19L) assertEquals(listOf("8", "8", "3"), hostDc.receipts().map { it.amount })
                    assertVaultPaused(hostDc.sent.last())
                    assertFalse(track.enabled)
                    val paidBeforeRecovery = hostDc.receipts().size
                    val readsBeforeRetry = reads
                    readFails = true
                    advanceTimeBy(3000)
                    runCurrent()
                    assertTrue(reads > readsBeforeRetry)
                    assertEquals(paidBeforeRecovery, hostDc.receipts().size)
                    assertVaultPaused(hostDc.sent.last())
                    assertFalse(track.enabled)

                    // No pending request or funded hint is needed; paused time is not charged.
                    readFails = false
                    deposit += 16L
                    advanceTimeBy(1000)
                    runCurrent()
                    assertTrue(track.enabled)
                    assertEquals(paidBeforeRecovery + 1, hostDc.receipts().size)
                    assertEquals("8", hostDc.receipts().last().amount)
                    assertEquals(initial + 8L, hostDc.receipts().sumOf { it.amount.toLong() })
                    viewerDc.receive(hostDc.sent.last())
                    runCurrent()
                    assertEquals((initial + 8L).toString(), client.spend.totalAmount)
                    assertTrue(viewerDc.sent.isEmpty())
                    assertTrue(hostDc.sent.none { it["type"]?.jsonPrimitive?.content == DCMessageType.SEGMENT_REQUEST.value })
                    coVerify(exactly = 0) { rail.createPaymentRequest(any()) }
                    coVerify(exactly = 0) { rail.createRailPayment(any()) }
                    coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
                    coVerify(exactly = 0) { consent.requestConsent(any()) }
                } finally {
                    server.terminate()
                    client.terminate()
                }
            }
        }

    @Test
    fun `EXPECT depletion and recovery of one viewer vault not to affect another viewer`() =
        runTest(dispatcher) {
            val rounds = MutableSharedFlow<Long>(extraBufferCapacity = 10)
            val otherViewer = encodeAlgorandAddress(ByteArray(32) { 3 })
            val deposits = mutableMapOf(viewer to 3L, otherViewer to 100L)
            val base =
                config().copy(
                    gating = config().gating.copy(mode = GatingMode.PARTIAL_TIME, amount = "8"),
                    blockDrivenBilling = true,
                )
            val channels = listOf(Channel(), Channel())
            val tracks = listOf(Track(), Track())
            val servers =
                listOf(base, base.copy(sessionId = "second-viewer", viewerAddress = otherViewer)).mapIndexed { index, serverConfig ->
                    PaywalledRTCServer(
                        rail,
                        serverConfig,
                        balance,
                        vaultReader = { payer, _, _, _, _ ->
                            val deposit = deposits.getValue(payer)
                            Result.success(HostViewerVaultReader.Snapshot(deposit, 0, deposit, deposit))
                        },
                        workDispatcher = dispatcher,
                        blockRounds = { rounds },
                    ).also { server -> server.listen(channels[index], listOf(tracks[index])) }
                }
            try {
                advanceTimeBy(101)
                runCurrent()
                rounds.emit(100)
                runCurrent()
                rounds.emit(101)
                runCurrent()
                assertEquals(listOf("3"), channels.first().receipts().map { it.amount })
                assertEquals(listOf("8"), channels.last().receipts().map { it.amount })
                assertNotEquals(channels.first().receipts().single().channelId, channels.last().receipts().single().channelId)
                assertEquals(otherViewer, channels.last().receipts().single().payFrom)
                assertEquals(1, servers.first().paidBlockCount)
                assertEquals(1, servers.last().paidBlockCount)
                rounds.emit(102)
                runCurrent()
                assertEquals(1, servers.first().paidBlockCount)
                assertEquals(2, servers.last().paidBlockCount)
                assertVaultPaused(channels.first().sent.last())
                assertFalse(tracks.first().enabled)
                assertTrue(tracks.last().enabled)
                deposits[viewer] = 5L
                rounds.emit(103)
                runCurrent()
                assertEquals(listOf("3", "2"), channels.first().receipts().map { it.amount })
                assertEquals(listOf("8", "8", "8"), channels.last().receipts().map { it.amount })
                assertTrue(tracks.all { it.enabled })
                assertEquals(2L, servers.first().voucherCoveredBlockCount(5, 0))
                assertEquals(3L, servers.last().voucherCoveredBlockCount(24, 0))
                assertTrue(channels.last().sent.none { it["type"]?.jsonPrimitive?.content == DCMessageType.SEGMENT_REJECTED.value })
                coVerify(exactly = 0) { rail.createPaymentRequest(any()) }
                coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
            } finally {
                servers.forEach { it.terminate() }
            }
        }

    @Test
    fun `EXPECT partial charge or pause based on on-chain progress rather than the total deposit`() =
        runTest(dispatcher) {
            for (available in listOf(0L, 3L)) {
                val dc = Channel()
                val server =
                    PaywalledRTCServer(
                        rail,
                        config().copy(gating = config().gating.copy(amount = "8")),
                        balance,
                        vaultReader = { _, _, _, _, _ ->
                            Result.success(
                                HostViewerVaultReader.Snapshot(
                                    remainingBalanceMicroUsdc = available,
                                    lastSettledMicroUsdc = 19L - available,
                                    progressBalanceMicroUsdc = available,
                                    totalDepositMicroUsdc = 19L,
                                ),
                            )
                        },
                        workDispatcher = dispatcher,
                    )
                try {
                    server.listen(dc, emptyList())
                    advanceTimeBy(101)
                    runCurrent()
                    if (available == 0L) {
                        assertVaultPaused(dc.sent.single())
                        assertTrue(dc.receipts().isEmpty())
                    } else {
                        assertEquals("3", dc.receipts().single().amount)
                        assertTrue(dc.receipts().single().settlementDeferred)
                    }
                    coVerify(exactly = 0) { rail.createPaymentRequest(any()) }
                    coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
                    coVerify(exactly = 0) { balance(any()) }
                } finally {
                    server.terminate()
                }
            }
        }

    @Test
    fun `EXPECT deferred receipt with no settlement or global balance use WHEN vault channel is already funded`() =
        runTest(dispatcher) {
            val dc = Channel()
            val server =
                PaywalledRTCServer(
                    rail,
                    config(),
                    balance,
                    vaultReader = { payer, payee, key, network, channelSalt ->
                        assertEquals(viewer, payer)
                        assertEquals(creator, payee)
                        assertTrue(key.contentEquals(signer))
                        assertTrue(channelSalt.contentEquals(salt))
                        assertEquals(MppNetworks.ALGORAND_TESTNET, network)
                        Result.success(HostViewerVaultReader.Snapshot(100, 0, 100, 100))
                    },
                    workDispatcher = dispatcher,
                )
            server.listen(dc, emptyList())
            advanceTimeBy(101)
            runCurrent()
            val accepted = Json.decodeFromJsonElement<PaymentReceipt>(dc.sent.single().getValue("payload"))
            assertEquals(BillingMode.SESSION_VAULT, accepted.billingMode)
            assertEquals(request().channelId, accepted.channelId)
            assertEquals(request().salt, accepted.salt)
            assertTrue(accepted.settlementDeferred)
            assertEquals("", accepted.txId)
            coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
            coVerify(exactly = 0) { rail.createPaymentRequest(any()) }
            coVerify(exactly = 0) { balance(any()) }
            server.terminate()
        }

    @Test
    fun `EXPECT a verified partial funded hint receipt without replay or direct payment after an unavailable lookup`() =
        runTest(dispatcher) {
            var funded = 0L
            var readFails = true
            val dc = Channel()
            val server =
                PaywalledRTCServer(
                    rail,
                    config(),
                    balance,
                    vaultReader = { _, _, _, _, _ ->
                        if (readFails) Result.failure(IllegalStateException("initial lookup unavailable"))
                        else Result.success(HostViewerVaultReader.Snapshot(funded, 0, funded, funded))
                    },
                    workDispatcher = dispatcher,
                )
            server.listen(dc, emptyList())
            advanceTimeBy(101)
            runCurrent()
            assertEquals(DCMessageType.SEGMENT_REQUEST.value, dc.sent.single()["type"]?.jsonPrimitive?.content)
            val request =
                paymentRequestFromJson(
                    dc.sent
                        .single()
                        .getValue("payload")
                        .jsonObject,
                )
            assertEquals(BillingMode.SESSION_VAULT, request.billingMode)
            assertEquals("10", request.amount)
            val hint =
                buildJsonObject {
                    put("type", DCMessageType.VIEWER_VAULT_FUNDED.value)
                    put("sessionId", request.sessionId)
                    put("nonce", request.nonce)
                    put("billingMode", request.billingMode)
                    put("channelId", request.channelId)
                    put("salt", request.salt)
                    put("balance", Long.MAX_VALUE) // Never trusted.
                }
            dc.receive(hint)
            dc.receive(envelope(DCMessageType.SEGMENT_PAYMENT, buildJsonObject { put("signedTransfer", "untrusted") }))
            runCurrent()
            assertEquals(1, dc.sent.size)
            readFails = false
            funded = 9
            dc.receive(
                buildJsonObject {
                    hint.forEach { (k, v) -> put(k, v) }
                    put("channelId", "wrong")
                },
            )
            runCurrent()
            assertEquals(1, dc.sent.size)
            dc.receive(hint)
            runCurrent()
            val accepted = dc.receipts().single()
            assertEquals("9", accepted.amount)
            assertEquals(request.segmentIndex, accepted.segmentIndex)
            assertEquals(request.channelId, accepted.channelId)
            assertEquals(request.salt, accepted.salt)
            assertEquals(BillingMode.SESSION_VAULT, accepted.billingMode)
            assertTrue(accepted.settlementDeferred)
            assertEquals("", accepted.txId)
            dc.receive(hint)
            runCurrent()
            assertEquals(2, dc.sent.size)
            coVerify(exactly = 1) { rail.createPaymentRequest(any()) }
            coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
            coVerify(exactly = 0) { balance(any()) }
            server.terminate()
        }

    @Test
    fun `EXPECT viewer identity request then a matching zero balance pause WHEN authorized signer key is missing`() =
        runTest(dispatcher) {
            val dc = Channel()
            val server =
                PaywalledRTCServer(
                    rail,
                    config().copy(viewerAuthorizedSignerPublicKey = null),
                    balance,
                    vaultReader = { _, _, _, _, _ -> Result.success(HostViewerVaultReader.Snapshot(0, 0, 0, 0)) },
                    workDispatcher = dispatcher,
                )
            server.listen(dc, emptyList())
            advanceTimeBy(5_101)
            runCurrent()
            assertEquals(
                "true",
                dc.sent
                    .single()["requestViewerIdentity"]
                    ?.jsonPrimitive
                    ?.content,
            )
            server.updateConfig(config())
            runCurrent()
            assertEquals(2, dc.sent.size)
            assertVaultPaused(dc.sent.last())
            coVerify(exactly = 0) { rail.createPaymentRequest(any()) }
            coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
            server.terminate()
        }

    @Test
    fun `EXPECT only a funded notification and single receipt consumption WHEN viewer consents to vault billing`() =
        runTest(dispatcher) {
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            var receipts = 0
            client.onPaymentReceipt = { receipts++ }
            client.connect(dc)
            dc.receive(envelope(DCMessageType.SEGMENT_REQUEST, request().toJson()))
            runCurrent()
            val hint = dc.sent.single()
            assertEquals(DCMessageType.VIEWER_VAULT_FUNDED.value, hint["type"]?.jsonPrimitive?.content)
            assertEquals(request().nonce, hint["nonce"]?.jsonPrimitive?.content)
            assertEquals(BillingMode.SESSION_VAULT, hint["billingMode"]?.jsonPrimitive?.content)
            assertEquals("0", client.spend.totalAmount)
            repeat(2) { dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt().toJson())) }
            runCurrent()
            assertEquals(1, receipts)
            assertEquals("10", client.spend.totalAmount)
            coVerify(exactly = 0) { rail.createRailPayment(any()) }
            client.terminate()
        }

    @Test
    fun `EXPECT no direct payment credentials to be built WHEN funding fails or billing mode is downgraded`() =
        runTest(dispatcher) {
            coEvery { consent.requestConsent(any()) } throws IllegalStateException("deposit failed")
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            client.connect(dc)
            dc.receive(envelope(DCMessageType.SEGMENT_REQUEST, request().toJson()))
            runCurrent()
            dc.receive(envelope(DCMessageType.SEGMENT_REQUEST, request().copy(billingMode = null).toJson()))
            runCurrent()
            assertTrue(dc.sent.isEmpty())
            coVerify(exactly = 0) { rail.createRailPayment(any()) }
            client.terminate()
        }

    @Test
    fun `EXPECT receipt consumption to trigger WHEN a funded receipt arrives without a prior request`() =
        runTest(dispatcher) {
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            var receipts = 0
            client.onPaymentReceipt = { receipts++ }
            client.connect(dc)
            dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt().toJson()))
            runCurrent()
            assertEquals(1, receipts)
            assertEquals("10", client.spend.totalAmount)
            coVerify(exactly = 0) { rail.createRailPayment(any()) }
            coVerify(exactly = 0) { consent.requestConsent(any()) }
            client.terminate()
        }

    @Test
    fun `EXPECT legacy wire defaults and config equality to remain explicit WHEN vaultOnlyBilling is unset`() {
        val legacy = config().copy(vaultOnlyBilling = false)
        assertFalse(ServerConfig(gating = legacy.gating).vaultOnlyBilling)
        assertNotEquals(legacy, config())
        assertEquals(config(), config().copy())
        assertEquals(config().hashCode(), config().copy().hashCode())
        assertFalse(request().copy(billingMode = null).toJson().containsKey("billingMode"))
        assertEquals(BillingMode.SESSION_VAULT, paymentRequestFromJson(request().toJson()).billingMode)
    }

    @Test
    fun `EXPECT direct payment creation WHEN legacy viewer receives a mesh-named session without a billing mode`() =
        runTest(dispatcher) {
            coEvery { rail.createRailPayment(any()) } returns RailPayment("test", 1, "nonce", JsonNull, JsonNull)
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            client.connect(dc)
            dc.receive(
                envelope(
                    DCMessageType.SEGMENT_REQUEST,
                    request().copy(sessionId = "mesh-legacy", billingMode = null).toJson(),
                ),
            )
            runCurrent()
            coVerify(exactly = 1) { rail.createRailPayment(any()) }
            assertEquals(
                DCMessageType.SEGMENT_PAYMENT.value,
                dc.sent
                    .single()["type"]
                    ?.jsonPrimitive
                    ?.content,
            )
            client.terminate()
        }

    @Test
    fun `EXPECT settlement through the rail WHEN legacy server has vaultOnlyBilling disabled`() =
        runTest(dispatcher) {
            val dc = Channel()
            val direct = RailPayment("test", 1, "nonce", JsonNull, JsonNull)
            coEvery { rail.verifyAndSettle(any(), any()) } returns
                receipt().copy(
                    txId = "real-transaction",
                    billingMode = null,
                    settlementDeferred = false,
                )
            val server =
                PaywalledRTCServer(
                    rail,
                    config().copy(vaultOnlyBilling = false),
                    balance,
                    workDispatcher = dispatcher,
                )
            server.listen(dc, emptyList())
            advanceTimeBy(101)
            runCurrent()
            dc.receive(envelope(DCMessageType.SEGMENT_PAYMENT, direct.toJson()))
            runCurrent()
            coVerify(exactly = 1) { rail.verifyAndSettle(any(), any()) }
            assertEquals(2, dc.sent.size)
            server.terminate()
        }

    @Test
    fun `EXPECT no fallback to rail settlement WHEN vault lookup fails`() =
        runTest(dispatcher) {
            val dc = Channel()
            val server =
                PaywalledRTCServer(
                    rail,
                    config(),
                    balance,
                    vaultReader = { _, _, _, _, _ -> Result.failure(IllegalStateException("offline")) },
                    workDispatcher = dispatcher,
                )
            server.listen(dc, emptyList())
            advanceTimeBy(101)
            runCurrent()
            assertEquals(
                BillingMode.SESSION_VAULT,
                dc.sent
                    .single()["payload"]
                    ?.jsonObject
                    ?.get("billingMode")
                    ?.jsonPrimitive
                    ?.content,
            )
            dc.receive(envelope(DCMessageType.SEGMENT_PAYMENT, RailPayment("test", 1, "nonce", JsonNull, JsonNull).toJson()))
            runCurrent()
            assertEquals(1, dc.sent.size)
            coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
            server.terminate()
        }

    @Test
    fun `EXPECT unsettled funds to be unusable for a new segment WHEN prior consumption is still deferred`() =
        runTest(dispatcher) {
            val dc = Channel()
            val serverConfig =
                config().let {
                    it.copy(
                        gating = it.gating.copy(mode = GatingMode.PARTIAL_TIME, segmentDuration = 1, leadTime = 0),
                    )
                }
            val server =
                PaywalledRTCServer(
                    rail,
                    serverConfig,
                    balance,
                    vaultReader = { _, _, _, _, _ -> Result.success(HostViewerVaultReader.Snapshot(10, 0, 10, 10)) },
                    workDispatcher = dispatcher,
                )
            server.listen(dc, emptyList())
            advanceTimeBy(101)
            runCurrent()
            assertEquals(
                DCMessageType.SEGMENT_ACCEPTED.value,
                dc.sent
                    .single()["type"]
                    ?.jsonPrimitive
                    ?.content,
            )
            advanceTimeBy(1_001)
            runCurrent()
            assertVaultPaused(dc.sent.last())
            assertEquals(2, dc.sent.size)
            assertEquals(listOf("10"), dc.receipts().map { it.amount })
            coVerify(exactly = 0) { rail.createPaymentRequest(any()) }
            coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
            server.terminate()
        }

    @Test
    fun `EXPECT legacy client and server to use the direct rail WHEN vaultOnlyBilling is disabled on both sides`() =
        runTest(dispatcher) {
            val payment = RailPayment("test", 1, "nonce", buildJsonObject {}, buildJsonObject {})
            coEvery { rail.createRailPayment(any()) } returns payment
            coEvery { rail.verifyAndSettle(any(), any()) } returns
                receipt().copy(
                    txId = "real-transaction",
                    billingMode = null,
                    settlementDeferred = false,
                    salt = null,
                )
            val hostDc = Channel()
            val server = PaywalledRTCServer(rail, config().copy(vaultOnlyBilling = false), balance, workDispatcher = dispatcher)
            server.listen(hostDc, emptyList())
            advanceTimeBy(101)
            runCurrent()
            val legacyRequest =
                paymentRequestFromJson(
                    hostDc.sent
                        .single()
                        .getValue("payload")
                        .jsonObject,
                )
            assertEquals(null, legacyRequest.billingMode)
            val viewerDc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            client.connect(viewerDc)
            viewerDc.receive(envelope(DCMessageType.SEGMENT_REQUEST, legacyRequest.toJson()))
            runCurrent()
            hostDc.receive(viewerDc.sent.single())
            runCurrent()
            coVerify(exactly = 1) { rail.createRailPayment(any()) }
            coVerify(exactly = 1) { rail.verifyAndSettle(any(), any()) }
            server.terminate()
            client.terminate()
        }

    @Test
    fun `EXPECT fail-closed behavior WHEN the vault reader is unavailable`() =
        runTest(dispatcher) {
            val dc = Channel()
            val server =
                PaywalledRTCServer(
                    rail,
                    config(),
                    balance,
                    vaultReader = { _, _, _, _, _ -> Result.failure(IllegalStateException("network unavailable")) },
                    workDispatcher = dispatcher,
                )
            server.listen(dc, emptyList())
            advanceTimeBy(101)
            runCurrent()
            assertEquals(
                DCMessageType.SEGMENT_REQUEST.value,
                dc.sent
                    .single()["type"]
                    ?.jsonPrimitive
                    ?.content,
            )
            coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
            coVerify(exactly = 0) { balance(any()) }
            server.terminate()
        }

    @Test
    fun `EXPECT viewer to retry without signing a payment WHEN host requests viewer identity`() =
        runTest(dispatcher) {
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            client.connect(dc)
            runCurrent()
            var retries = 0
            client.onDataChannelOpen = { retries++ }
            dc.receive(
                buildJsonObject {
                    put("type", DCMessageType.SEGMENT_HANDSHAKE.value)
                    put("billingMode", BillingMode.SESSION_VAULT)
                    put("requestViewerIdentity", true)
                },
            )
            runCurrent()
            assertEquals(1, retries)
            coVerify(exactly = 0) { rail.createRailPayment(any()) }
            client.terminate()
        }

    @Test
    fun `EXPECT budget cap to be honored without signing a payment WHEN deferred vault consumption exceeds the cap`() =
        runTest(dispatcher) {
            coEvery { consent.requestConsent(any()) } returns
                ConsentApproval(
                    approved = true,
                    autoPaySegments = true,
                    budgetCap = BudgetCap("10", "USDC"),
                )
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            var receipts = 0
            var exceeded = 0
            client.onPaymentReceipt = { receipts++ }
            client.onBudgetExceeded = { exceeded++ }
            client.connect(dc)
            dc.receive(envelope(DCMessageType.SEGMENT_REQUEST, request().toJson()))
            runCurrent()
            dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt().toJson()))
            dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt().copy(segmentIndex = 1).toJson()))
            runCurrent()
            assertEquals(1, receipts)
            assertEquals(1, exceeded)
            assertEquals("10", client.spend.totalAmount)
            coVerify(exactly = 0) { rail.createRailPayment(any()) }
            client.terminate()
        }

    @Test
    fun `EXPECT blocked segment 10 to replay after extending cap 80 by 91 and finish at 171 with 22 receipts`() =
        runTest(dispatcher) {
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            val delivered = mutableListOf<PaymentReceipt>()
            val expected = (0..21).map { receipt().copy(segmentIndex = it, amount = if (it == 21) "3" else "8") }
            var exceeded = 0
            client.onPaymentReceipt = { delivered += it }
            client.onBudgetExceeded = { exceeded++ }
            try {
                client.connect(dc)
                client.extendBudget(80, "USDC")
                expected.take(10).forEach { dc.accept(it) }
                runCurrent()
                assertVaultSpend(client, delivered, expected.take(10))
                assertEquals("80", client.spend.totalAmount)

                dc.accept(expected[10])
                runCurrent()
                assertEquals(1, exceeded)
                assertVaultSpend(client, delivered, expected.take(10))
                // Neither an already processed duplicate nor a pending duplicate may charge.
                dc.accept(expected[0])
                dc.accept(expected[10])
                runCurrent()
                assertVaultSpend(client, delivered, expected.take(10))

                client.extendBudget(91, "USDC")
                runCurrent()
                // No retransmission or subsequent receipt is needed to drain segment 10.
                assertVaultSpend(client, delivered, expected.take(11))
                assertEquals("88", client.spend.totalAmount)
                expected.drop(11).forEach { dc.accept(it) }
                runCurrent()
                assertVaultSpend(client, delivered, expected)
                assertEquals("171", client.spend.totalAmount)
                assertEquals(22, delivered.size)

                expected.forEach { dc.accept(it) }
                client.extendBudget(1, "USDC")
                runCurrent()
                assertVaultSpend(client, delivered, expected)
                assertTrue(dc.sent.isEmpty())
                coVerify(exactly = 0) { rail.createRailPayment(any()) }
                coVerify(exactly = 0) { consent.requestConsent(any()) }
            } finally {
                client.terminate()
            }
        }

    @Test
    fun `EXPECT multiple blocked receipts to drain in arrival order without smaller receipts jumping an unaffordable head`() =
        runTest(dispatcher) {
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            val delivered = mutableListOf<PaymentReceipt>()
            // Deliberately not segment-index order: replay must preserve arrival order.
            val expected =
                listOf(
                    receipt(),
                    receipt().copy(segmentIndex = 3, amount = "8"),
                    receipt().copy(segmentIndex = 1, amount = "8"),
                    receipt().copy(segmentIndex = 2, amount = "3"),
                    receipt().copy(segmentIndex = 4, amount = "1"),
                )
            client.onPaymentReceipt = { delivered += it }
            try {
                client.connect(dc)
                client.extendBudget(10, "USDC")
                expected.take(4).forEach { dc.accept(it) }
                runCurrent()
                assertVaultSpend(client, delivered, expected.take(1))

                client.extendBudget(3, "USDC")
                runCurrent()
                assertVaultSpend(client, delivered, expected.take(1))
                // This new receipt fits the remaining budget, but must join the tail.
                dc.accept(expected.last())
                dc.accept(expected[1])
                dc.accept(expected[2])
                runCurrent()
                assertVaultSpend(client, delivered, expected.take(1))

                client.extendBudget(10, "USDC")
                runCurrent()
                // Replay must budget-check each receipt, not just the first.
                assertVaultSpend(client, delivered, expected.take(2))
                assertEquals("18", client.spend.totalAmount)
                dc.accept(expected[1])
                runCurrent()
                assertVaultSpend(client, delivered, expected.take(2))

                client.extendBudget(12, "USDC")
                runCurrent()
                assertVaultSpend(client, delivered, expected)
                assertEquals("30", client.spend.totalAmount)
                client.extendBudget(100, "USDC")
                runCurrent()
                assertVaultSpend(client, delivered, expected)
                coVerify(exactly = 0) { rail.createRailPayment(any()) }
            } finally {
                client.terminate()
            }
        }

    @Test
    fun `EXPECT consent approval to drain deferred receipts while still honoring the approved cap`() =
        runTest(dispatcher) {
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            val delivered = mutableListOf<PaymentReceipt>()
            val expected =
                listOf(
                    receipt(),
                    receipt().copy(segmentIndex = 1, amount = "8"),
                    receipt().copy(segmentIndex = 2, amount = "3"),
                )
            client.onPaymentReceipt = { delivered += it }
            try {
                client.connect(dc)
                client.extendBudget(10, "USDC")
                expected.forEach { dc.accept(it) }
                runCurrent()
                assertVaultSpend(client, delivered, expected.take(1))

                for ((cap, count) in listOf("18" to 2, "21" to 3)) {
                    coEvery { consent.requestConsent(any()) } returns
                        ConsentApproval(
                            approved = true,
                            autoPaySegments = true,
                            budgetCap = BudgetCap(cap, "USDC"),
                        )
                    dc.receive(
                        envelope(
                            DCMessageType.SEGMENT_REQUEST,
                            request().copy(id = "top-up-$cap", nonce = "top-up-$cap", segmentIndex = 3, amount = "0").toJson(),
                        ),
                    )
                    runCurrent()
                    assertVaultSpend(client, delivered, expected.take(count))
                    assertEquals(cap, client.spend.totalAmount)
                }
                coVerify(exactly = 2) { consent.requestConsent(any()) }
                coVerify(exactly = 0) { rail.createRailPayment(any()) }
            } finally {
                client.terminate()
            }
        }

    @Test
    fun `EXPECT terminate to discard deferred receipts before a later budget extension`() =
        runTest(dispatcher) {
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            val delivered = mutableListOf<PaymentReceipt>()
            client.onPaymentReceipt = { delivered += it }
            try {
                client.connect(dc)
                client.extendBudget(10, "USDC")
                dc.accept(receipt())
                dc.accept(receipt().copy(segmentIndex = 1, amount = "8"))
                dc.accept(receipt().copy(segmentIndex = 2, amount = "3"))
                runCurrent()
                assertVaultSpend(client, delivered, listOf(receipt()))

                client.terminate()
                client.extendBudget(100, "USDC")
                runCurrent()
                assertVaultSpend(client, delivered, listOf(receipt()))
                coVerify(exactly = 0) { rail.createRailPayment(any()) }
            } finally {
                client.terminate()
            }
        }

    @Test
    fun `EXPECT invalid vault identities to be rejected rather than deferred while the budget is exhausted`() =
        runTest(dispatcher) {
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            val delivered = mutableListOf<PaymentReceipt>()
            val expected = (0..4).map { receipt().copy(segmentIndex = it) }
            client.onPaymentReceipt = { delivered += it }
            try {
                client.connect(dc)
                client.extendBudget(10, "USDC")
                dc.accept(expected[0])
                dc.accept(expected[1])
                runCurrent()
                val channel = EscrowSessionVaultHybridManagerClient.channelId?.copyOf()
                val channelSalt = EscrowSessionVaultHybridManagerClient.salt?.copyOf()
                dc.accept(expected[2].copy(sessionId = "other-session"))
                dc.accept(expected[3].copy(channelId = Base64.encode(ByteArray(32) { 9 })))
                dc.accept(expected[4].copy(salt = Base64.encode(ByteArray(32) { 9 })))
                runCurrent()
                assertVaultSpend(client, delivered, expected.take(1))

                client.extendBudget(100, "USDC")
                runCurrent()
                assertVaultSpend(client, delivered, expected.take(2))
                assertTrue(channel.contentEquals(EscrowSessionVaultHybridManagerClient.channelId))
                assertTrue(channelSalt.contentEquals(EscrowSessionVaultHybridManagerClient.salt))
                // Rejected identities must not poison deduplication for valid receipts.
                expected.drop(2).forEach { dc.accept(it) }
                runCurrent()
                assertVaultSpend(client, delivered, expected)
                coVerify(exactly = 0) { rail.createRailPayment(any()) }
            } finally {
                client.terminate()
            }
        }

    @Test
    fun `EXPECT vault identity to pin on first receipt and reject session or channel changes WHEN receipt arrives before request`() =
        runTest(dispatcher) {
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            var receipts = 0
            client.onPaymentReceipt = { receipts++ }
            client.connect(dc)
            dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt().toJson()))
            runCurrent()
            val channel = EscrowSessionVaultHybridManagerClient.channelId?.copyOf()
            val invalid =
                listOf(
                    receipt(),
                    receipt().copy(sessionId = "other-session"),
                    receipt().copy(segmentIndex = 1, channelId = Base64.encode(ByteArray(32) { 9 })),
                    receipt().copy(segmentIndex = 1, salt = Base64.encode(ByteArray(32) { 9 })),
                )
            invalid.forEach { dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, it.toJson())) }
            dc.receive(envelope(DCMessageType.SEGMENT_REQUEST, request().copy(sessionId = "other-session").toJson()))
            runCurrent()
            assertEquals(1, receipts)
            assertEquals("10", client.spend.totalAmount)
            assertTrue(channel.contentEquals(EscrowSessionVaultHybridManagerClient.channelId))
            assertTrue(dc.sent.isEmpty())
            dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt().copy(segmentIndex = 1).toJson()))
            runCurrent()
            assertEquals(2, receipts)
            assertEquals("20", client.spend.totalAmount)
            coVerify(exactly = 0) { rail.createRailPayment(any()) }
            client.terminate()
        }

    @Test
    fun `EXPECT vault identity to pin before any consumption WHEN request arrives before receipt`() =
        runTest(dispatcher) {
            val dc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            var receipts = 0
            client.onPaymentReceipt = { receipts++ }
            client.connect(dc)
            dc.receive(envelope(DCMessageType.SEGMENT_REQUEST, request().toJson()))
            runCurrent()
            dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt().copy(sessionId = "other-session").toJson()))
            dc.receive(
                envelope(
                    DCMessageType.SEGMENT_ACCEPTED,
                    receipt().copy(channelId = Base64.encode(ByteArray(32) { 9 })).toJson(),
                ),
            )
            dc.receive(envelope(DCMessageType.SEGMENT_REQUEST, request().copy(sessionId = "other-session").toJson()))
            runCurrent()
            assertEquals(0, receipts)
            assertEquals("0", client.spend.totalAmount)
            assertEquals(1, dc.sent.size)
            dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt().toJson()))
            runCurrent()
            assertEquals(1, receipts)
            assertEquals("10", client.spend.totalAmount)
            coVerify(exactly = 0) { rail.createRailPayment(any()) }
            client.terminate()
        }

    @Test
    fun `EXPECT no funding or direct payment needed WHEN a vault segment is free`() =
        runTest(dispatcher) {
            val dc = Channel()
            var reads = 0
            val server =
                PaywalledRTCServer(
                    rail,
                    config().copy(gating = config().gating.copy(amount = "0")),
                    balance,
                    vaultReader = { _, _, _, _, _ ->
                        reads++
                        Result.failure(IllegalStateException("no funded channel"))
                    },
                    workDispatcher = dispatcher,
                )
            server.listen(dc, emptyList())
            advanceTimeBy(101)
            runCurrent()
            val message = dc.sent.single()
            assertEquals(DCMessageType.SEGMENT_ACCEPTED.value, message["type"]?.jsonPrimitive?.content)
            val accepted = Json.decodeFromJsonElement<PaymentReceipt>(message.getValue("payload"))
            assertEquals("0", accepted.amount)
            assertEquals("", accepted.txId)
            assertEquals(BillingMode.SESSION_VAULT, accepted.billingMode)
            assertTrue(accepted.settlementDeferred)
            assertEquals(request().channelId, accepted.channelId)
            assertEquals(0, reads)
            coVerify(exactly = 0) { rail.createPaymentRequest(any()) }
            coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
            coVerify(exactly = 0) { balance(any()) }
            val viewerDc = Channel()
            val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
            var receipts = 0
            client.onPaymentReceipt = { receipts++ }
            client.connect(viewerDc)
            viewerDc.receive(dc.sent.single())
            runCurrent()
            assertEquals(1, receipts)
            assertEquals("0", client.spend.totalAmount)
            assertTrue(viewerDc.sent.isEmpty())
            coVerify(exactly = 0) { consent.requestConsent(any()) }
            coVerify(exactly = 0) { rail.createRailPayment(any()) }
            server.terminate()
            client.terminate()
        }

    @Test
    fun `EXPECT receipt and voucher identity to remain consistent WHEN vault starts funded or lookup is unavailable`() =
        runTest(dispatcher) {
            for (initialBalance in listOf(0L, 100L)) {
                var funded = initialBalance
                var readFails = initialBalance == 0L
                val hostDc = Channel()
                val viewerDc = Channel()
                val server =
                    PaywalledRTCServer(
                        rail,
                        config(),
                        balance,
                        vaultReader = { _, _, _, _, _ ->
                            if (readFails) Result.failure(IllegalStateException("initial lookup unavailable"))
                            else Result.success(HostViewerVaultReader.Snapshot(funded, 0, funded, funded))
                        },
                        workDispatcher = dispatcher,
                    )
                val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
                var routedVoucher: JsonObject? = null
                server.onVoucherReceived = { routedVoucher = Json.parseToJsonElement(it).jsonObject }
                var acknowledged: PaymentReceipt? = null
                client.onPaymentReceipt = { accepted ->
                    acknowledged = accepted
                    client.sendVoucher(
                        MppPayments.createVoucherJson(
                            sessionId = accepted.sessionId,
                            appId = 1,
                            viewerAddress = accepted.payFrom,
                            viewerPublicKey = signer,
                            creatorAddress = accepted.payTo,
                            blocksConsumed = 1,
                            totalAmountUsed = accepted.amount.toLong(),
                            remainingMicroUsdc = funded - accepted.amount.toLong(),
                            signatureBase64 = Base64.encode(byteArrayOf(42)),
                        ),
                    )
                }
                client.connect(viewerDc)
                server.listen(hostDc, emptyList())
                advanceTimeBy(101)
                runCurrent()
                viewerDc.receive(hostDc.sent.single())
                runCurrent()
                if (initialBalance == 0L) {
                    assertEquals(
                        DCMessageType.VIEWER_VAULT_FUNDED.value,
                        viewerDc.sent
                            .single()["type"]
                            ?.jsonPrimitive
                            ?.content,
                    )
                    // The funded-notification path must also voucher only the partial charge.
                    funded = 3
                    readFails = false
                    hostDc.receive(viewerDc.sent.single())
                    runCurrent()
                    viewerDc.receive(hostDc.sent.last())
                    runCurrent()
                }
                hostDc.receive(viewerDc.sent.last())
                runCurrent()
                val accepted = requireNotNull(acknowledged)
                val voucher = requireNotNull(routedVoucher)
                assertEquals(if (initialBalance == 0L) "3" else "10", accepted.amount)
                assertEquals(accepted.amount, client.spend.totalAmount)
                assertEquals(config().sessionId, accepted.sessionId)
                assertEquals(accepted.sessionId, voucher["id"]?.jsonPrimitive?.content)
                assertEquals(accepted.channelId, voucher["channelId"]?.jsonPrimitive?.content)
                assertEquals(accepted.payFrom, voucher["viewer"]?.jsonPrimitive?.content)
                assertEquals(accepted.payTo, voucher["creator"]?.jsonPrimitive?.content)
                assertEquals(accepted.amount, voucher["totalAmountClaimedMicroUsdc"]?.jsonPrimitive?.content)
                assertEquals(BillingMode.SESSION_VAULT, accepted.billingMode)
                assertEquals(request().salt, accepted.salt)
                assertTrue(accepted.settlementDeferred)
                coVerify(exactly = 0) { rail.createRailPayment(any()) }
                coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
                server.terminate()
                client.terminate()
            }
        }

    private fun Channel.accept(receipt: PaymentReceipt) =
        receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt.toJson()))

    private fun assertVaultSpend(
        client: PaywalledRTCClient,
        delivered: List<PaymentReceipt>,
        expected: List<PaymentReceipt>,
    ) {
        assertEquals(expected, delivered)
        assertEquals(expected.sumOf { it.amount.toLong() }.toString(), client.spend.totalAmount)
        assertEquals(expected.size, client.spend.segmentsPaid)
        assertEquals(expected.map { it.segmentIndex }, client.spend.transactions.map { it.segmentIndex })
        assertEquals(expected.map { it.amount }, client.spend.transactions.map { it.amount })
    }

    private fun assertVaultPaused(message: JsonObject) {
        assertEquals(DCMessageType.SEGMENT_REJECTED.value, message["type"]?.jsonPrimitive?.content)
        assertEquals(config().sessionId, message["sessionId"]?.jsonPrimitive?.content)
        assertEquals(BillingMode.SESSION_VAULT, message["billingMode"]?.jsonPrimitive?.content)
        assertEquals(request().channelId, message["channelId"]?.jsonPrimitive?.content)
        assertEquals(request().salt, message["salt"]?.jsonPrimitive?.content)
        assertEquals(creator, message["payTo"]?.jsonPrimitive?.content)
        assertFalse(message.containsKey("nonce"))
        val payload = message.getValue("payload").jsonObject
        assertTrue(payload["reason"]?.jsonPrimitive?.content?.isNotBlank() == true)
        assertFalse(payload.containsKey("amount"))
        assertFalse(payload.containsKey("nonce"))
    }

    private fun Channel.receipts(): List<PaymentReceipt> =
        sent.filter { it["type"]?.jsonPrimitive?.content == DCMessageType.SEGMENT_ACCEPTED.value }
            .map { Json.decodeFromJsonElement<PaymentReceipt>(it.getValue("payload")) }

    private class Track : RtcRtpSender {
        var enabled = false
            private set

        override fun setTrackEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
    }

    private fun envelope(
        type: DCMessageType,
        payload: JsonObject,
    ) = buildJsonObject {
        put("type", type.value)
        put("sessionId", "ordinary-session")
        put("segmentIndex", 0)
        put("payload", payload)
    }

    private class Channel : RtcDataChannel {
        val sent = mutableListOf<JsonObject>()
        private lateinit var observer: RtcDataChannelObserver
        private var currentState = RtcDataChannelState.OPEN

        override fun state() = currentState

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
