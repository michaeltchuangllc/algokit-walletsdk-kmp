package com.michaeltchuang.walletsdk.ui.liquidStream.domain.manager

import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamViewer
import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentHandler
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannel
import com.michaeltchuang.walletsdk.core.railmpp.core.RtcDataChannelState
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BillingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToLong
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
        val getHostAddress: () -> String = { "" },
        val channelIdProvider: () -> ByteArray? = { EscrowSessionVaultHybridManagerClient.channelId?.copyOf() },
        val setViewerPaymentProcessing: (Boolean) -> Unit = {},
    )

    private data class VaultFundingResult(
        val result: Result<String>,
        val openedSession: Boolean,
    )

    private class PaymentSession(val params: StartParams) {
        val job = SupervisorJob(params.scope.coroutineContext[Job])
        val scope = CoroutineScope(params.scope.coroutineContext + job)
        val mutex = Mutex()
        var pendingDeposit: Long? = null
        var extendBudgetOnConfirmation = false
        var processing = false
        var consentActive = false
    }

    private var paymentSession: PaymentSession? = null
    private val manualPaymentMutex = Mutex()
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
    private var externalConfirmationPending = false
    private var paymentRevision: Long = 0L
    private var currentStreamCostMicroUsdc: Long? = null
    private var activeStartParams: StartParams? = null
    private var vaultHandshake: ViewerVaultHandshake? = null
    private val voucherMutex = Mutex()

    /** Refresh metadata after a top-up without changing payment budget or gating. */
    fun refreshVaultIdentity(viewerAddress: String, force: Boolean = false) {
        val params = activeStartParams?.takeIf { it.viewerAddress == viewerAddress } ?: return
        // Hint only: never retarget the connection's captured viewer/signer to the singleton.
        // The host's readChannel lookup rejects unrelated participants or signer keys.
        vaultHandshake?.send(
            channelId = params.channelIdProvider()?.copyOf(),
            isOpen = params.dataChannel.state() == RtcDataChannelState.OPEN,
            force = force,
        ) { message ->
            runCatching { params.dataChannel.send(message.encodeToByteArray()) }
                .onFailure { Napier.w("[VIEWER_VAULT_HELLO_SEND_ERR]", it, tag = TAG) }
                .isSuccess
        }
    }

    fun markPaymentPending() {
        paymentRevision++
        externalConfirmationPending = false
        pendingPayment = true
        Napier.d("[PAYMENT_PENDING_SET] pendingPayment=$pendingPayment", tag = TAG)
    }

    fun clearPendingPayment() {
        if (paymentSession?.pendingDeposit != null) return
        paymentRevision++
        externalConfirmationPending = false
        pendingPayment = false
        Napier.d("[PAYMENT_PENDING_CLEARED] pendingPayment=$pendingPayment", tag = TAG)
    }

    fun completePendingPayment(fundingSucceeded: Boolean) {
        paymentRevision++
        if (fundingSucceeded) {
            externalConfirmationPending = true
            paymentSession?.let(::startRefresh)
        } else {
            clearPendingPayment()
        }
    }

    fun sendChatMessage(message: ChatMessage) {
        liquidStreamViewer?.sendChatMessage(message)
        val amt = message.amount
        if (amt != null) {
            val giftUsdc = amt.toDoubleOrNull()
            if (giftUsdc != null && giftUsdc > 0.0) {
                processGiftVoucher(giftUsdc)
            }
        }
    }

    fun updateStreamCost(cost: Long) {
        currentStreamCostMicroUsdc = cost
        Napier.d("[VIEWER_STREAM_COST_UPDATED] cost=$cost", tag = TAG)
    }

    fun start(params: StartParams) {
        val viewerAddress = params.viewerAddress
        val signer = params.signer
        val sessionVaultAppId = params.sessionVaultAppId

        cancelPaymentSession()
        val session = PaymentSession(params)
        paymentSession = session
        activeStartParams = params
        vaultHandshake = ViewerVaultHandshake(viewerAddress, signer.authorizedSignerPublicKey.copyOf())
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
                            val request = session.scope.async {
                                session.mutex.withLock {
                                    try {
                                        requestConsent(session, terms)
                                    } finally {
                                        finishProcessing(session)
                                    }
                                }
                            }
                            return try {
                                request.await()
                            } finally {
                                request.cancel()
                            }
                        }
                    },
                clientConfig = ClientConfig(autoPaySegments = false),
            ).also { viewer ->
                viewer.rtcClient.onDataChannelOpen = {
                    if (activeStartParams === params) {
                        refreshVaultIdentity(viewerAddress, force = true)
                    }
                }

                viewer.rtcClient.onPaymentRequested = { request ->
                    if (activeStartParams === params) {
                        refreshVaultIdentity(viewerAddress)
                    }
                    Napier.d(
                        "[VIEWER_PAYMENT_REQUEST_RECEIVED] session=${request.id} payTo=${request.payTo} amount=${request.amount} asset=${request.asset}",
                        tag = TAG,
                    )
                }

                viewer.rtcClient.onPaymentReceipt = { receipt ->
                    params.scope.launch {
                        voucherMutex.withLock {
                            if (activeStartParams !== params) return@withLock
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
                                vaultChannelId = if (receipt.billingMode == BillingMode.SESSION_VAULT) receipt.channelId else null,
                            )
                        }
                    }
                }

                viewer.rtcClient.onStreamGated = { reason ->
                    Napier.w("[VIEWER_STREAM_GATED] viewer=$viewerAddress reason=$reason", tag = TAG)
                    session.scope.launch {
                        handleStreamGated(session)
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
        val refreshParams = activeStartParams
        val session = paymentSession
        viewerOnChainRefreshJob =
            (session?.scope ?: scope).launch {
                while (isActive) {
                    if (session == null || session.mutex.tryLock()) {
                        var exhausted = false
                        try {
                            if (activeStartParams !== refreshParams || paymentSession !== session) return@launch
                            if (session != null && (session.pendingDeposit != null || externalConfirmationPending)) {
                                confirmFunding(session)
                            } else if (!pendingPayment || externalConfirmationPending) {
                                val revision = paymentRevision
                                val remaining = getRemainingSessionVaultBalanceUseCase(
                                    GetRemainingSessionVaultBalanceUseCase.Params(
                                        viewerAddress = viewerAddress,
                                        appId = sessionVaultAppId,
                                        authorizedSignerPublicKey = authorizedSignerPublicKey ?: viewerAuthorizedSignerPublicKey,
                                    ),
                                ).getOrThrow()
                                currentCoroutineContext().ensureActive()
                                if (activeStartParams !== refreshParams || paymentSession !== session) return@launch
                                if (revision != paymentRevision) continue
                                if (externalConfirmationPending && remaining > 0L) clearPendingPayment()
                                refreshVaultIdentity(viewerAddress)
                                setViewerSessionVaultProgress(remaining, remaining)
                                exhausted = remaining == 0L && !pendingPayment
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (err: Throwable) {
                            Napier.e("[VIEWER_SESSION_VAULT_REFRESH_ERR] viewer=$viewerAddress", err, tag = TAG)
                        } finally {
                            session?.mutex?.unlock()
                        }
                        if (exhausted) liquidStreamViewer?.rtcClient?.onStreamGated?.invoke("Session balance exhausted")
                    }
                    delay(1000L.milliseconds)
                }
            }
    }

    fun stop() {
        cancelPaymentSession()
        vaultHandshake = null
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
        activeStartParams = null
        clearPendingPayment()
    }

    fun processGiftVoucher(giftUsdc: Double) {
        val params =
            activeStartParams ?: run {
                Napier.w("[GIFT_VOUCHER_SKIPPED] reason=missing_active_params", tag = TAG)
                return
            }
        val giftMicroUsdc = (giftUsdc * 1_000_000.0).roundToLong().coerceAtLeast(1L)
        params.scope.launch {
            voucherMutex.withLock {
                if (activeStartParams !== params) return@withLock
                handleGiftVoucher(
                    giftMicroUsdc = giftMicroUsdc,
                    params = params,
                )
            }
        }
    }

    private suspend fun handleGiftVoucher(
        giftMicroUsdc: Long,
        params: StartParams,
    ) {
        val viewerAddress = params.viewerAddress
        val sessionVaultAppId = params.sessionVaultAppId
        val signer = params.signer

        val preUpdateDynamicData =
            safeApiCall("getSessionDynamicData.gift") {
                MppPayments.getSessionDynamicDataFromVault()
            }
        val preUpdateLatestVoucher = preUpdateDynamicData?.latestVoucherAmount ?: 0L
        val preUpdateLastSettled = preUpdateDynamicData?.lastSettled ?: 0L
        val preUpdateTotalDeposit = preUpdateDynamicData?.totalDeposit ?: 0L
        val hasOnChainSessionData = (preUpdateDynamicData != null) && (preUpdateTotalDeposit > 0L)

        val voucherBase = maxOf(viewerVoucherClaimedMicroUsdc, preUpdateLatestVoucher)
        val voucherClaimedRaw = (voucherBase + giftMicroUsdc).coerceAtLeast(0L)
        val minRequiredCumulative =
            if (giftMicroUsdc > 0) {
                maxOf(preUpdateLatestVoucher, preUpdateLastSettled) + 1L
            } else {
                maxOf(preUpdateLatestVoucher, preUpdateLastSettled)
            }
        val maxAllowedCumulative = if (hasOnChainSessionData) preUpdateTotalDeposit else Long.MAX_VALUE
        val voucherClaimed = voucherClaimedRaw.coerceAtLeast(minRequiredCumulative).coerceAtMost(maxAllowedCumulative)

        viewerVoucherClaimedMicroUsdc = voucherClaimed
        val channelId = EscrowSessionVaultHybridManagerClient.channelId
        if (channelId == null) {
            Napier.w("[GIFT_VOUCHER_SKIPPED] reason=missing_channel_id", tag = TAG)
            return
        }

        val voucherSignature =
            runCatching {
                val message =
                    MppPayments.buildLogicSigSettlementVoucher(
                        channelId = channelId,
                        cumulativeAmountMicroUsdc = voucherClaimed,
                        payeeAddress = EscrowSessionVaultHybridManagerClient.hostAddress.orEmpty(),
                    )
                params.signFido2Challenge(message, viewerAddress)
            }.getOrNull()

        if (voucherSignature != null && voucherSignature.isNotEmpty()) {
            val blocksConsumed = viewerVoucherBlocksConsumed.coerceAtLeast(0)
            updateAndSendVoucher(
                receiptSessionId = viewerVoucherSessionId.orEmpty(),
                receiptSegmentIndex = 0,
                receiptViewerAddress = viewerAddress,
                receiptPayTo = EscrowSessionVaultHybridManagerClient.hostAddress.orEmpty(),
                sessionVaultAppId = sessionVaultAppId,
                signer = signer,
                voucherClaimed = voucherClaimed,
                voucherSignature = voucherSignature,
                blocksConsumed = blocksConsumed,
            )
            Napier.d(
                "[GIFT_VOUCHER_SENT] giftMicroUsdc=$giftMicroUsdc totalVoucherClaimed=$voucherClaimed viewer=$viewerAddress",
                tag = TAG,
            )
        } else {
            Napier.e("[GIFT_VOUCHER_SIGN_FAILED] viewer=$viewerAddress", tag = TAG)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
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
        vaultChannelId: String? = null,
    ) {
        val explicitChannel = vaultChannelId?.let(Base64::decode)
        val debit = if (explicitChannel != null) receiptAmount.toLongOrNull() ?: 0L
            else currentStreamCostMicroUsdc ?: receiptAmount.toLongOrNull() ?: 0L
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
                MppPayments.getSessionProgressSnapshotFromVault(explicitChannel ?: EscrowSessionVaultHybridManagerClient.channelId)
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
                    MppPayments.getSessionDynamicDataFromVault(explicitChannel ?: EscrowSessionVaultHybridManagerClient.channelId)
                }
            val preUpdateLatestVoucher = preUpdateDynamicData?.latestVoucherAmount ?: 0L
            val preUpdateLastSettled = preUpdateDynamicData?.lastSettled ?: 0L
            val preUpdateTotalDeposit = preUpdateDynamicData?.totalDeposit ?: 0L
            val hasOnChainSessionData = (preUpdateDynamicData != null) && (preUpdateTotalDeposit > 0L)
            val voucherBase = if (explicitChannel != null) {
                maxOf(viewerVoucherClaimedMicroUsdc, preUpdateLatestVoucher, preUpdateLastSettled)
            } else maxOf(viewerVoucherClaimedMicroUsdc, preUpdateLatestVoucher)
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
            val channelId = explicitChannel ?: EscrowSessionVaultHybridManagerClient.channelId ?: return
            val voucherSignature =
                runCatching {
                    val message =
                        MppPayments.buildLogicSigSettlementVoucher(
                            channelId = channelId,
                            cumulativeAmountMicroUsdc = voucherClaimed,
                            payeeAddress = receiptPayTo,
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
                    vaultChannelId = vaultChannelId,
                )
            }
        }

        setViewerSessionVaultProgress(
            progressSnapshot?.remainingSettledMicroUsdc ?: 0L,
            progressSnapshot?.progressBalanceMicroUsdc ?: 0L,
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
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
        vaultChannelId: String? = null,
    ) {
        val progressSnapshot =
            safeApiCall("getSessionProgressSnapshot.preSend") {
                MppPayments.getSessionProgressSnapshotFromVault(
                    vaultChannelId?.let(Base64::decode) ?: EscrowSessionVaultHybridManagerClient.channelId,
                )
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
        val wireVoucher = if (vaultChannelId != null) {
            buildJsonObject {
                Json.parseToJsonElement(voucherJson).jsonObject.forEach { (key, value) -> put(key, value) }
                put("channelId", vaultChannelId)
                put("billingMode", BillingMode.SESSION_VAULT)
            }.toString()
        } else voucherJson
        liquidStreamViewer?.rtcClient?.sendVoucher(wireVoucher)
    }

    private fun cancelPaymentSession() {
        val session = paymentSession ?: return
        paymentSession = null
        session.job.cancel()
        if (session.processing || session.consentActive) session.params.setViewerPaymentProcessing(false)
    }

    private fun finishProcessing(session: PaymentSession) {
        if (paymentSession !== session) return
        if (!session.processing && !session.consentActive) return
        session.processing = false
        session.consentActive = false
        session.params.setViewerPaymentProcessing(false)
    }

    private suspend fun ensureCurrent(session: PaymentSession) {
        currentCoroutineContext().ensureActive()
        if (paymentSession !== session || !session.job.isActive) throw CancellationException("Viewer session ended")
    }

    private fun startRefresh(session: PaymentSession) {
        val params = session.params
        startViewerOnChainRefresh(
            scope = session.scope,
            viewerAddress = params.viewerAddress,
            sessionVaultAppId = params.sessionVaultAppId,
            authorizedSignerPublicKey = params.signer.authorizedSignerPublicKey,
            setViewerSessionVaultProgress = params.setViewerSessionVaultProgress,
        )
    }

    private suspend fun readRemaining(session: PaymentSession): Long {
        val params = session.params
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            ensureCurrent(session)
            try {
                val revision = paymentRevision
                val remaining = getRemainingSessionVaultBalanceUseCase(
                    GetRemainingSessionVaultBalanceUseCase.Params(
                        viewerAddress = params.viewerAddress,
                        appId = params.sessionVaultAppId,
                        authorizedSignerPublicKey = params.signer.authorizedSignerPublicKey,
                    ),
                ).getOrThrow()
                ensureCurrent(session)
                check(revision == paymentRevision) { "Session vault payment changed during balance read" }
                check(remaining >= 0L) { "Invalid session vault balance" }
                return remaining
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                lastError = err
            }
            if (attempt < 2) delay(1000L.milliseconds)
        }
        throw checkNotNull(lastError)
    }

    private suspend fun confirmFunding(session: PaymentSession): Long {
        repeat(3) { attempt ->
            val remaining = readRemaining(session)
            check(session.pendingDeposit != null || externalConfirmationPending) { "External funding is still in progress" }
            if (remaining > 0L) {
                val deposit = session.pendingDeposit
                session.pendingDeposit = null
                clearPendingPayment()
                session.params.setViewerSessionVaultProgress(remaining, remaining)
                if (deposit != null && session.extendBudgetOnConfirmation) {
                    session.extendBudgetOnConfirmation = false
                    liquidStreamViewer?.rtcClient?.extendBudget(additionalMicroUsdc = deposit, asset = "USDC")
                    liquidStreamViewer?.rtcClient?.notifyVaultFunded(sessionId = viewerVoucherSessionId ?: "")
                }
                return remaining
            }
            if (attempt < 2) delay(1000L.milliseconds)
        }
        error("Session vault funding is awaiting confirmation")
    }

    suspend fun topUpViewerSessionVault(
        viewerAddress: String,
        depositMicroUsdc: Long,
        fund: suspend () -> Unit,
        readBalance: suspend () -> Long,
    ): Long {
        val session = paymentSession
        if (session == null) {
            return manualPaymentMutex.withLock {
                markPaymentPending()
                try {
                    fund()
                    readBalance()
                } finally {
                    clearPendingPayment()
                }
            }
        }
        require(session.params.viewerAddress == viewerAddress)
        val request = session.scope.async {
            check(session.mutex.tryLock()) { "Viewer payment is already in progress" }
            try {
                ensureCurrent(session)
                if (session.pendingDeposit != null) confirmFunding(session)
                else performFunding(session, depositMicroUsdc, gated = false, fund = fund)
            } finally {
                try {
                    finishProcessing(session)
                } finally {
                    session.mutex.unlock()
                }
            }
        }
        return try {
            request.await()
        } finally {
            request.cancel()
        }
    }

    private suspend fun fundAndConfirm(session: PaymentSession, deposit: Long, gated: Boolean): Long =
        performFunding(session, deposit, gated) {
            val funding = fundSessionVault(
                signer = session.params.signer,
                viewerAddress = session.params.viewerAddress,
                depositMicroUsdc = deposit,
            )
            ensureCurrent(session)
            funding.result.onFailure {
                session.pendingDeposit = null
                session.extendBudgetOnConfirmation = false
                clearPendingPayment()
            }.getOrThrow()
        }

    private suspend fun performFunding(
        session: PaymentSession,
        deposit: Long,
        gated: Boolean,
        fund: suspend () -> Unit,
    ): Long {
        ensureCurrent(session)
        session.pendingDeposit = deposit
        session.extendBudgetOnConfirmation = gated
        markPaymentPending()
        val params = session.params
        session.processing = true
        try {
            params.setViewerPaymentProcessing(true)
            try {
                fund()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                ensureCurrent(session)
                session.pendingDeposit = null
                session.extendBudgetOnConfirmation = false
                clearPendingPayment()
                throw err
            }
            ensureCurrent(session)
            refreshVaultIdentity(params.viewerAddress)
            return confirmFunding(session)
        } finally {
            if (paymentSession === session) {
                startRefresh(session)
            }
        }
    }

    private suspend fun requestConsent(session: PaymentSession, terms: ConsentTerms): ConsentApproval {
        ensureCurrent(session)
        val params = session.params
        awaitExternalFunding(session)
        val remaining = if (session.pendingDeposit != null || externalConfirmationPending) {
            confirmFunding(session)
        } else readRemaining(session)
        if (pendingPayment) error("Session vault payment is pending")
        if (remaining > 0L) {
            params.setViewerSessionVaultProgress(remaining, remaining)
            startRefresh(session)
            return fundedApproval(session, terms, remaining)
        }
        session.consentActive = true
        val approval = params.requestMppConsent(terms)
        ensureCurrent(session)
        if (!approval.approved) return approval
        awaitExternalFunding(session)
        val freshRemaining = if (externalConfirmationPending) confirmFunding(session) else readRemaining(session)
        if (pendingPayment) error("Session vault payment is pending")
        if (freshRemaining > 0L) {
            params.setViewerSessionVaultProgress(freshRemaining, freshRemaining)
            startRefresh(session)
            return fundedApproval(session, terms, freshRemaining)
        }
        val deposit = approval.budgetCap?.amount?.toLongOrNull()?.takeIf { it > 0L } ?: 1_000_000L
        val confirmedRemaining = fundAndConfirm(session, deposit, gated = false)
        return if (terms.billingMode == BillingMode.SESSION_VAULT) {
            val funded = fundedApproval(session, terms, confirmedRemaining)
            if (funded.approved) approval.copy(budgetCap = funded.budgetCap) else funded
        } else approval
    }

    private suspend fun awaitExternalFunding(session: PaymentSession) {
        while (pendingPayment && session.pendingDeposit == null && !externalConfirmationPending) {
            delay(100L.milliseconds)
            ensureCurrent(session)
        }
    }

    private suspend fun fundedApproval(session: PaymentSession, terms: ConsentTerms, remaining: Long): ConsentApproval {
        val vaultOnly = terms.billingMode == BillingMode.SESSION_VAULT
        val available = if (vaultOnly) {
            val data = checkNotNull(MppPayments.getSessionDynamicDataFromVault()) {
                "Session vault budget is unavailable"
            }
            ensureCurrent(session)
            minOf(remaining, (data.totalDeposit - maxOf(
                data.lastSettled, data.latestVoucherAmount, viewerVoucherClaimedMicroUsdc,
            )).coerceAtLeast(0L))
        } else remaining
        if (vaultOnly && available < (terms.amount.toLongOrNull() ?: Long.MAX_VALUE)) {
            return ConsentApproval(approved = false, autoPaySegments = false)
        }
        val spent = if (vaultOnly) liquidStreamViewer?.rtcClient?.spend?.totalAmount?.toLongOrNull() ?: 0L else 0L
        return ConsentApproval(
            approved = true,
            autoPaySegments = true,
            budgetCap = BudgetCap(amount = (available + spent).toString(), asset = terms.asset),
        )
    }

    private suspend fun handleStreamGated(session: PaymentSession) {
        if (!session.mutex.tryLock()) return
        try {
            ensureCurrent(session)
            if (session.pendingDeposit != null || externalConfirmationPending) {
                confirmFunding(session)
                return
            }
            if (pendingPayment) return
            val remaining = readRemaining(session)
            if (pendingPayment) return
            if (remaining > 0L) {
                session.params.setViewerSessionVaultProgress(remaining, remaining)
                return
            }
            session.consentActive = true
            val approval = session.params.requestMppConsent(
                ConsentTerms(
                    gatingMode = GatingMode.PARTIAL_TIME,
                    amount = MppPayments.voucherSettleWindowMicroUsdc().toString(),
                    asset = "USDC",
                    network = session.params.mppNetwork,
                    segmentDuration = 3,
                ),
            )
            ensureCurrent(session)
            if (!approval.approved) return
            if (externalConfirmationPending) {
                confirmFunding(session)
                return
            }
            if (pendingPayment) return
            val freshRemaining = readRemaining(session)
            if (pendingPayment) return
            if (freshRemaining > 0L) {
                session.params.setViewerSessionVaultProgress(freshRemaining, freshRemaining)
                return
            }
            val deposit = approval.budgetCap?.amount?.toLongOrNull()?.takeIf { it > 0L } ?: 1_000_000L
            fundAndConfirm(session, deposit, gated = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            Napier.e("[VIEWER_STREAM_GATED_CONSENT_ERR] viewer=${session.params.viewerAddress}", err, tag = TAG)
        } finally {
            try {
                finishProcessing(session)
            } finally {
                session.mutex.unlock()
            }
        }
    }

    private suspend fun fundSessionVault(
        signer: MppWalletSigner,
        viewerAddress: String,
        depositMicroUsdc: Long,
    ): VaultFundingResult {
        val fundingParams = activeStartParams
        val existingSessionData =
            MppPayments.getSessionDynamicDataFromVault()
        currentCoroutineContext().ensureActive()
        if (existingSessionData != null) {
            val topUpResult =
                MppPayments.topUpSessionVault(
                    signer = signer,
                    additionalDepositMicroUsdc = depositMicroUsdc,
                )
            currentCoroutineContext().ensureActive()
            // The channel may already exist on-chain (e.g. from an earlier session/app run)
            // without ever having had its settlement LogicSig registered — this is idempotent
            // and cheap, so just always ensure it's set up rather than tracking whether this
            // particular signer/channel already did so.
            ensureAuthorizedSignerAndSettlementLogicSig(signer, viewerAddress)
            topUpResult.onSuccess {
                if (activeStartParams === fundingParams) refreshVaultIdentity(viewerAddress)
            }
            return VaultFundingResult(result = topUpResult, openedSession = false)
        }

        val openResult =
            MppPayments.openSessionAndDeposit(
                signer = signer,
                viewerAddress = viewerAddress,
                depositAmountMicroUsdc = depositMicroUsdc,
            )
        currentCoroutineContext().ensureActive()
        openResult.onSuccess {
            ensureAuthorizedSignerAndSettlementLogicSig(signer, viewerAddress)
            if (activeStartParams === fundingParams) refreshVaultIdentity(viewerAddress)
        }
        return VaultFundingResult(result = openResult, openedSession = true)
    }

    private suspend fun ensureAuthorizedSignerAndSettlementLogicSig(
        signer: MppWalletSigner,
        viewerAddress: String,
    ) {
        MppPayments
            .setAuthorizedSignerForSession(
                signer = signer,
                viewerAddress = viewerAddress,
                authorizedSignerPublicKey = signer.authorizedSignerPublicKey,
            ).onSuccess {
                // Must run after setAuthorizedSignerForSession: the settlement LogicSig is
                // compiled with this signer's session key, and the payee can't settle any
                // voucher until it's registered on-chain (see ensureLogicSigSetup).
                MppPayments
                    .registerSettlementLogicSig(signer = signer)
                    .onFailure { err ->
                        Napier.e(
                            "[VIEWER_REGISTER_LOGIC_SIG_ERR] viewer=$viewerAddress",
                            err,
                            tag = TAG,
                        )
                    }
            }.onFailure { err ->
                Napier.e(
                    "[VIEWER_SET_AUTH_SIGNER_ERR] viewer=$viewerAddress",
                    err,
                    tag = TAG,
                )
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
