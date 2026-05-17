package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import android.util.Log
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
internal class LiquidStreamBlockConsumptionManager(
    private val tag: String,
    private val getViewModel: () -> LiquidAuthOfferViewModel?,
    private val getActiveViewerAddress: () -> String?,
    private val getActiveCreatorAddress: () -> String?,
    private val getCreatorVoucherClaimSnapshot: () -> CreatorVoucherClaimSnapshot?,
    private val buildCreatorWalletSigner: suspend (String) -> MppWalletSigner?,
    private val sendMessage: (String) -> Unit,
) {

    data class CreatorVoucherClaimSnapshot(
        val sessionId: String,
        val viewerAddress: String,
        val viewerPublicKeyBase64: String,
        val signatureBase64: String,
        val totalAmountClaimedMicroUsdc: Long,
    )

    companion object {
        private const val ZERO_BALANCE_GRACE_BLOCKS = 3
        private const val CHAIN_READ_TIMEOUT_MS = 10_000L
        private const val CHAIN_WRITE_TIMEOUT_MS = 15_000L
    }

    /**
     * Single managed scope.
     * Prevents leaked coroutines from anonymous CoroutineScope().
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    )

    /**
     * Prevents concurrent settlements.
     */
    private val settlementMutex = Mutex()

    private var blockDrivenConsumptionJob: Job? = null

    private var blocksConsumed: Int = 0
    private var currentSessionId: String? = null
    private var consecutiveZeroProgressBlocks: Int = 0
    private val settlementGateLock = Any()
    private var settlementInFlight: Boolean = false

    fun start(sessionId: String) {

        Log.e(
            tag,
            "[SESSION_VAULT_BLOCK_LOOP_START_REQUEST] " +
                    "session=$sessionId " +
                    "active=${blockDrivenConsumptionJob?.isActive == true} " +
                    "currentSession=$currentSessionId",
        )

        if (
            currentSessionId == sessionId &&
            blockDrivenConsumptionJob?.isActive == true
        ) {
            Log.e(
                tag,
                "[SESSION_VAULT_BLOCK_LOOP_ALREADY_RUNNING] " +
                        "session=$sessionId blocks=$blocksConsumed",
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
        blocksConsumed = 0
        consecutiveZeroProgressBlocks = 0

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

                    val advanced = (blockNumber - previous).toInt()

                    if (advanced <= 0) {
                        return@collect
                    }

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
        blocksConsumed = 0
        consecutiveZeroProgressBlocks = 0
        endSettlement()
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

        blocksConsumed++

        /**
         * Snapshot immediately.
         */
        val localBlocksConsumed = blocksConsumed

        val viewerAddress =
            getActiveViewerAddress()
                ?.takeIf { it.isNotBlank() }

        val snapshotSignerPublicKey =
            getCreatorVoucherClaimSnapshot()
                ?.viewerPublicKeyBase64
                ?.takeIf { it.isNotBlank() }
                ?.let { encoded ->
                    runCatching {
                        Base64.getDecoder().decode(encoded)
                    }.getOrNull()
                }

        val progressSnapshot =
            if (viewerAddress == null) {
                null
            } else {
                try {
                    withTimeout(CHAIN_READ_TIMEOUT_MS) {
                        MppPayments.getSessionProgressSnapshotFromVault(
                            viewerAddress = viewerAddress,
                            hostAddress = creatorAddress,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            authorizedSignerPublicKey = snapshotSignerPublicKey,
                        )
                    }
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
        used: Long,
        localBlocksConsumed: Int,
    ) {

        Log.e(
            tag,
            "[SESSION_VAULT_CLAIM_ATTEMPT] " +
                    "session=$sessionId " +
                    "blocks=$localBlocksConsumed",
        )

        val claimSnapshot =
            getCreatorVoucherClaimSnapshot() ?: run {

                Log.e(
                    tag,
                    "[SESSION_VAULT_CLAIM_SKIP] reason=claim_snapshot_missing",
                )

                return
            }

        if (claimSnapshot.sessionId != sessionId) {

            Log.e(
                tag,
                "[SESSION_VAULT_CLAIM_SKIP] " +
                        "reason=session_mismatch",
            )

            return
        }

        if (claimSnapshot.viewerAddress != viewerAddress) {

            Log.e(
                tag,
                "[SESSION_VAULT_CLAIM_SKIP] " +
                        "reason=viewer_mismatch",
            )

            return
        }

        val signerPublicKey =
            Base64
                .getDecoder()
                .decode(claimSnapshot.viewerPublicKeyBase64)

        val signedTotalAmount =
            claimSnapshot.totalAmountClaimedMicroUsdc
                .coerceAtLeast(0L)

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
                    withTimeout(CHAIN_READ_TIMEOUT_MS) {
                        MppPayments.getSessionDynamicDataFromVault(
                            viewerAddress = claimSnapshot.viewerAddress,
                            hostAddress = creatorAddress,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            authorizedSignerPublicKey = signerPublicKey,
                        )
                    }

                val refreshedLatest =
                    refreshed?.latestVoucherAmount ?: 0L

                val refreshedSettled =
                    refreshed?.lastSettled ?: 0L

                val refreshedUnclaimed =
                    (refreshedLatest - refreshedSettled)
                        .coerceAtLeast(0L)

                if (refreshedUnclaimed <= 0L) {
                    // Creator is payee: only settle on-chain voucher amounts.
                    // Viewer (payer) is responsible for updateVoucher transactions.
                    if (signedTotalAmount > refreshedLatest) {
                        Log.e(
                            tag,
                            "[SESSION_VAULT_CLAIM_SKIP] " +
                                    "reason=viewer_update_pending " +
                                    "signedTotal=$signedTotalAmount " +
                                    "onChainLatest=$refreshedLatest",
                        )
                    }

                    Log.e(
                        tag,
                        "[SESSION_VAULT_CLAIM_SKIP] " +
                                "reason=nothing_to_settle",
                    )

                    Result.success("nothing_to_settle")
                } else {
                    val txId =
                        withTimeout(CHAIN_WRITE_TIMEOUT_MS) {
                            MppPayments
                                .settleLatestVoucher(
                                    signer = signer,
                                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                    viewerAddress = claimSnapshot.viewerAddress,
                                    hostAddress = creatorAddress,
                                    authorizedSignerPublicKey = signerPublicKey,
                                ).getOrThrow()
                        }
                    Result.success(txId)
                }
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
                            "used=$used",
                )
            }
            .onFailure {

                Log.e(
                    tag,
                    "[SESSION_VAULT_CLAIM_ERR] " +
                            "blocks=$localBlocksConsumed",
                    it,
                )
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
        if (activeSession.isNullOrBlank() || activeSession != sessionId) {
            Log.e(
                tag,
                "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=session_mismatch session=$sessionId current=$activeSession",
            )
            return
        }
        if (!tryBeginSettlement()) {
            Log.e(tag, "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=request_in_flight session=$sessionId")
            return
        }

        val localBlocksConsumed = blocksConsumed.coerceAtLeast(0)
        val used = MppPayments.computeVoucherMicroUsdcUsage(localBlocksConsumed)
        scope.launch {
            try {
                settlementMutex.withLock {
                    if (sessionId != currentSessionId) {
                        Log.e(
                            tag,
                            "[SESSION_VAULT_SETTLEMENT_TRIGGER_SKIP] reason=session_mismatch session=$sessionId current=$currentSessionId",
                        )
                        return@withLock
                    }
                    startSettlement(
                        sessionId = sessionId,
                        viewerAddress = viewerAddress,
                        creatorAddress = creatorAddress,
                        used = used,
                        localBlocksConsumed = localBlocksConsumed,
                    )
                }
            } finally {
                endSettlement()
            }
        }
    }

    private fun tryBeginSettlement(): Boolean =
        synchronized(settlementGateLock) {
            if (settlementInFlight) {
                false
            } else {
                settlementInFlight = true
                true
            }
        }

    private fun endSettlement() {
        synchronized(settlementGateLock) {
            settlementInFlight = false
        }
    }
}
