package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetMppVoucherNoteUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthPaymentVoucherMessage
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_BATCH_BLOCK_COUNT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class ViewerVaultBillingSessionTest {
    private val key = byteArrayOf(1, 2, 3)
    private val channel = ByteArray(32) { 7 }
    private val errors = mutableListOf<Throwable>()

    private class FakeRepository : MppVoucherRepository {
        val rows = mutableMapOf<String, MppVoucherEntity>()
        var failWrite = false
        var writes = 0

        override suspend fun upsertVoucher(voucher: MppVoucherEntity) {
            check(!failWrite) { "Storage unavailable" }
            writes++
            rows[voucher.channelIdBase64] = voucher
        }

        override suspend fun getAllVouchers() = rows.values.toList()

        override suspend fun deleteSettledVoucher(
            channelIdBase64: String,
            confirmedAmount: Long,
        ) {
            if ((rows[channelIdBase64]?.totalAmountClaimedMicroUsdc ?: return) <= confirmedAmount) {
                rows.remove(channelIdBase64)
            }
        }

        override suspend fun deleteVoucherByChannelId(channelIdBase64: String) {
            rows.remove(channelIdBase64)
        }

        override suspend fun deleteVouchersBeforeRound(
            channelIdBase64: String,
            network: String,
            startRound: Long,
        ) {
            val row = rows[channelIdBase64] ?: return
            if (row.network == network && row.blockNumber > 0 && row.blockNumber < startRound) {
                rows.remove(channelIdBase64)
            }
        }

        override suspend fun deleteVoucherBySessionAndViewer(
            sessionId: String,
            viewerAddress: String,
        ) {
            rows.entries.removeAll { it.value.sessionId == sessionId && it.value.viewerAddress == viewerAddress }
        }

        override suspend fun deleteVoucherBySessionId(sessionId: String) {
            rows.entries.removeAll { it.value.sessionId == sessionId }
        }
    }

    private val signer =
        object : MppWalletSigner {
            override val address = "creator"
            override val authorizedSignerPublicKey = byteArrayOf(9)

            override suspend fun signTransactionBytes(txnMsgpack: ByteArray): ByteArray = error("Tests must never sign a transaction")
        }

    private class FakeAdapter : ViewerVaultBillingSession.Adapter {
        var startRound: Long? = null
        var deposit = 1_000L
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

        override suspend fun validateAuthorization(voucher: ViewerVaultBillingSession.Voucher): Result<HostViewerVaultReader.Snapshot> {
            val authorization = authorizationAction(voucher)
            authorization.exceptionOrNull()?.let { return Result.failure(it) }
            return readChannel(voucher)
        }

        override suspend fun readChannel(voucher: ViewerVaultBillingSession.Voucher): Result<HostViewerVaultReader.Snapshot> {
            reads++
            readAction(voucher)
            if (failRead) return Result.failure(IllegalStateException("read failed"))
            return Result.success(HostViewerVaultReader.Snapshot(deposit - settled, settled, deposit - settled, deposit, startRound))
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
        repository: FakeRepository = FakeRepository(),
        settlementError: (Throwable?) -> Unit = { error -> if (error != null) errors += error },
        getVoucherCoveredBlockCount: (suspend (Long, Long) -> Long)? = null,
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
        onSettlementError = settlementError,
        voucherRepository = repository,
        getMppVoucherNoteUseCase = GetMppVoucherNoteUseCase(),
        payoutFrequencyBlocks = frequency,
        getVoucherCoveredBlockCount = getVoucherCoveredBlockCount,
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

    private fun pendingRow() =
        MppVoucherEntity(
            channelIdBase64 = Base64.encode(channel),
            sessionId = "previous-mesh-session",
            viewerAddress = "viewer",
            viewerPublicKeyBase64 = Base64.encode(key),
            signatureBase64 = Base64.encode(byteArrayOf(5)),
            totalAmountClaimedMicroUsdc = 100,
            creatorAddress = "creator",
            blockNumber = 42,
            note = """{"persisted":"original note"}""",
            network = MppNetworks.ALGORAND_TESTNET,
        )

    @Test
    fun `EXPECT reopened channel recovery to discard old vouchers without replaying against fresh funds`() =
        runTest {
            // Test both the reported over-deposit failure and a smaller old voucher that would
            // otherwise validate and incorrectly charge the new deposit.
            for (amount in listOf(2_000L, 100L)) {
                val repository = FakeRepository()
                val old = pendingRow().copy(totalAmountClaimedMicroUsdc = amount)
                repository.rows[old.channelIdBase64] = old
                val adapter =
                    FakeAdapter().apply {
                        startRound = 50
                        authorizationAction = { error("Old lifetime must not be validated") }
                    }
                val session = session(adapter, repository = repository)
                assertEquals(0L, session.restorePending(channel))
                runCurrent()
                assertTrue(repository.rows.isEmpty())
                assertTrue(adapter.submissions.isEmpty())

                adapter.authorizationAction = { Result.success(Unit) }
                session.onBlock(50).join()
                assertTrue(session.acceptVoucher(message(10)))
                session.requestSettlement().join()
                runCurrent()
                assertEquals(10L, adapter.submissions.single().totalAmountClaimedMicroUsdc)
                session.close().join()
            }
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT recovery to preserve current and unknown age authorizations when validation fails`() =
        runTest {
            for ((block, start) in listOf(50L to 50L, 60L to 50L, 0L to 50L, 42L to null)) {
                val repository = FakeRepository()
                val row = pendingRow().copy(blockNumber = block, totalAmountClaimedMicroUsdc = 2_000)
                repository.rows[row.channelIdBase64] = row
                val adapter =
                    FakeAdapter().apply {
                        startRound = start
                        authorizationAction = { Result.failure(IllegalArgumentException("Voucher exceeds deposit")) }
                    }
                val session = session(adapter, repository = repository)
                assertEquals(2_000L, session.restorePending(channel))
                assertEquals(row, repository.rows[row.channelIdBase64])
                assertTrue(adapter.submissions.isEmpty())
                session.close().join()
            }
            assertEquals(4, errors.size)
        }

    private suspend fun TestScope.recoverPending(
        repository: FakeRepository,
        adapter: FakeAdapter,
        buildSigner: suspend (String) -> MppWalletSigner? = { signer },
    ) = ViewerVaultBillingSession.recoverPending(
        scope = backgroundScope,
        repository = repository,
        noteUseCase = GetMppVoucherNoteUseCase(),
        buildSigner = buildSigner,
        onError = { errors += it },
        adapter = adapter,
    )

    @Test
    fun `EXPECT recoverPending to settle a known network row without an incoming voucher`() =
        runTest {
            val saved = pendingRow()
            val repository = FakeRepository().apply { rows[saved.channelIdBase64] = saved }
            val release = CompletableDeferred<Unit>()
            val adapter = FakeAdapter().apply { action = { release.await() } }

            val recovery = async { recoverPending(repository, adapter) }
            runCurrent()

            assertFalse(recovery.isCompleted)
            val submitted = adapter.submissions.single()
            assertEquals(saved.sessionId, submitted.sessionId)
            assertEquals(saved.viewerAddress, submitted.viewerAddress)
            assertEquals(saved.creatorAddress, submitted.creatorAddress)
            assertEquals(saved.network, submitted.network)
            assertContentEquals(key, submitted.signerPublicKey)
            assertContentEquals(channel, submitted.channelId)
            assertContentEquals(Base64.decode(saved.signatureBase64), submitted.signature)
            assertEquals(saved.totalAmountClaimedMicroUsdc, submitted.totalAmountClaimedMicroUsdc)
            assertEquals(saved.note, submitted.note)
            assertEquals(saved.blockNumber, submitted.blockNumber)
            assertEquals(saved, repository.rows[saved.channelIdBase64])
            assertEquals(0, repository.writes)

            release.complete(Unit)
            recovery.await()
            assertEquals(saved.totalAmountClaimedMicroUsdc, adapter.settled)
            assertEquals(1, adapter.submissions.size)
            assertTrue(repository.rows.isEmpty())
            assertEquals(0, repository.writes)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT recoverPending to leave an unknown null network row untouched`() =
        runTest {
            val saved = pendingRow().copy(network = null)
            val repository = FakeRepository().apply { rows[saved.channelIdBase64] = saved }
            val adapter =
                FakeAdapter().apply {
                    authorizationAction = { error("Unknown network must not be authorized") }
                }

            recoverPending(repository, adapter, buildSigner = { error("Unknown network must not build a signer") })
            runCurrent()

            assertEquals(0, adapter.reads)
            assertTrue(adapter.submissions.isEmpty())
            assertEquals(mapOf(saved.channelIdBase64 to saved), repository.rows)
            assertEquals(0, repository.writes)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT recoverPending to delete an already settled row without authorization or submission`() =
        runTest {
            val saved = pendingRow()
            for (settledAmount in listOf(100L, 150L)) {
                val repository = FakeRepository().apply { rows[saved.channelIdBase64] = saved }
                val adapter =
                    FakeAdapter().apply {
                        settled = settledAmount
                        authorizationAction = { error("Settled row must not be authorized") }
                    }

                recoverPending(repository, adapter, buildSigner = { error("Settled row must not build a signer") })

                assertEquals(1, adapter.reads)
                assertTrue(adapter.submissions.isEmpty())
                assertTrue(repository.rows.isEmpty())
                assertEquals(0, repository.writes)
            }
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT concurrent live settlement and recovery on the same channel not to submit twice`() =
        runTest {
            val repository = FakeRepository()
            val release = CompletableDeferred<Unit>()
            // The fake shares chain state but does not serialize submissions.
            val adapter = FakeAdapter().apply { action = { release.await() } }
            val live = session(adapter, repository = repository)
            assertTrue(live.acceptVoucher(message()))
            live.requestSettlement().join()
            runCurrent()
            assertEquals(1, adapter.active)
            val saved = repository.rows.getValue(Base64.encode(channel))
            val readsBeforeRecovery = adapter.reads

            val recovery = async { recoverPending(repository, adapter) }
            runCurrent()

            assertFalse(recovery.isCompleted)
            // Recovery reads and validates the durable row, then waits for the channel lock.
            assertEquals(readsBeforeRecovery + 2, adapter.reads)
            assertEquals(1, adapter.active)
            assertEquals(1, adapter.submissions.size)
            assertEquals(saved, repository.rows[saved.channelIdBase64])
            assertEquals(1, repository.writes)

            release.complete(Unit)
            recovery.await()
            live.close().join()

            // Live confirmation and recovery each re-read the now-settled chain.
            assertEquals(readsBeforeRecovery + 4, adapter.reads)
            assertEquals(100L, adapter.settled)
            assertEquals(0, adapter.active)
            assertEquals(1, adapter.maxActive)
            assertEquals(1, adapter.submissions.size)
            assertTrue(repository.rows.isEmpty())
            assertEquals(1, repository.writes)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT explicit flush with null note params to settle without a block boundary`() =
        runTest {
            val repository = FakeRepository()
            val adapter = FakeAdapter()
            val session = session(adapter, repository = repository)

            assertTrue(session.acceptVoucher(message()))
            val saved = repository.rows.getValue(Base64.encode(channel))
            assertEquals(MppNetworks.ALGORAND_TESTNET, saved.network)
            assertEquals(0L, saved.blockNumber)
            assertTrue(saved.note.contains(Base64.encode(channel)))
            runCurrent()
            assertTrue(adapter.submissions.isEmpty())
            session.requestSettlement().join()
            runCurrent()

            assertEquals(100L, adapter.settled)
            assertEquals(saved.note, adapter.submissions.single().note)
            assertTrue(repository.rows.isEmpty())
            assertTrue(errors.isEmpty())
            session.close().join()
            assertEquals(1, adapter.submissions.size)
        }

    @Test
    fun `EXPECT requestSettlement to flush without closing and accept later vouchers`() =
        runTest {
            val repository = FakeRepository()
            val adapter = FakeAdapter()
            val session = session(adapter, repository = repository)
            assertTrue(session.acceptVoucher(message(100)))
            runCurrent()
            assertTrue(adapter.submissions.isEmpty())

            session.requestSettlement().join()
            runCurrent()
            assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            assertTrue(repository.rows.isEmpty())

            assertTrue(session.acceptVoucher(message(200)))
            runCurrent()
            assertEquals(1, adapter.submissions.size)
            assertEquals(200L, repository.rows.getValue(Base64.encode(channel)).totalAmountClaimedMicroUsdc)
            session.requestSettlement().join()
            runCurrent()
            assertEquals(listOf(100L, 200L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            assertTrue(repository.rows.isEmpty())
            session.close().join()
            assertEquals(2, adapter.submissions.size)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT duplicate acceptance before and after confirmation not to rewrite or recreate a row`() =
        runTest {
            val repository = FakeRepository()
            val release = CompletableDeferred<Unit>()
            val adapter = FakeAdapter().apply { action = { release.await() } }
            val session = session(adapter, repository = repository)
            assertTrue(session.acceptVoucher(message()))
            val saved = repository.rows.getValue(Base64.encode(channel))
            val reads = adapter.reads

            session.onBlock(1).join()
            session.recordChatMessage(ChatMessage("viewer", "later chat", 1)).join()
            assertTrue(session.acceptVoucher(message()))
            assertEquals(reads, adapter.reads)
            assertEquals(saved, repository.rows[Base64.encode(channel)])
            assertEquals(1, repository.writes)
            session.requestSettlement().join()
            runCurrent()
            assertEquals(1, adapter.active)

            assertTrue(session.acceptVoucher(message()))
            session.requestSettlement().join()
            runCurrent()
            assertEquals(1, adapter.submissions.size)
            assertEquals(saved, repository.rows[Base64.encode(channel)])
            release.complete(Unit)
            runCurrent()
            assertTrue(repository.rows.isEmpty())
            val confirmedReads = adapter.reads

            assertTrue(session.acceptVoucher(message()))
            assertTrue(session.acceptVoucher(message()))
            assertTrue(repository.rows.isEmpty())
            runCurrent()
            session.requestSettlement().join()
            runCurrent()
            session.close().join()
            assertEquals(1, repository.writes)
            assertTrue(repository.rows.isEmpty())
            assertEquals(1, adapter.submissions.size)
            assertEquals(confirmedReads, adapter.reads)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT an explicit flush of a newer voucher during an old submission to settle exactly once afterward`() =
        runTest {
            val repository = FakeRepository()
            val releaseOld = CompletableDeferred<Unit>()
            val releaseNew = CompletableDeferred<Unit>()
            val adapter =
                FakeAdapter().apply {
                    action = {
                        if (submissions.last().totalAmountClaimedMicroUsdc == 100L) {
                            releaseOld.await()
                        } else {
                            releaseNew.await()
                        }
                    }
                }
            val session = session(adapter, repository = repository)
            assertTrue(session.acceptVoucher(message(100)))
            session.requestSettlement().join()
            runCurrent()
            assertEquals(1, adapter.active)
            assertTrue(session.acceptVoucher(message(200)))
            session.requestSettlement().join()
            val newer = repository.rows.getValue(Base64.encode(channel))
            runCurrent()
            assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })

            releaseOld.complete(Unit)
            runCurrent()
            assertEquals(listOf(100L, 200L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            assertEquals(100L, adapter.settled)
            assertEquals(newer, repository.rows[Base64.encode(channel)])
            assertEquals(1, adapter.active)
            assertEquals(1, adapter.maxActive)
            releaseNew.complete(Unit)
            runCurrent()
            assertEquals(200L, adapter.settled)
            assertTrue(repository.rows.isEmpty())
            session.requestSettlement().join()
            runCurrent()
            session.close().join()
            assertEquals(listOf(100L, 200L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT concurrent sessions on the same network channel to serialize and reread before submitting`() =
        runTest {
            val repository = FakeRepository()
            val release = CompletableDeferred<Unit>()
            // Sharing chain state does not serialize calls: only the session static lock can do that.
            val adapter = FakeAdapter().apply { action = { release.await() } }
            val secondSnapshots = mutableListOf<HostViewerVaultReader.Snapshot>()
            val first = session(adapter, repository = repository)
            val second = session(adapter, repository = repository, snapshot = { secondSnapshots += it })
            assertTrue(first.acceptVoucher(message()))
            assertTrue(second.acceptVoucher(message()))
            first.requestSettlement().join()
            runCurrent()
            assertEquals(1, adapter.active)
            val readsWhileFirstIsSubmitting = adapter.reads

            second.requestSettlement().join()
            runCurrent()
            assertEquals(readsWhileFirstIsSubmitting, adapter.reads)
            assertTrue(secondSnapshots.isEmpty())
            assertEquals(1, adapter.active)
            assertEquals(1, adapter.submissions.size)

            release.complete(Unit)
            runCurrent()
            assertEquals(1, adapter.maxActive)
            assertEquals(0, adapter.active)
            assertEquals(1, adapter.submissions.size)
            assertEquals(readsWhileFirstIsSubmitting + 2, adapter.reads)
            assertEquals(100L, secondSnapshots.single().lastSettledMicroUsdc)
            assertTrue(repository.rows.isEmpty())
            first.close().join()
            second.close().join()
            assertEquals(1, adapter.submissions.size)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT restorePending to recover matching identity and network preserving persisted note and block`() =
        runTest {
            val saved = pendingRow()
            val repository = FakeRepository().apply { rows[saved.channelIdBase64] = saved }
            val release = CompletableDeferred<Unit>()
            val adapter = FakeAdapter().apply { action = { release.await() } }
            val session = session(adapter, repository = repository)
            session.onBlock(500).join()
            session.recordChatMessage(ChatMessage("viewer", "new session chat", 1)).join()

            session.restorePending(channel)
            runCurrent()
            val submitted = adapter.submissions.single()
            assertEquals("mesh-session", submitted.sessionId)
            assertEquals(saved.viewerAddress, submitted.viewerAddress)
            assertEquals(saved.creatorAddress, submitted.creatorAddress)
            assertEquals(saved.network, submitted.network)
            assertContentEquals(key, submitted.signerPublicKey)
            assertContentEquals(channel, submitted.channelId)
            assertContentEquals(Base64.decode(saved.signatureBase64), submitted.signature)
            assertEquals(saved.totalAmountClaimedMicroUsdc, submitted.totalAmountClaimedMicroUsdc)
            assertEquals(saved.note, submitted.note)
            assertEquals(saved.blockNumber, submitted.blockNumber)
            assertEquals(saved, repository.rows[saved.channelIdBase64])
            assertEquals(0, repository.writes)

            release.complete(Unit)
            runCurrent()
            assertTrue(repository.rows.isEmpty())
            session.close().join()
            assertEquals(1, adapter.submissions.size)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT restorePending to ignore mismatched network channel identity or key and null network`() =
        runTest {
            val saved = pendingRow()
            val ignoredRows =
                listOf(
                    saved.copy(network = MppNetworks.ALGORAND_MAINNET),
                    saved.copy(network = null),
                    saved.copy(channelIdBase64 = Base64.encode(ByteArray(32) { 8 })),
                    saved.copy(viewerAddress = "other-viewer"),
                    saved.copy(creatorAddress = "other-creator"),
                    saved.copy(viewerPublicKeyBase64 = Base64.encode(byteArrayOf(9))),
                )
            ignoredRows.forEach { row ->
                val repository = FakeRepository().apply { rows[row.channelIdBase64] = row }
                val adapter = FakeAdapter()
                val session = session(adapter, repository = repository)
                session.restorePending(channel)
                runCurrent()
                session.requestSettlement().join()
                runCurrent()
                session.close().join()

                assertEquals(0, adapter.reads, "Ignored row must not reach the chain: $row")
                assertTrue(adapter.submissions.isEmpty())
                assertEquals(mapOf(row.channelIdBase64 to row), repository.rows)
                assertEquals(0, repository.writes)
            }
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT persisted note to be submitted and deleted only after chain confirmation`() =
        runTest {
            val repository = FakeRepository()
            val adapter = FakeAdapter().apply { confirm = false }
            val session = session(adapter, repository = repository)
            session.onBlock(100).join()
            assertTrue(session.acceptVoucher(message()))
            val saved = repository.rows.getValue(Base64.encode(channel))
            assertEquals(100, saved.totalAmountClaimedMicroUsdc)
            assertEquals(100, saved.blockNumber)
            assertTrue(saved.note.contains(Base64.encode(channel)))
            session.onBlock(103).join()
            runCurrent()
            assertEquals(saved.note, adapter.submissions.single().note)
            assertEquals(saved, repository.rows[Base64.encode(channel)])
            adapter.settled = 100
            session.onBlock(104).join()
            advanceTimeBy(501.milliseconds)
            runCurrent()
            assertTrue(repository.rows.isEmpty())
            assertEquals(1, adapter.submissions.size)
        }

    @Test
    fun `EXPECT no persistence for invalid vouchers and no submission after storage failure`() =
        runTest {
            val repository = FakeRepository()
            val adapter = FakeAdapter()
            val session = session(adapter, repository = repository)
            assertFalse(session.acceptVoucher(message(viewer = "other")))
            assertTrue(repository.rows.isEmpty())
            repository.failWrite = true
            assertFalse(session.acceptVoucher(message()))
            session.onBlock(100).join()
            session.onBlock(103).join()
            runCurrent()
            assertTrue(adapter.submissions.isEmpty())
        }

    @Test
    fun `EXPECT newer and other viewer vouchers to survive an older settlement`() =
        runTest {
            val repository = FakeRepository()
            val adapter = FakeAdapter()
            val session = session(adapter, repository = repository)
            val other = session(FakeAdapter(), viewer = "other", repository = repository)
            val otherChannel = ByteArray(32) { 8 }
            assertTrue(other.acceptVoucher(message(viewer = "other", channelId = otherChannel)))
            session.onBlock(100).join()
            assertTrue(session.acceptVoucher(message(100)))
            val submitted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            adapter.action = {
                submitted.complete(Unit)
                release.await()
            }
            session.onBlock(103).join()
            submitted.await()
            assertTrue(session.acceptVoucher(message(200)))
            val newer = repository.rows.getValue(Base64.encode(channel))
            release.complete(Unit)
            runCurrent()
            assertEquals(newer, repository.rows[Base64.encode(channel)])
            assertTrue(repository.rows.containsKey(Base64.encode(otherChannel)))
        }

    @Test
    fun `EXPECT notes to contain only this viewers observed blocks and chats`() =
        runTest {
            val repository = FakeRepository()
            val session = session(FakeAdapter(), repository = repository)
            val other = session(FakeAdapter(), viewer = "other", repository = repository)
            session.onBlock(100, isPaid = false).join()
            session.onBlock(102, isPaid = false).join()
            session.onBlock(105, isPaid = true).join()
            session.recordChatMessage(ChatMessage("viewer", "hello", 1)).join()
            session.recordChatMessage(ChatMessage("viewer", "gift", 2, "0.0001", "USDC")).join()
            assertTrue(session.acceptVoucher(message()))
            assertTrue(other.acceptVoucher(message(viewer = "other", channelId = ByteArray(32) { 8 })))
            val note = Json.parseToJsonElement(repository.rows.getValue(Base64.encode(channel)).note).jsonObject
            assertEquals(listOf(100L, 105L), note.getValue("range").jsonArray.map { it.jsonPrimitive.long })
            val items = note.getValue("items").jsonArray
            assertEquals(
                listOf(2L, 3L, 1L, 1L),
                items.map {
                    it.jsonObject
                        .getValue("quantity")
                        .jsonPrimitive.long
                },
            )
            assertEquals(
                100L,
                items
                    .last()
                    .jsonObject
                    .getValue("total")
                    .jsonPrimitive.long,
            )
            val otherNote =
                Json
                    .parseToJsonElement(
                        repository.rows.getValue(Base64.encode(ByteArray(32) { 8 })).note,
                    ).jsonObject
            assertTrue(
                otherNote.getValue("items").jsonArray.all {
                    it.jsonObject
                        .getValue("quantity")
                        .jsonPrimitive.long == 0L
                },
            )
        }

    @Test
    fun `EXPECT retargeting and decreasing amounts to be rejected WHEN a channel is already pinned`() =
        runTest {
            val adapter = FakeAdapter()
            val session = session(adapter)
            val malformed =
                listOf(
                    message(session = null),
                    message(session = "request-id"),
                    message(viewer = null),
                    message(viewer = "other"),
                    message(publicKey = null),
                    message(publicKey = byteArrayOf(9)),
                    message(channelId = null),
                    message(channelId = ByteArray(31)),
                    message(signature = null),
                    message(signature = ""),
                    message(signature = "!!!"),
                    message(amount = null),
                    message(amount = -1),
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
    fun `EXPECT normal vouchers with primary UI notes to respect every block and batch intervals`() =
        runTest {
            for (frequency in listOf(1, PAYOUT_BATCH_BLOCK_COUNT).distinct()) {
                val repository = FakeRepository()
                val adapter = FakeAdapter()
                val session = session(adapter, frequency = frequency, repository = repository)
                val params =
                    GetMppVoucherNoteUseCase.Params(
                        channelId = Base64.encode(channel),
                        startBlock = 100L,
                        currentBlock = 100L,
                        freeBlocks = 0L,
                        paidBlocks = 0L,
                        costPerPaidBlock = 100L,
                        settledAmount = 0L,
                        totalCumulativeAmount = 100L,
                        freeChatCount = 0L,
                        tipChatCount = 0L,
                        tipChatTotal = 0L,
                    )
                session.onBlock(100).join()
                assertTrue(session.acceptVoucher(message(), noteParams = params))
                val saved = repository.rows.getValue(Base64.encode(channel))
                runCurrent()
                assertTrue(adapter.submissions.isEmpty())

                for (offset in 1 until frequency) {
                    session.onBlock(100L + offset).join()
                    assertTrue(session.acceptVoucher(message(), noteParams = params))
                    runCurrent()
                    assertTrue(adapter.submissions.isEmpty())
                }
                assertEquals(1, repository.writes)
                session.onBlock(100L + frequency).join()
                runCurrent()
                assertEquals(100L, adapter.settled)
                assertEquals(saved.note, adapter.submissions.single().note)
                assertTrue(repository.rows.isEmpty())

                assertTrue(session.acceptVoucher(message(200)))
                runCurrent()
                assertEquals(1, adapter.submissions.size)
                session.close().join()
                assertEquals(listOf(100L, 200L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            }
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT only increasing chain rounds to reach the boundary with the latest signed total`() =
        runTest {
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
    fun `EXPECT retry to read the chain without resubmitting WHEN an uncertain payment is confirmed`() =
        runTest {
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
    fun `EXPECT the pending voucher to be retained WHEN a read fails or a submission is unconfirmed`() =
        runTest {
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
            advanceTimeBy(1_001.milliseconds)
            runCurrent()
            assertTrue(errors.any { it.message == "Voucher settlement confirmation timed out; payment may still be pending" })
            adapter.confirm = true
            session.onBlock(4)
            runCurrent()
            assertEquals(2, adapter.submissions.size)
            session.close().join()
        }

    @Test
    fun `EXPECT confirmation polling without errors or resubmission WHEN broadcast precedes inclusion`() =
        runTest {
            val adapter = FakeAdapter().apply { confirm = false }
            val repository = FakeRepository()
            val statuses = mutableListOf<Throwable?>()
            val session = session(adapter, frequency = 1, repository = repository, settlementError = { statuses += it })
            session.onBlock(1)
            session.acceptVoucher(message())
            session.onBlock(2)
            runCurrent()
            assertEquals(1, adapter.submissions.size)
            assertTrue(repository.rows.isNotEmpty())
            assertTrue(statuses.isEmpty())
            adapter.settled = 100
            advanceTimeBy(501.milliseconds)
            runCurrent()
            assertEquals(1, adapter.submissions.size)
            assertTrue(repository.rows.isEmpty())
            assertEquals(listOf<Throwable?>(null), statuses)
            assertTrue(errors.isEmpty())
            session.close().join()
        }

    @Test
    fun `EXPECT recovered status only after confirmation WHEN a pending settlement times out`() =
        runTest {
            val adapter = FakeAdapter().apply { confirm = false }
            val repository = FakeRepository()
            val statuses = mutableListOf<Throwable?>()
            val session = session(adapter, frequency = 1, repository = repository, settlementError = { statuses += it })
            session.onBlock(1)
            session.acceptVoucher(message())
            session.onBlock(2)
            runCurrent()
            advanceTimeBy(1_001.milliseconds)
            runCurrent()
            assertEquals(1, statuses.size)
            assertTrue(statuses.single()?.message?.contains("confirmation timed out") == true)
            assertTrue(repository.rows.isNotEmpty())
            adapter.settled = 100
            session.onBlock(3)
            runCurrent()
            assertEquals(2, statuses.size)
            assertEquals(null, statuses.last())
            assertTrue(repository.rows.isEmpty())
            assertEquals(1, adapter.submissions.size)
            session.close().join()
        }

    @Test
    fun `EXPECT signing and submission to be skipped WHEN the voucher is already settled`() =
        runTest {
            val adapter = FakeAdapter().apply { settled = 150 }
            val session = session(adapter)
            session.acceptVoucher(message())
            session.close().join()
            assertTrue(adapter.submissions.isEmpty())
            assertEquals(2, adapter.reads)
        }

    @Test
    fun `EXPECT authority to stay unchanged WHEN caller or adapter arrays are mutated`() =
        runTest {
            val adapter = FakeAdapter()
            val mutableKey = key.copyOf()
            val mutableChannel = channel.copyOf()
            val session = session(adapter, suppliedKey = mutableKey)
            mutableKey.fill(9)
            session.signerPublicKey.fill(9)
            assertTrue(session.acceptVoucher(message(channelId = mutableChannel)))
            mutableChannel.fill(9)
            adapter.action = {
                adapter.submissions
                    .last()
                    .channelId
                    .fill(9)
                adapter.submissions
                    .last()
                    .signerPublicKey
                    .fill(9)
                adapter.submissions
                    .last()
                    .signature
                    .fill(9)
            }
            session.close().join()
            val submitted = adapter.submissions.single()
            assertContentEquals(channel, submitted.channelId)
            assertContentEquals(key, submitted.signerPublicKey)
            assertContentEquals(byteArrayOf(5), submitted.signature)
        }

    @Test
    fun `EXPECT the final drain to finish the in-flight voucher then settle only the latest WHEN closing`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val adapter = FakeAdapter().apply { action = { gate.await() } }
            val session = session(adapter, frequency = 1)
            session.onBlock(100)
            session.acceptVoucher(message(100))
            session.onBlock(101)
            runCurrent()
            assertEquals(1, adapter.active)
            assertTrue(session.acceptVoucher(message(200)))
            assertTrue(session.acceptVoucher(message(300)))
            val closing = session.close()
            assertFalse(session.acceptVoucher(message(400)))
            gate.complete(Unit)
            closing.join()
            assertEquals(listOf(100L, 300L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            assertEquals(1, adapter.maxActive)
            session.close().join()
            assertEquals(2, adapter.submissions.size)
        }

    @Test
    fun `EXPECT final partial cumulative depletion to settle with unchanged coverage at every block and batch frequencies`() =
        runTest {
            for (frequency in listOf(1, 3)) {
                val fullBlockAmount = frequency * 100L
                val repository = FakeRepository()
                val adapter = FakeAdapter().apply { deposit = fullBlockAmount + 25L }
                val coverageChecks = mutableListOf<Pair<Long, Long>>()
                val session =
                    session(
                        adapter,
                        frequency = frequency,
                        repository = repository,
                        getVoucherCoveredBlockCount = { amount, confirmed ->
                            coverageChecks += amount to confirmed
                            amount / 100L
                        },
                    )
                session.onBlock(100, costMicroUsdc = 100).join()
                assertTrue(session.acceptVoucher(message(fullBlockAmount)))
                runCurrent()
                assertEquals(listOf(fullBlockAmount), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
                assertEquals(25L, adapter.deposit - adapter.settled)
                assertTrue(repository.rows.isEmpty())

                // The last 25 micro-USDC do not cover another full block. No round advance,
                // explicit flush or close may be needed to settle the full cumulative total.
                coverageChecks.clear()
                assertTrue(session.acceptVoucher(message(adapter.deposit)))
                val saved = repository.rows.values.single()
                val readsAfterAcceptance = adapter.reads
                runCurrent()
                assertEquals(listOf(adapter.deposit to frequency.toLong()), coverageChecks)
                assertTrue(adapter.reads > readsAfterAcceptance, "Depletion must reach the settlement channel read")
                assertEquals(listOf(fullBlockAmount, adapter.deposit), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
                assertEquals(saved.note, adapter.submissions.last().note)
                assertEquals(0L, adapter.deposit - adapter.settled)
                assertTrue(repository.rows.isEmpty())

                val writes = repository.writes
                assertTrue(session.acceptVoucher(message(adapter.deposit)))
                session.onBlock(101, costMicroUsdc = 100).join()
                runCurrent()
                session.close().join()
                assertEquals(writes, repository.writes)
                assertTrue(repository.rows.isEmpty())
                assertEquals(2, adapter.submissions.size)
            }
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT unchanged coverage to keep a nondepleting cumulative voucher batched`() =
        runTest {
            for (frequency in listOf(1, 3)) {
                val fullBlockAmount = frequency * 100L
                val repository = FakeRepository()
                val adapter = FakeAdapter()
                val session =
                    session(
                        adapter,
                        frequency = frequency,
                        repository = repository,
                        getVoucherCoveredBlockCount = { amount, _ -> amount / 100L },
                    )
                session.onBlock(100, costMicroUsdc = 100).join()
                assertTrue(session.acceptVoucher(message(fullBlockAmount)))
                runCurrent()
                assertEquals(fullBlockAmount, adapter.settled)

                assertTrue(session.acceptVoucher(message(fullBlockAmount + 25L)))
                val saved = repository.rows.values.single()
                runCurrent()
                assertEquals(listOf(fullBlockAmount), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
                assertEquals(saved, repository.rows.values.single())
                assertTrue(adapter.deposit - saved.totalAmountClaimedMicroUsdc >= 100L)
                session.close().join()
            }
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT final partial depletion to coalesce to the latest voucher while a full block settlement confirms`() =
        runTest {
            val repository = FakeRepository()
            val adapter =
                FakeAdapter().apply {
                    deposit = 325
                    confirm = false
                }
            val coverageChecks = mutableListOf<Pair<Long, Long>>()
            val session =
                session(
                    adapter,
                    frequency = 3,
                    repository = repository,
                    getVoucherCoveredBlockCount = { amount, confirmed ->
                        coverageChecks += amount to confirmed
                        amount / 100L
                    },
                )
            session.onBlock(100, costMicroUsdc = 100).join()
            assertTrue(session.acceptVoucher(message(300)))
            runCurrent()
            assertEquals(listOf(300L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })

            assertTrue(session.acceptVoucher(message(310)))
            assertTrue(session.acceptVoucher(message(320)))
            val finalSignature = byteArrayOf(8, 9)
            val finalMessage = message(325, signature = Base64.encode(finalSignature))
            assertTrue(session.acceptVoucher(finalMessage))
            assertTrue(session.acceptVoucher(finalMessage))
            val saved = repository.rows.values.single()
            runCurrent()
            assertEquals(325L, saved.totalAmountClaimedMicroUsdc)
            assertEquals(4, repository.writes)
            assertEquals(1, adapter.submissions.size)

            adapter.settled = 300
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertEquals(listOf(300L, 325L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            assertEquals(listOf(300L to 0L, 325L to 3L), coverageChecks)
            assertContentEquals(finalSignature, adapter.submissions.last().signature)
            assertEquals(saved.note, adapter.submissions.last().note)
            // Confirming the old voucher must not delete the newer durable authorization.
            assertEquals(saved, repository.rows.values.single())
            assertEquals(300L, adapter.settled)

            assertTrue(session.acceptVoucher(finalMessage))
            adapter.settled = 325
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertEquals(0L, adapter.deposit - adapter.settled)
            assertTrue(repository.rows.isEmpty())
            assertTrue(session.acceptVoucher(finalMessage))
            session.close().join()
            assertEquals(4, repository.writes)
            assertEquals(listOf(300L, 325L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            assertEquals(1, adapter.maxActive)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `EXPECT intermediate vouchers to be replaced while a settlement awaits confirmation`() =
        runTest {
            val repository = FakeRepository()
            val adapter = FakeAdapter().apply { confirm = false }
            val session = session(
                adapter,
                frequency = 1,
                repository = repository,
                getVoucherCoveredBlockCount = { amount, _ -> amount / 100L },
            )
            session.acceptVoucher(message(100))
            runCurrent()
            assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            session.acceptVoucher(message(200))
            session.acceptVoucher(message(300))
            session.acceptVoucher(message(400))
            runCurrent()
            assertEquals(1, adapter.submissions.size)
            assertEquals(400L, repository.rows.values.single().totalAmountClaimedMicroUsdc)
            adapter.settled = 100
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertEquals(listOf(100L, 400L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            // Confirmation of 100 must not delete the newer persisted authorization.
            assertEquals(400L, repository.rows.values.single().totalAmountClaimedMicroUsdc)
            adapter.settled = 400
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertTrue(repository.rows.isEmpty())
            session.close().join()
            assertEquals(2, adapter.submissions.size)
        }

    @Test
    fun `EXPECT only the latest cumulative voucher to retry after an uncertain settlement`() =
        runTest {
            val repository = FakeRepository()
            val adapter = FakeAdapter().apply { confirm = false }
            val session = session(adapter, frequency = 1, repository = repository)
            session.acceptVoucher(message(100))
            session.requestSettlement()
            runCurrent()
            advanceTimeBy(1001.milliseconds)
            runCurrent()
            session.acceptVoucher(message(200))
            session.acceptVoucher(message(300))
            adapter.confirm = true
            session.requestSettlement()
            runCurrent()
            assertEquals(listOf(100L, 300L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            assertEquals(300L, adapter.settled)
            assertTrue(repository.rows.isEmpty())
            session.close().join()
        }

    @Test
    fun `EXPECT other peers to remain unblocked and removal to be bounded WHEN a peer hangs`() =
        runTest {
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
            advanceTimeBy(1_501.milliseconds)
            runCurrent()
            assertTrue(close.isCompleted)
            assertEquals(0, hung.active)
            assertEquals(1, hung.maxActive)
            second.close().join()
            assertEquals("viewer-two", healthy.submissions.single().viewerAddress)
        }

    @Test
    fun `EXPECT settlement to proceed WHEN the snapshot observer throws`() =
        runTest {
            val adapter = FakeAdapter()
            val session = session(adapter, snapshot = { error("UI observer failure") })
            session.acceptVoucher(message())
            session.close().join()
            assertEquals(100L, adapter.settled)
            assertTrue(errors.any { it.message == "UI observer failure" })
        }

    @Test
    fun `EXPECT the channel to stay unpinned and the amount to stay valid WHEN identity read fails`() =
        runTest {
            val badHint = ByteArray(32) { 99 }
            val adapter =
                FakeAdapter().apply {
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
    fun `EXPECT the session to remain available for a valid voucher WHEN identity read times out`() =
        runTest {
            val adapter = FakeAdapter().apply { readAction = { awaitCancellation() } }
            val session = session(adapter)
            val pending = async { session.acceptVoucher(message()) }
            runCurrent()
            advanceTimeBy(1_001.milliseconds)
            runCurrent()
            assertFalse(pending.await())
            adapter.readAction = {}
            assertTrue(session.acceptVoucher(message(channelId = ByteArray(32) { 8 })))
            session.close().join()
            assertEquals(1, adapter.submissions.size)
            assertTrue(errors.any { it.message == "Voucher authorization validation timed out" })
        }

    @Test
    fun `EXPECT close to skip an unaccepted identity read and reject late acceptance`() =
        runTest {
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
    fun `EXPECT the pinned channel and monotonic amount to be rechecked WHEN first reads run concurrently`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val adapter =
                FakeAdapter().apply {
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
    fun `EXPECT the accepted channel to be retained WHEN a suspended identity read resolves late`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val otherChannel = ByteArray(32) { 8 }
            val adapter =
                FakeAdapter().apply {
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
    fun `EXPECT the round baseline to be kept and other peers to stay isolated WHEN frequency updates`() =
        runTest {
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
    fun `EXPECT the valid pending voucher to remain WHEN a higher signature is invalid`() =
        runTest {
            val adapter =
                FakeAdapter().apply {
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
            session.close().join()
            assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            assertContentEquals(byteArrayOf(5), adapter.submissions.single().signature)
            assertEquals(1, errors.count { it.message == "Invalid voucher signature" })
        }

    @Test
    fun `EXPECT the channel and amount to stay unpinned WHEN the first signature is invalid`() =
        runTest {
            val adapter =
                FakeAdapter().apply {
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
    fun `EXPECT the valid pending voucher to be retained WHEN authorization times out`() =
        runTest {
            val adapter = FakeAdapter()
            val session = session(adapter)
            assertTrue(session.acceptVoucher(message(100)))
            adapter.authorizationAction = { awaitCancellation() }
            val pending = async { session.acceptVoucher(message(900)) }
            runCurrent()
            advanceTimeBy(1_001.milliseconds)
            runCurrent()
            assertFalse(pending.await())
            session.close().join()
            assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
            assertTrue(errors.any { it.message == "Voucher authorization validation timed out" })
        }

    @Test
    fun `EXPECT the monotonic amount to be rechecked before replacement WHEN authorizations run concurrently`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val adapter =
                FakeAdapter().apply {
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
    fun `EXPECT close to drain the validated voucher without waiting for pending authorization`() =
        runTest {
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
    fun `EXPECT cancellation to propagate without replacing the pending voucher WHEN authorization is cancelled`() =
        runTest {
            val adapter = FakeAdapter()
            val session = session(adapter)
            assertTrue(session.acceptVoucher(message(100)))
            adapter.authorizationAction = { throw CancellationException("cancelled validation") }
            assertFailsWith<CancellationException> { session.acceptVoucher(message(200)) }
            session.close().join()
            assertEquals(listOf(100L), adapter.submissions.map { it.totalAmountClaimedMicroUsdc })
        }

    @Test
    fun `EXPECT the valid pending voucher to remain WHEN a new voucher exceeds the deposit`() =
        runTest {
            val adapter = FakeAdapter()
            val session = session(adapter)
            assertTrue(session.acceptVoucher(message(100)))
            assertFalse(session.acceptVoucher(message(1_001)))
            session.close().join()
            assertEquals(100L, adapter.submissions.single().totalAmountClaimedMicroUsdc)
            assertTrue(errors.any { it.message == "Voucher exceeds deposit" })
        }
}
