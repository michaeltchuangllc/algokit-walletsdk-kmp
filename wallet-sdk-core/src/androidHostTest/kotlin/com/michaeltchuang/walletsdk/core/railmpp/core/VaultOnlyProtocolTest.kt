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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
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
                sessionId = p.sessionId, segmentIndex = p.segmentIndex, amount = p.amount,
                asset = p.asset, network = p.network, payTo = p.payTo, meta = p.meta,
                billingMode = null, channelId = null, salt = null,
            )
        }
        coEvery { consent.requestConsent(any()) } returns ConsentApproval(
            approved = true, autoPaySegments = true, budgetCap = BudgetCap("100", "USDC"),
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

    private fun config() = ServerConfig(
        sessionId = "ordinary-session", // Intentionally not a mesh-name heuristic.
        gating = GatingConfig(
            mode = GatingMode.WHOLE_STREAM, amount = "10", asset = "USDC",
            network = MppNetworks.ALGORAND_TESTNET, payTo = creator,
        ),
        viewerAddress = viewer,
        viewerAuthorizedSignerPublicKey = signer,
        vaultOnlyBilling = true,
    )

    private fun request() = PaymentRequest(
        id = "request", sessionId = "ordinary-session", segmentIndex = 0,
        amount = "10", asset = "USDC", network = MppNetworks.ALGORAND_TESTNET,
        payTo = creator, ttl = 30, nonce = "nonce",
        meta = PaymentRequestMeta(GatingMode.WHOLE_STREAM, EnforcementMode.TRACK),
        channelId = Base64.encode(HostViewerVaultReader.deriveChannelId(viewer, creator, signer, MppNetworks.ALGORAND_TESTNET, salt)),
        salt = Base64.encode(salt), billingMode = BillingMode.SESSION_VAULT,
    )

    private fun receipt() = PaymentReceipt(
        txId = "", sessionId = "ordinary-session", segmentIndex = 0, amount = "10",
        asset = "USDC", payTo = creator, payFrom = viewer,
        network = MppNetworks.ALGORAND_TESTNET, timestamp = 1,
        channelId = request().channelId, salt = request().salt,
        billingMode = BillingMode.SESSION_VAULT, settlementDeferred = true,
    )

    @Test
    fun fundedShortcutIsDeferredAndNeverSettlesOrUsesGlobalBalance() = runTest(dispatcher) {
        val dc = Channel()
        val server = PaywalledRTCServer(
            rail, config(), balance,
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
    fun unfundedHandshakeRequiresOnChainFundingAndCannotReplayOrSubmitDirectPayment() = runTest(dispatcher) {
        var funded = 0L
        val dc = Channel()
        val server = PaywalledRTCServer(
            rail, config(), balance,
            vaultReader = { _, _, _, _, _ -> Result.success(HostViewerVaultReader.Snapshot(funded, 0, funded, funded)) },
            workDispatcher = dispatcher,
        )
        server.listen(dc, emptyList())
        advanceTimeBy(101)
        runCurrent()
        val request = paymentRequestFromJson(dc.sent.single().getValue("payload").jsonObject)
        assertEquals(BillingMode.SESSION_VAULT, request.billingMode)
        val hint = buildJsonObject {
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
        funded = 9
        dc.receive(hint)
        runCurrent()
        assertEquals(1, dc.sent.size)
        funded = 100
        dc.receive(buildJsonObject { hint.forEach { (k, v) -> put(k, v) }; put("channelId", "wrong") })
        runCurrent()
        assertEquals(1, dc.sent.size)
        dc.receive(hint)
        runCurrent()
        dc.receive(hint)
        runCurrent()
        assertEquals(2, dc.sent.size)
        coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
        coVerify(exactly = 0) { balance(any()) }
        server.terminate()
    }

    @Test
    fun missingKeyRequestsHelloThenRetriesWithMatchingSaltAndChannel() = runTest(dispatcher) {
        val dc = Channel()
        val server = PaywalledRTCServer(
            rail, config().copy(viewerAuthorizedSignerPublicKey = null), balance,
            vaultReader = { _, _, _, _, _ -> Result.success(HostViewerVaultReader.Snapshot(0, 0, 0, 0)) },
            workDispatcher = dispatcher,
        )
        server.listen(dc, emptyList())
        advanceTimeBy(5_101)
        runCurrent()
        assertEquals("true", dc.sent.single()["requestViewerIdentity"]?.jsonPrimitive?.content)
        server.updateConfig(config())
        runCurrent()
        val payment = paymentRequestFromJson(dc.sent.last().getValue("payload").jsonObject)
        assertEquals(request().channelId, payment.channelId)
        assertEquals(request().salt, payment.salt)
        coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
        server.terminate()
    }

    @Test
    fun viewerConsentSendsOnlyFundedNotificationAndDeferredReceiptsConsumeOnce() = runTest(dispatcher) {
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
    fun failedFundingAndModeDowngradeNeverBuildDirectCredentials() = runTest(dispatcher) {
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
    fun fundedReceiptWithoutRequestStillTriggersConsumption() = runTest(dispatcher) {
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
    fun legacyWireDefaultsAndConfigEqualityRemainExplicit() {
        val legacy = config().copy(vaultOnlyBilling = false)
        assertFalse(ServerConfig(gating = legacy.gating).vaultOnlyBilling)
        assertNotEquals(legacy, config())
        assertEquals(config(), config().copy())
        assertFalse(request().copy(billingMode = null).toJson().containsKey("billingMode"))
        assertEquals(BillingMode.SESSION_VAULT, paymentRequestFromJson(request().toJson()).billingMode)
    }

    @Test
    fun legacyViewerStillCreatesDirectPaymentEvenWithMeshSessionName() = runTest(dispatcher) {
        coEvery { rail.createRailPayment(any()) } returns RailPayment("test", 1, "nonce", JsonNull, JsonNull)
        val dc = Channel()
        val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
        client.connect(dc)
        dc.receive(envelope(
            DCMessageType.SEGMENT_REQUEST,
            request().copy(sessionId = "mesh-legacy", billingMode = null).toJson(),
        ))
        runCurrent()
        coVerify(exactly = 1) { rail.createRailPayment(any()) }
        assertEquals(DCMessageType.SEGMENT_PAYMENT.value, dc.sent.single()["type"]?.jsonPrimitive?.content)
        client.terminate()
    }

    @Test
    fun legacyServerStillSettlesThroughRail() = runTest(dispatcher) {
        val dc = Channel()
        val direct = RailPayment("test", 1, "nonce", JsonNull, JsonNull)
        coEvery { rail.verifyAndSettle(any(), any()) } returns receipt().copy(
            txId = "real-transaction", billingMode = null, settlementDeferred = false,
        )
        val server = PaywalledRTCServer(
            rail, config().copy(vaultOnlyBilling = false), balance, workDispatcher = dispatcher,
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
    fun failedVaultLookupNeverFallsBackToRailSettlement() = runTest(dispatcher) {
        val dc = Channel()
        val server = PaywalledRTCServer(
            rail, config(), balance,
            vaultReader = { _, _, _, _, _ -> Result.failure(IllegalStateException("offline")) },
            workDispatcher = dispatcher,
        )
        server.listen(dc, emptyList())
        advanceTimeBy(101)
        runCurrent()
        assertEquals(BillingMode.SESSION_VAULT, dc.sent.single()["payload"]?.jsonObject?.get("billingMode")?.jsonPrimitive?.content)
        dc.receive(envelope(DCMessageType.SEGMENT_PAYMENT, RailPayment("test", 1, "nonce", JsonNull, JsonNull).toJson()))
        runCurrent()
        assertEquals(1, dc.sent.size)
        coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
        server.terminate()
    }

    @Test
    fun deferredConsumptionCannotReuseUnsettledFunds() = runTest(dispatcher) {
        val dc = Channel()
        val serverConfig = config().let { it.copy(
            gating = it.gating.copy(mode = GatingMode.PARTIAL_TIME, segmentDuration = 1, leadTime = 0),
        ) }
        val server = PaywalledRTCServer(
            rail, serverConfig, balance,
            vaultReader = { _, _, _, _, _ -> Result.success(HostViewerVaultReader.Snapshot(10, 0, 10, 10)) },
            workDispatcher = dispatcher,
        )
        server.listen(dc, emptyList())
        advanceTimeBy(101)
        runCurrent()
        assertEquals(DCMessageType.SEGMENT_ACCEPTED.value, dc.sent.single()["type"]?.jsonPrimitive?.content)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(DCMessageType.SEGMENT_REQUEST.value, dc.sent.last()["type"]?.jsonPrimitive?.content)
        assertEquals(2, dc.sent.size)
        coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
        server.terminate()
    }

    @Test
    fun legacyClientAndServerStillUseDirectRail() = runTest(dispatcher) {
        val payment = RailPayment("test", 1, "nonce", buildJsonObject {}, buildJsonObject {})
        coEvery { rail.createRailPayment(any()) } returns payment
        coEvery { rail.verifyAndSettle(any(), any()) } returns receipt().copy(
            txId = "real-transaction", billingMode = null, settlementDeferred = false, salt = null,
        )
        val hostDc = Channel()
        val server = PaywalledRTCServer(rail, config().copy(vaultOnlyBilling = false), balance, workDispatcher = dispatcher)
        server.listen(hostDc, emptyList())
        advanceTimeBy(101)
        runCurrent()
        val legacyRequest = paymentRequestFromJson(hostDc.sent.single().getValue("payload").jsonObject)
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
    fun unavailableVaultReaderFailsClosed() = runTest(dispatcher) {
        val dc = Channel()
        val server = PaywalledRTCServer(
            rail, config(), balance,
            vaultReader = { _, _, _, _, _ -> Result.failure(IllegalStateException("network unavailable")) },
            workDispatcher = dispatcher,
        )
        server.listen(dc, emptyList())
        advanceTimeBy(101)
        runCurrent()
        assertEquals(DCMessageType.SEGMENT_REQUEST.value, dc.sent.single()["type"]?.jsonPrimitive?.content)
        coVerify(exactly = 0) { rail.verifyAndSettle(any(), any()) }
        coVerify(exactly = 0) { balance(any()) }
        server.terminate()
    }

    @Test
    fun viewerRespondsToIdentityRetryWithoutSigningPayment() = runTest(dispatcher) {
        val dc = Channel()
        val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
        client.connect(dc)
        runCurrent()
        var retries = 0
        client.onDataChannelOpen = { retries++ }
        dc.receive(buildJsonObject {
            put("type", DCMessageType.SEGMENT_HANDSHAKE.value)
            put("billingMode", BillingMode.SESSION_VAULT)
            put("requestViewerIdentity", true)
        })
        runCurrent()
        assertEquals(1, retries)
        coVerify(exactly = 0) { rail.createRailPayment(any()) }
        client.terminate()
    }

    @Test
    fun deferredConsumptionHonorsBudgetCapWithoutSigningPayment() = runTest(dispatcher) {
        coEvery { consent.requestConsent(any()) } returns ConsentApproval(
            approved = true, autoPaySegments = true, budgetCap = BudgetCap("10", "USDC"),
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
    fun receiptFirstPinsVaultIdentityAndRejectsChangedSessionsChannelsAndDuplicates() = runTest(dispatcher) {
        val dc = Channel()
        val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
        var receipts = 0
        client.onPaymentReceipt = { receipts++ }
        client.connect(dc)
        dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt().toJson()))
        runCurrent()
        val channel = EscrowSessionVaultHybridManagerClient.channelId?.copyOf()
        val invalid = listOf(
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
    fun requestFirstPinsVaultIdentityBeforeAnyConsumption() = runTest(dispatcher) {
        val dc = Channel()
        val client = PaywalledRTCClient(rail, consent, workDispatcher = dispatcher)
        var receipts = 0
        client.onPaymentReceipt = { receipts++ }
        client.connect(dc)
        dc.receive(envelope(DCMessageType.SEGMENT_REQUEST, request().toJson()))
        runCurrent()
        dc.receive(envelope(DCMessageType.SEGMENT_ACCEPTED, receipt().copy(sessionId = "other-session").toJson()))
        dc.receive(envelope(
            DCMessageType.SEGMENT_ACCEPTED,
            receipt().copy(channelId = Base64.encode(ByteArray(32) { 9 })).toJson(),
        ))
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
    fun freeVaultSegmentNeedsNoFundingOrDirectPayment() = runTest(dispatcher) {
        val dc = Channel()
        var reads = 0
        val server = PaywalledRTCServer(
            rail, config().copy(gating = config().gating.copy(amount = "0")), balance,
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
    fun fundedAndInitiallyEmptyExtrasKeepReceiptAndVoucherIdentityWithoutDirectCharges() = runTest(dispatcher) {
        for (initialBalance in listOf(0L, 100L)) {
            var funded = initialBalance
            val hostDc = Channel()
            val viewerDc = Channel()
            val server = PaywalledRTCServer(
                rail, config(), balance,
                vaultReader = { _, _, _, _, _ -> Result.success(HostViewerVaultReader.Snapshot(funded, 0, funded, funded)) },
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
                        remainingMicroUsdc = 90,
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
                assertEquals(DCMessageType.VIEWER_VAULT_FUNDED.value, viewerDc.sent.single()["type"]?.jsonPrimitive?.content)
                funded = 100
                hostDc.receive(viewerDc.sent.single())
                runCurrent()
                viewerDc.receive(hostDc.sent.last())
                runCurrent()
            }
            hostDc.receive(viewerDc.sent.last())
            runCurrent()
            val accepted = requireNotNull(acknowledged)
            val voucher = requireNotNull(routedVoucher)
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

    private fun envelope(type: DCMessageType, payload: JsonObject) = buildJsonObject {
        put("type", type.value)
        put("sessionId", "ordinary-session")
        put("segmentIndex", 0)
        put("payload", payload)
    }

    private class Channel : RtcDataChannel {
        val sent = mutableListOf<JsonObject>()
        private lateinit var observer: RtcDataChannelObserver
        override fun state() = RtcDataChannelState.OPEN
        override fun send(bytes: ByteArray) { sent += Json.parseToJsonElement(bytes.decodeToString()).jsonObject }
        override fun registerObserver(observer: RtcDataChannelObserver) { this.observer = observer }
        override fun close() = Unit
        fun receive(message: JsonObject) = observer.onMessage(message.toString().encodeToByteArray())
    }
}
