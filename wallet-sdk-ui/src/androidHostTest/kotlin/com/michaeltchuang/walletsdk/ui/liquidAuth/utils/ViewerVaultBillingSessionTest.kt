package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthPaymentVoucherMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class ViewerVaultBillingSessionTest {
    private val key = byteArrayOf(1, 2, 3)
    private val channel = ByteArray(32) { 7 }
    private val errors = mutableListOf<Throwable>()
    private val signer = object : MppWalletSigner {
        override val address = "creator"
        override val authorizedSignerPublicKey = byteArrayOf(9)
        override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray =
            error("Tests must never sign a transaction")
    }

    private class FakeAdapter : ViewerVaultBillingSession.Adapter {
        var settled = 0L
        var reads = 0
        var failRead = false
        var confirm = true
        var readAction: suspend (ViewerVaultBillingSession.Voucher) -> Unit = {}
        var authorizationAction: suspend (ViewerVaultBillingSession.Voucher) -> Result<Unit> = { Result.success(Unit) }
        var action: suspend () -> Unit = {}
        var active = 0
        var maxActive = 0
        val submissions = mutableListOf<ViewerVaultBillingSession.Voucher>()

        override suspend fun validateAuthorization(
            voucher: ViewerVaultBillingSession.Voucher,
        ): Result<HostViewerVaultReader.Snapshot> {
            val authorization = authorizationAction(voucher)
            authorization.exceptionOrNull()?.let { return Result.failure(it) }
            return readChannel(voucher)
        }

        override suspend fun readChannel(
            voucher: ViewerVaultBillingSession.Voucher,
        ): Result<HostViewerVaultReader.Snapshot> {
            reads++
            readAction(voucher)
            if (failRead) return Result.failure(IllegalStateException("read failed"))
            return Result.success(HostViewerVaultReader.Snapshot(1_000 - settled, settled, 1_000 - settled, 1_000))
        }

        override suspend fun settle(
            voucher: ViewerVaultBillingSession.Voucher,
            creatorWalletSigner: MppWalletSigner,
        ): Result<Unit> {
            submissions += voucher
            active++
            maxActive = maxOf(maxActive, active)
            try {
                action()
                if (confirm) settled = voucher.totalAmountClaimedMicroUsdc
                return Result.success(Unit)
            } finally {
                active--
            }
        }
    }

    private fun TestScope.session(
        adapter: FakeAdapter,
        viewer: String = "viewer",
        frequency: Int = 3,
        suppliedKey: ByteArray = key,
        snapshot: (HostViewerVaultReader.Snapshot) -> Unit = {},
    ) = ViewerVaultBillingSession(
        scope = backgroundScope,
        sessionId = "mesh-session",
        viewerAddress = viewer,
        creatorAddress = "creator",
        network = MppNetworks.ALGORAND_TESTNET,
        signerPublicKey = suppliedKey,
        buildCreatorWalletSigner = { signer },
        onSnapshot = snapshot,
        onError = { errors += it },
        payoutFrequencyBlocks = frequency,
        adapter = adapter,
        operationTimeoutMillis = 1_000,
        finalDrainTimeoutMillis = 1_500,
    )

    private fun message(
        amount: Long? = 100,
        session: String? = "mesh-session",
        viewer: String? = "viewer",
        publicKey: ByteArray? = key,
        channelId: ByteArray? = channel,
        signature: String? = Base64.encode(byteArrayOf(5)),
    ) = LiquidAuthPaymentVoucherMessage(
        sessionId = session,
        viewerAddress = viewer,
        viewerPublicKeyBase64 = publicKey?.let { Base64.encode(it) },
        viewerPublicKey = publicKey,
        signatureBase64 = signature,
        totalAmountClaimedMicroUsdc = amount,
        channelIdBase64 = channelId?.let { Base64.encode(it) },
        channelId = channelId,
    )

    @Test
    fun `EXPECT retargeting and decreasing amounts to be rejected WHEN a channel is already pinned`() = runTest {
        val adapter = FakeAdapter()
        val session = session(adapter)
        val malformed = listOf(
            message(session = null), message(session = "request-id"),
            message(viewer = null), message(viewer = "other"),
            message(publicKey = null), message(publicKey = byteArrayOf(9)),
            message(channelId = null), message(channelId = ByteArray(31)),
            message(signature = null), message(signature = ""), message(signature = "!!!"),
            message(amount = null), message(amount = -1),
        )
        malformed.forEach { assertFalse(session.acceptVoucher(it)) }
        assertTrue(session.acceptVoucher(message(amount = 0)))
        assertTrue(session.acceptVoucher(message()))
        assertFalse(session.acceptVoucher(message(amount = 99)))
        assertFalse(session.acceptVoucher(message(amount = 200, channelId = ByteArray(32) { 8 })))
        assertTrue(session.acceptVoucher(message())) // equal cumulative total is idempotent
        session.close().join()
        assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
    }

    @Test
    fun `EXPECT only increasing chain rounds to reach the boundary with the latest signed total`() = runTest {
        val adapter = FakeAdapter()
        val session = session(adapter)
        session.onBlock(500)
        assertTrue(session.acceptVoucher(message(100)))
        session.onBlock(500)
        session.onBlock(499)
        session.onBlock(502)
        runCurrent()
        assertTrue(adapter.submissions.isEmpty())
        assertTrue(session.acceptVoucher(message(250)))
        session.onBlock(503)
        runCurrent()
        assertEquals(listOf(250L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
        assertTrue(session.acceptVoucher(message(300)))
        session.onBlock(505)
        runCurrent()
        assertEquals(1, adapter.submissions.size)
        session.onBlock(506)
        runCurrent()
        assertEquals(listOf(250L, 300L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
        session.close().join()
    }

    @Test
    fun `EXPECT retry to read the chain without resubmitting WHEN an uncertain payment is confirmed`() = runTest {
        val adapter = FakeAdapter()
        val session = session(adapter, frequency = 1)
        adapter.action = {
            adapter.settled = 100 // chain succeeded but transport lost its response
            error("lost response")
        }
        session.onBlock(10)
        session.acceptVoucher(message())
        session.onBlock(11)
        runCurrent()
        assertEquals(1, adapter.submissions.size)
        assertTrue(errors.isNotEmpty())
        session.onBlock(12)
        runCurrent()
        assertEquals(1, adapter.submissions.size)
        assertEquals(3, adapter.reads)
        session.close().join()
    }

    @Test
    fun `EXPECT the pending voucher to be retained WHEN a read fails or a submission is unconfirmed`() = runTest {
        val adapter = FakeAdapter()
        val session = session(adapter, frequency = 1)
        session.onBlock(1)
        assertTrue(session.acceptVoucher(message()))
        adapter.failRead = true
        session.onBlock(2)
        runCurrent()
        assertTrue(adapter.submissions.isEmpty())
        adapter.failRead = false
        adapter.confirm = false
        session.onBlock(3)
        runCurrent()
        assertEquals(1, adapter.submissions.size)
        assertTrue(errors.any { it.message == "Voucher settlement is not confirmed" })
        adapter.confirm = true
        session.onBlock(4)
        runCurrent()
        assertEquals(2, adapter.submissions.size)
        session.close().join()
    }

    @Test
    fun `EXPECT signing and submission to be skipped WHEN the voucher is already settled`() = runTest {
        val adapter = FakeAdapter().apply { settled = 150 }
        val session = session(adapter)
        session.acceptVoucher(message())
        session.close().join()
        assertTrue(adapter.submissions.isEmpty())
        assertEquals(2, adapter.reads)
    }

    @Test
    fun `EXPECT authority to stay unchanged WHEN caller or adapter arrays are mutated`() = runTest {
        val adapter = FakeAdapter()
        val mutableKey = key.copyOf()
        val mutableChannel = channel.copyOf()
        val session = session(adapter, suppliedKey = mutableKey)
        mutableKey.fill(9)
        session.signerPublicKey.fill(9)
        assertTrue(session.acceptVoucher(message(channelId = mutableChannel)))
        mutableChannel.fill(9)
        adapter.action = {
            adapter.submissions.last().channelId.fill(9)
            adapter.submissions.last().signerPublicKey.fill(9)
            adapter.submissions.last().signature.fill(9)
        }
        session.close().join()
        val submitted = adapter.submissions.single()
        assertContentEquals(channel, submitted.channelId)
        assertContentEquals(key, submitted.signerPublicKey)
        assertContentEquals(byteArrayOf(5), submitted.signature)
    }

    @Test
    fun `EXPECT the final drain to wait serially then use the newest voucher WHEN closing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val adapter = FakeAdapter().apply { action = { gate.await() } }
        val session = session(adapter, frequency = 1)
        session.onBlock(100)
        session.acceptVoucher(message(100))
        session.onBlock(101)
        runCurrent()
        assertEquals(1, adapter.active)
        assertTrue(session.acceptVoucher(message(200)))
        val closing = session.close()
        assertFalse(session.acceptVoucher(message(300)))
        gate.complete(Unit)
        closing.join()
        assertEquals(listOf(100L, 200L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
        assertEquals(1, adapter.maxActive)
        session.close().join()
        assertEquals(2, adapter.submissions.size)
    }

    @Test
    fun `EXPECT other peers to remain unblocked and removal to be bounded WHEN a peer hangs`() = runTest {
        val hung = FakeAdapter().apply { action = { awaitCancellation() } }
        val healthy = FakeAdapter()
        val first = session(hung, frequency = 1)
        val second = session(healthy, viewer = "viewer-two", frequency = 1)
        first.onBlock(1)
        second.onBlock(1)
        first.acceptVoucher(message(100))
        second.acceptVoucher(message(200, viewer = "viewer-two", channelId = ByteArray(32) { 8 }))
        first.onBlock(2)
        second.onBlock(2)
        runCurrent()
        assertEquals(200L, healthy.settled)
        assertEquals(1, hung.active)
        val close = first.close()
        advanceTimeBy(1_501)
        runCurrent()
        assertTrue(close.isCompleted)
        assertEquals(0, hung.active)
        assertEquals(1, hung.maxActive)
        second.close().join()
        assertEquals("viewer-two", healthy.submissions.single().viewerAddress)
    }

    @Test
    fun `EXPECT settlement to proceed WHEN the snapshot observer throws`() = runTest {
        val adapter = FakeAdapter()
        val session = session(adapter, snapshot = { error("UI observer failure") })
        session.acceptVoucher(message())
        session.close().join()
        assertEquals(100L, adapter.settled)
        assertTrue(errors.any { it.message == "UI observer failure" })
    }

    @Test
    fun `EXPECT the channel to stay unpinned and the amount to stay valid WHEN identity read fails`() = runTest {
        val badHint = ByteArray(32) { 99 }
        val adapter = FakeAdapter().apply {
            readAction = { voucher ->
                require(!voucher.channelId.contentEquals(badHint)) { "Channel signer mismatch" }
            }
        }
        val session = session(adapter)
        assertFalse(session.acceptVoucher(message(900, channelId = badHint)))
        assertTrue(adapter.submissions.isEmpty())
        assertTrue(session.acceptVoucher(message(100)))
        session.close().join()
        assertContentEquals(channel, adapter.submissions.single().channelId)
        assertEquals(100L, adapter.submissions.single().totalAmountClaimedMicroUsdc)
    }

    @Test
    fun `EXPECT the session to remain available for a valid voucher WHEN identity read times out`() = runTest {
        val adapter = FakeAdapter().apply { readAction = { awaitCancellation() } }
        val session = session(adapter)
        val pending = async { session.acceptVoucher(message()) }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(pending.await())
        adapter.readAction = {}
        assertTrue(session.acceptVoucher(message(channelId = ByteArray(32) { 8 })))
        session.close().join()
        assertEquals(1, adapter.submissions.size)
        assertTrue(errors.any { it.message == "Voucher authorization validation timed out" })
    }

    @Test
    fun `EXPECT close to skip an unaccepted identity read and reject late acceptance`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val adapter = FakeAdapter().apply { readAction = { gate.await() } }
        val session = session(adapter)
        val pending = async { session.acceptVoucher(message()) }
        runCurrent()
        val closing = session.close()
        runCurrent()
        assertTrue(closing.isCompleted)
        gate.complete(Unit)
        assertFalse(pending.await())
        assertTrue(adapter.submissions.isEmpty())
    }

    @Test
    fun `EXPECT the pinned channel and monotonic amount to be rechecked WHEN first reads run concurrently`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val adapter = FakeAdapter().apply {
            readAction = { if (it.totalAmountClaimedMicroUsdc == 100L) gate.await() }
        }
        val session = session(adapter)
        val older = async { session.acceptVoucher(message(100)) }
        runCurrent()
        assertTrue(session.acceptVoucher(message(200)))
        gate.complete(Unit)
        assertFalse(older.await())
        session.close().join()
        assertEquals(listOf(200L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
    }

    @Test
    fun `EXPECT the accepted channel to be retained WHEN a suspended identity read resolves late`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val otherChannel = ByteArray(32) { 8 }
        val adapter = FakeAdapter().apply {
            readAction = { if (it.channelId.contentEquals(otherChannel)) gate.await() }
        }
        val session = session(adapter)
        val competing = async { session.acceptVoucher(message(200, channelId = otherChannel)) }
        runCurrent()
        assertTrue(session.acceptVoucher(message(100)))
        gate.complete(Unit)
        assertFalse(competing.await())
        session.close().join()
        assertContentEquals(channel, adapter.submissions.single().channelId)
    }

    @Test
    fun `EXPECT the round baseline to be kept and other peers to stay isolated WHEN frequency updates`() = runTest {
        val firstAdapter = FakeAdapter()
        val secondAdapter = FakeAdapter()
        val first = session(firstAdapter, frequency = 10)
        val second = session(secondAdapter, viewer = "second", frequency = 10)
        first.onBlock(50)
        second.onBlock(50)
        first.acceptVoucher(message())
        second.acceptVoucher(message(viewer = "second"))
        first.onBlock(53)
        second.onBlock(53)
        runCurrent()
        assertTrue(firstAdapter.submissions.isEmpty())
        first.updatePayoutFrequencyBlocks(3).join()
        runCurrent()
        assertEquals(1, firstAdapter.submissions.size)
        assertTrue(secondAdapter.submissions.isEmpty())
        assertFailsWith<IllegalArgumentException> { first.updatePayoutFrequencyBlocks(0) }
        assertFailsWith<IllegalArgumentException> { first.updatePayoutFrequencyBlocks(-1) }
        first.updatePayoutFrequencyBlocks(5).join()
        first.acceptVoucher(message(200))
        first.onBlock(56)
        first.onBlock(56)
        first.onBlock(55)
        runCurrent()
        assertEquals(1, firstAdapter.submissions.size)
        first.onBlock(58)
        runCurrent()
        assertEquals(2, firstAdapter.submissions.size)
        first.close().join()
        second.close().join()
    }

    @Test
    fun `EXPECT the valid pending voucher to remain WHEN a higher or equal signature is invalid`() = runTest {
        val adapter = FakeAdapter().apply {
            authorizationAction = {
                if (it.signature.contentEquals(byteArrayOf(5))) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalArgumentException("Invalid voucher signature"))
                }
            }
        }
        val session = session(adapter)
        assertTrue(session.acceptVoucher(message(100)))
        val invalidSignature = Base64.encode(byteArrayOf(9))
        assertFalse(session.acceptVoucher(message(900, signature = invalidSignature)))
        assertFalse(session.acceptVoucher(message(100, signature = invalidSignature)))
        session.close().join()
        assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
        assertContentEquals(byteArrayOf(5), adapter.submissions.single().signature)
        assertEquals(2, errors.count { it.message == "Invalid voucher signature" })
    }

    @Test
    fun `EXPECT the channel and amount to stay unpinned WHEN the first signature is invalid`() = runTest {
        val adapter = FakeAdapter().apply {
            authorizationAction = { Result.failure(IllegalArgumentException("Invalid voucher signature")) }
        }
        val session = session(adapter)
        assertFalse(session.acceptVoucher(message(900, channelId = ByteArray(32) { 9 })))
        assertEquals(0, adapter.reads)
        adapter.authorizationAction = { Result.success(Unit) }
        assertTrue(session.acceptVoucher(message(100)))
        session.close().join()
        assertContentEquals(channel, adapter.submissions.single().channelId)
        assertEquals(100L, adapter.settled)
    }

    @Test
    fun `EXPECT the valid pending voucher to be retained WHEN authorization times out`() = runTest {
        val adapter = FakeAdapter()
        val session = session(adapter)
        assertTrue(session.acceptVoucher(message(100)))
        adapter.authorizationAction = { awaitCancellation() }
        val pending = async { session.acceptVoucher(message(900)) }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(pending.await())
        session.close().join()
        assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
        assertTrue(errors.any { it.message == "Voucher authorization validation timed out" })
    }

    @Test
    fun `EXPECT the monotonic amount to be rechecked before replacement WHEN authorizations run concurrently`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val adapter = FakeAdapter().apply {
            authorizationAction = {
                if (it.totalAmountClaimedMicroUsdc == 200L) gate.await()
                Result.success(Unit)
            }
        }
        val session = session(adapter)
        assertTrue(session.acceptVoucher(message(100)))
        val pending = async { session.acceptVoucher(message(200)) }
        runCurrent()
        assertTrue(session.acceptVoucher(message(300)))
        gate.complete(Unit)
        assertFalse(pending.await())
        session.close().join()
        assertEquals(listOf(300L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
    }

    @Test
    fun `EXPECT close to drain the validated voucher without waiting for pending authorization`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val adapter = FakeAdapter()
        val session = session(adapter)
        assertTrue(session.acceptVoucher(message(100)))
        adapter.authorizationAction = {
            gate.await()
            Result.success(Unit)
        }
        val pending = async { session.acceptVoucher(message(200)) }
        runCurrent()
        session.close().join()
        gate.complete(Unit)
        assertFalse(pending.await())
        assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
    }

    @Test
    fun `EXPECT cancellation to propagate without replacing the pending voucher WHEN authorization is cancelled`() = runTest {
        val adapter = FakeAdapter()
        val session = session(adapter)
        assertTrue(session.acceptVoucher(message(100)))
        adapter.authorizationAction = { throw CancellationException("cancelled validation") }
        assertFailsWith<CancellationException> { session.acceptVoucher(message(200)) }
        session.close().join()
        assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
    }

    @Test
    fun `EXPECT the valid pending voucher to remain WHEN a new voucher exceeds the deposit`() = runTest {
        val adapter = FakeAdapter()
        val session = session(adapter)
        assertTrue(session.acceptVoucher(message(100)))
        assertFalse(session.acceptVoucher(message(1_001)))
        session.close().join()
        assertEquals(100L, adapter.submissions.single().totalAmountClaimedMicroUsdc)
        assertTrue(errors.any { it.message == "Voucher exceeds deposit" })
    }
}
