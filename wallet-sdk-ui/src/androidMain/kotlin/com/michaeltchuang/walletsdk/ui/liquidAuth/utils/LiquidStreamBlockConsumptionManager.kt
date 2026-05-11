package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import android.util.Log
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.AndroidSessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.usecases.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Base64

internal class LiquidStreamBlockConsumptionManager(
    private val tag: String,
    private val getViewModel: () -> LiquidAuthOfferViewModel?,
    private val getActiveViewerAddress: () -> String?,
    private val getActiveCreatorAddress: () -> String?,
    private val getCreatorVoucherClaimSnapshot: () -> CreatorVoucherClaimSnapshot?,
    private val buildCreatorWalletSigner: suspend (String) -> MppWalletSigner?,
    private val sendMessage: (String) -> Unit,
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase =
        GetRemainingSessionVaultBalanceUseCase(AndroidSessionVaultBalanceRepository()),
) {
    data class CreatorVoucherClaimSnapshot(
        val sessionId: String,
        val viewerAddress: String,
        val viewerPublicKeyBase64: String,
        val signatureBase64: String,
        val totalAmountClaimedMicroUsdc: Long,
    )

    private var blockDrivenConsumptionJob: Job? = null
    private var blocksConsumed: Int = 0
    private var currentSessionId: String? = null
    private var consecutiveZeroProgressBlocks: Int = 0

    companion object {
        private const val ZERO_BALANCE_GRACE_BLOCKS = 3
        private const val ALLOWED_VOUCHER_LAG_MICRO_USDC = 300_000L
    }

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

        val viewModel =
            getViewModel() ?: run {
                Log.e(tag, "[SESSION_VAULT_BLOCK_LOOP_START_SKIP] reason=viewModel_null session=$sessionId")
                return
            }
        currentSessionId = sessionId
        blocksConsumed = 0
        consecutiveZeroProgressBlocks = 0

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

                        val claimSnapshot = getCreatorVoucherClaimSnapshot()
                        if (claimSnapshot == null) {
                            lastObservedBlock = blockNumber
                            Log.e(
                                tag,
                                "[SESSION_VAULT_BLOCK_WAITING_FOR_CLAIM_SNAPSHOT] session=$sessionId block=$blockNumber previous=$previous action=hold_consumption_until_viewer_voucher",
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
        consecutiveZeroProgressBlocks = 0
    }

    private suspend fun consumeBlock() {
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
        val snapshotSignerPublicKey =
            getCreatorVoucherClaimSnapshot()
                ?.viewerPublicKeyBase64
                ?.takeIf { it.isNotBlank() }
                ?.let { encoded -> runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() }

        val remainingVaultBalance =
            if (viewerAddress == null) {
                Log.e(tag, "[SESSION_VAULT_CLAIM_SKIP] reason=viewer_missing session=$sessionId blocks=$blocksConsumed")
                0L
            } else {
                Log.e(
                    tag,
                    "[SESSION_VAULT_REMAINING_FETCH] session=$sessionId blocks=$blocksConsumed viewer=$viewerAddress appId=${RailMppConstants.MPP_SESSION_VAULT_APP_ID} signerKeyPresent=${snapshotSignerPublicKey != null}}",
                )
                getRemainingSessionVaultBalanceUseCase(
                    GetRemainingSessionVaultBalanceUseCase.Params(
                        viewerAddress = viewerAddress,
                        hostAddress = creatorAddress,
                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                        authorizedSignerPublicKey = snapshotSignerPublicKey,
                    ),
                ).getOrDefault(0L)
            }

        val used = MppPayments.computeVoucherMicroUsdcUsage(blocksConsumed)
        val progressBarBalanceMicroUsdc = (remainingVaultBalance - used).coerceAtLeast(0L)

        viewModel.consumeBlock(
            onChainRemainingMicroUsdc = remainingVaultBalance,
            progressBarBalanceMicroUsdc = progressBarBalanceMicroUsdc,
        )

        val viewerPublicKey =
            viewerAddress
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching {
                        com.algorand.algosdk.crypto
                            .Address(it)
                            .bytes
                    }.getOrDefault(ByteArray(0))
                }
                ?: ByteArray(0)

        val voucherJson =
            MppPayments.createVoucherJson(
                sessionId = sessionId,
                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                viewerAddress = viewerAddress.orEmpty(),
                viewerPublicKey = viewerPublicKey,
                creatorAddress = creatorAddress,
                blocksConsumed = blocksConsumed,
                totalAmountUsed = used,
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
                    "[SESSION_VAULT_CLAIM_ATTEMPT] session=$sessionId blocks=$blocksConsumed usedMicroUsdc=$used viewer=$viewerAddress creator=$creatorAddress",
                )

                val claimSnapshot =
                    getCreatorVoucherClaimSnapshot() ?: run {
                        Log.e(
                            tag,
                            "[SESSION_VAULT_CLAIM_SKIP] reason=claim_snapshot_missing session=$sessionId blocks=$blocksConsumed usedMicroUsdc=$used",
                        )
                        return@launch
                    }

                if (claimSnapshot.sessionId != sessionId) {
                    Log.e(
                        tag,
                        "[SESSION_VAULT_CLAIM_SKIP] reason=claim_snapshot_session_mismatch session=$sessionId snapshotSession=${claimSnapshot.sessionId}",
                    )
                    return@launch
                }

                if (claimSnapshot.viewerAddress != viewerAddress) {
                    Log.e(
                        tag,
                        "[SESSION_VAULT_CLAIM_SKIP] reason=claim_snapshot_viewer_mismatch session=$sessionId expectedViewer=$viewerAddress snapshotViewer=${claimSnapshot.viewerAddress}",
                    )
                    return@launch
                }

                val signatureBytes =
                    runCatching {
                        Base64
                            .getDecoder()
                            .decode(claimSnapshot.signatureBase64)
                    }.getOrNull() ?: run {
                        Log.e(
                            tag,
                            "[SESSION_VAULT_CLAIM_SKIP] reason=signature_decode_failed session=$sessionId blocks=$blocksConsumed usedMicroUsdc=$used",
                        )
                        return@launch
                    }

                val signerPublicKey = Base64.getDecoder().decode(claimSnapshot.viewerPublicKeyBase64)
                val signedTotalAmount = claimSnapshot.totalAmountClaimedMicroUsdc.coerceAtLeast(0L)
                val channelId =
                    MppPayments.deriveChannelId(
                        viewerAddress = claimSnapshot.viewerAddress,
                        hostAddress = creatorAddress,
                        authorizedSignerPublicKey = signerPublicKey,
                    )
                val claimMessageHash =
                    MppPayments.buildClaimMessageHashHex(
                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                        channelId = channelId,
                        totalAmountClaimedMicroUsdc = signedTotalAmount,
                    )
                val signatureHash = MppPayments.hashHex(signatureBytes)
                val isEd25519Signature = signatureBytes.size == 64
                val creatorLocalVerify =
                    if (isEd25519Signature) {
                        MppPayments.verifyClaimSignatureLocally(
                            viewerAddress = claimSnapshot.viewerAddress,
                            appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                            channelId = channelId,
                            totalAmountClaimedMicroUsdc = signedTotalAmount,
                            signature = signatureBytes,
                        )
                    } else {
                        true
                    }
                Log.e(
                    tag,
                    "[CREATOR_CLAIM_MSG_HASH] session=$sessionId appId=${RailMppConstants.MPP_SESSION_VAULT_APP_ID} totalAmountClaimedMicroUsdc=$signedTotalAmount hash=$claimMessageHash viewer=${claimSnapshot.viewerAddress} sigHash=$signatureHash localVerify=$creatorLocalVerify localVerifySkippedForFalcon=${!isEd25519Signature} sigLen=${signatureBytes.size}",
                )
                if (signedTotalAmount < used) {
                    Log.w(
                        tag,
                        "[SESSION_VAULT_CLAIM_BEHIND_CONTINUE] session=$sessionId blocks=$blocksConsumed usedMicroUsdc=$used signedTotalMicroUsdc=$signedTotalAmount action=settle_latest_signed_voucher",
                    )
                }

                Log.e(
                    tag,
                    "[SESSION_VAULT_VOUCHER_VALIDATED] session=$sessionId blocks=$blocksConsumed viewer=${claimSnapshot.viewerAddress} cumulativeValid=true signatureMatch=$creatorLocalVerify signedTotalMicroUsdc=$signedTotalAmount usedMicroUsdc=$used localMaxDepositGuardDisabled=true",
                )

                val settlementResult =
                    runCatching {
                        val signer =
                            buildCreatorWalletSigner(creatorAddress)
                                ?: error("Unsupported creator account")

                        val onChainDynamicData =
                            MppPayments.getSessionDynamicDataFromVault(
                                viewerAddress = claimSnapshot.viewerAddress,
                                hostAddress = creatorAddress,
                                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                authorizedSignerPublicKey = signerPublicKey,
                            )
                        val onChainLatestVoucher = onChainDynamicData?.latestVoucherAmount ?: 0L
                        val onChainLastSettled = onChainDynamicData?.lastSettled ?: 0L
                        val onChainUnclaimed = (onChainLatestVoucher - onChainLastSettled).coerceAtLeast(0L)
                        val viewerLag = (signedTotalAmount - onChainLatestVoucher).coerceAtLeast(0L)
                        val allowLagWindow = viewerLag <= ALLOWED_VOUCHER_LAG_MICRO_USDC
                        if (onChainUnclaimed <= 0L && !allowLagWindow) {
                            Log.e(
                                tag,
                                "[SESSION_VAULT_CLAIM_BACKFILL_VOUCHER] session=$sessionId blocks=$blocksConsumed signedTotalMicroUsdc=$signedTotalAmount onChainLastSettled=$onChainLastSettled onChainLatestVoucher=$onChainLatestVoucher viewerLagMicroUsdc=$viewerLag lagWindowMicroUsdc=$ALLOWED_VOUCHER_LAG_MICRO_USDC",
                            )
                            MppPayments
                                .updateVoucherOnChain(
                                    signer = signer,
                                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                    viewerAddress = claimSnapshot.viewerAddress,
                                    hostAddress = creatorAddress,
                                    totalAmountUsedMicroUsdc = signedTotalAmount,
                                    signature = signatureBytes,
                                    authorizedSignerPublicKey = signerPublicKey,
                                ).getOrThrow()
                        } else if (onChainUnclaimed <= 0L) {
                            Log.e(
                                tag,
                                "[SESSION_VAULT_CLAIM_LAG_WINDOW] session=$sessionId blocks=$blocksConsumed signedTotalMicroUsdc=$signedTotalAmount onChainLastSettled=$onChainLastSettled onChainLatestVoucher=$onChainLatestVoucher viewerLagMicroUsdc=$viewerLag lagWindowMicroUsdc=$ALLOWED_VOUCHER_LAG_MICRO_USDC",
                            )
                        }

                        if (RailMppConstants.ENABLE_CREATOR_DEBUG_VERIFY_HELPER_ON_CHAIN) {
                            Log.e(
                                tag,
                                "[CREATOR_VERIFY_HELPER_ATTEMPT] session=$sessionId totalAmountClaimedMicroUsdc=$signedTotalAmount viewer=${claimSnapshot.viewerAddress}",
                            )
                            val helperResult =
                                MppPayments
                                    .debugVerifyClaimVoucherSignatureOnChain(
                                        signer = signer,
                                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                        viewerAddress = claimSnapshot.viewerAddress,
                                        hostAddress = creatorAddress,
                                        totalAmountClaimedMicroUsdc = signedTotalAmount,
                                        signature = signatureBytes,
                                    ).onSuccess { result ->
                                        Log.e(
                                            tag,
                                            "[CREATOR_VERIFY_HELPER_TX] session=$sessionId txId=${result.txId} verified=${result.verified} logCount=${result.logCount} confirmedRound=${result.confirmedRound} totalAmountClaimedMicroUsdc=$signedTotalAmount viewer=${claimSnapshot.viewerAddress}",
                                        )
                                    }.onFailure {
                                        Log.e(
                                            tag,
                                            "[CREATOR_VERIFY_HELPER_ERR] session=$sessionId totalAmountClaimedMicroUsdc=$signedTotalAmount viewer=${claimSnapshot.viewerAddress}",
                                            it,
                                        )
                                    }.getOrNull()

                            Log.e(
                                tag,
                                "[CREATOR_VERIFY_HELPER_RESULT] session=$sessionId helperVerified=${helperResult?.verified} totalAmountClaimedMicroUsdc=$signedTotalAmount viewer=${claimSnapshot.viewerAddress}",
                            )
                            if (helperResult?.verified != true) {
                                Log.e(
                                    tag,
                                    "[SESSION_VAULT_CLAIM_SKIP] reason=helper_not_verified session=$sessionId blocks=$blocksConsumed usedMicroUsdc=$used signedTotalMicroUsdc=$signedTotalAmount helperVerified=${helperResult?.verified}",
                                )
                                return@runCatching "helper_not_verified"
                            }
                        } else {
                            Log.e(
                                tag,
                                "[CREATOR_VERIFY_HELPER_SKIP] session=$sessionId reason=feature_flag_disabled totalAmountClaimedMicroUsdc=$signedTotalAmount viewer=${claimSnapshot.viewerAddress}",
                            )
                        }

                        val postUpdateDynamicData =
                            MppPayments.getSessionDynamicDataFromVault(
                                viewerAddress = claimSnapshot.viewerAddress,
                                hostAddress = creatorAddress,
                                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                authorizedSignerPublicKey = signerPublicKey,
                            )
                        val postUpdateLatestVoucher = postUpdateDynamicData?.latestVoucherAmount ?: 0L
                        val postUpdateLastSettled = postUpdateDynamicData?.lastSettled ?: 0L
                        val postUpdateUnclaimed = (postUpdateLatestVoucher - postUpdateLastSettled).coerceAtLeast(0L)

                        Log.e(
                            tag,
                            "[SESSION_VAULT_SETTLE_LATEST_PRECHECK] session=$sessionId viewer=${claimSnapshot.viewerAddress} creator=$creatorAddress totalDeposit=${postUpdateDynamicData?.totalDeposit} latestVoucherAmount=$postUpdateLatestVoucher lastSettled=$postUpdateLastSettled unclaimedVoucherAmount=$postUpdateUnclaimed signedTotalMicroUsdc=$signedTotalAmount",
                        )

                        if (postUpdateUnclaimed <= 0L) {
                            Log.e(
                                tag,
                                "[SESSION_VAULT_CLAIM_SKIP] reason=nothing_to_settle session=$sessionId blocks=$blocksConsumed usedMicroUsdc=$used signedTotalMicroUsdc=$signedTotalAmount latestVoucherAmount=$postUpdateLatestVoucher lastSettled=$postUpdateLastSettled",
                            )
                            return@runCatching "nothing_to_settle"
                        }

                        MppPayments
                            .settleLatestVoucher(
                                signer = signer,
                                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                viewerAddress = claimSnapshot.viewerAddress,
                                hostAddress = creatorAddress,
                                authorizedSignerPublicKey = signerPublicKey,
                            ).getOrThrow()
                    }

                settlementResult
                    .onSuccess { txId ->
                        Log.e(
                            tag,
                            "[SESSION_VAULT_CLAIM_OK] txId=$txId session=$sessionId blocks=$blocksConsumed usedMicroUsdc=$used",
                        )
                    }.onFailure {
                        Log.e(
                            tag,
                            "[SESSION_VAULT_CLAIM_ERR] session=$sessionId blocks=$blocksConsumed usedMicroUsdc=$used",
                            it,
                        )
                    }
            }
        }

        if (progressBarBalanceMicroUsdc <= 0L) {
            val hasSignerSnapshot = getCreatorVoucherClaimSnapshot() != null
            if (!hasSignerSnapshot) {
                Log.w(
                    tag,
                    "[SESSION_VAULT_DEPLETED_GUARD_SKIP] session=$sessionId blocks=$blocksConsumed reason=missing_signer_snapshot",
                )
                return
            }

            consecutiveZeroProgressBlocks++
            if (consecutiveZeroProgressBlocks < ZERO_BALANCE_GRACE_BLOCKS) {
                Log.w(
                    tag,
                    "[SESSION_VAULT_DEPLETED_DELAY] session=$sessionId blocks=$blocksConsumed zeroStreak=$consecutiveZeroProgressBlocks threshold=$ZERO_BALANCE_GRACE_BLOCKS",
                )
                return
            }

            Log.d(tag, "💰 Funds depleted after $blocksConsumed blocks")
            stop()

            val depletedJson =
                """{"reference":"liquid:payment:depleted","id":"$sessionId","totalBlocksWatched":$blocksConsumed,"totalConsumedMicroAlgos":${MppPayments.computeVoucherMicroUsdcUsage(
                    blocksConsumed,
                )}}"""
            sendMessage(depletedJson)
            return
        } else {
            consecutiveZeroProgressBlocks = 0
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
