package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import android.util.Log
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Base64

internal class LiquidAuthBlockConsumptionManager(
    private val tag: String,
    private val getViewModel: () -> LiquidAuthOfferViewModel?,
    private val getActiveViewerAddress: () -> String?,
    private val getActiveCreatorAddress: () -> String?,
    private val getCreatorVoucherSignatureBase64: () -> String?,
    private val buildCreatorWalletSigner: suspend (String) -> MppWalletSigner?,
    private val sendMessage: (String) -> Unit,
) {
    private var blockDrivenConsumptionJob: Job? = null
    private var blocksConsumed: Int = 0
    private var currentSessionId: String? = null

    fun start(sessionId: String) {
        Log.e(
            tag,
            "[SESSION_VAULT_BLOCK_LOOP_START_REQUEST] session=$sessionId active=${blockDrivenConsumptionJob?.isActive == true} currentSession=$currentSessionId",
        )
        if (currentSessionId == sessionId && blockDrivenConsumptionJob?.isActive == true) {
            Log.e(
                tag,
                "[SESSION_VAULT_BLOCK_LOOP_ALREADY_RUNNING] session=$sessionId blocks=$blocksConsumed",
            )
            return
        }

        Log.e(tag, "[SESSION_VAULT_BLOCK_LOOP_START] session=$sessionId")
        stop()

        val viewModel = getViewModel() ?: run {
            Log.e(tag, "[SESSION_VAULT_BLOCK_LOOP_START_SKIP] reason=viewModel_null session=$sessionId")
            return
        }
        currentSessionId = sessionId
        blocksConsumed = 0

        viewModel.monitorBlockchainBlocks()
        viewModel.startRealtimeBlockNumberUpdates()
        Log.e(tag, "[SESSION_VAULT_BLOCK_SOURCE_START] session=$sessionId source=realtime_block_updates")

        var lastObservedBlock: Long? = null
        blockDrivenConsumptionJob =
            CoroutineScope(Dispatchers.Default).launch {
                Log.e(tag, "[SESSION_VAULT_BLOCK_LOOP_JOB_STARTED] session=$sessionId")
                viewModel.currentBlockNumber
                    .collectLatest { blockNumber ->
                        if (blockNumber == null) {
                            Log.e(
                                tag,
                                "[SESSION_VAULT_BLOCK_WAITING] session=$sessionId currentBlock=null",
                            )
                            return@collectLatest
                        }

                        val previous = lastObservedBlock
                        Log.e(
                            tag,
                            "[SESSION_VAULT_BLOCK_OBSERVED] session=$sessionId block=$blockNumber previous=$previous",
                        )
                        if (previous == null) {
                            lastObservedBlock = blockNumber
                            Log.e(
                                tag,
                                "[SESSION_VAULT_BLOCK_BASELINE_SET] session=$sessionId baseline=$blockNumber",
                            )
                            return@collectLatest
                        }

                        val advanced = (blockNumber - previous).toInt()
                        if (advanced > 0) {
                            Log.e(
                                tag,
                                "[SESSION_VAULT_BLOCK_ADVANCED] session=$sessionId from=$previous to=$blockNumber count=$advanced",
                            )
                            repeat(advanced) { consumeBlock() }
                            lastObservedBlock = blockNumber
                        } else {
                            Log.e(
                                tag,
                                "[SESSION_VAULT_BLOCK_NO_ADVANCE] session=$sessionId block=$blockNumber previous=$previous",
                            )
                        }
                    }
            }

        Log.e(tag, "[SESSION_VAULT_BLOCK_LOOP_MONITORING] session=$sessionId")
    }

    fun stop() {
        Log.e(
            tag,
            "[SESSION_VAULT_BLOCK_LOOP_STOP] session=$currentSessionId active=${blockDrivenConsumptionJob?.isActive == true} blocks=$blocksConsumed",
        )
        blockDrivenConsumptionJob?.cancel()
        blockDrivenConsumptionJob = null
        getViewModel()?.stopRealtimeBlockNumberUpdates()
        Log.e(tag, "[SESSION_VAULT_BLOCK_SOURCE_STOP] session=$currentSessionId source=realtime_block_updates")
        currentSessionId = null
        blocksConsumed = 0
    }

    private fun consumeBlock() {
        val viewModel = getViewModel()
        val sessionId = currentSessionId
        val creatorAddress = getActiveCreatorAddress()
        Log.e(tag, "[SESSION_VAULT_CLAIM_TICK] session=$sessionId blocks=$blocksConsumed creator=$creatorAddress")

        if (viewModel == null) {
            Log.e(tag, "[SESSION_VAULT_CLAIM_SKIP] reason=viewModel_null")
            return
        }
        if (sessionId.isNullOrBlank()) {
            Log.e(tag, "[SESSION_VAULT_CLAIM_SKIP] reason=session_missing")
            return
        }
        if (creatorAddress.isNullOrBlank()) {
            Log.e(tag, "[SESSION_VAULT_CLAIM_SKIP] reason=creator_missing session=$sessionId")
            return
        }

        blocksConsumed++

        val viewerAddress = getActiveViewerAddress()?.takeIf { it.isNotBlank() }
        if (viewerAddress == null) {
            Log.e(tag, "[SESSION_VAULT_CLAIM_SKIP] reason=viewer_missing session=$sessionId blocks=$blocksConsumed")
        }
        val remainingFromVault =
            viewerAddress?.let {
                Log.e(
                    tag,
                    "[SESSION_VAULT_REMAINING_FETCH] session=$sessionId blocks=$blocksConsumed viewer=$it appId=${RailMppConstants.MPP_SESSION_VAULT_APP_ID}",
                )
                MppPayments.getRemainingBalanceFromSessionVault(
                    viewerAddress = it,
                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                )
            }

        val remainingVaultBalance =
            remainingFromVault ?: run {
                Log.e(
                    tag,
                    "[SESSION_VAULT_CLAIM_SKIP] reason=remaining_unavailable session=$sessionId blocks=$blocksConsumed viewer=$viewerAddress",
                )
                return
            }

        val claimed = MppPayments.computeVoucherClaimedMicroAlgos(blocksConsumed)
        val progressBarBalanceMicroUsdc = (remainingVaultBalance - claimed).coerceAtLeast(0L)

        viewModel.consumeBlock(
            onChainRemainingMicroUsdc = remainingVaultBalance,
            progressBarBalanceMicroUsdc = progressBarBalanceMicroUsdc,
        )

        val voucherJson =
            MppPayments.createVoucherJson(
                sessionId = sessionId,
                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                viewerAddress = viewerAddress,
                creatorAddress = creatorAddress,
                blocksConsumed = blocksConsumed,
                totalAmountClaimed = claimed,
                remainingMicroUsdc = progressBarBalanceMicroUsdc,
            )
        sendMessage(voucherJson)

        val shouldSettleByCadence = MppPayments.shouldAttemptVoucherSettlement(blocksConsumed)
        if (!shouldSettleByCadence) {
            Log.e(
                tag,
                "[SESSION_VAULT_CLAIM_SKIP] reason=cadence_not_reached session=$sessionId blocks=$blocksConsumed remainingVault=$remainingVaultBalance progress=$progressBarBalanceMicroUsdc",
            )
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                Log.e(
                    tag,
                    "[SESSION_VAULT_CLAIM_ATTEMPT] session=$sessionId blocks=$blocksConsumed claimedMicroUsdc=$claimed viewer=$viewerAddress creator=$creatorAddress",
                )

                val claimSignature =
                    getCreatorVoucherSignatureBase64()
                        ?.let {
                            runCatching {
                                Base64
                                    .getDecoder()
                                    .decode(it)
                            }.getOrNull()
                        }

                val settlementResult =
                    runCatching {
                        val signer =
                            buildCreatorWalletSigner(creatorAddress)
                                ?: error("Unsupported creator account")
                        MppPayments
                            .claimVoucher(
                                signer = signer,
                                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                viewerAddress = viewerAddress,
                                totalAmountClaimedMicroUsdc = claimed,
                                signature =
                                    claimSignature
                                        ?: MppPayments.buildClaimMessage(
                                            RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                            claimed,
                                        ),
                            ).getOrThrow()
                    }

                settlementResult
                    .onSuccess { txId ->
                        Log.e(
                            tag,
                            "[SESSION_VAULT_CLAIM_OK] txId=$txId session=$sessionId blocks=$blocksConsumed claimedMicroUsdc=$claimed",
                        )
                    }.onFailure {
                        Log.e(
                            tag,
                            "[SESSION_VAULT_CLAIM_ERR] session=$sessionId blocks=$blocksConsumed claimedMicroUsdc=$claimed",
                            it,
                        )
                    }
            }
        }

        if (progressBarBalanceMicroUsdc <= 0L) {
            Log.d(tag, "💰 Funds depleted after $blocksConsumed blocks")
            stop()

            val depletedJson =
                """{"reference":"liquid:payment:depleted","id":"$sessionId","totalBlocksWatched":$blocksConsumed,"totalConsumedMicroAlgos":${blocksConsumed * 100_000L}}"""
            sendMessage(depletedJson)
            return
        }

        val balanceJson =
            MppPayments.createBalanceUpdateJson(
                sessionId = sessionId,
                blocksConsumed = blocksConsumed,
                remainingMicroUsdc = progressBarBalanceMicroUsdc,
            )
        sendMessage(balanceJson)
        Log.d(tag, "💰 Sent balance update: ${MppPayments.remainingUsdcFromMicroAlgos(remainingVaultBalance)} USDC remaining")
    }
}
