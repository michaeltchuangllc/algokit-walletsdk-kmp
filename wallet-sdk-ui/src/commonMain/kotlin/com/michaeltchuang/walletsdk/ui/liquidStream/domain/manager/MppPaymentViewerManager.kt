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
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.resolvePricingConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.sessionVaultMinimumBalanceMicroUsdc
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
import kotlinx.coroutines.yield
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
        val onVaultSnapshot: (MppPayments.SessionProgressSnapshot) -> Unit = {},
        val getConnectionType: () -> IceConnectionType = { IceConnectionType.UNKNOWN },
    )

    private data class VaultFundingResult(
        val result: Result<String>,
        val openedSession: Boolean,
    )

    private class PaymentSession(
        val params: StartParams,
    ) {
        val job = SupervisorJob(params.scope.coroutineContext[Job])
        val scope = CoroutineScope(params.scope.coroutineContext + job)
        val mutex = Mutex()
        var pendingDeposit: Long? = null
        var expectedTotalDeposit: Long? = null
        var fundingChannelId: ByteArray? = null
        var observedVaultChannelId: ByteArray? = null
        var publishedSnapshot: MppPayments.SessionProgressSnapshot? = null
        var consentUiRefreshJob: Job? = null
        var extendBudgetOnConfirmation = false
        var processing = false
        var consentActive = false
        lateinit var viewer: LiquidStreamViewer
        val pendingVouchers = ArrayDeque<VoucherObligation>()
        private var pricingConnectionType = IceConnectionType.UNKNOWN

        fun minimumBalance(): Long {
            val detected = params.getConnectionType()
            val previous = pricingConnectionType
            pricingConnectionType = resolvePricingConnectionType(previous, detected)
            if (previous != pricingConnectionType) {
                ViewerVaultDebug.log(
                    "PRICING_TIER previous=$previous detected=$detected effective=$pricingConnectionType " +
                        "minimumMicro=${pricingConnectionType.sessionVaultMinimumBalanceMicroUsdc()}",
                )
            }
            return pricingConnectionType.sessionVaultMinimumBalanceMicroUsdc()
        }
    }

    private class VoucherObligation(
        val sessionId: String,
        val segmentIndex: Int,
        val viewerAddress: String,
        val payTo: String,
        val channelId: ByteArray,
        val vaultChannelId: String?,
        val cumulativeAmount: Long,
        val blocksConsumed: Int,
    ) {
        var signature: ByteArray? = null
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

    private fun trace(event: String) {
        ViewerVaultDebug.log(
            "$event session=${viewerVoucherSessionId ?: "pending"} revision=$paymentRevision " +
                "pendingPayment=$pendingPayment pendingDepositMicro=${paymentSession?.pendingDeposit} " +
                "processing=${paymentSession?.processing} consentActive=${paymentSession?.consentActive}",
        )
    }

    /** Refresh metadata after a top-up without changing payment budget or gating. */
    fun refreshVaultIdentity(
        viewerAddress: String,
        force: Boolean = false,
    ) {
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
        trace("PAYMENT_PENDING")
        Napier.d("[PAYMENT_PENDING_SET] pendingPayment=$pendingPayment", tag = TAG)
    }

    fun clearPendingPayment() {
        if (paymentSession?.pendingDeposit != null) return
        paymentRevision++
        externalConfirmationPending = false
        pendingPayment = false
        trace("PAYMENT_CLEARED")
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
        trace("STREAM_PRICE costMicro=$cost")
        Napier.d("[VIEWER_STREAM_COST_UPDATED] cost=$cost", tag = TAG)
    }

    fun start(params: StartParams) {
        val viewerAddress = params.viewerAddress
        val signer = params.signer

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
                            val request =
                                session.scope.async {
                                    session.mutex.withLock {
                                        try {
                                            requestConsent(session, terms)
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (err: Throwable) {
                                            // Recheck after an unavailable read without requesting another deposit.
                                            startRefresh(session)
                                            throw err
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
                session.viewer = viewer
                viewer.rtcClient.onDataChannelOpen = {
                    if (activeStartParams === params) {
                        refreshVaultIdentity(viewerAddress, force = true)
                    }
                }

                viewer.rtcClient.onPaymentRequested = { request ->
                    trace("PAYMENT_REQUEST request=${request.id} amountMicro=${request.amount}")
                    if (activeStartParams === params) {
                        refreshVaultIdentity(viewerAddress)
                    }
                    Napier.d(
                        "[VIEWER_PAYMENT_REQUEST_RECEIVED] session=${request.id} payTo=${request.payTo} amount=${request.amount} asset=${request.asset}",
                        tag = TAG,
                    )
                }

                viewer.rtcClient.onPaymentReceipt = { receipt ->
                    trace("RECEIPT_QUEUED receiptSession=${receipt.sessionId} segment=${receipt.segmentIndex} amountMicro=${receipt.amount}")
                    session.scope.launch {
                        voucherMutex.withLock {
                            try {
                                ensureCurrent(session)
                                handlePaymentReceipt(
                                    session = session,
                                    receiptSessionId = receipt.sessionId,
                                    receiptSegmentIndex = receipt.segmentIndex,
                                    receiptAmount = receipt.amount,
                                    receiptPayFrom = receipt.payFrom,
                                    receiptPayTo = receipt.payTo,
                                    txId = receipt.txId,
                                    vaultChannelId = if (receipt.billingMode == BillingMode.SESSION_VAULT) receipt.channelId else null,
                                )
                            } catch (ce: CancellationException) {
                                trace("RECEIPT_CANCELLED receiptSession=${receipt.sessionId} segment=${receipt.segmentIndex}")
                                throw ce
                            } catch (err: Throwable) {
                                trace(
                                    "RECEIPT_PROCESSING_ERROR receiptSession=${receipt.sessionId} segment=${receipt.segmentIndex} " +
                                        "errorType=${err::class.simpleName}",
                                )
                                throw err
                            }
                        }
                    }
                }

                viewer.rtcClient.onStreamGated = { reason ->
                    trace("STREAM_GATED reason=$reason")
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
                        var needsTopUp = false
                        try {
                            if (activeStartParams !== refreshParams || paymentSession !== session) return@launch
                            if (session != null && (session.pendingDeposit != null || externalConfirmationPending)) {
                                confirmFunding(session)
                                refreshVaultSnapshot(session)
                            } else if (!pendingPayment || externalConfirmationPending) {
                                val revision = paymentRevision
                                val remaining =
                                    getRemainingSessionVaultBalanceUseCase(
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
                                val balance = if (session != null) readViewerBalance(session, remaining) else ViewerBalance(remaining, remaining)
                                setViewerSessionVaultProgress(balance.onChainRemaining, balance.spendable)
                                val minimum = if (currentStreamCostMicroUsdc != 0L) session?.minimumBalance() else null
                                needsTopUp =
                                    minimum != null &&
                                    balance.onChainRemaining in 0L..minimum &&
                                    !pendingPayment
                                if (session != null) {
                                    trace(
                                        "POLL_DECISION remainingMicro=$remaining onChainRemainingMicro=${balance.onChainRemaining} " +
                                            "availableMicro=${balance.spendable} basis=on_chain_remaining " +
                                            "minimumMicro=$minimum detected=${session.params.getConnectionType()} " +
                                            "costMicro=$currentStreamCostMicroUsdc needsTopUp=$needsTopUp",
                                    )
                                    val snapshotRevision = paymentRevision
                                    refreshVaultSnapshot(session)
                                    if (snapshotRevision != paymentRevision) continue
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (err: Throwable) {
                            trace("POLL_ERROR errorType=${err::class.simpleName}")
                            Napier.e("[VIEWER_SESSION_VAULT_REFRESH_ERR] viewer=$viewerAddress", err, tag = TAG)
                        } finally {
                            session?.mutex?.unlock()
                        }
                        if (needsTopUp) liquidStreamViewer?.rtcClient?.onStreamGated?.invoke("Session balance at or below minimum")
                    } else {
                        trace("POLL_SKIPPED reason=payment_or_consent_lock")
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
        val session =
            paymentSession ?: run {
                Napier.w("[GIFT_VOUCHER_SKIPPED] reason=missing_active_params", tag = TAG)
                return
            }
        val giftMicroUsdc = (giftUsdc * 1_000_000.0).roundToLong().coerceAtLeast(1L)
        session.scope.launch {
            voucherMutex.withLock {
                ensureCurrent(session)
                handleGiftVoucher(
                    giftMicroUsdc = giftMicroUsdc,
                    session = session,
                )
            }
        }
    }

    private suspend fun handleGiftVoucher(
        giftMicroUsdc: Long,
        session: PaymentSession,
    ) {
        val params = session.params
        val viewerAddress = params.viewerAddress
        val channelId = params.channelIdProvider()?.copyOf() ?: return
        val payTo = EscrowSessionVaultHybridManagerClient.hostAddress.orEmpty()

        val preUpdateDynamicData =
            safeApiCall("getSessionDynamicData.gift") {
                MppPayments.getSessionDynamicDataFromVault(channelId)
            }
        ensureCurrent(session)
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
        session.pendingVouchers.addLast(
            VoucherObligation(
                sessionId = viewerVoucherSessionId.orEmpty(),
                segmentIndex = 0,
                viewerAddress = viewerAddress,
                payTo = payTo,
                channelId = channelId,
                vaultChannelId = null,
                cumulativeAmount = voucherClaimed,
                blocksConsumed = viewerVoucherBlocksConsumed.coerceAtLeast(0),
            ),
        )
        drainVouchers(session)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun handlePaymentReceipt(
        session: PaymentSession,
        receiptSessionId: String,
        receiptSegmentIndex: Int,
        receiptAmount: String,
        receiptPayFrom: String,
        receiptPayTo: String,
        txId: String,
        vaultChannelId: String? = null,
    ) {
        val params = session.params
        val viewerAddress = params.viewerAddress
        val explicitChannel = vaultChannelId?.let(Base64::decode)
        val channelId = explicitChannel ?: params.channelIdProvider()?.copyOf() ?: run {
            trace("RECEIPT_SKIPPED receiptSession=$receiptSessionId segment=$receiptSegmentIndex reason=missing_channel")
            return
        }
        val debit =
            if (explicitChannel != null) {
                receiptAmount.toLongOrNull() ?: 0L
            } else {
                currentStreamCostMicroUsdc ?: receiptAmount.toLongOrNull() ?: 0L
            }
        val receiptViewerAddress = receiptPayFrom.ifBlank { viewerAddress }
        if (viewerVoucherSessionId != receiptSessionId) {
            viewerVoucherSessionId = receiptSessionId
            viewerVoucherBlocksConsumed = 0
            viewerPaidBlocksConsumed = 0
            viewerFreeBlocksConsumed = 0
            viewerVoucherClaimedMicroUsdc = 0L
            viewerVoucherCapLoggedSessionId = null
        }

        val progressRevision = paymentRevision
        val progressSnapshot =
            safeApiCall("getSessionProgressSnapshot.onReceipt") {
                MppPayments.getSessionProgressSnapshotFromVault(channelId)
            }
        ensureCurrent(session)
        if (progressSnapshot != null) observeVault(session, channelId)
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
                    MppPayments.getSessionDynamicDataFromVault(channelId)
                }
            ensureCurrent(session)
            if (preUpdateDynamicData != null) observeVault(session, channelId)
            val preUpdateLatestVoucher = preUpdateDynamicData?.latestVoucherAmount ?: 0L
            val preUpdateLastSettled = preUpdateDynamicData?.lastSettled ?: 0L
            val preUpdateTotalDeposit = preUpdateDynamicData?.totalDeposit ?: 0L
            val hasOnChainSessionData = (preUpdateDynamicData != null) && (preUpdateTotalDeposit > 0L)
            val voucherBase =
                if (explicitChannel != null) {
                    maxOf(viewerVoucherClaimedMicroUsdc, preUpdateLatestVoucher, preUpdateLastSettled)
                } else {
                    maxOf(viewerVoucherClaimedMicroUsdc, preUpdateLatestVoucher)
                }
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
            trace(
                "BLOCK_RESERVED segment=$receiptSegmentIndex paidBlocks=$viewerPaidBlocksConsumed debitMicro=$debit " +
                    "totalDepositMicro=${preUpdateDynamicData?.totalDeposit} settledMicro=${preUpdateDynamicData?.lastSettled} " +
                    "latestVoucherMicro=${preUpdateDynamicData?.latestVoucherAmount} localClaimedMicro=$voucherClaimed " +
                    "availableMicro=${preUpdateDynamicData?.let { (it.totalDeposit - voucherClaimed).coerceAtLeast(0L) }}",
            )
            session.pendingVouchers.addLast(
                VoucherObligation(
                    sessionId = receiptSessionId,
                    segmentIndex = receiptSegmentIndex,
                    viewerAddress = receiptViewerAddress,
                    payTo = receiptPayTo,
                    channelId = channelId,
                    cumulativeAmount = voucherClaimed,
                    blocksConsumed = blocksConsumed,
                    vaultChannelId = vaultChannelId,
                ),
            )
            drainVouchers(session)
        }

        ensureCurrent(session)
        if (progressSnapshot == null || !isCurrentVaultSnapshot(session, channelId, progressRevision, progressSnapshot)) return
        params.setViewerSessionVaultProgress(
            progressSnapshot.remainingSettledMicroUsdc,
            progressSnapshot.progressBalanceMicroUsdc,
        )
        ensureCurrent(session)
        if (isCurrentVaultSnapshot(session, channelId, progressRevision, progressSnapshot)) {
            publishVaultSnapshot(session, progressSnapshot)
        }
    }

    private suspend fun refreshVaultSnapshot(session: PaymentSession) {
        ensureCurrent(session)
        val params = session.params
        val channelId = params.channelIdProvider()?.copyOf() ?: return
        val revision = paymentRevision
        val snapshot =
            safeApiCall("getSessionProgressSnapshot.refresh") {
                MppPayments.getSessionProgressSnapshotFromVault(channelId)
            }
        ensureCurrent(session)
        if (snapshot != null && isCurrentVaultSnapshot(session, channelId, revision, snapshot)) {
            observeVault(session, channelId)
            publishVaultSnapshot(session, snapshot)
        }
    }

    private fun isCurrentVaultSnapshot(
        session: PaymentSession,
        channelId: ByteArray,
        revision: Long,
        snapshot: MppPayments.SessionProgressSnapshot,
    ): Boolean =
        paymentSession === session &&
            activeStartParams === session.params &&
            revision == paymentRevision &&
            channelId.contentEquals(session.params.channelIdProvider()) &&
            snapshot.totalDepositMicroUsdc >= 0L &&
            snapshot.remainingSettledMicroUsdc in 0L..snapshot.totalDepositMicroUsdc &&
            snapshot.progressBalanceMicroUsdc in 0L..snapshot.remainingSettledMicroUsdc &&
            snapshot.lastSettledMicroUsdc >= 0L &&
            snapshot.latestVoucherAmountMicroUsdc >= 0L &&
            snapshot.startRound >= 0L &&
            session.publishedSnapshot.let { previous ->
                previous == null ||
                    snapshot.startRound > previous.startRound ||
                    (
                        snapshot.startRound == previous.startRound &&
                            snapshot.totalDepositMicroUsdc >= previous.totalDepositMicroUsdc &&
                            snapshot.lastSettledMicroUsdc >= previous.lastSettledMicroUsdc &&
                            snapshot.latestVoucherAmountMicroUsdc >= previous.latestVoucherAmountMicroUsdc
                    )
            }

    private fun publishVaultSnapshot(session: PaymentSession, snapshot: MppPayments.SessionProgressSnapshot) {
        session.publishedSnapshot = snapshot
        session.params.onVaultSnapshot(snapshot)
    }

    private suspend fun drainVouchers(session: PaymentSession) {
        while (session.pendingVouchers.isNotEmpty()) {
            ensureCurrent(session)
            val obligation = session.pendingVouchers.first()
            val signature =
                obligation.signature ?: safeApiCall("signVoucher") {
                    val message =
                        MppPayments.buildLogicSigSettlementVoucher(
                            channelId = obligation.channelId.copyOf(),
                            cumulativeAmountMicroUsdc = obligation.cumulativeAmount,
                            payeeAddress = obligation.payTo,
                        )
                    session.params.signFido2Challenge(message, session.params.viewerAddress)
                }
            ensureCurrent(session)
            if (signature == null || signature.isEmpty()) {
                Napier.w("[VIEWER_VOUCHER_SIGN_PENDING] session=${obligation.sessionId} segment=${obligation.segmentIndex}", tag = TAG)
                return // Retry this exact amount on the next receipt/gift; never sign past it.
            }
            obligation.signature = signature.copyOf()
            val attempted =
                safeApiCall("sendVoucher") {
                    updateAndSendVoucher(session, obligation, signature)
                    true
                } ?: false
            ensureCurrent(session)
            if (!attempted) return
            // sendVoucher is best-effort (Unit, swallowed transport errors), NOT an acknowledgement.
            session.pendingVouchers.removeFirst()
            yield()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun updateAndSendVoucher(
        session: PaymentSession,
        obligation: VoucherObligation,
        voucherSignature: ByteArray,
    ) {
        val params = session.params
        val vaultChannelId = obligation.vaultChannelId
        val progressSnapshot =
            safeApiCall("getSessionProgressSnapshot.preSend") {
                MppPayments.getSessionProgressSnapshotFromVault(
                    obligation.channelId,
                )
            }
        ensureCurrent(session)

        val voucherJson =
            MppPayments.createVoucherJson(
                sessionId = obligation.sessionId,
                viewerAddress = obligation.viewerAddress,
                viewerPublicKey = params.signer.authorizedSignerPublicKey,
                creatorAddress = obligation.payTo,
                blocksConsumed = obligation.blocksConsumed,
                totalAmountUsed = obligation.cumulativeAmount,
                remainingMicroUsdc = progressSnapshot?.progressBalanceMicroUsdc ?: 0L,
                signatureBase64 = MppPayments.serializeVoucherSignature(voucherSignature),
                appId = params.sessionVaultAppId,
            )
        Napier.e(
            "[SESSION_VAULT_VOUCHER_SEND_ATTEMPT] session=${obligation.sessionId} segment=${obligation.segmentIndex} claimedAmountMicroUsdc=${obligation.cumulativeAmount}",
            tag = TAG,
        )
        val wireVoucher =
            if (vaultChannelId != null) {
                buildJsonObject {
                    Json.parseToJsonElement(voucherJson).jsonObject.forEach { (key, value) -> put(key, value) }
                    put("channelId", vaultChannelId)
                    put("billingMode", BillingMode.SESSION_VAULT)
                }.toString()
            } else {
                voucherJson
            }
        ensureCurrent(session)
        session.viewer.rtcClient.sendVoucher(wireVoucher)
        trace("VOUCHER_SEND_ATTEMPT segment=${obligation.segmentIndex} cumulativeMicro=${obligation.cumulativeAmount}")
    }

    private fun cancelPaymentSession() {
        val session = paymentSession ?: return
        paymentSession = null
        session.job.cancel()
        if (session.processing || session.consentActive) session.params.setViewerPaymentProcessing(false)
    }

    private fun finishProcessing(session: PaymentSession) {
        session.consentUiRefreshJob?.cancel()
        session.consentUiRefreshJob = null
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

    /** Display-only polling must not initiate another payment or outlive the consent flow. */
    private fun startConsentUiRefresh(session: PaymentSession) {
        session.consentUiRefreshJob?.cancel()
        session.consentUiRefreshJob = session.scope.launch {
            while (isActive) {
                delay(1000L.milliseconds)
                if (!session.consentActive) return@launch
                if (session.processing || pendingPayment) continue
                try {
                    refreshVaultSnapshot(session)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Throwable) {
                    trace("CONSENT_UI_REFRESH_ERROR errorType=${err::class.simpleName}")
                }
            }
        }
    }

    private suspend fun readRemaining(session: PaymentSession): Long {
        val params = session.params
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            ensureCurrent(session)
            try {
                val revision = paymentRevision
                val remaining =
                    getRemainingSessionVaultBalanceUseCase(
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
                trace("BALANCE_READ_RETRY attempt=${attempt + 1} errorType=${err::class.simpleName}")
                lastError = err
            }
            if (attempt < 2) delay(1000L.milliseconds)
        }
        throw checkNotNull(lastError)
    }

    private suspend fun confirmFunding(session: PaymentSession): Long {
        repeat(3) { attempt ->
            var remaining = readRemaining(session)
            check(session.pendingDeposit != null || externalConfirmationPending) { "External funding is still in progress" }
            val expected = session.expectedTotalDeposit
            val data =
                if (session.pendingDeposit != null) {
                    checkNotNull(expected) { "Missing funding confirmation target" }
                    val channel = checkNotNull(session.fundingChannelId)
                    check(channel.contentEquals(session.params.channelIdProvider())) { "Funding channel changed" }
                    MppPayments.getSessionDynamicDataFromVault(channel)
                } else {
                    null
                }
            ensureCurrent(session)
            if (expected != null) {
                check(session.fundingChannelId.contentEquals(session.params.channelIdProvider())) { "Funding channel changed" }
                if (data != null) observeVault(session, checkNotNull(session.fundingChannelId))
            }
            val depositObserved = expected == null || (data != null && data.totalDeposit >= expected)

            if (expected != null && data != null) {
                remaining = (data.totalDeposit - data.lastSettled).coerceAtLeast(0L)
            }
            trace(
                "FUNDING_CONFIRM_READ attempt=${attempt + 1} remainingMicro=$remaining " +
                    "expectedTotalDepositMicro=$expected observedTotalDepositMicro=${data?.totalDeposit} " +
                    "depositObserved=$depositObserved",
            )
            if (depositObserved && (expected != null || remaining > 0L)) {
                trace("FUNDING_CONFIRM_ACCEPTED remainingMicro=$remaining expectedTotalDepositMicro=$expected")
                val deposit = session.pendingDeposit
                session.pendingDeposit = null
                session.expectedTotalDeposit = null
                session.fundingChannelId = null
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
        trace("FUNDING_CONFIRM_WAIT reason=deposit_not_yet_observed")
        error("Session vault funding is awaiting confirmation")
    }

    private fun observeVault(session: PaymentSession, channel: ByteArray) {
        check(channel.contentEquals(session.params.channelIdProvider())) { "Vault channel changed" }
        val observed = session.observedVaultChannelId
        check(observed == null || observed.contentEquals(channel)) { "Observed vault channel changed" }
        session.observedVaultChannelId = channel.copyOf()
    }

    private suspend fun readConsentVaultData(session: PaymentSession): MppPayments.SessionDynamicData? {
        val channel = checkNotNull(session.params.channelIdProvider()?.copyOf()) { "Missing vault channel" }
        val observed = session.observedVaultChannelId
        check(observed == null || observed.contentEquals(channel)) { "Observed vault channel changed" }
        repeat(3) { attempt ->
            val data = MppPayments.getSessionDynamicDataFromVault(channel)
            ensureCurrent(session)
            check(channel.contentEquals(session.params.channelIdProvider())) { "Vault channel changed during read" }
            if (data != null) {
                observeVault(session, channel)
                return data
            }
            if (session.observedVaultChannelId == null && viewerVoucherClaimedMicroUsdc == 0L) return null
            trace("VAULT_READ_UNAVAILABLE attempt=${attempt + 1} action=retry_without_popup")
            if (attempt < 2) delay(1000L.milliseconds)
        }
        trace("POPUP_SKIPPED reason=known_vault_unavailable")
        error("Existing session vault data is temporarily unavailable")
    }

    private data class ViewerBalance(val onChainRemaining: Long, val spendable: Long)

    private suspend fun readViewerBalance(
        session: PaymentSession,
        remaining: Long,
    ): ViewerBalance {
        val channel = session.params.channelIdProvider()?.copyOf()
        val revision = paymentRevision
        val data = readConsentVaultData(session)
        if (data != null && channel != null) {
            val snapshot = MppPayments.computeSessionProgressSnapshot(data)
            if (isCurrentVaultSnapshot(session, channel, revision, snapshot)) {
                publishVaultSnapshot(session, snapshot)
                ensureCurrent(session)
                trace(
                    "POPUP_BALANCE_PUBLISHED onChainRemainingMicro=${snapshot.remainingSettledMicroUsdc} " +
                        "settledMicro=${snapshot.lastSettledMicroUsdc}",
                )
            }
        }
        val onChainRemaining = data?.let { (it.totalDeposit - it.lastSettled).coerceAtLeast(0L) } ?: remaining
        val available = if (data == null) {
            remaining
        } else {
            minOf(
                if (remaining == 0L) (data.totalDeposit - data.lastSettled).coerceAtLeast(0L) else remaining,
                (data.totalDeposit - maxOf(data.lastSettled, data.latestVoucherAmount, viewerVoucherClaimedMicroUsdc)).coerceAtLeast(0L),
            )
        }
        trace(
            "BALANCE_SNAPSHOT remainingMicro=$remaining totalDepositMicro=${data?.totalDeposit} " +
                "settledMicro=${data?.lastSettled} latestVoucherMicro=${data?.latestVoucherAmount} " +
                "localClaimedMicro=$viewerVoucherClaimedMicroUsdc availableMicro=$available " +
                "onChainRemainingMicro=$onChainRemaining " +
                "source=${if (data == null) "remaining_fallback" else "vault_and_local_vouchers"}",
        )
        return ViewerBalance(onChainRemaining, available)
    }

    private suspend fun isAboveMinimum(session: PaymentSession, remaining: Long, source: String): Boolean {
        val balance = readViewerBalance(session, remaining)
        val minimum = session.minimumBalance()
        val aboveMinimum = balance.onChainRemaining > minimum
        trace(
            "BALANCE_DECISION source=$source onChainRemainingMicro=${balance.onChainRemaining} " +
                "availableMicro=${balance.spendable} basis=on_chain_remaining minimumMicro=$minimum " +
                "detected=${session.params.getConnectionType()} costMicro=$currentStreamCostMicroUsdc " +
                "aboveMinimum=$aboveMinimum",
        )
        return aboveMinimum
    }

    suspend fun topUpViewerSessionVault(
        viewerAddress: String,
        depositMicroUsdc: Long,
        fund: suspend () -> Unit,
        readBalance: suspend () -> Long,
    ): Long {
        val session = paymentSession
        trace("MANUAL_DEPOSIT_REQUEST amountMicro=$depositMicroUsdc")
        if (session == null) {
            return manualPaymentMutex.withLock {
                markPaymentPending()
                try {
                    fund()
                    trace("MANUAL_DEPOSIT_CALL_RETURNED amountMicro=$depositMicroUsdc")
                    readBalance().also { trace("MANUAL_DEPOSIT_BALANCE remainingMicro=$it") }
                } finally {
                    clearPendingPayment()
                }
            }
        }
        require(session.params.viewerAddress == viewerAddress)
        val request =
            session.scope.async {
                check(session.mutex.tryLock()) { "Viewer payment is already in progress" }
                try {
                    ensureCurrent(session)
                    if (session.pendingDeposit != null) {
                        confirmFunding(session)
                    } else {
                        performFunding(session, depositMicroUsdc, gated = false, fund = fund)
                    }
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

    private suspend fun fundAndConfirm(
        session: PaymentSession,
        deposit: Long,
        gated: Boolean,
    ): Long =
        performFunding(session, deposit, gated) {
            val funding =
                fundSessionVault(
                    signer = session.params.signer,
                    viewerAddress = session.params.viewerAddress,
                    depositMicroUsdc = deposit,
                )
            ensureCurrent(session)
            funding.result
                .onFailure {
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
        require(deposit > 0L)
        val channel = checkNotNull(session.params.channelIdProvider()?.copyOf()) { "Missing funding channel" }
        val baseline = readConsentVaultData(session)
        ensureCurrent(session)
        val previousTotal =
            baseline?.totalDeposit ?: run {
                check(readRemaining(session) == 0L) { "Funding baseline is unavailable" }
                0L
            }
        check(channel.contentEquals(session.params.channelIdProvider())) { "Funding channel changed" }
        check(previousTotal >= 0L && previousTotal <= Long.MAX_VALUE - deposit) { "Invalid funding total" }
        session.expectedTotalDeposit = previousTotal + deposit
        session.fundingChannelId = channel
        trace("FUNDING_TARGET previousTotalDepositMicro=$previousTotal expectedTotalDepositMicro=${session.expectedTotalDeposit}")
        trace("DEPOSIT_START amountMicro=$deposit gated=$gated")
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
                trace("DEPOSIT_CANCELLED amountMicro=$deposit")
                throw ce
            } catch (err: Throwable) {
                trace("DEPOSIT_ERROR amountMicro=$deposit errorType=${err::class.simpleName}")
                ensureCurrent(session)
                session.pendingDeposit = null
                session.expectedTotalDeposit = null
                session.fundingChannelId = null
                session.extendBudgetOnConfirmation = false
                clearPendingPayment()
                throw err
            }
            ensureCurrent(session)
            trace("DEPOSIT_CALL_RETURNED amountMicro=$deposit")
            refreshVaultIdentity(params.viewerAddress)
            return confirmFunding(session)
        } finally {
            if (paymentSession === session) {
                startRefresh(session)
            }
        }
    }

    private suspend fun requestConsent(
        session: PaymentSession,
        terms: ConsentTerms,
    ): ConsentApproval {
        ensureCurrent(session)
        val params = session.params
        awaitExternalFunding(session)
        val remaining =
            if (session.pendingDeposit != null || externalConfirmationPending) {
                confirmFunding(session)
            } else {
                readRemaining(session)
            }
        if (pendingPayment) error("Session vault payment is pending")
        if (isAboveMinimum(session, remaining, "initial_consent")) {
            params.setViewerSessionVaultProgress(remaining, remaining)
            startRefresh(session)
            return fundedApproval(session, terms, remaining)
        }
        session.consentActive = true
        startConsentUiRefresh(session)
        trace("CONSENT_REQUEST source=initial amountMicro=${terms.amount}")
        val approval = params.requestMppConsent(terms)
        trace("CONSENT_RESULT source=initial approved=${approval.approved} depositMicro=${approval.budgetCap?.amount}")
        ensureCurrent(session)
        if (!approval.approved) return approval
        awaitExternalFunding(session)
        val freshRemaining = if (externalConfirmationPending) confirmFunding(session) else readRemaining(session)
        if (pendingPayment) error("Session vault payment is pending")
        if (isAboveMinimum(session, freshRemaining, "initial_after_approval")) {
            params.setViewerSessionVaultProgress(freshRemaining, freshRemaining)
            startRefresh(session)
            return fundedApproval(session, terms, freshRemaining)
        }
        val deposit =
            approval.budgetCap
                ?.amount
                ?.toLongOrNull()
                ?.takeIf { it > 0L } ?: 1_000_000L
        val confirmedRemaining = fundAndConfirm(session, deposit, gated = false)
        return if (terms.billingMode == BillingMode.SESSION_VAULT) {
            val funded = fundedApproval(session, terms, confirmedRemaining)
            if (funded.approved) approval.copy(budgetCap = funded.budgetCap) else funded
        } else {
            approval
        }
    }

    private suspend fun awaitExternalFunding(session: PaymentSession) {
        while (pendingPayment && session.pendingDeposit == null && !externalConfirmationPending) {
            delay(100L.milliseconds)
            ensureCurrent(session)
        }
    }

    private suspend fun fundedApproval(
        session: PaymentSession,
        terms: ConsentTerms,
        remaining: Long,
    ): ConsentApproval {
        val vaultOnly = terms.billingMode == BillingMode.SESSION_VAULT
        val available =
            if (vaultOnly) {
                val data =
                    checkNotNull(readConsentVaultData(session)) {
                        "Session vault budget is unavailable"
                    }
                ensureCurrent(session)
                minOf(
                    if (remaining == 0L) (data.totalDeposit - data.lastSettled).coerceAtLeast(0L) else remaining,
                    (
                        data.totalDeposit -
                            maxOf(
                                data.lastSettled,
                                data.latestVoucherAmount,
                                viewerVoucherClaimedMicroUsdc,
                            )
                    ).coerceAtLeast(0L),
                )
            } else {
                remaining
            }
        if (vaultOnly && available < (terms.amount.toLongOrNull() ?: Long.MAX_VALUE)) {
            return ConsentApproval(approved = false, autoPaySegments = false)
        }
        val spent =
            if (vaultOnly) {
                liquidStreamViewer
                    ?.rtcClient
                    ?.spend
                    ?.totalAmount
                    ?.toLongOrNull() ?: 0L
            } else {
                0L
            }
        return ConsentApproval(
            approved = true,
            autoPaySegments = true,
            budgetCap = BudgetCap(amount = (available + spent).toString(), asset = terms.asset),
        )
    }

    private suspend fun handleStreamGated(session: PaymentSession) {
        if (currentStreamCostMicroUsdc == 0L) {
            trace("POPUP_SKIPPED reason=free_stream")
            return
        }
        if (!session.mutex.tryLock()) {
            trace("POPUP_SKIPPED reason=payment_or_consent_lock")
            return
        }
        try {
            ensureCurrent(session)
            if (session.pendingDeposit != null || externalConfirmationPending) {
                trace("POPUP_SKIPPED reason=awaiting_funding_confirmation")
                confirmFunding(session)
                return
            }
            if (pendingPayment) {
                trace("POPUP_SKIPPED reason=pending_payment")
                return
            }
            val remaining = readRemaining(session)
            if (pendingPayment) return
            if (isAboveMinimum(session, remaining, "stream_gated")) {
                session.params.setViewerSessionVaultProgress(remaining, remaining)
                return
            }
            session.consentActive = true
            startConsentUiRefresh(session)
            trace("CONSENT_REQUEST source=stream_gated")
            val approval =
                session.params.requestMppConsent(
                    ConsentTerms(
                        gatingMode = GatingMode.PARTIAL_TIME,
                        amount = MppPayments.voucherSettleWindowMicroUsdc().toString(),
                        asset = "USDC",
                        network = session.params.mppNetwork,
                        segmentDuration = 3,
                    ),
                )
            trace("CONSENT_RESULT source=stream_gated approved=${approval.approved} depositMicro=${approval.budgetCap?.amount}")
            ensureCurrent(session)
            if (!approval.approved) return
            if (externalConfirmationPending) {
                confirmFunding(session)
                return
            }
            if (pendingPayment) return
            val freshRemaining = readRemaining(session)
            if (pendingPayment) return
            if (isAboveMinimum(session, freshRemaining, "gated_after_approval")) {
                session.params.setViewerSessionVaultProgress(freshRemaining, freshRemaining)
                return
            }
            val deposit =
                approval.budgetCap
                    ?.amount
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L } ?: 1_000_000L
            fundAndConfirm(session, deposit, gated = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            trace("CONSENT_ERROR source=stream_gated errorType=${err::class.simpleName}")
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
            readConsentVaultData(checkNotNull(paymentSession))
        currentCoroutineContext().ensureActive()
        trace(
            "DEPOSIT_ROUTE route=${if (existingSessionData != null) "top_up" else "open_session"} " +
                "amountMicro=$depositMicroUsdc previousTotalDepositMicro=${existingSessionData?.totalDeposit}",
        )
        if (existingSessionData != null) {
            val topUpResult =
                MppPayments.topUpSessionVault(
                    signer = signer,
                    additionalDepositMicroUsdc = depositMicroUsdc,
                )
            trace("DEPOSIT_TX_RESULT route=top_up success=${topUpResult.isSuccess} errorType=${topUpResult.exceptionOrNull()?.let { it::class.simpleName }}")
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
        trace("DEPOSIT_TX_RESULT route=open_session success=${openResult.isSuccess} errorType=${openResult.exceptionOrNull()?.let { it::class.simpleName }}")
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
