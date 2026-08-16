package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import android.util.Log
import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds

internal class LiquidStreamBlockConsumptionManager(
    private val tag: String,
    private val getViewModel: () -> LiquidAuthOfferViewModel?,
    private val getActiveViewerAddress: () -> String?,
    private val getActiveCreatorAddress: () -> String?,
    private val getCreatorVoucherClaimSnapshot: () -> CreatorVoucherClaimSnapshot?,
    private val buildCreatorWalletSigner: suspend (String) -> MppWalletSigner?,
    private val voucherRepository: MppVoucherRepository,
) {
    data class CreatorVoucherClaimSnapshot(
        val sessionId: String,
        val viewerAddress: String,
        val viewerPublicKeyBase64: String,
        val signatureBase64: String,
        val totalAmountClaimedMicroUsdc: Long,
    )

    companion object {
        private const val CHAIN_READ_TIMEOUT_MS = 10_000L
        private const val CHAIN_WRITE_TIMEOUT_MS = 15_000L
    }

    /**
     * Single managed scope.
     * Prevents leaked coroutines from anonymous CoroutineScope().
     */
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO,
        )

    private val settlementMutex = Mutex()
    private val settlementJobLock = Any()

    private var blockDrivenConsumptionJob: Job? = null
    private var settlementJob: Job? = null

    private val blocksConsumed = AtomicInteger(0)

    @Volatile
    private var currentSessionId: String? = null

    fun start(sessionId: String) {
        Log.e(
            tag,
            "[SESSION_VAULT_BLOCK_LOOP_START_REQUEST] " +
                "session=$sessionId " +
                "active=${blockDrivenConsumptionJob?.isActive == true} " +
                "currentSession=$currentSessionId",
        )

        if (
            (currentSessionId == sessionId) &&
            (blockDrivenConsumptionJob?.isActive == true)
        ) {
            Log.e(
                tag,
                "[SESSION_VAULT_BLOCK_LOOP_ALREADY_RUNNING] " +
                    "session=$sessionId blocks=${blocksConsumed.get()}",
            )
            return
        }

        stop()

        val viewModel =
            getViewModel() ?: run {
                Log.e(
                    tag,
                    "[SESSION_VAULT_BLOCK_LOOP_START_SKIP] " +
                        "reason=viewModel_null session=$sessionId",
                )
                return
            }

        currentSessionId = sessionId
        blocksConsumed.set(0)

        processPendingSettlements()

        viewModel.monitorBlockchainBlocks()
        viewModel.startRealtimeBlockNumberUpdates()

        var lastObservedBlock: Long? = null

        blockDrivenConsumptionJob =
            scope.launch {
                Log.e(
                    tag,
                    "[SESSION_VAULT_BLOCK_LOOP_JOB_STARTED] session=$sessionId",
                )

                viewModel.currentBlockNumber.collect { blockNumber ->

                    if (blockNumber == null) {
                        return@collect
                    }

                    val previous = lastObservedBlock

                    if (previous == null) {
                        lastObservedBlock = blockNumber
                        Log.e(
                            tag,
                            "[SESSION_VAULT_BLOCK_BASELINE_SET] " +
                                "baseline=$blockNumber",
                        )
                        return@collect
                    }

                    val claimSnapshot = getCreatorVoucherClaimSnapshot()

                    if (claimSnapshot == null) {
                        lastObservedBlock = blockNumber
                        Log.e(
                            tag,
                            "[SESSION_VAULT_BLOCK_WAITING_FOR_CLAIM_SNAPSHOT] " +
                                "session=$sessionId block=$blockNumber",
                        )
                        return@collect
                    }

                    if (claimSnapshot.sessionId != sessionId) {
                        lastObservedBlock = blockNumber
                        Log.e(
                            tag,
                            "[SESSION_VAULT_BLOCK_WAITING_FOR_SESSION_VOUCHER] " +
                                "session=$sessionId " +
                                "snapshotSession=${claimSnapshot.sessionId}",
                        )
                        return@collect
                    }

                    val advancedLong = blockNumber - previous

                    if (advancedLong <= 0L) {
                        return@collect
                    }

                    val advanced = advancedLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

                    Log.e(
                        tag,
                        "[SESSION_VAULT_BLOCK_ADVANCED] " +
                            "from=$previous to=$blockNumber count=$advanced",
                    )

                    /**
                     * STRICTLY SEQUENTIAL
                     * Financial operations must complete in order.
                     */
                    repeat(advanced) {
                        /**
                         * Session may have been stopped while suspended.
                         */
                        if (sessionId != currentSessionId) {
                            Log.w(
                                tag,
                                "[SESSION_VAULT_BLOCK_ABORT] " +
                                    "session_changed current=$currentSessionId",
                            )
                            return@collect
                        }

                        consumeBlockSequentially()
                    }

                    lastObservedBlock = blockNumber
                }
            }
    }

    fun stop() {
        Log.e(
            tag,
            "[SESSION_VAULT_BLOCK_LOOP_STOP] " +
                "session=$currentSessionId " +
                "active=${blockDrivenConsumptionJob?.isActive == true}",
        )

        blockDrivenConsumptionJob?.cancel()
        blockDrivenConsumptionJob = null

        getViewModel()?.stopRealtimeBlockNumberUpdates()

        currentSessionId = null
        blocksConsumed.set(0)
        val jobToCancel =
            synchronized(settlementJobLock) {
                val job = settlementJob
                settlementJob = null
                job
            }
        jobToCancel?.cancel()
    }

    private suspend fun consumeBlockSequentially() {
        val sessionId = currentSessionId
        val creatorAddress = getActiveCreatorAddress()
        val viewModel = getViewModel()

        if (viewModel == null) {
            Log.e(tag, "[SESSION_VAULT_CLAIM_SKIP] reason=viewModel_null")
            return
        }

        if (sessionId.isNullOrBlank()) {
            Log.e(tag, "[SESSION_VAULT_CLAIM_SKIP] reason=session_missing")
            return
        }

        if (creatorAddress.isNullOrBlank()) {
            Log.e(tag, "[SESSION_VAULT_CLAIM_SKIP] reason=creator_missing")
            return
        }

        val localBlocksConsumed = blocksConsumed.incrementAndGet()

        val viewerAddress =
            getActiveViewerAddress()
                ?.takeIf { it.isNotBlank() }

        val progressSnapshot =
            if (viewerAddress == null) {
                null
            } else {
                try {
                    withTimeout(CHAIN_READ_TIMEOUT_MS.milliseconds) {
                        MppPayments.getSessionProgressSnapshotFromVault()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(
                        tag,
                        "[SESSION_VAULT_PROGRESS_FETCH_TIMEOUT_OR_ERR] session=$sessionId blocks=$localBlocksConsumed viewer=$viewerAddress creator=$creatorAddress timeoutMs=$CHAIN_READ_TIMEOUT_MS",
                        t,
                    )
                    null
                }
            }

        val remainingVaultBalance =
            progressSnapshot?.remainingSettledMicroUsdc ?: 0L

        val progressBarBalanceMicroUsdc =
            progressSnapshot?.progressBalanceMicroUsdc ?: 0L

        viewModel.consumeBlock(
            onChainRemainingMicroUsdc = remainingVaultBalance,
            progressBarBalanceMicroUsdc = progressBarBalanceMicroUsdc,
        )
    }

    private suspend fun startSettlement(
        sessionId: String,
        viewerAddress: String?,
        creatorAddress: String,
        signatureBase64: String,
        signedTotalAmount: Long,
        localBlocksConsumed: Int,
    ) {
        Log.e(
            tag,
            "[SESSION_VAULT_CLAIM_ATTEMPT] " +
                "session=$sessionId " +
                "viewer=$viewerAddress " +
                "blocks=$localBlocksConsumed",
        )

        val settlementResult =
            try {
                /**
                 * Session stopped while suspended.
                 */
                if (sessionId != currentSessionId) {
                    error("Session changed during settlement")
                }

                val signer =
                    buildCreatorWalletSigner(creatorAddress)
                        ?: error("Unsupported creator account")

                val refreshed =
                    withTimeout(CHAIN_READ_TIMEOUT_MS.milliseconds) {
                        MppPayments.getSessionDynamicDataFromVault()
                    }

                val refreshedSettled =
                    refreshed?.lastSettled ?: 0L

                if (signedTotalAmount <= refreshedSettled) {
                    Log.e(
                        tag,
                        "[SESSION_VAULT_CLAIM_SKIP] " +
                            "reason=nothing_to_settle " +
                            "signedTotal=$signedTotalAmount " +
                            "onChainSettled=$refreshedSettled",
                    )

                    Result.success("nothing_to_settle")
                } else {
                    @OptIn(ExperimentalEncodingApi::class)
                    val signature = Base64.decode(signatureBase64)
                    val settleResult =
                        try {
                            Result.success(
                                withTimeout(CHAIN_WRITE_TIMEOUT_MS.milliseconds) {
                                    MppPayments
                                        .settle(
                                            signer = signer,
                                            cumulativeAmountMicroUsdc = signedTotalAmount,
                                            signature = signature,
                                        ).getOrThrow()
                                },
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Result.failure(t)
                        }

                    if (settleResult.isSuccess) {
                        settleResult
                    } else {
                        val err = settleResult.exceptionOrNull()
                        val nothingToSettleAssert = MppPayments.isNothingToSettleError(err?.message.orEmpty())
                        val postFailureData =
                            if (nothingToSettleAssert) {
                                withTimeout(CHAIN_READ_TIMEOUT_MS.milliseconds) {
                                    MppPayments.getSessionDynamicDataFromVault()
                                }
                            } else {
                                null
                            }
                        val postFailureSettled = postFailureData?.lastSettled ?: 0L
                        val nothingLeftToSettle = signedTotalAmount <= postFailureSettled

                        if (nothingToSettleAssert && nothingLeftToSettle) {
                            Log.e(
                                tag,
                                "[SESSION_VAULT_CLAIM_SKIP] reason=already_settled signedTotal=$signedTotalAmount settled=$postFailureSettled",
                            )
                            Result.success("already_settled")
                        } else {
                            settleResult
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }

        settlementResult
            .onSuccess { txId ->
                Log.e(
                    tag,
                    "[SESSION_VAULT_CLAIM_OK] " +
                        "txId=$txId " +
                        "blocks=$localBlocksConsumed " +
                        "session=$sessionId",
                )
                // Delete voucher from DB after successful settlement
                voucherRepository.deleteVoucherBySessionId(sessionId)
            }.onFailure {
                Log.e(
                    tag,
                    "[SESSION_VAULT_CLAIM_ERR] " +
                        "blocks=$localBlocksConsumed " +
                        "session=$sessionId",
                    it,
                )
            }
    }

    fun processPendingSettlements() {
        scope.launch {
            val vouchers = voucherRepository.getAllVouchers()
            if (vouchers.isNotEmpty()) {
                Log.d(tag, "🔄 processing ${vouchers.size} pending settlements")
                vouchers.forEach { voucher ->
                    startSettlement(
                        sessionId = voucher.sessionId,
                        viewerAddress = voucher.viewerAddress,
                        creatorAddress = voucher.creatorAddress,
                        signatureBase64 = voucher.signatureBase64,
                        signedTotalAmount = voucher.totalAmountClaimedMicroUsdc,
                        localBlocksConsumed = 0,
                    )
                }
            }
        }
    }

    fun triggerSettlementFromViewerVoucher(sessionId: String) {
        val creatorAddress = getActiveCreatorAddress()
        val viewerAddress = getActiveViewerAddress()
        val activeSession = currentSessionId
        if (creatorAddress.isNullOrBlank()) {
            Log.e(tag, "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=creator_missing session=$sessionId")
            return
        }
        if (activeSession.isNullOrBlank() || (activeSession != sessionId)) {
            Log.e(
                tag,
                "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=session_mismatch session=$sessionId current=$activeSession",
            )
            return
        }

        if (!settlementMutex.tryLock()) {
            Log.e(
                tag,
                "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=request_in_flight session=$sessionId",
            )
            return
        }

        val claimSnapshot =
            getCreatorVoucherClaimSnapshot() ?: run {
                Log.e(
                    tag,
                    "[SESSION_VAULT_CLAIM_SKIP] reason=claim_snapshot_missing",
                )
                settlementMutex.unlock()
                return
            }

        if (claimSnapshot.sessionId != sessionId) {
            Log.e(
                tag,
                "[SESSION_VAULT_CLAIM_SKIP] reason=session_mismatch",
            )
            settlementMutex.unlock()
            return
        }

        val localBlocksConsumed = blocksConsumed.get().coerceAtLeast(0)
        val job =
            scope.launch {
                val currentJob = coroutineContext[Job]

                try {
                    if (sessionId != currentSessionId) {
                        Log.e(
                            tag,
                            "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=session_mismatch session=$sessionId current=$currentSessionId",
                        )
                        return@launch
                    }

                    // Save latest voucher to DB before settlement
                    voucherRepository.upsertVoucher(
                        MppVoucherEntity(
                            sessionId = sessionId,
                            viewerAddress = viewerAddress.orEmpty(),
                            viewerPublicKeyBase64 = claimSnapshot.viewerPublicKeyBase64,
                            signatureBase64 = claimSnapshot.signatureBase64,
                            totalAmountClaimedMicroUsdc = claimSnapshot.totalAmountClaimedMicroUsdc,
                            creatorAddress = creatorAddress,
                            note = "N/A",
                        ),
                    )

                    startSettlement(
                        sessionId = sessionId,
                        viewerAddress = viewerAddress,
                        creatorAddress = creatorAddress,
                        signatureBase64 = claimSnapshot.signatureBase64,
                        signedTotalAmount = claimSnapshot.totalAmountClaimedMicroUsdc,
                        localBlocksConsumed = localBlocksConsumed,
                    )
                } finally {
                    settlementMutex.unlock()
                    synchronized(settlementJobLock) {
                        if (settlementJob == currentJob) {
                            settlementJob = null
                        }
                    }
                }
            }
        synchronized(settlementJobLock) {
            settlementJob = job
        }
    }
}
