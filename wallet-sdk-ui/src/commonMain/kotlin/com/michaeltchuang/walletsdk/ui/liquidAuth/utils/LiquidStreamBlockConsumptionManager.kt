package com.michaeltchuang.walletsdk.ui.liquidAuth.utils

import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.CreatorVoucherClaimSnapshot
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetMppVoucherNoteUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.VoucherSettlementPolicy
import com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthPaymentVoucherMessage
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared (commonMain) block-consumption + settlement manager for the Liquid Stream host/creator
 * side. Used identically by both Android and iOS actual [com.michaeltchuang.walletsdk.ui.liquidAuth.service.LiquidAuthConnectionManager]
 * implementations. Retains primary UI accounting; all financial writes are delegated to
 * [ViewerVaultBillingSession], just like additional viewers.
 */
internal class LiquidStreamBlockConsumptionManager(
    private val tag: String,
    private val getViewModel: () -> LiquidAuthOfferViewModel?,
    private val getActiveViewerAddress: () -> String?,
    private val getActiveCreatorAddress: () -> String?,
    private val getCreatorVoucherClaimSnapshot: () -> CreatorVoucherClaimSnapshot?,
    private val getIsPaidStreaming: () -> Boolean,
    private val buildCreatorWalletSigner: suspend (String) -> MppWalletSigner?,
    private val getMppVoucherNoteUseCase: GetMppVoucherNoteUseCase,
    private val voucherRepository: MppVoucherRepository,
) {
    companion object {
        private const val CHAIN_READ_TIMEOUT_MS = VoucherSettlementPolicy.CHAIN_READ_TIMEOUT_MS
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var blockDrivenConsumptionJob: Job? = null
    private var billing: ViewerVaultBillingSession? = null
    @Volatile
    private var billingGeneration = 0L
    // Ordered intake survives screen teardown and drains before a replacement session starts.
    private val billingCommands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (action in billingCommands) {
                try {
                    action()
                } catch (e: TimeoutCancellationException) {
                    Napier.e("Primary viewer billing timed out; persisted vouchers retained", e, tag = tag)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Napier.e("Primary viewer billing failed", e, tag = tag)
                }
            }
        }
    }

    private fun enqueueBilling(command: suspend () -> Unit) {
        billingCommands.trySend(command)
    }

    fun processPendingSettlements() {
        scope.launch {
            try {
                ViewerVaultBillingSession.recoverPending(
                    scope, voucherRepository, getMppVoucherNoteUseCase, buildCreatorWalletSigner,
                ) { Napier.e("Pending viewer settlement failed", it, tag = tag) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Pending voucher recovery failed", e, tag = tag)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun acceptVoucher(voucher: LiquidAuthPaymentVoucherMessage, network: String) {
        val creator = getActiveCreatorAddress() ?: return
        val viewer = voucher.viewerAddress ?: return
        val session = voucher.sessionId ?: return
        val key = voucher.viewerPublicKey?.copyOf() ?: return
        val channel = voucher.channelId?.copyOf() ?: return
        if (session != currentSessionId || (voucher.totalAmountClaimedMicroUsdc ?: 0L) <= 0L) return
        val round = getViewModel()?.currentBlockNumber?.value
        val generation = billingGeneration
        val paid = getIsPaidStreaming()
        val cost = getViewModel()?.currentCostPerBlockMicroUsdc ?: 0L
        // Keep primary note accounting identical to the existing balance-driven UI counters.
        val params = GetMppVoucherNoteUseCase.Params(
            channelId = Base64.encode(channel),
            startBlock = startRound,
            currentBlock = round ?: 0L,
            freeBlocks = freeBlocksConsumed.toLong(),
            paidBlocks = paidBlocksConsumed.toLong(),
            costPerPaidBlock = cost,
            settledAmount = lastSettledMicroUsdc,
            totalCumulativeAmount = voucher.totalAmountClaimedMicroUsdc ?: 0L,
            freeChatCount = freeChatCount,
            tipChatCount = tipChatCount,
            tipChatTotal = tipChatTotalMicroUsdc,
        )
        enqueueBilling {
            // Disconnect discards unvalidated backlog; already persisted work is drained below.
            if (generation != billingGeneration) return@enqueueBilling
            withTimeout(25_000.milliseconds) {
                val current = billing
                if (current != null) {
                    require(current.sessionId == session && current.viewerAddress == viewer &&
                        current.creatorAddress == creator && current.network == network &&
                        current.signerPublicKey.contentEquals(key)) { "Primary billing identity changed" }
                }
                val target = current ?: ViewerVaultBillingSession(
                    scope = scope,
                    sessionId = session,
                    viewerAddress = viewer,
                    creatorAddress = creator,
                    network = network,
                    signerPublicKey = key,
                    buildCreatorWalletSigner = buildCreatorWalletSigner,
                    onSnapshot = {},
                    onError = { Napier.e("Primary viewer settlement failed", it, tag = tag) },
                    voucherRepository = voucherRepository,
                    getMppVoucherNoteUseCase = getMppVoucherNoteUseCase,
                    payoutFrequencyBlocks = payoutFrequencyBlocks,
                ).also { billing = it }
                if (current == null) target.restorePending(channel)
                round?.let { target.onBlock(it, paid, cost).join() }
                if (target.acceptVoucher(voucher, force = true, noteParams = params) &&
                    currentSessionId == session && generation == billingGeneration) {
                    // Legacy primary UI reads still use this client; never set it from unvalidated input.
                    EscrowSessionVaultHybridManagerClient.channelId = channel
                }
            }
        }
    }

    /** Terminal disconnect, unlike stop() which is a resumable UI pause. */
    fun closeBilling() {
        billingGeneration++
        currentSessionId = null
        enqueueBilling {
            billing?.close(refundVault = true)?.join()
            billing = null
        }
    }

    @Volatile
    private var blocksConsumed: Int = 0

    @Volatile
    private var paidBlocksConsumed: Int = 0

    @Volatile
    private var freeBlocksConsumed: Int = 0

    @Volatile
    private var freeChatCount: Long = 0L

    @Volatile
    private var tipChatCount: Long = 0L

    @Volatile
    private var tipChatTotalMicroUsdc: Long = 0L

    @Volatile
    private var currentSessionId: String? = null

    fun recordChatMessage(message: ChatMessage) {
        val amountStr = message.amount
        val giftUsdc = amountStr?.toDoubleOrNull()
        if (giftUsdc != null && giftUsdc > 0.0) {
            tipChatCount++
            val giftMicroUsdc = (giftUsdc * 1_000_000.0).roundToLong()
            tipChatTotalMicroUsdc += giftMicroUsdc
            Napier.d("[SUPERCHAT_RECORDED] tipCount=$tipChatCount tipTotalMicroUsdc=$tipChatTotalMicroUsdc amount=$giftUsdc", tag = tag)
        } else {
            freeChatCount++
            Napier.d("[FREE_CHAT_RECORDED] freeCount=$freeChatCount", tag = tag)
        }
    }

    @Volatile
    private var startRound: Long = 0L

    @Volatile
    private var lastSettledMicroUsdc: Long = 0L

    @Volatile
    var payoutFrequencyBlocks: Int = 1
        set(value) {
            require(value > 0)
            field = value
            enqueueBilling { billing?.updatePayoutFrequencyBlocks(value)?.join() }
        }

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
        if (currentSessionId != null && currentSessionId != sessionId) closeBilling()

        val viewModel =
            getViewModel() ?: run {
                Napier.e(
                    "[SESSION_VAULT_BLOCK_LOOP_START_SKIP] " +
                        "reason=viewModel_null session=$sessionId",
                    tag = tag,
                )
                return
            }

        val isNewSession = currentSessionId != sessionId
        if (isNewSession) {
            blocksConsumed = 0
            paidBlocksConsumed = 0
            freeBlocksConsumed = 0
            freeChatCount = 0L
            tipChatCount = 0L
            tipChatTotalMicroUsdc = 0L
            startRound = 0L
            lastSettledMicroUsdc = 0L
        }
        currentSessionId = sessionId

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
                    val paid = getIsPaidStreaming()
                    val cost = viewModel.currentCostPerBlockMicroUsdc
                    enqueueBilling { billing?.onBlock(blockNumber, paid, cost)?.join() }

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

        enqueueBilling { billing?.requestSettlement()?.join() }

        blockDrivenConsumptionJob?.cancel()
        blockDrivenConsumptionJob = null

        getViewModel()?.stopRealtimeBlockNumberUpdates()

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
                        "[SESSION_VAULT_PROGRESS_FETCH_TIMEOUT_OR_ERR] session=$sessionId viewer=$viewerAddress creator=$creatorAddress timeoutMs=$CHAIN_READ_TIMEOUT_MS",
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

        if (progressBarBalanceMicroUsdc > 0) {
            val isPaid = getIsPaidStreaming()
            if (isPaid) {
                paidBlocksConsumed++
            } else {
                freeBlocksConsumed++
            }
            blocksConsumed++

            Napier.e(
                "[BLOCK_CONSUMED] session=$sessionId " +
                    "paid=$paidBlocksConsumed " +
                    "free=$freeBlocksConsumed " +
                    "total=$blocksConsumed" +
                    " isPaidStreaming=$isPaid",
                tag = tag,
            )
        } else {
            Napier.w(
                "[BLOCK_CONSUMED_SKIPPED_ZERO_BALANCE] session=$sessionId balance=$progressBarBalanceMicroUsdc",
                tag = tag,
            )
        }

        val lastSettled =
            progressSnapshot?.lastSettledMicroUsdc ?: 0L
        val start =
            progressSnapshot?.startRound ?: 0L

        lastSettledMicroUsdc = lastSettled
        startRound = start

        viewModel.consumeBlock(
            onChainRemainingMicroUsdc = remainingVaultBalance,
            progressBarBalanceMicroUsdc = progressBarBalanceMicroUsdc,
            lastSettledMicroUsdc = lastSettled,
            startRound = start,
            paidBlocks = paidBlocksConsumed,
            freeBlocks = freeBlocksConsumed,
        )
    }

}
