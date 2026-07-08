package com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager

import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamViewer
import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.LiquidDcMessages
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.HelloMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.toJson
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

class MppPaymentViewerManager(
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
) {
    companion object {
        private const val TAG = "MppPaymentViewerManager"
        private const val DISABLE_VIEWER_UPDATE_VOUCHER_FOR_DEBUG = false
        private const val CHAIN_WRITE_TIMEOUT_MS = 15_000L
    }

    data class StartParams(
        val dataChannel: RtcDataChannel,
        val viewerAddress: String,
        val hostAddress: String,
        val scope: CoroutineScope,
        val signer: MppWalletSigner,
        val mppNetwork: String,
        val sessionVaultAppId: Long,
        val requestMppConsent: suspend (ConsentTerms) -> ConsentApproval,
        val setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
        val signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
        val sendMessage: (String) -> Unit,
    )

    private var liquidStreamViewer: LiquidStreamViewer? = null
    private var viewerOnChainRefreshJob: Job? = null
    private var viewerAuthorizedSignerPublicKey: ByteArray? = null
    private var viewerVoucherSessionId: String? = null
    private var viewerVoucherBlocksConsumed: Int = 0
    private var viewerVoucherClaimedMicroUsdc: Long = 0L
    private var viewerVoucherCapLoggedSessionId: String? = null
    private var pendingPayment: Boolean = false

    fun markPaymentPending() {
        pendingPayment = true
        Napier.d("[VIEWER_SESSION_VAULT_PAYMENT_PENDING] pendingPayment=$pendingPayment", tag = TAG)
    }

    fun clearPendingPayment() {
        pendingPayment = false
    }

    fun start(params: StartParams) {
        val viewerAddress = params.viewerAddress
        val sessionVaultHostAddress = params.hostAddress
        val signer = params.signer
        val sessionVaultAppId = params.sessionVaultAppId
        val scope = params.scope

        viewerAuthorizedSignerPublicKey = signer.authorizedSignerPublicKey
        stopViewerOnChainRefresh()
        liquidStreamViewer?.terminate()
        viewerVoucherSessionId = null
        viewerVoucherBlocksConsumed = 0
        viewerVoucherClaimedMicroUsdc = 0L
        viewerVoucherCapLoggedSessionId = null
        pendingPayment = false

        Napier.d(
            "[VIEWER_MPP_CREATE_VIEWER] viewer=$viewerAddress host=$sessionVaultHostAddress network=${params.mppNetwork}",
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
                                "[VIEWER_MPP_CONSENT_REQUEST] viewer=$viewerAddress host=$sessionVaultHostAddress amount=${terms.amount} asset=${terms.asset} network=${terms.network} gating=${terms.gatingMode}",
                                tag = TAG,
                            )
                            val existingOnChainBalance =
                                getRemainingSessionVaultBalanceUseCase(
                                    GetRemainingSessionVaultBalanceUseCase.Params(
                                        viewerAddress = viewerAddress,
                                        hostAddress = sessionVaultHostAddress,
                                        appId = sessionVaultAppId,
                                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                    ),
                                ).getOrDefault(0L)

                            if (existingOnChainBalance > 0L) {
                                pendingPayment = false
                                params.setViewerSessionVaultProgress(existingOnChainBalance, existingOnChainBalance)
                                Napier.d(
                                    "[VIEWER_SESSION_VAULT_FUNDED] balanceMicroUsdc=$existingOnChainBalance balanceUsdc=${existingOnChainBalance / 1_000_000.0} action=skip_payment_modal",
                                    tag = TAG,
                                )
                                startViewerOnChainRefresh(
                                    scope = scope,
                                    viewerAddress = viewerAddress,
                                    hostAddress = sessionVaultHostAddress,
                                    sessionVaultAppId = sessionVaultAppId,
                                    authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                    setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                                )
                                return ConsentApproval(
                                    approved = true,
                                    autoPaySegments = true,
                                    budgetCap = BudgetCap(
                                        amount = existingOnChainBalance.toString(),
                                        asset = "USDC"
                                    ),
                                )
                            }

                            val approval = params.requestMppConsent(terms)
                            Napier.d(
                                "[VIEWER_MPP_CONSENT_RESULT] viewer=$viewerAddress host=$sessionVaultHostAddress approved=${approval.approved} autoPay=${approval.autoPaySegments} budget=${approval.budgetCap?.amount}",
                                tag = TAG,
                            )
                            if (!approval.approved) return approval

                            val depositMicroUsdc =
                                approval.budgetCap
                                    ?.amount
                                    ?.toLongOrNull()
                                    ?.takeIf { it > 0L }
                                    ?: 1_000_000L

                            MppPayments
                                .openSessionAndDeposit(
                                    signer = signer,
                                    viewerAddress = viewerAddress,
                                    creatorAddress = sessionVaultHostAddress,
                                    depositAmountMicroUsdc = depositMicroUsdc,
                                ).onSuccess { txId ->
                                    markPaymentPending()
                                    Napier.d("[VIEWER_SESSION_VAULT_DEPOSIT_OK] txId=$txId pendingPayment=$pendingPayment", tag = TAG)
                                    MppPayments
                                        .setAuthorizedSignerForSession(
                                            signer = signer,
                                            viewerAddress = viewerAddress,
                                    hostAddress = sessionVaultHostAddress,
                                    authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                        ).onFailure { err ->
                                            Napier.e(
                                                "[VIEWER_SET_AUTH_SIGNER_ERR] viewer=$viewerAddress host=$sessionVaultHostAddress",
                                                err,
                                                tag = TAG,
                                            )
                                        }

                                    val onChainRemaining =
                                        getRemainingSessionVaultBalanceUseCase(
                                            GetRemainingSessionVaultBalanceUseCase.Params(
                                                viewerAddress = viewerAddress,
                                                hostAddress = sessionVaultHostAddress,
                                                appId = sessionVaultAppId,
                                                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                            ),
                                        ).getOrDefault(0L)
                                    if (onChainRemaining > 0L) {
                                        pendingPayment = false
                                    }
                                    params.setViewerSessionVaultProgress(onChainRemaining, onChainRemaining)
                                    startViewerOnChainRefresh(
                                        scope = scope,
                                        viewerAddress = viewerAddress,
                                        hostAddress = sessionVaultHostAddress,
                                        sessionVaultAppId = sessionVaultAppId,
                                        authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                                        setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                                    )
                                }.onFailure { err ->
                                    pendingPayment = false
                                    Napier.e("[VIEWER_SESSION_VAULT_DEPOSIT_ERR]", err, tag = TAG)
                                }

                            return approval
                        }
                    },
                clientConfig = ClientConfig(autoPaySegments = false),
            ).also { viewer ->
                viewer.rtcClient.onDataChannelOpen = {
                    val pubKeyBytes = Base64.encode(signer.authorizedSignerPublicKey)
                    val helloMsg =
                        HelloMessage(
                            reference = LiquidDcMessages.REF_VIEWER_HELLO,
                            viewer = viewerAddress,
                            viewerPublicKey = pubKeyBytes,
                        )
                    params.sendMessage(helloMsg.toJson())
                    Napier.d("[VIEWER_HELLO_SENT] viewer=$viewerAddress pubKeyLen=${pubKeyBytes.length}", tag = TAG)
                }

                viewer.rtcClient.onPaymentRequested = { request ->
                    Napier.d(
                        "[VIEWER_PAYMENT_REQUEST_RECEIVED] session=${request.id} payTo=${request.payTo} amount=${request.amount} asset=${request.asset}",
                        tag = TAG,
                    )
                }

                viewer.rtcClient.onPaymentReceipt = { receipt ->
                    scope.launch {
                        handlePaymentReceipt(
                            receiptSessionId = receipt.sessionId,
                            receiptSegmentIndex = receipt.segmentIndex,
                            receiptAmount = receipt.amount,
                            receiptPayFrom = receipt.payFrom,
                            receiptPayTo = receipt.payTo,
                            txId = receipt.txId,
                            viewerAddress = viewerAddress,
                            hostAddress = sessionVaultHostAddress,
                            sessionVaultAppId = sessionVaultAppId,
                            signer = signer,
                            signFido2Challenge = params.signFido2Challenge,
                            sendMessage = params.sendMessage,
                            setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                        )
                    }
                }

                viewer.rtcClient.onStreamGated = { reason ->
                    Napier.w("[VIEWER_STREAM_GATED] viewer=$viewerAddress host=$sessionVaultHostAddress reason=$reason", tag = TAG)
                    scope.launch {
                        handleStreamGated(
                            scope = scope,
                            viewerAddress = viewerAddress,
                            hostAddress = sessionVaultHostAddress,
                            sessionVaultAppId = sessionVaultAppId,
                            signer = signer,
                            mppNetwork = params.mppNetwork,
                            requestMppConsent = params.requestMppConsent,
                            setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
                        )
                    }
                }

                Napier.d("[VIEWER_MPP_START] viewer=$viewerAddress host=$sessionVaultHostAddress network=${params.mppNetwork}", tag = TAG)
                viewer.start()
                Napier.d("[VIEWER_MPP_STARTED] viewer=$viewerAddress host=$sessionVaultHostAddress", tag = TAG)
            }
    }

    fun startViewerOnChainRefresh(
        scope: CoroutineScope,
        viewerAddress: String,
        hostAddress: String?,
        sessionVaultAppId: Long,
        authorizedSignerPublicKey: ByteArray? = null,
        setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
    ) {
        if (viewerAddress.isBlank()) {
            Napier.w("[VIEWER_SESSION_VAULT_REFRESH_SKIP] reason=blank_viewer", tag = TAG)
            return
        }
        val sessionVaultHostAddress = hostAddress?.takeIf { it.isNotBlank() } ?: return
       // Napier.d("[VIEWER_SESSION_VAULT_REFRESH_START] viewer=$viewerAddress host=$sessionVaultHostAddress", tag = TAG)
        stopViewerOnChainRefresh()
        viewerOnChainRefreshJob =
            scope.launch {
                while (isActive) {
                    runCatching {
                        getRemainingSessionVaultBalanceUseCase(
                            GetRemainingSessionVaultBalanceUseCase.Params(
                                viewerAddress = viewerAddress,
                                hostAddress = sessionVaultHostAddress,
                                appId = sessionVaultAppId,
                                authorizedSignerPublicKey = authorizedSignerPublicKey ?: viewerAuthorizedSignerPublicKey,
                            ),
                        ).getOrThrow()
                    }.onSuccess { remaining ->
                        if (remaining > 0L) {
                            pendingPayment = false
                        }
                        setViewerSessionVaultProgress(remaining, remaining)
                        if (remaining == 0L && !pendingPayment) {
                            liquidStreamViewer?.rtcClient?.onStreamGated?.invoke("Session balance exhausted")
                        } else if (remaining == 0L) {
                            Napier.d(
                                "[VIEWER_SESSION_VAULT_REFRESH_PENDING] balanceMicroUsdc=0 action=skip_stream_gated",
                                tag = TAG,
                            )
                        }
                    }.onFailure { err ->
                        Napier.e(
                            "[VIEWER_SESSION_VAULT_REFRESH_ERR] viewer=$viewerAddress host=$sessionVaultHostAddress",
                            err,
                            tag = TAG,
                        )
                    }
                    delay(1000L)
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
        viewerVoucherClaimedMicroUsdc = 0L
        viewerVoucherCapLoggedSessionId = null
        pendingPayment = false
    }

    private suspend fun handlePaymentReceipt(
        receiptSessionId: String,
        receiptSegmentIndex: Int,
        receiptAmount: String,
        receiptPayFrom: String,
        receiptPayTo: String,
        txId: String,
        viewerAddress: String,
        hostAddress: String,
        sessionVaultAppId: Long,
        signer: MppWalletSigner,
        signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
        sendMessage: (String) -> Unit,
        setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
    ) {
        val debit = receiptAmount.toLongOrNull() ?: 0L
        val receiptViewerAddress = receiptPayFrom.ifBlank { viewerAddress }
        if (viewerVoucherSessionId != receiptSessionId) {
            viewerVoucherSessionId = receiptSessionId
            viewerVoucherBlocksConsumed = 0
            viewerVoucherClaimedMicroUsdc = 0L
            viewerVoucherCapLoggedSessionId = null
        }
        viewerVoucherBlocksConsumed += 1
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
            val hasOnChainSessionData = preUpdateDynamicData != null && preUpdateTotalDeposit > 0L
            val voucherBase = maxOf(viewerVoucherClaimedMicroUsdc, preUpdateLatestVoucher)
            val voucherClaimedRaw = (voucherBase + voucherIncrement).coerceAtLeast(0L)
            val minRequiredCumulative = maxOf(preUpdateLatestVoucher, preUpdateLastSettled) + 1L
            val maxAllowedCumulative = if (hasOnChainSessionData) preUpdateTotalDeposit else Long.MAX_VALUE
            val voucherClaimed = voucherClaimedRaw.coerceAtLeast(minRequiredCumulative).coerceAtMost(maxAllowedCumulative)

            if (voucherClaimedRaw > maxAllowedCumulative && viewerVoucherCapLoggedSessionId != receiptSessionId) {
                viewerVoucherCapLoggedSessionId = receiptSessionId
                Napier.e(
                    "[VIEWER_VOUCHER_CLAMP_DEPOSIT] session=$receiptSessionId claimedRaw=$voucherClaimedRaw clampedClaimed=$voucherClaimed maxAllowedCumulative=$maxAllowedCumulative totalDeposit=$preUpdateTotalDeposit viewer=$receiptViewerAddress host=$hostAddress",
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

            if (voucherSignature != null && voucherSignature.isNotEmpty()) {
                if (DISABLE_VIEWER_UPDATE_VOUCHER_FOR_DEBUG) {
                    Napier.d(
                        "[VIEWER_UPDATE_VOUCHER_DISABLED_DEBUG] session=$receiptSessionId segment=$receiptSegmentIndex claimed=$voucherClaimed viewer=$receiptViewerAddress host=$hostAddress",
                        tag = TAG,
                    )
                } else {
                    updateAndSendVoucher(
                        receiptSessionId = receiptSessionId,
                        receiptSegmentIndex = receiptSegmentIndex,
                        receiptViewerAddress = receiptViewerAddress,
                        receiptPayTo = receiptPayTo,
                        hostAddress = hostAddress,
                        sessionVaultAppId = sessionVaultAppId,
                        signer = signer,
                        voucherClaimed = voucherClaimed,
                        voucherSignature = voucherSignature,
                        blocksConsumed = blocksConsumed,
                        preUpdateLatestVoucher = preUpdateLatestVoucher,
                        sendMessage = sendMessage,
                    )
                }
            }
        }

        val progressSnapshot =
            safeApiCall("getSessionProgressSnapshot.onReceipt") {
                MppPayments.getSessionProgressSnapshotFromVault()
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
        hostAddress: String,
        sessionVaultAppId: Long,
        signer: MppWalletSigner,
        voucherClaimed: Long,
        voucherSignature: ByteArray,
        blocksConsumed: Int,
        preUpdateLatestVoucher: Long,
        sendMessage: (String) -> Unit,
    ) {
        val updateVoucherOnChain =
            suspend {
                try {
                    Result.success(
                        withTimeout(CHAIN_WRITE_TIMEOUT_MS) {
                            MppPayments.updateVoucherOnChain(
                                signer = signer,
                                viewerAddress = receiptViewerAddress,
                                hostAddress = hostAddress,
                                totalAmountUsedMicroUsdc = voucherClaimed,
                                signature = voucherSignature,
                            ).getOrThrow()
                        },
                    )
                } catch (timeout: TimeoutCancellationException) {
                    Result.failure(IllegalStateException("updateVoucher timeout after ${CHAIN_WRITE_TIMEOUT_MS}ms", timeout))
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }

        var updateResult =
            if (voucherClaimed <= preUpdateLatestVoucher) {
                Result.success("SKIPPED_ALREADY_ONCHAIN")
            } else {
                updateVoucherOnChain()
            }

        if (updateResult.isFailure && !MppPayments.isDuplicateVoucherUpdateError(updateResult.exceptionOrNull()?.message.orEmpty())) {
            delay(350L)
            updateResult = updateVoucherOnChain()
        }

        if (updateResult.isFailure) {
            val errText = updateResult.exceptionOrNull()?.message.orEmpty()
            val missingSignerBox =
                errText.contains("box_len; bury 1; assert", ignoreCase = true) ||
                    errText.contains("Authorized signer public key not set yet", ignoreCase = true)
            if (missingSignerBox) {
                MppPayments.setAuthorizedSignerForSession(
                    signer = signer,
                    viewerAddress = receiptViewerAddress,
                    hostAddress = hostAddress,
                    authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                )
                delay(350L)
                updateResult = updateVoucherOnChain()
            }
        }

        val onChainDynamicData =
            safeApiCall("getSessionDynamicData.postUpdate") {
                MppPayments.getSessionDynamicDataFromVault()
            }
        val onChainLatestVoucher = onChainDynamicData?.latestVoucherAmount ?: 0L
        val duplicateVoucherUpdate = MppPayments.isDuplicateVoucherUpdateError(updateResult.exceptionOrNull()?.message.orEmpty())
        val caughtUp = onChainLatestVoucher >= voucherClaimed
        val effectiveUpdateOk = updateResult.isSuccess || (duplicateVoucherUpdate && caughtUp)

        if (effectiveUpdateOk) {
            val progressSnapshot =
                safeApiCall("getSessionProgressSnapshot.postUpdate") {
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
                "[SESSION_VAULT_VOUCHER_SEND] session=$receiptSessionId segment=$receiptSegmentIndex claimedAmountMicroUsdc=$voucherClaimed viewer=$receiptViewerAddress host=$hostAddress sigLen=${voucherSignature.size}",
                tag = TAG,
            )
            sendMessage(voucherJson)
        } else {
            val lagMicroUsdc = (voucherClaimed - onChainLatestVoucher).coerceAtLeast(0L)
            Napier.e(
                "[SESSION_VAULT_VOUCHER_SEND_SKIP] session=$receiptSessionId segment=$receiptSegmentIndex claimedAmountMicroUsdc=$voucherClaimed onChainLatestVoucherMicroUsdc=$onChainLatestVoucher lagMicroUsdc=$lagMicroUsdc reason=update_not_confirmed",
                tag = TAG,
            )
        }
    }

    private suspend fun handleStreamGated(
        scope: CoroutineScope,
        viewerAddress: String,
        hostAddress: String,
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
                        payTo = hostAddress,
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
            val existingSessionData =
                safeApiCall("getSessionDynamicData.streamGated") {
                    withContext(Dispatchers.IO) {
                        MppPayments.getSessionDynamicDataFromVault()
                    }
                }

            val depositResult =
                if (existingSessionData != null) {
                    MppPayments.topUpSessionVault(
                        signer = signer,
                        additionalDepositMicroUsdc = depositMicroUsdc,
                    )
                } else {
                    MppPayments.openSessionAndDeposit(
                        signer = signer,
                        viewerAddress = viewerAddress,
                        creatorAddress = hostAddress,
                        depositAmountMicroUsdc = depositMicroUsdc,
                    ).onSuccess {
                        MppPayments.setAuthorizedSignerForSession(
                            signer = signer,
                            viewerAddress = viewerAddress,
                            hostAddress = hostAddress,
                            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                        )
                    }
                }

            depositResult.onSuccess {
                val onChainRemaining =
                    getRemainingSessionVaultBalanceUseCase(
                        GetRemainingSessionVaultBalanceUseCase.Params(
                            viewerAddress = viewerAddress,
                            hostAddress = hostAddress,
                            appId = sessionVaultAppId,
                            authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                        ),
                    ).getOrDefault(0L)
                setViewerSessionVaultProgress(onChainRemaining, onChainRemaining)
                startViewerOnChainRefresh(
                    scope = scope,
                    viewerAddress = viewerAddress,
                    hostAddress = hostAddress,
                    sessionVaultAppId = sessionVaultAppId,
                    authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
                    setViewerSessionVaultProgress = setViewerSessionVaultProgress,
                )
                liquidStreamViewer?.rtcClient?.extendBudget(additionalMicroUsdc = depositMicroUsdc, asset = "USDC")
                liquidStreamViewer?.rtcClient?.notifyVaultFunded(sessionId = viewerVoucherSessionId ?: "")
            }.onFailure { err ->
                Napier.e("[VIEWER_STREAM_GATED_DEPOSIT_ERR] viewer=$viewerAddress host=$hostAddress", err, tag = TAG)
            }
        }.onFailure { err ->
            Napier.e("[VIEWER_STREAM_GATED_CONSENT_ERR] viewer=$viewerAddress host=$hostAddress", err, tag = TAG)
        }
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
