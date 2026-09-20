package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.ViewerVaultSettlement
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthPaymentVoucherMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
    payoutFrequencyBlocks: Int = 1,
    private val adapter: Adapter = CoreAdapter,
    private val operationTimeoutMillis: Long = 25_000,
    private val finalDrainTimeoutMillis: Long = 30_000,
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

        suspend fun settle(voucher: Voucher, creatorWalletSigner: MppWalletSigner): Result<Unit>
    }

    private object CoreAdapter : Adapter {
        override suspend fun validateAuthorization(voucher: Voucher): Result<HostViewerVaultReader.Snapshot> {
            return ViewerVaultSettlement.validateVoucher(
                viewerAddress = voucher.viewerAddress,
                creatorAddress = voucher.creatorAddress,
                authorizedSignerPublicKey = voucher.signerPublicKey,
                channelId = voucher.channelId,
                signature = voucher.signature,
                cumulativeAmount = voucher.totalAmountClaimedMicroUsdc,
                network = voucher.network,
            )
        }

        override suspend fun settle(voucher: Voucher, creatorWalletSigner: MppWalletSigner): Result<Unit> =
            ViewerVaultSettlement(creatorWalletSigner).settle(
                viewerAddress = voucher.viewerAddress,
                creatorAddress = voucher.creatorAddress,
                authorizedSignerPublicKey = voucher.signerPublicKey,
                channelId = voucher.channelId,
                signature = voucher.signature,
                cumulativeAmount = voucher.totalAmountClaimedMicroUsdc,
                network = voucher.network,
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

    init {
        require(sessionId.isNotBlank() && viewerAddress.isNotBlank() && creatorAddress.isNotBlank())
        require(network.isNotBlank() && key.isNotEmpty())
        require(payoutFrequencyBlocks > 0)
        require(operationTimeoutMillis > 0 && finalDrainTimeoutMillis > 0)
    }

    private val job = scope.launch {
        try {
            for (ignored in wake) {
                val final = mutex.withLock { closed }
                attemptSettlement(final)
                if (final) break
            }
        } finally {
            wake.close()
        }
    }

    suspend fun acceptVoucher(message: LiquidAuthPaymentVoucherMessage): Boolean {
        val voucher = try {
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
        }
        try {
            val snapshot = withTimeoutOrNull(operationTimeoutMillis) {
                adapter.validateAuthorization(voucher).getOrThrow()
            } ?: error("Voucher authorization validation timed out")
            require(voucher.totalAmountClaimedMicroUsdc <= snapshot.totalDepositMicroUsdc) {
                "Voucher exceeds deposit"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
            return false
        }
        val accepted = mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!canAccept(voucher)) return@withLock false
            latest = voucher
            wake.trySend(Unit)
            true
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

    fun onBlock(block: Long): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        mutex.withLock {
            if (closed || block < 0 || latestRound?.let { block <= it } == true) return@withLock
            latestRound = block
            if (paidRound == null) paidRound = block
            wake.trySend(Unit)
        }
    }

    fun close(): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        mutex.withLock {
            closed = true
            wake.trySend(Unit)
        }
        if (withTimeoutOrNull(finalDrainTimeoutMillis) { job.join(); true } != true) {
            job.cancel()
            report(IllegalStateException("Viewer voucher final drain timed out; payment may be pending"))
        }
    }

    private suspend fun attemptSettlement(force: Boolean) {
        val candidate = mutex.withLock {
            val voucher = latest ?: return
            if (voucher.totalAmountClaimedMicroUsdc <= confirmedAmount) return
            val round = latestRound
            val boundary = paidRound
            if (!force && (round == null || boundary == null || round - boundary < payoutFrequencyBlocks)) return
            voucher to round
        }
        try {
            withTimeout(operationTimeoutMillis) {
                val voucher = candidate.first
                val before = adapter.readChannel(voucher).getOrThrow()
                publish(before)
                if (before.lastSettledMicroUsdc < voucher.totalAmountClaimedMicroUsdc) {
                    val signer = buildCreatorWalletSigner(creatorAddress) ?: error("Creator signer unavailable")
                    require(signer.address == creatorAddress) { "Creator wallet signer mismatch" }
                    adapter.settle(voucher, signer).getOrThrow()
                    val after = adapter.readChannel(voucher).getOrThrow()
                    publish(after)
                    check(after.lastSettledMicroUsdc >= voucher.totalAmountClaimedMicroUsdc) {
                        "Voucher settlement is not confirmed"
                    }
                    confirm(after.lastSettledMicroUsdc, candidate.second)
                } else {
                    confirm(before.lastSettledMicroUsdc, candidate.second)
                }
            }
        } catch (e: TimeoutCancellationException) {
            report(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
    }

    private suspend fun confirm(amount: Long, round: Long?) {
        mutex.withLock {
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
