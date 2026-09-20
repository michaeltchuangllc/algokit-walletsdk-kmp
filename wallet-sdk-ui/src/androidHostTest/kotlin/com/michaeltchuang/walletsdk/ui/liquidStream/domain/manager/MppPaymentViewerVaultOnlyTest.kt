package com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager

import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelObserver
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelState
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BillingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class MppPaymentViewerVaultOnlyTest {
    @Test
    fun deferredReceiptsSignCumulativeVouchersWithoutRailReceipts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val oldChannel = EscrowSessionVaultHybridManagerClient.channelId
        val oldSalt = EscrowSessionVaultHybridManagerClient.salt
        val oldHost = EscrowSessionVaultHybridManagerClient.hostAddress
        val manager = MppPaymentViewerManager(mockk<GetRemainingSessionVaultBalanceUseCase>())
        mockkObject(MppPayments)
        try {
            val channel = ByteArray(32) { 9 }
            val dc = Channel()
            val signer = mockk<MppWalletSigner>(relaxed = true)
            every { signer.authorizedSignerPublicKey } returns byteArrayOf(1, 2, 3)
            val data = MppPayments.SessionDynamicData(100, 20, 20, 1)
            coEvery { MppPayments.getSessionDynamicDataFromVault(any()) } returns data
            coEvery { MppPayments.getSessionProgressSnapshotFromVault(any()) } returns MppPayments.computeSessionProgressSnapshot(data)
            val cumulative = mutableListOf<Long>()
            every { MppPayments.buildLogicSigSettlementVoucher(any(), any(), any()) } answers {
                assertTrue(firstArg<ByteArray>().contentEquals(channel))
                cumulative += secondArg<Long>()
                byteArrayOf(secondArg<Long>().toByte())
            }
            val signed = mutableListOf<ByteArray>()
            manager.start(
                MppPaymentViewerManager.StartParams(
                    dataChannel = dc,
                    viewerAddress = "viewer",
                    scope = this,
                    signer = signer,
                    mppNetwork = MppNetworks.ALGORAND_TESTNET,
                    sessionVaultAppId = 123,
                    requestMppConsent = { error("Funded receipts should not prompt") },
                    setViewerSessionVaultProgress = { _, _ -> },
                    signFido2Challenge = { challenge, _ ->
                        signed += challenge
                        byteArrayOf(7, 8, 9)
                    },
                    channelIdProvider = { channel },
                ),
            )
            runCurrent()
            fun deliver(segment: Int) {
                val receipt = PaymentReceipt(
                    txId = "", sessionId = "ordinary-session", segmentIndex = segment,
                    amount = "10", asset = "USDC", payTo = "creator", payFrom = "viewer",
                    network = MppNetworks.ALGORAND_TESTNET, timestamp = 1,
                    channelId = Base64.encode(channel), salt = Base64.encode(ByteArray(32) { 1 }),
                    billingMode = BillingMode.SESSION_VAULT, settlementDeferred = true,
                )
                dc.receive(buildJsonObject {
                    put("type", DCMessageType.SEGMENT_ACCEPTED.value)
                    put("payload", Json.encodeToJsonElement(receipt))
                })
            }
            deliver(0)
            runCurrent()
            deliver(1)
            deliver(1)
            runCurrent()
            assertEquals(listOf(30L, 40L), cumulative)
            assertEquals(2, signed.size)
            val vouchers = dc.sent.filter { it["type"]?.jsonPrimitive?.content == DCMessageType.SEGMENT_VOUCHER.value }
            assertEquals(2, vouchers.size)
            assertTrue(vouchers.all { it["channelId"]?.jsonPrimitive?.content == Base64.encode(channel) })
            assertTrue(vouchers.all { it["billingMode"]?.jsonPrimitive?.content == BillingMode.SESSION_VAULT })
            assertTrue(vouchers.all { !it["signature"]?.jsonPrimitive?.content.isNullOrBlank() })
            assertTrue(dc.sent.none { it["type"]?.jsonPrimitive?.content == DCMessageType.SEGMENT_PAYMENT.value })
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
        override fun send(bytes: ByteArray) { sent += Json.parseToJsonElement(bytes.decodeToString()).jsonObject }
        override fun registerObserver(observer: RtcDataChannelObserver) { this.observer = observer }
        override fun close() = Unit
        fun receive(message: JsonObject) = observer.onMessage(message.toString().encodeToByteArray())
    }
}
