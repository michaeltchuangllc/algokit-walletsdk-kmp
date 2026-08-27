package com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager

import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamViewer
import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds

class MppPaymentViewerManager(
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
) {
    companion object {
        private const val TAG = "MppPaymentViewerManager"
        private const val DISABLE_VIEWER_UPDATE_VOUCHER_FOR_DEBUG = false
    }

    data class StartParams(
        val dataChannel: RtcDataChannel,
        val viewerAddress: String,
        val scope: CoroutineScope,
        val signer: MppWalletSigner,
        val mppNetwork: String,
        val sessionVaultAppId: Long,
        val requestMppConsent: suspend (ConsentTerms) -> ConsentApproval,
        val setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
        val signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
        val onChatMessageReceived: (ChatMessage) -> Unit = {},
    )

    private data class VaultFundingResult(
        val result: Result<String>,
        val openedSession: Boolean,
    )

    private var liquidStreamViewer: LiquidStreamViewer? = null
    private var viewerOnChainRefreshJob: Job? = null
    private var viewerAuthorizedSignerPublicKey: ByteArray? = null
    private var viewerVoucherSessionId: String? = null
    private var viewerVoucherBlocksConsumed: Int = 0
    private var viewerPaidBlocksConsumed: Int = 0
    private var viewerFreeBlocksConsumed: Int = 0
    private var viewerVoucherClaimedMicroUsdc: Long = 0L
    private var viewerVoucherCapLoggedSessionId: String? = null
    private var pendingPayment: Boolean = false
    private var currentStreamCostMicroUsdc: Long? = null

    fun markPaymentPending() {
        pendingPayment = true
        Napier.d("[PAYMENT_PENDING_SET] pendingPayment=$pendingPayment", tag = TAG)
    }

    fun clearPendingPayment() {
        pendingPayment = false
        Napier.d("[PAYMENT_PENDING_CLEARED] pendingPayment=$pendingPayment", tag = TAG)
    }

    fun sendChatMessage(message: ChatMessage) {
        liquidStreamViewer?.sendChatMessage(message)
    }

    fun updateStreamCost(cost: Long) {
        currentStreamCostMicroUsdc = cost
        Napier.d("[VIEWER_STREAM_COST_UPDATED] cost=$cost", tag = TAG)
    }

    fun start(params: StartParams) {
        val viewerAddress = params.viewerAddress
        val signer = params.signer
        val sessionVaultAppId = params.sessionVaultAppId

        viewerAuthorizedSignerPublicKey = signer.authorizedSignerPublicKey
        stopViewerOnChainRefresh()
        liquidStreamViewer?.terminate()
        viewerVoucherSessionId = null
        viewerVoucherBlocksConsumed = 0
        viewerPaidBlocksConsumed = 0
        viewerFreeBlocksConsumed = 0
        viewerVoucherClaimedMicroUsdc = 0L
        viewerVoucherCapLoggedSessionId = null
        clearPendingPayment()

        Napier.d(
            "[VIEWER_MPP_CREATE_VIEWER] viewer=$viewerAddress network=${params.mppNetwork}",
            tag = TAG,
        )

        liquidStreamViewer =
            LiquidStreamViewer(
                dataChannel = params.dataChannel,
                mppClientConfig =
                    MppClientConfig(
                        network = params.mppNetwork,
                        signer = signer,
                    ),
                consentHandler =
                    object : ConsentHandler {
                        override suspend fun requestConsent(terms: ConsentTerms): ConsentApproval {
                            Napier.d(
                                "[VIEWER_MPP_CONSENT_REQUEST] viewer=$viewerAddress amount=${terms.amount} asset=${terms.asset} network=${terms.network} gating=${terms.gatingMode}",
                                tag = TAG,
                            )
                            val existingOnChainBalance =
                                getRemainingSessionVaultBalanceUseCase(
                                    GetRemainingSessionVaultBalanceUseCase.Params(
                                        viewerAddress = viewerAddress,
                                        appId = sessionVaultAppId,
                                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                    ),
                                ).getOrDefault(0L)

                            if (existingOnChainBalance > 0L) {
                                clearPendingPayment()
                                params.setViewerSessionVaultProgress(existingOnChainBalance, existingOnChainBalance)
                                Napier.d(
                                    "[VIEWER_SESSION_VAULT_FUNDED] balanceMicroUsdc=$existingOnChainBalance balanceUsdc=${existingOnChainBalance / 1_000_000.0} action=skip_payment_modal",
                                    tag = TAG,
                                )
                                startViewerOnChainRefresh(
                                    scope = params.scope,
                                    viewerAddress = viewerAddress,
                                    sessionVaultAppId = sessionVaultAppId,
                                    authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                    setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                                )
                                return ConsentApproval(
                                    approved = true,
                                    autoPaySegments = true,
                                    budgetCap =
                                        BudgetCap(
                                            amount = existingOnChainBalance.toString(),
                                            asset = "USDC",
                                        ),
                                )
                            }

                            val approval = params.requestMppConsent(terms)
                            Napier.d(
                                "[VIEWER_MPP_CONSENT_RESULT] viewer=$viewerAddress approved=${approval.approved} autoPay=${approval.autoPaySegments} budget=${approval.budgetCap?.amount}",
                                tag = TAG,
                            )
                            if (!approval.approved) return approval

                            val depositMicroUsdc =
                                approval.budgetCap
                                    ?.amount
                                    ?.toLongOrNull()
                                    ?.takeIf { it > 0L }
                                    ?: 1_000_000L
                            markPaymentPending()
                            val funding =
                                fundSessionVault(
                                    signer = signer,
                                    viewerAddress = viewerAddress,
                                    depositMicroUsdc = depositMicroUsdc,
                                    logContext = "initialConsent",
                                )

                            funding.result
                                .onSuccess { txId ->
                                    Napier.d(
                                        "[VIEWER_SESSION_VAULT_DEPOSIT_OK] txId=$txId action=${if (funding.openedSession) "open" else "top_up"} pendingPayment=$pendingPayment",
                                        tag = TAG,
                                    )

                                    val onChainRemaining =
                                        getRemainingSessionVaultBalanceUseCase(
                                            GetRemainingSessionVaultBalanceUseCase.Params(
                                                viewerAddress = viewerAddress,
                                                appId = sessionVaultAppId,
                                                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                            ),
                                        ).getOrDefault(0L)
                                    if (onChainRemaining > 0L) {
                                        clearPendingPayment()
                                    }
                                    params.setViewerSessionVaultProgress(onChainRemaining, onChainRemaining)
                                    startViewerOnChainRefresh(
                                        scope = params.scope,
                                        viewerAddress = viewerAddress,
                                        sessionVaultAppId = sessionVaultAppId,
                                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                        setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                                    )
                                }.onFailure { err ->
                                    clearPendingPayment()
                                    Napier.e("[VIEWER_SESSION_VAULT_DEPOSIT_ERR]", err, tag = TAG)
                                }

                            return approval
                        }
                    },
                clientConfig = ClientConfig(autoPaySegments = false),
            ).also { viewer ->
                viewer.rtcClient.onDataChannelOpen = {
                    val pubKeyBytes = Base64.encode(signer.authorizedSignerPublicKey)
                    viewer.rtcClient.sendHello(viewer = viewerAddress, viewerPublicKey = pubKeyBytes)
                    Napier.d("[VIEWER_HELLO_SENT] viewer=$viewerAddress pubKeyLen=${pubKeyBytes.length}", tag = TAG)
                }

                viewer.rtcClient.onPaymentRequested = { request ->
                    Napier.d(
                        "[VIEWER_PAYMENT_REQUEST_RECEIVED] session=${request.id} payTo=${request.payTo} amount=${request.amount} asset=${request.asset}",
                        tag = TAG,
                    )
                }

                viewer.rtcClient.onPaymentReceipt = { receipt ->
                    params.scope.launch {
                        handlePaymentReceipt(
                            receiptSessionId = receipt.sessionId,
                            receiptSegmentIndex = receipt.segmentIndex,
                            receiptAmount = receipt.amount,
                            receiptPayFrom = receipt.payFrom,
                            receiptPayTo = receipt.payTo,
                            txId = receipt.txId,
                            viewerAddress = viewerAddress,
                            sessionVaultAppId = sessionVaultAppId,
                            signer = signer,
                            signFido2Challenge = params.signFido2Challenge,
                            setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                        )
                    }
                }

                viewer.rtcClient.onStreamGated = { reason ->
                    Napier.w("[VIEWER_STREAM_GATED] viewer=$viewerAddress reason=$reason", tag = TAG)
                    params.scope.launch {
                        handleStreamGated(
                            scope = params.scope,
                            viewerAddress = viewerAddress,
                            sessionVaultAppId = sessionVaultAppId,
                            signer = signer,
                            mppNetwork = params.mppNetwork,
                            requestMppConsent = params.requestMppConsent,
                            setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                        )
                    }
                }

                viewer.onChatMessageReceived = { chatMsg ->
                    params.onChatMessageReceived(chatMsg)
                }

                Napier.d("[VIEWER_MPP_START] viewer=$viewerAddress network=${params.mppNetwork}", tag = TAG)
                viewer.start()
                Napier.d("[VIEWER_MPP_STARTED] viewer=$viewerAddress", tag = TAG)
            }
    }

    fun startViewerOnChainRefresh(
        scope: CoroutineScope,
        viewerAddress: String,
        sessionVaultAppId: Long,
        authorizedSignerPublicKey: ByteArray? = null,
        setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
    ) {
        if (viewerAddress.isBlank()) {
            Napier.w("[VIEWER_SESSION_VAULT_REFRESH_SKIP] reason=blank_viewer", tag = TAG)
            return
        }
        // Napier.d("[VIEWER_SESSION_VAULT_REFRESH_START] viewer=$viewerAddress host=$sessionVaultHostAddress", tag = TAG)
        stopViewerOnChainRefresh()
        viewerOnChainRefreshJob =
            scope.launch {
                while (isActive) {
                    runCatching {
                        getRemainingSessionVaultBalanceUseCase(
                            GetRemainingSessionVaultBalanceUseCase.Params(
                                viewerAddress = viewerAddress,
                                appId = sessionVaultAppId,
                                authorizedSignerPublicKey = authorizedSignerPublicKey ?: viewerAuthorizedSignerPublicKey,
                            ),
                        ).getOrThrow()
                    }.onSuccess { remaining ->
                        if (remaining > 0L) {
                            clearPendingPayment()
                        }
                        setViewerSessionVaultProgress(remaining, remaining)
                        if ((remaining == 0L) && !pendingPayment) {
                            liquidStreamViewer?.rtcClient?.onStreamGated?.invoke("Session balance exhausted")
                        } else if (remaining == 0L) {
                            Napier.d(
                                "[VIEWER_SESSION_VAULT_REFRESH_PENDING] balanceMicroUsdc=0 action=skip_stream_gated",
                                tag = TAG,
                            )
                        }
                    }.onFailure { err ->
                        Napier.e(
                            "[VIEWER_SESSION_VAULT_REFRESH_ERR] viewer=$viewerAddress",
                            err,
                            tag = TAG,
                        )
                    }
                    delay(1000L.milliseconds)
                }
            }
    }

    fun stop() {
        stopViewerOnChainRefresh()
        liquidStreamViewer?.terminate()
        liquidStreamViewer = null
        viewerAuthorizedSignerPublicKey = null
        viewerVoucherSessionId = null
        viewerVoucherBlocksConsumed = 0
        viewerPaidBlocksConsumed = 0
        viewerFreeBlocksConsumed = 0
        viewerVoucherClaimedMicroUsdc = 0L
        viewerVoucherCapLoggedSessionId = null
        clearPendingPayment()
    }

    private suspend fun handlePaymentReceipt(
        receiptSessionId: String,
        receiptSegmentIndex: Int,
        receiptAmount: String,
        receiptPayFrom: String,
        receiptPayTo: String,
        txId: String,
        viewerAddress: String,
        sessionVaultAppId: Long,
        signer: MppWalletSigner,
        signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
        setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
    ) {
        val debit = currentStreamCostMicroUsdc ?: receiptAmount.toLongOrNull() ?: 0L
        val receiptViewerAddress = receiptPayFrom.ifBlank { viewerAddress }
        if (viewerVoucherSessionId != receiptSessionId) {
            viewerVoucherSessionId = receiptSessionId
            viewerVoucherBlocksConsumed = 0
            viewerPaidBlocksConsumed = 0
            viewerFreeBlocksConsumed = 0
            viewerVoucherClaimedMicroUsdc = 0L
            viewerVoucherCapLoggedSessionId = null
        }

        val progressSnapshot =
            safeApiCall("getSessionProgressSnapshot.onReceipt") {
                MppPayments.getSessionProgressSnapshotFromVault()
            }
        val currentBalance = progressSnapshot?.progressBalanceMicroUsdc ?: 0L

        if (currentBalance > 0) {
            if (debit > 0) {
                viewerPaidBlocksConsumed++
            } else {
                viewerFreeBlocksConsumed++
            }
            viewerVoucherBlocksConsumed += 1

            Napier.d(
                "[VIEWER_BLOCK_CONSUMED] session=$receiptSessionId paid=$viewerPaidBlocksConsumed free=$viewerFreeBlocksConsumed total=$viewerVoucherBlocksConsumed debit=$debit",
                tag = TAG,
            )
        } else {
            Napier.w(
                "[VIEWER_BLOCK_CONSUMED_SKIPPED_ZERO_BALANCE] session=$receiptSessionId balance=$currentBalance",
                tag = TAG,
            )
        }

        val blocksConsumed = viewerVoucherBlocksConsumed.coerceAtLeast(0)
        val voucherIncrement = debit.coerceAtLeast(0L)

        Napier.d(
            "[VIEWER_PAYMENT_RECEIPT_CALLBACK] session=$receiptSessionId segment=$receiptSegmentIndex amount=$receiptAmount txId=$txId",
            tag = TAG,
        )

        if (receiptViewerAddress.isNotBlank()) {
            val preUpdateDynamicData =
                safeApiCall("getSessionDynamicData.preUpdate") {
                    MppPayments.getSessionDynamicDataFromVault()
                }
            val preUpdateLatestVoucher = preUpdateDynamicData?.latestVoucherAmount ?: 0L
            val preUpdateLastSettled = preUpdateDynamicData?.lastSettled ?: 0L
            val preUpdateTotalDeposit = preUpdateDynamicData?.totalDeposit ?: 0L
            val hasOnChainSessionData = (preUpdateDynamicData != null) && (preUpdateTotalDeposit > 0L)
            val voucherBase = maxOf(viewerVoucherClaimedMicroUsdc, preUpdateLatestVoucher)
            val voucherClaimedRaw = (voucherBase + voucherIncrement).coerceAtLeast(0L)
            val minRequiredCumulative =
                if (voucherIncrement > 0) {
                    maxOf(preUpdateLatestVoucher, preUpdateLastSettled) + 1L
                } else {
                    maxOf(preUpdateLatestVoucher, preUpdateLastSettled)
                }
            val maxAllowedCumulative = if (hasOnChainSessionData) preUpdateTotalDeposit else Long.MAX_VALUE
            val voucherClaimed = voucherClaimedRaw.coerceAtLeast(minRequiredCumulative).coerceAtMost(maxAllowedCumulative)

            if ((voucherClaimedRaw > maxAllowedCumulative) && (viewerVoucherCapLoggedSessionId != receiptSessionId)) {
                viewerVoucherCapLoggedSessionId = receiptSessionId
                Napier.e(
                    "[VIEWER_VOUCHER_CLAMP_DEPOSIT] session=$receiptSessionId claimedRaw=$voucherClaimedRaw clampedClaimed=$voucherClaimed maxAllowedCumulative=$maxAllowedCumulative totalDeposit=$preUpdateTotalDeposit viewer=$receiptViewerAddress",
                    tag = TAG,
                )
            }

            viewerVoucherClaimedMicroUsdc = voucherClaimed
            val channelId = EscrowSessionVaultManagerClient.channelId ?: return
            val voucherSignature =
                runCatching {
                    val message =
                        MppPayments.buildClaimMessage(
                            totalAmountClaimedMicroUsdc = voucherClaimed,
                            channelId = channelId,
                        )
                    signFido2Challenge(message, viewerAddress)
                }.getOrNull()

            if ((voucherSignature != null) && voucherSignature.isNotEmpty()) {
                if (DISABLE_VIEWER_UPDATE_VOUCHER_FOR_DEBUG) {
                    Napier.d(
                        "[VIEWER_UPDATE_VOUCHER_DISABLED_DEBUG] session=$receiptSessionId segment=$receiptSegmentIndex claimed=$voucherClaimed viewer=$receiptViewerAddress",
                        tag = TAG,
                    )
                }

                // Since creator can now settle directly using the voucher signature,
                // we no longer REQUIRE an on-chain update from the viewer side.
                // We send the voucher immediately.
                updateAndSendVoucher(
                    receiptSessionId = receiptSessionId,
                    receiptSegmentIndex = receiptSegmentIndex,
                    receiptViewerAddress = receiptViewerAddress,
                    receiptPayTo = receiptPayTo,
                    sessionVaultAppId = sessionVaultAppId,
                    signer = signer,
                    voucherClaimed = voucherClaimed,
                    voucherSignature = voucherSignature,
                    blocksConsumed = blocksConsumed,
                )
            }
        }

        setViewerSessionVaultProgress(
            progressSnapshot?.remainingSettledMicroUsdc ?: 0L,
            progressSnapshot?.progressBalanceMicroUsdc ?: 0L,
        )
    }

    private suspend fun updateAndSendVoucher(
        receiptSessionId: String,
        receiptSegmentIndex: Int,
        receiptViewerAddress: String,
        receiptPayTo: String,
        sessionVaultAppId: Long,
        signer: MppWalletSigner,
        voucherClaimed: Long,
        voucherSignature: ByteArray,
        blocksConsumed: Int,
    ) {
        val progressSnapshot =
            safeApiCall("getSessionProgressSnapshot.preSend") {
                MppPayments.getSessionProgressSnapshotFromVault()
            }

        val voucherJson =
            MppPayments.createVoucherJson(
                sessionId = receiptSessionId,
                viewerAddress = receiptViewerAddress,
                viewerPublicKey = signer.authorizedSignerPublicKey,
                creatorAddress = receiptPayTo,
                blocksConsumed = blocksConsumed,
                totalAmountUsed = voucherClaimed,
                remainingMicroUsdc = progressSnapshot?.progressBalanceMicroUsdc ?: 0L,
                signatureBase64 = MppPayments.serializeVoucherSignature(voucherSignature),
                appId = sessionVaultAppId,
            )
        Napier.e(
            "[SESSION_VAULT_VOUCHER_SEND] session=$receiptSessionId segment=$receiptSegmentIndex claimedAmountMicroUsdc=$voucherClaimed viewer=$receiptViewerAddress sigLen=${voucherSignature.size}",
            tag = TAG,
        )
        liquidStreamViewer?.rtcClient?.sendVoucher(voucherJson)
    }

    private suspend fun handleStreamGated(
        scope: CoroutineScope,
        viewerAddress: String,
        sessionVaultAppId: Long,
        signer: MppWalletSigner,
        mppNetwork: String,
        requestMppConsent: suspend (ConsentTerms) -> ConsentApproval,
        setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
    ) {
        runCatching {
            val approval =
                requestMppConsent(
                    ConsentTerms(
                        gatingMode = GatingMode.PARTIAL_TIME,
                        amount = MppPayments.voucherSettleWindowMicroUsdc().toString(),
                        asset = "USDC",
                        network = mppNetwork,
                        segmentDuration = 3,
                    ),
                )
            if (!approval.approved) return@runCatching

            val depositMicroUsdc =
                approval.budgetCap
                    ?.amount
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: 1_000_000L
            markPaymentPending()
            val funding =
                fundSessionVault(
                    signer = signer,
                    viewerAddress = viewerAddress,
                    depositMicroUsdc = depositMicroUsdc,
                    logContext = "streamGated",
                )

            funding.result
                .onSuccess {
                    val onChainRemaining =
                        getRemainingSessionVaultBalanceUseCase(
                            GetRemainingSessionVaultBalanceUseCase.Params(
                                viewerAddress = viewerAddress,
                                appId = sessionVaultAppId,
                                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                            ),
                        ).getOrDefault(0L)
                    if (onChainRemaining > 0L) {
                        clearPendingPayment()
                    }
                    setViewerSessionVaultProgress(onChainRemaining, onChainRemaining)
                    startViewerOnChainRefresh(
                        scope = scope,
                        viewerAddress = viewerAddress,
                        sessionVaultAppId = sessionVaultAppId,
                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                        setViewerSessionVaultProgress = setViewerSessionVaultProgress,
                    )
                    liquidStreamViewer?.rtcClient?.extendBudget(additionalMicroUsdc = depositMicroUsdc, asset = "USDC")
                    liquidStreamViewer?.rtcClient?.notifyVaultFunded(sessionId = viewerVoucherSessionId ?: "")
                }.onFailure { err ->
                    clearPendingPayment()
                    Napier.e("[VIEWER_STREAM_GATED_DEPOSIT_ERR] viewer=$viewerAddress", err, tag = TAG)
                }
        }.onFailure { err ->
            Napier.e("[VIEWER_STREAM_GATED_CONSENT_ERR] viewer=$viewerAddress", err, tag = TAG)
        }
    }

    private suspend fun fundSessionVault(
        signer: MppWalletSigner,
        viewerAddress: String,
        depositMicroUsdc: Long,
        logContext: String,
    ): VaultFundingResult {
        val existingSessionData =
            safeApiCall("getSessionDynamicData.$logContext") {
                withContext(Dispatchers.IO) {
                    MppPayments.getSessionDynamicDataFromVault()
                }
            }
        if (existingSessionData != null) {
            return VaultFundingResult(
                result =
                    MppPayments.topUpSessionVault(
                        signer = signer,
                        additionalDepositMicroUsdc = depositMicroUsdc,
                    ),
                openedSession = false,
            )
        }

        val openResult =
            MppPayments.openSessionAndDeposit(
                signer = signer,
                viewerAddress = viewerAddress,
                depositAmountMicroUsdc = depositMicroUsdc,
            )
        openResult.onSuccess {
            MppPayments
                .setAuthorizedSignerForSession(
                    signer = signer,
                    viewerAddress = viewerAddress,
                    authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                ).onFailure { err ->
                    Napier.e(
                        "[VIEWER_SET_AUTH_SIGNER_ERR] viewer=$viewerAddress",
                        err,
                        tag = TAG,
                    )
                }
        }
        return VaultFundingResult(result = openResult, openedSession = true)
    }

    private fun stopViewerOnChainRefresh() {
        viewerOnChainRefreshJob?.cancel()
        viewerOnChainRefreshJob = null
    }

    private suspend fun <T> safeApiCall(
        apiName: String,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Napier.e("[VIEWER_API_ERR] api=$apiName", t, tag = TAG)
            null
        }
}
