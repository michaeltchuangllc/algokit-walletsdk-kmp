package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.railmpp.data.database.model.MppVoucherEntity
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.CreatorVoucherClaimSnapshot
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.deleteVoucher
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.VoucherSettlementPolicy
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared (commonMain) block-consumption + settlement manager for the Liquid Stream host/creator
 * side. Used identically by both Android and iOS actual [com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager]
 * implementations so the block-driven consumption loop, staleness checks, voucher persistence,
 * and on-chain settlement logic live in exactly one place.
 */
internal class LiquidStreamBlockConsumptionManager(
    private val tag: String,
    private val getViewModel: () -> LiquidAuthOfferViewModel?,
    private val getActiveViewerAddress: () -> String?,
    private val getActiveCreatorAddress: () -> String?,
    private val getCreatorVoucherClaimSnapshot: () -> CreatorVoucherClaimSnapshot?,
    private val buildCreatorWalletSigner: suspend (String) -> MppWalletSigner?,
    private val voucherRepository: MppVoucherRepository,
) {
    companion object {
        private const val CHAIN_READ_TIMEOUT_MS = VoucherSettlementPolicy.CHAIN_READ_TIMEOUT_MS
        private const val CHAIN_WRITE_TIMEOUT_MS = VoucherSettlementPolicy.CHAIN_WRITE_TIMEOUT_MS
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

    private var blockDrivenConsumptionJob: Job? = null

    @Volatile
    private var settlementJob: Job? = null

    @Volatile
    private var blocksConsumed: Int = 0

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    var payoutFrequencyBlocks: Int = 1

    private var lastSettledBlockCount: Int = 0

    fun start(sessionId: String) {
        Napier.e(
            "[SESSION_VAULT_BLOCK_LOOP_START_REQUEST] " +
                "session=$sessionId " +
                "active=${blockDrivenConsumptionJob?.isActive == true} " +
                "currentSession=$currentSessionId",
            tag = tag,
        )

        if (
            (currentSessionId == sessionId) &&
            (blockDrivenConsumptionJob?.isActive == true)
        ) {
            Napier.e(
                "[SESSION_VAULT_BLOCK_LOOP_ALREADY_RUNNING] " +
                    "session=$sessionId blocks=$blocksConsumed",
                tag = tag,
            )
            return
        }

        stop()

        val viewModel =
            getViewModel() ?: run {
                Napier.e(
                    "[SESSION_VAULT_BLOCK_LOOP_START_SKIP] " +
                        "reason=viewModel_null session=$sessionId",
                    tag = tag,
                )
                return
            }

        currentSessionId = sessionId
        blocksConsumed = 0
        lastSettledBlockCount = 0

        processPendingSettlements()

        viewModel.monitorBlockchainBlocks()
        viewModel.startRealtimeBlockNumberUpdates()

        var lastObservedBlock: Long? = null

        blockDrivenConsumptionJob =
            scope.launch {
                Napier.e(
                    "[SESSION_VAULT_BLOCK_LOOP_JOB_STARTED] session=$sessionId",
                    tag = tag,
                )

                viewModel.currentBlockNumber.collect { blockNumber ->

                    if (blockNumber == null) {
                        return@collect
                    }

                    val previous = lastObservedBlock

                    if (previous == null) {
                        lastObservedBlock = blockNumber
                        Napier.e(
                            "[SESSION_VAULT_BLOCK_BASELINE_SET] " +
                                "baseline=$blockNumber",
                            tag = tag,
                        )
                        return@collect
                    }

                    val claimSnapshot = getCreatorVoucherClaimSnapshot()

                    if (claimSnapshot == null) {
                        lastObservedBlock = blockNumber
                        Napier.e(
                            "[SESSION_VAULT_BLOCK_NO_VOUCHER_YET] " +
                                "session=$sessionId block=$blockNumber — " +
                                "proceeding to update UI with on-chain balance",
                            tag = tag,
                        )
                    }

                    if (claimSnapshot != null && claimSnapshot.sessionId != sessionId) {
                        lastObservedBlock = blockNumber
                        Napier.e(
                            "[SESSION_VAULT_BLOCK_WAITING_FOR_SESSION_VOUCHER] " +
                                "session=$sessionId " +
                                "snapshotSession=${claimSnapshot.sessionId}",
                            tag = tag,
                        )
                        return@collect
                    }

                    val advancedLong = blockNumber - previous

                    if (advancedLong <= 0L) {
                        return@collect
                    }

                    val advanced = advancedLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

                    Napier.e(
                        "[SESSION_VAULT_BLOCK_ADVANCED] " +
                            "from=$previous to=$blockNumber count=$advanced",
                        tag = tag,
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
                            Napier.w(
                                "[SESSION_VAULT_BLOCK_ABORT] " +
                                    "session_changed current=$currentSessionId",
                                tag = tag,
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
        Napier.e(
            "[SESSION_VAULT_BLOCK_LOOP_STOP] " +
                "session=$currentSessionId " +
                "active=${blockDrivenConsumptionJob?.isActive == true}",
            tag = tag,
        )

        val sessionId = currentSessionId
        if (sessionId != null) {
            scope.launch {
                triggerSettlementFromViewerVoucher(sessionId, force = true)
            }
        }

        blockDrivenConsumptionJob?.cancel()
        blockDrivenConsumptionJob = null

        getViewModel()?.stopRealtimeBlockNumberUpdates()

        currentSessionId = null
        blocksConsumed = 0
        val jobToCancel = settlementJob
        settlementJob = null
        jobToCancel?.cancel()
    }

    private suspend fun consumeBlockSequentially() {
        val sessionId = currentSessionId
        val creatorAddress = getActiveCreatorAddress()
        val viewModel = getViewModel()

        if (viewModel == null) {
            Napier.e("[SESSION_VAULT_CLAIM_SKIP] reason=viewModel_null", tag = tag)
            return
        }

        if (sessionId.isNullOrBlank()) {
            Napier.e("[SESSION_VAULT_CLAIM_SKIP] reason=session_missing", tag = tag)
            return
        }

        if (creatorAddress.isNullOrBlank()) {
            Napier.e("[SESSION_VAULT_CLAIM_SKIP] reason=creator_missing", tag = tag)
            return
        }

        val localBlocksConsumed = ++blocksConsumed

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
                    Napier.e(
                        "[SESSION_VAULT_PROGRESS_FETCH_TIMEOUT_OR_ERR] session=$sessionId blocks=$localBlocksConsumed viewer=$viewerAddress creator=$creatorAddress timeoutMs=$CHAIN_READ_TIMEOUT_MS",
                        t,
                        tag = tag,
                    )
                    null
                }
            }

        val remainingVaultBalance =
            progressSnapshot?.remainingSettledMicroUsdc ?: 0L

        val progressBarBalanceMicroUsdc =
            progressSnapshot?.progressBalanceMicroUsdc ?: 0L

        val lastSettledMicroUsdc =
            progressSnapshot?.lastSettledMicroUsdc ?: 0L
        val startRound =
            progressSnapshot?.startRound ?: 0L
        viewModel.consumeBlock(
            onChainRemainingMicroUsdc = remainingVaultBalance,
            progressBarBalanceMicroUsdc = progressBarBalanceMicroUsdc,
            lastSettledMicroUsdc = lastSettledMicroUsdc,
            startRound = startRound,
        )
    }

    private suspend fun startSettlement(
        sessionId: String,
        viewerAddress: String?,
        creatorAddress: String,
        signatureBase64: String,
        signedTotalAmount: Long,
        localBlocksConsumed: Int,
        voucherBlockNumber: Long? = null,
        channelIdBase64: String? = null,
    ) {
        Napier.d(
            "[SESSION_VAULT_CLAIM_ATTEMPT] " +
                "session=$sessionId " +
                "viewer=$viewerAddress " +
                "blocks=$localBlocksConsumed " +
                "voucherBlock=$voucherBlockNumber " +
                "hasChannelId=${!channelIdBase64.isNullOrBlank()}",
            tag = tag,
        )

        val viewModel = getViewModel()
        val currentBlock = viewModel?.currentBlockNumber?.value

        if (VoucherSettlementPolicy.isTooStaleToSettle(currentBlock, voucherBlockNumber)) {
            Napier.d(
                "[SESSION_VAULT_CLAIM_SKIP] " +
                    "reason=block_diff_too_high " +
                    "session=$sessionId " +
                    "current=$currentBlock " +
                    "voucher=$voucherBlockNumber",
                tag = tag,
            )
            // Delete voucher from DB immediately as requested
            voucherRepository.deleteVoucher(sessionId, viewerAddress, channelIdBase64)
            return
        }

        val settlementResult =
            try {
                /**
                 * Session stopped while suspended.
                 * Guard is only active if currentSessionId is non-null (active streaming).
                 * Pending settlements during app-restart (currentSessionId == null) bypass this check.
                 */
                if (currentSessionId != null && sessionId != currentSessionId) {
                    error("Session changed during settlement")
                }

                val signer =
                    buildCreatorWalletSigner(creatorAddress)
                        ?: error("Unsupported creator account")

                @OptIn(ExperimentalEncodingApi::class)
                val channelId = channelIdBase64?.let { Base64.decode(it) }

                val refreshed =
                    withTimeout(CHAIN_READ_TIMEOUT_MS.milliseconds) {
                        MppPayments.getSessionDynamicDataFromVault(channelId)
                    }

                val refreshedSettled =
                    refreshed?.lastSettled ?: 0L

                if (signedTotalAmount <= refreshedSettled) {
                    Napier.e(
                        "[SESSION_VAULT_CLAIM_SKIP] " +
                            "reason=nothing_to_settle " +
                            "signedTotal=$signedTotalAmount " +
                            "onChainSettled=$refreshedSettled",
                        tag = tag,
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
                                            channelId = channelId,
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
                        val nothingToSettleAssert =
                            MppPayments.isNothingToSettleError(err?.message.orEmpty())
                        val postFailureData =
                            if (nothingToSettleAssert) {
                                withTimeout(CHAIN_READ_TIMEOUT_MS.milliseconds) {
                                    MppPayments.getSessionDynamicDataFromVault(channelId)
                                }
                            } else {
                                null
                            }
                        val postFailureSettled = postFailureData?.lastSettled ?: 0L
                        val nothingLeftToSettle = signedTotalAmount <= postFailureSettled

                        if (nothingToSettleAssert && nothingLeftToSettle) {
                            Napier.e(
                                "[SESSION_VAULT_CLAIM_SKIP] reason=already_settled signedTotal=$signedTotalAmount settled=$postFailureSettled",
                                tag = tag,
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
                Napier.e(
                    "[SESSION_VAULT_CLAIM_OK] " +
                        "txId=$txId " +
                        "blocks=$localBlocksConsumed " +
                        "session=$sessionId",
                    tag = tag,
                )
                // Delete voucher from DB after successful settlement
                voucherRepository.deleteVoucher(sessionId, viewerAddress, channelIdBase64)
            }.onFailure {
                Napier.e(
                    "[SESSION_VAULT_CLAIM_ERR] " +
                        "blocks=$localBlocksConsumed " +
                        "session=$sessionId",
                    it,
                    tag = tag,
                )
            }
    }

    fun processPendingSettlements() {
        scope.launch {
            val vouchers = voucherRepository.getAllVouchers()
            if (vouchers.isNotEmpty()) {
                Napier.d("processing ${vouchers.size} pending settlements", tag = tag)
                vouchers.forEach { voucher ->
                    startSettlement(
                        sessionId = voucher.sessionId,
                        viewerAddress = voucher.viewerAddress,
                        creatorAddress = voucher.creatorAddress,
                        signatureBase64 = voucher.signatureBase64,
                        signedTotalAmount = voucher.totalAmountClaimedMicroUsdc,
                        localBlocksConsumed = 0,
                        voucherBlockNumber = voucher.blockNumber,
                        channelIdBase64 = voucher.channelIdBase64,
                    )
                }
            }
        }
    }

    fun triggerSettlementFromViewerVoucher(
        sessionId: String,
        force: Boolean = false,
    ) {
        val creatorAddress = getActiveCreatorAddress()
        val viewerAddress = getActiveViewerAddress()
        val activeSession = currentSessionId
        if (creatorAddress.isNullOrBlank()) {
            Napier.e(
                "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=creator_missing session=$sessionId",
                tag = tag,
            )
            return
        }
        if (!force && (activeSession.isNullOrBlank() || (activeSession != sessionId))) {
            Napier.e(
                "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=session_mismatch session=$sessionId current=$activeSession",
                tag = tag,
            )
            return
        }

        val localBlocksConsumed = blocksConsumed.coerceAtLeast(0)

        if (!force) {
            val blocksSinceLastSettle = localBlocksConsumed - lastSettledBlockCount
            if (blocksSinceLastSettle < payoutFrequencyBlocks) {
                Napier.d(
                    "[SESSION_VAULT_SETTLEMENT_TRIGGER_DEFERRED] " +
                        "reason=frequency_not_met " +
                        "blocksSinceLast=$blocksSinceLastSettle " +
                        "frequency=$payoutFrequencyBlocks",
                    tag = tag,
                )
                return
            }
        }

        if (!settlementMutex.tryLock()) {
            Napier.e(
                "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=request_in_flight session=$sessionId",
                tag = tag,
            )
            return
        }

        val claimSnapshot =
            getCreatorVoucherClaimSnapshot() ?: run {
                Napier.e(
                    "[SESSION_VAULT_CLAIM_SKIP] reason=claim_snapshot_missing",
                    tag = tag,
                )
                settlementMutex.unlock()
                return
            }

        if (claimSnapshot.sessionId != sessionId) {
            Napier.e(
                "[SESSION_VAULT_CLAIM_SKIP] reason=session_mismatch",
                tag = tag,
            )
            settlementMutex.unlock()
            return
        }

        val job =
            scope.launch {
                val currentJob = coroutineContext[Job]

                try {
                    if (!force && sessionId != currentSessionId) {
                        Napier.e(
                            "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=session_mismatch session=$sessionId current=$currentSessionId",
                            tag = tag,
                        )
                        return@launch
                    }

                    @OptIn(ExperimentalEncodingApi::class)
                    val currentChannelIdBase64 = EscrowSessionVaultManagerClient.channelId?.let { Base64.encode(it) }
                    currentChannelIdBase64?.let { channelId ->
                        // Save latest voucher to DB before settlement
                        val viewModel = getViewModel()
                        val currentBlock = viewModel?.currentBlockNumber?.value ?: 0L
                        voucherRepository.upsertVoucher(
                            MppVoucherEntity(
                                sessionId = sessionId,
                                viewerAddress = viewerAddress.orEmpty(),
                                viewerPublicKeyBase64 = claimSnapshot.viewerPublicKeyBase64,
                                signatureBase64 = claimSnapshot.signatureBase64,
                                totalAmountClaimedMicroUsdc = claimSnapshot.totalAmountClaimedMicroUsdc,
                                creatorAddress = creatorAddress,
                                blockNumber = currentBlock,
                                channelIdBase64 = channelId,
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
                            voucherBlockNumber = currentBlock,
                            channelIdBase64 = channelId,
                        )
                        lastSettledBlockCount = localBlocksConsumed
                    }
                } finally {
                    settlementMutex.unlock()
                    if (settlementJob == currentJob) {
                        settlementJob = null
                    }
                }
            }
        settlementJob = job
    }
}
