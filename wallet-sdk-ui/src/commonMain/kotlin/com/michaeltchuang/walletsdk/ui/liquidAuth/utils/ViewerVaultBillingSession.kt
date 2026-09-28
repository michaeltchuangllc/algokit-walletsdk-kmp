package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetMppVoucherNoteUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.ViewerVaultSettlement
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthPaymentVoucherMessage
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalEncodingApi::class)
internal class ViewerVaultBillingSession(
    private val scope: CoroutineScope,
    val sessionId: String,
    val viewerAddress: String,
    val creatorAddress: String,
    val network: String,
    signerPublicKey: ByteArray,
    private val buildCreatorWalletSigner: suspend (String) -> MppWalletSigner?,
    private val onSnapshot: (HostViewerVaultReader.Snapshot) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val voucherRepository: MppVoucherRepository,
    private val getMppVoucherNoteUseCase: GetMppVoucherNoteUseCase,
    payoutFrequencyBlocks: Int = 1,
    private val adapter: Adapter = CoreAdapter,
    private val operationTimeoutMillis: Long = 25_000,
    private val finalDrainTimeoutMillis: Long = 30_000,
    private val onSettlementError: (Throwable?) -> Unit = { error -> if (error != null) onError(error) },
) {
    interface Adapter {
        suspend fun validateAuthorization(voucher: Voucher): Result<HostViewerVaultReader.Snapshot>

        suspend fun readChannel(voucher: Voucher): Result<HostViewerVaultReader.Snapshot> =
            HostViewerVaultReader.readChannel(
                channelId = voucher.channelId,
                viewerAddress = voucher.viewerAddress,
                creatorAddress = voucher.creatorAddress,
                authorizedSignerPublicKey = voucher.signerPublicKey,
                network = voucher.network,
            )

        suspend fun settle(
            voucher: Voucher,
            creatorWalletSigner: MppWalletSigner,
        ): Result<Unit>
    }

    private object CoreAdapter : Adapter {
        override suspend fun validateAuthorization(voucher: Voucher): Result<HostViewerVaultReader.Snapshot> =
            ViewerVaultSettlement.validateVoucher(
                viewerAddress = voucher.viewerAddress,
                creatorAddress = voucher.creatorAddress,
                authorizedSignerPublicKey = voucher.signerPublicKey,
                channelId = voucher.channelId,
                signature = voucher.signature,
                cumulativeAmount = voucher.totalAmountClaimedMicroUsdc,
                network = voucher.network,
            )

        override suspend fun settle(
            voucher: Voucher,
            creatorWalletSigner: MppWalletSigner,
        ): Result<Unit> =
            ViewerVaultSettlement(creatorWalletSigner)
                .settle(
                    viewerAddress = voucher.viewerAddress,
                    creatorAddress = voucher.creatorAddress,
                    authorizedSignerPublicKey = voucher.signerPublicKey,
                    channelId = voucher.channelId,
                    signature = voucher.signature,
                    cumulativeAmount = voucher.totalAmountClaimedMicroUsdc,
                    network = voucher.network,
                    note = voucher.note,
                ).map { Unit }
    }

    class Voucher internal constructor(
        val sessionId: String,
        val viewerAddress: String,
        val creatorAddress: String,
        val network: String,
        signerPublicKey: ByteArray,
        channelId: ByteArray,
        signature: ByteArray,
        val totalAmountClaimedMicroUsdc: Long,
        val blockNumber: Long = 0L,
        val note: String = "N/A",
    ) {
        private val key = signerPublicKey.copyOf()
        private val channel = channelId.copyOf()
        private val signed = signature.copyOf()
        val signerPublicKey: ByteArray get() = key.copyOf()
        val channelId: ByteArray get() = channel.copyOf()
        val signature: ByteArray get() = signed.copyOf()
    }

    private val key = signerPublicKey.copyOf()
    val signerPublicKey: ByteArray get() = key.copyOf()
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var latest: Voucher? = null
    private var confirmedAmount = 0L
    private var latestRound: Long? = null
    private var paidRound: Long? = null
    private var closed = false
    private var payoutFrequencyBlocks = payoutFrequencyBlocks
    private var startBlock: Long? = null
    private var paidBlocks = 0L
    private var freeBlocks = 0L
    private var costPerPaidBlock = 0L
    private var freeChatCount = 0L
    private var tipChatCount = 0L
    private var tipChatTotal = 0L
    private var forceRequested = false

    // Live/reconnecting sessions must re-read the chain serially for the same channel.
    companion object {
        private val registryMutex = Mutex()
        private val settlementLocks = mutableMapOf<String, ChannelLock>()
        private val recoveryMutex = Mutex()

        /** Replay does not upsert: a live session may already hold a newer durable voucher. */
        suspend fun recoverPending(
            scope: CoroutineScope,
            repository: MppVoucherRepository,
            noteUseCase: GetMppVoucherNoteUseCase,
            buildSigner: suspend (String) -> MppWalletSigner?,
            adapter: Adapter = CoreAdapter,
            onError: (Throwable) -> Unit,
        ) {
            recoveryMutex.withLock {
                repository.getAllVouchers().forEach { row ->
                    val network = row.network ?: return@forEach
                    var session: ViewerVaultBillingSession? = null
                    try {
                        session = ViewerVaultBillingSession(
                            scope = scope,
                            sessionId = row.sessionId,
                            viewerAddress = row.viewerAddress,
                            creatorAddress = row.creatorAddress,
                            network = network,
                            signerPublicKey = Base64.decode(row.viewerPublicKeyBase64),
                            buildCreatorWalletSigner = buildSigner,
                            onSnapshot = {},
                            onError = onError,
                            voucherRepository = repository,
                            getMppVoucherNoteUseCase = noteUseCase,
                            adapter = adapter,
                        )
                        session.restorePending(Base64.decode(row.channelIdBase64))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(e)
                    } finally {
                        session?.close()?.join()
                    }
                }
            }
        }

        private class ChannelLock(val mutex: Mutex = Mutex(), var users: Int = 0)

        private suspend fun <T> withChannelLock(id: String, action: suspend () -> T): T {
            val entry = registryMutex.withLock {
                settlementLocks.getOrPut(id) { ChannelLock() }.also { it.users++ }
            }
            try {
                return entry.mutex.withLock { action() }
            } finally {
                // Release even when a timed-out operation is cancelled.
                withContext(NonCancellable) {
                    registryMutex.withLock {
                        if (--entry.users == 0) settlementLocks.remove(id)
                    }
                }
            }
        }
    }

    init {
        require(sessionId.isNotBlank() && viewerAddress.isNotBlank() && creatorAddress.isNotBlank())
        require(network.isNotBlank() && key.isNotEmpty())
        require(payoutFrequencyBlocks > 0)
        require(operationTimeoutMillis > 0 && finalDrainTimeoutMillis > 0)
    }

    private val job =
        scope.launch {
            try {
                for (ignored in wake) {
                    val (final, force) = mutex.withLock {
                        (closed to (closed || forceRequested)).also { forceRequested = false }
                    }
                    attemptSettlement(force)
                    if (final) break
                }
            } finally {
                wake.close()
            }
        }

    suspend fun acceptVoucher(
        message: LiquidAuthPaymentVoucherMessage,
        force: Boolean = false,
        noteParams: GetMppVoucherNoteUseCase.Params? = null,
    ): Boolean {
        val voucher =
            try {
                require(message.sessionId == sessionId) { "Voucher session mismatch" }
                require(message.viewerAddress == viewerAddress) { "Voucher viewer mismatch" }
                val suppliedKey = requireNotNull(message.viewerPublicKey).copyOf()
                require(suppliedKey.contentEquals(key)) { "Voucher signer mismatch" }
                message.viewerPublicKeyBase64?.let {
                    require(Base64.decode(it).contentEquals(suppliedKey)) { "Inconsistent signer encoding" }
                }
                val channel = requireNotNull(message.channelId).copyOf()
                require(channel.size == 32) { "Voucher channel must be 32 bytes" }
                message.channelIdBase64?.let {
                    require(Base64.decode(it).contentEquals(channel)) { "Inconsistent channel encoding" }
                }
                val signature = Base64.decode(requireNotNull(message.signatureBase64))
                require(signature.isNotEmpty()) { "Voucher signature is empty" }
                val amount = requireNotNull(message.totalAmountClaimedMicroUsdc)
                require(amount >= 0) { "Voucher amount is negative" }
                Voucher(sessionId, viewerAddress, creatorAddress, network, key, channel, signature, amount)
            } catch (e: IllegalArgumentException) {
                report(e)
                return false
            }
        mutex.withLock {
            if (!canAccept(voucher)) return false
            if (isDuplicate(voucher)) {
                if (force) {
                    forceRequested = true
                    wake.trySend(Unit)
                }
                return true
            }
        }
        val snapshot = try {
            val validated =
                withTimeoutOrNull(operationTimeoutMillis.milliseconds) {
                    adapter.validateAuthorization(voucher).getOrThrow()
                } ?: error("Voucher authorization validation timed out")
            require(voucher.totalAmountClaimedMicroUsdc <= validated.totalDepositMicroUsdc) {
                "Voucher exceeds deposit"
            }
            validated
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
            return false
        }
        val accepted = try {
            withTimeout(operationTimeoutMillis.milliseconds) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!canAccept(voucher)) return@withLock false
                    if (isDuplicate(voucher)) {
                        if (force) forceRequested = true
                        wake.trySend(Unit)
                        return@withLock true
                    }
                    val block = latestRound ?: 0L
                    val note = getMppVoucherNoteUseCase(
                        noteParams ?: GetMppVoucherNoteUseCase.Params(
                            channelId = Base64.encode(voucher.channelId),
                            startBlock = startBlock ?: block,
                            currentBlock = block,
                            freeBlocks = freeBlocks,
                            paidBlocks = paidBlocks,
                            costPerPaidBlock = costPerPaidBlock,
                            settledAmount = snapshot.lastSettledMicroUsdc,
                            totalCumulativeAmount = voucher.totalAmountClaimedMicroUsdc,
                            freeChatCount = freeChatCount,
                            tipChatCount = tipChatCount,
                            tipChatTotal = tipChatTotal,
                        ),
                    )
                    val saved = Voucher(
                        sessionId, viewerAddress, creatorAddress, network, key,
                        voucher.channelId, voucher.signature, voucher.totalAmountClaimedMicroUsdc,
                        block, note,
                    )
                    // Persist before publishing/waking settlement. Failed writes must never submit.
                    voucherRepository.upsertVoucher(
                        MppVoucherEntity(
                            channelIdBase64 = Base64.encode(saved.channelId),
                            sessionId = sessionId,
                            viewerAddress = viewerAddress,
                            viewerPublicKeyBase64 = Base64.encode(saved.signerPublicKey),
                            signatureBase64 = Base64.encode(saved.signature),
                            totalAmountClaimedMicroUsdc = saved.totalAmountClaimedMicroUsdc,
                            creatorAddress = creatorAddress,
                            blockNumber = saved.blockNumber,
                            note = saved.note,
                            network = network,
                        ),
                    )
                    latest = saved
                    if (force) forceRequested = true
                    wake.trySend(Unit)
                    true
                }
            }
        } catch (e: TimeoutCancellationException) {
            report(e)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
            return false
        }
        if (!accepted) report(IllegalArgumentException("Session closed, channel changed or amount decreased"))
        return accepted
    }

    private fun canAccept(voucher: Voucher): Boolean {
        if (closed || !job.isActive) return false
        val previous = latest ?: return true
        return previous.channelId.contentEquals(voucher.channelId) &&
            voucher.totalAmountClaimedMicroUsdc >= previous.totalAmountClaimedMicroUsdc
    }

    private fun isDuplicate(voucher: Voucher): Boolean =
        latest?.let {
            it.channelId.contentEquals(voucher.channelId) &&
                it.totalAmountClaimedMicroUsdc == voucher.totalAmountClaimedMicroUsdc
        } == true

    /**
     * Recover only this known identity/channel on its persisted network. Old rows with no
     * network remain untouched; guessing from the currently selected network is unsafe.
     * Called before live acceptance so replay and live writes use the same worker.
     * Returns the retained authorization even if recovery settles/deletes its durable row.
     */
    suspend fun restorePending(channelId: ByteArray): Long {
        val channel = channelId.copyOf()
        val row = voucherRepository.getAllVouchers().firstOrNull {
            it.channelIdBase64 == Base64.encode(channel) && it.network == network &&
                it.viewerAddress == viewerAddress && it.creatorAddress == creatorAddress &&
                it.viewerPublicKeyBase64 == Base64.encode(key)
        } ?: return 0L
        try {
            return withTimeout(operationTimeoutMillis.milliseconds) {
                val voucher = Voucher(
                    sessionId, viewerAddress, creatorAddress, network, key, channel,
                    Base64.decode(row.signatureBase64), row.totalAmountClaimedMicroUsdc,
                    row.blockNumber, row.note,
                )
                val snapshot = adapter.readChannel(voucher).getOrThrow()
                val startRound = snapshot.startRound
                if (startRound != null && row.blockNumber > 0 && row.blockNumber < startRound) {
                    // Its old cumulative voucher must never be replayed against the new deposit.
                    voucherRepository.deleteVouchersBeforeRound(row.channelIdBase64, network, startRound)
                    return@withTimeout 0L
                }
                if (snapshot.lastSettledMicroUsdc >= voucher.totalAmountClaimedMicroUsdc) {
                    voucherRepository.deleteSettledVoucher(row.channelIdBase64, snapshot.lastSettledMicroUsdc)
                    return@withTimeout voucher.totalAmountClaimedMicroUsdc
                }
                adapter.validateAuthorization(voucher).getOrThrow()
                mutex.withLock {
                    if (!canAccept(voucher)) return@withLock
                    latest = voucher
                    forceRequested = true
                    wake.trySend(Unit)
                }
                voucher.totalAmountClaimedMicroUsdc
            }
        } catch (e: TimeoutCancellationException) {
            report(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
        // A failed read/validation is not evidence that a stored authorization can be reset.
        return row.totalAmountClaimedMicroUsdc
    }

    /** Flush without closing: depletion/minimize may pause and later resume the same viewer. */
    fun requestSettlement(): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        mutex.withLock {
            if (closed) return@withLock
            forceRequested = true
            wake.trySend(Unit)
        }
    }

    fun updatePayoutFrequencyBlocks(blocks: Int): Job {
        require(blocks > 0) { "Payout frequency must be positive" }
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                if (closed) return@withLock
                payoutFrequencyBlocks = blocks
                wake.trySend(Unit)
            }
        }
    }

    fun onBlock(block: Long, isPaid: Boolean = true, costMicroUsdc: Long = 0L): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                if (closed || block < 0 || latestRound?.let { block <= it } == true) return@withLock
                val advanced = latestRound?.let { block - it } ?: 0L
                if (isPaid) paidBlocks += advanced else freeBlocks += advanced
                costPerPaidBlock = costMicroUsdc
                if (startBlock == null) startBlock = block
                latestRound = block
                if (paidRound == null) paidRound = block
                wake.trySend(Unit)
            }
        }

    fun recordChatMessage(message: ChatMessage): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                if (closed) return@withLock
                val amount = message.amount?.toDoubleOrNull()
                if (amount != null && amount.isFinite() && amount > 0) {
                    tipChatCount++
                    tipChatTotal += (amount * 1_000_000).roundToLong()
                } else {
                    freeChatCount++
                }
            }
        }

    /**
     * Shuts down the settlement worker after draining any pending voucher. Shared by every
     * host platform (Android/iOS) and by both the primary-viewer and mesh-viewer paths, so a
     * single [refundVault] flag decides whether this is a resumable pause (`false`, the
     * default — for example a screen backgrounding briefly) or the viewer actually leaving for
     * good (`true`), in which case the vault's leftover deposit is refunded back to them.
     */
    fun close(refundVault: Boolean = false): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                closed = true
                wake.trySend(Unit)
            }
            if (withTimeoutOrNull(finalDrainTimeoutMillis.milliseconds) {
                    job.join()
                    true
                } != true
            ) {
                job.cancel()
                report(IllegalStateException("Viewer voucher final drain timed out; payment may be pending"))
            }
            if (refundVault) refundRemainingVaultBalance()
        }

    /**
     * Viewer disconnect (not a pause): the final voucher has just been settled above, so this
     * only needs to submit the on-chain `close` call. Per the escrow contract only the payee
     * (the creator/host) may call it, which refunds whatever remains of the payer's deposit
     * back to the departing viewer.
     */
    private suspend fun refundRemainingVaultBalance() {
        val channelId = mutex.withLock { latest?.channelId } ?: return
        try {
            val signer = buildCreatorWalletSigner(creatorAddress)
            if (signer == null) {
                report(IllegalStateException("Skipping session vault close: signer unavailable for $creatorAddress"))
                return
            }
            MppPayments
                .closeSessionVault(signer = signer, channelId = channelId)
                .onSuccess { txId ->
                    Napier.d("Session vault closed (refund) for $sessionId. txId=$txId")
                }.onFailure { throwable ->
                    Napier.e("Failed to close session vault (refund) for $sessionId", throwable)
                    report(throwable)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Unexpected error while closing session vault (refund) for $sessionId", e)
            report(e)
        }
    }

    private suspend fun attemptSettlement(force: Boolean) {
        val candidate =
            mutex.withLock {
                val voucher = latest ?: return
                if (voucher.totalAmountClaimedMicroUsdc <= confirmedAmount) return
                val round = latestRound
                val boundary = paidRound
                if (!force && (round == null || boundary == null || round - boundary < payoutFrequencyBlocks)) return
                voucher to round
            }
        var awaitingConfirmation = false
        try {
            withTimeout(operationTimeoutMillis.milliseconds) {
                val voucher = candidate.first
                withChannelLock("$network/$viewerAddress/$creatorAddress/${Base64.encode(voucher.channelId)}") {
                    val before = adapter.readChannel(voucher).getOrThrow()
                    publish(before)
                    if (before.lastSettledMicroUsdc < voucher.totalAmountClaimedMicroUsdc) {
                        val signer = buildCreatorWalletSigner(creatorAddress) ?: error("Creator signer unavailable")
                        require(signer.address == creatorAddress) { "Creator wallet signer mismatch" }
                        adapter.settle(voucher, signer).getOrThrow()
                        // Submission only broadcasts. Wait for the chain watermark rather than
                        // treating the normal pre-confirmation read as a failed settlement.
                        awaitingConfirmation = true
                        var after = adapter.readChannel(voucher).getOrThrow()
                        publish(after)
                        while (after.lastSettledMicroUsdc < voucher.totalAmountClaimedMicroUsdc) {
                            delay(500.milliseconds)
                            after = adapter.readChannel(voucher).getOrThrow()
                            publish(after)
                        }
                        awaitingConfirmation = false
                        confirm(voucher, after.lastSettledMicroUsdc, candidate.second)
                    } else {
                        confirm(voucher, before.lastSettledMicroUsdc, candidate.second)
                    }
                }
            }
            reportSettlementError(null)
        } catch (e: TimeoutCancellationException) {
            reportSettlementError(
                if (awaitingConfirmation) {
                    IllegalStateException("Voucher settlement confirmation timed out; payment may still be pending", e)
                } else {
                    e
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportSettlementError(e)
        }
    }

    private fun reportSettlementError(error: Throwable?) {
        try {
            onSettlementError(error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
    }

    private suspend fun confirm(
        voucher: Voucher,
        amount: Long,
        round: Long?,
    ) {
        mutex.withLock {
            // Atomic conditional deletion preserves a newer voucher, including across sessions.
            voucherRepository.deleteSettledVoucher(Base64.encode(voucher.channelId), amount)
            confirmedAmount = maxOf(confirmedAmount, amount)
            if (round != null) paidRound = round
        }
    }

    private fun publish(snapshot: HostViewerVaultReader.Snapshot) {
        try {
            onSnapshot(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
    }

    private fun report(error: Throwable) {
        try {
            onError(error)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
