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
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.solanasmartcontract.SolanaStreamVault
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.solana.transaction.SolanaTransactionSigner
import com.michaeltchuang.walletsdk.core.solana.utils.Base58
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        internal fun spendableBalance(
            data: MppPayments.SessionDynamicData,
            localClaimed: Long,
        ): Long = (data.totalDeposit - maxOf(data.lastSettled, data.latestVoucherAmount, localClaimed)).coerceAtLeast(0L)

        /** Signing stays in the backend; only the wire envelope is shared. */
        @OptIn(ExperimentalEncodingApi::class)
        internal fun createVoucherJson(
            sessionId: String,
            viewerAddress: String,
            viewerPublicKey: ByteArray,
            creatorAddress: String,
            blocksConsumed: Int,
            cumulativeAmount: Long,
            remainingMicroUsdc: Long,
            signedPayload: ByteArray,
            chain: VoucherChain,
        ): String =
            buildJsonObject {
                put("type", DCMessageType.SEGMENT_VOUCHER.value)
                put("id", sessionId)
                put("viewer", viewerAddress)
                put("viewerPublicKey", Base64.encode(viewerPublicKey))
                put("creator", creatorAddress)
                put("blocksWatched", blocksConsumed)
                put("totalAmountClaimedMicroUsdc", cumulativeAmount)
                put("remainingMicroUsdc", remainingMicroUsdc)
                put("signature", Base64.encode(signedPayload))
                chain.channelId?.let { put("channelId", it) }
                when (chain) {
                    is VoucherChain.Algorand -> {
                        put("appId", chain.appId)
                        put("costPerBlockMicroUsdc", MppPayments.computeVoucherMicroUsdcUsage(1))
                        if (chain.vaultOnly) put("billingMode", BillingMode.SESSION_VAULT)
                    }
                    is VoucherChain.Solana -> {
                        put("mppNetwork", chain.network)
                        put("feePayer", chain.feePayer)
                        put("billingMode", BillingMode.SESSION_VAULT)
                    }
                }
            }.toString()
    }

    internal sealed interface VoucherChain {
        val channelId: String?

        data class Algorand(
            val appId: Long,
            override val channelId: String?,
            val vaultOnly: Boolean,
        ) : VoucherChain

        data class Solana(
            val network: String,
            override val channelId: String,
            val feePayer: String,
        ) : VoucherChain
    }

    /** Connection-owned mechanics shared by both chain workflows, never singleton state. */
    internal class SessionRuntime(
        private val params: StartParams,
    ) {
        val job = SupervisorJob(params.scope.coroutineContext[Job])
        val scope = CoroutineScope(params.scope.coroutineContext + job)
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

        fun checkAlive() {
            if (!job.isActive) throw CancellationException("Viewer session ended")
        }

        suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            checkAlive()
        }

        /** Caller cancellation and connection shutdown both cancel the owned request. */
        suspend fun <T> ownedRequest(block: suspend () -> T): T {
            val request = scope.async { block() }
            return try {
                request.await()
            } finally {
                request.cancel()
            }
        }

        fun createViewer(
            signer: MppWalletSigner,
            consentHandler: ConsentHandler,
            clientConfig: ClientConfig = ClientConfig(autoPaySegments = false),
        ): LiquidStreamViewer =
            LiquidStreamViewer(
                dataChannel = params.dataChannel,
                mppClientConfig = MppClientConfig(network = params.mppNetwork, signer = signer),
                consentHandler = consentHandler,
                clientConfig = clientConfig,
            ).also { viewer ->
                viewer.onChatMessageReceived = { message ->
                    if (job.isActive) params.onChatMessageReceived(message)
                }
                viewer.rtcClient.onStreamRejected = { reason, message ->
                    if (job.isActive) params.onStreamRejected(reason, message)
                }
            }
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
        val onStreamRejected: (reason: String, message: String?) -> Unit = { _, _ -> },
        val signSolanaTransaction: (suspend (String, ByteArray) -> ByteArray)? = null,
        val onPaymentError: (String) -> Unit = {},
    )

    private data class VaultFundingResult(
        val result: Result<String>,
        val openedSession: Boolean,
    )

    private class PaymentSession(
        val params: StartParams,
    ) {
        val runtime = SessionRuntime(params)
        val job = runtime.job
        val scope = runtime.scope
        val mutex = Mutex()

        // A user request reserves its slot before waiting for a background Solana read.
        val paymentRequestMutex = Mutex()
        val voucherMutex = Mutex()
        val solana =
            if (params.mppNetwork.startsWith("solana", ignoreCase = true)) {
                SolanaState(params.signer.authorizedSignerPublicKey.copyOf(), params.getHostAddress())
            } else {
                null
            }
        var signer = params.signer
        var progress: (Long, Long) -> Unit = params.setViewerSessionVaultProgress
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

        fun minimumBalance(): Long = runtime.minimumBalance()
    }

    /** Chain-specific data only; lifecycle, requests and viewer belong to PaymentSession. */
    private class SolanaState(
        val signerKey: ByteArray,
        val expectedHost: String,
    ) {
        var route: SolanaRoute? = null
        var terms: ConsentTerms? = null
        var cumulative = 0L
        var blocks = 0
        val receipts = mutableSetOf<Pair<String, Int>>()
        val gifts = mutableSetOf<ChatMessage>()
        val pendingDebits = ArrayDeque<Pair<Long, Boolean>>()
        var observed: MppPayments.SessionDynamicData? = null
        var voucherRefreshElapsed = 20_000L
    }

    private data class SolanaRoute(
        val session: String,
        val payTo: String,
        val channel: String,
        val feePayer: String,
    )

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
        val session = paymentSession ?: return
        if (!session.job.isActive) return
        // Hint only: never retarget the connection's captured viewer/signer to the singleton.
        // The host's readChannel lookup rejects unrelated participants or signer keys.
        vaultHandshake?.send(
            channelId =
                if (session.solana != null) {
                    session.solana.route
                        ?.channel
                        ?.let(Base64::decode)
                } else {
                    params.channelIdProvider()?.copyOf()
                },
            isOpen = params.dataChannel.state() == RtcDataChannelState.OPEN,
            force = force,
        ) { message ->
            runCatching {
                session.runtime.checkAlive()
                params.dataChannel.send(message.encodeToByteArray())
            }.onFailure { Napier.w("[VIEWER_VAULT_HELLO_SEND_ERR]", it, tag = TAG) }
                .isSuccess
        }
    }

    fun markPaymentPending() {
        if (paymentSession?.solana != null && paymentSession?.job?.isActive != true) return
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
        paymentSession?.takeIf { it.solana != null }?.let {
            // Only the captured Solana deposit target can confirm external funding.
            if (!fundingSucceeded) clearPendingPayment()
            startRefresh(it)
            return
        }
        paymentRevision++
        if (fundingSucceeded) {
            externalConfirmationPending = true
            paymentSession?.let(::startRefresh)
        } else {
            clearPendingPayment()
        }
    }

    fun sendChatMessage(message: ChatMessage) {
        val session = paymentSession
        val solana = session?.solana
        if (solana != null && !session.job.isActive) return
        liquidStreamViewer?.sendChatMessage(message)
        val amt = message.amount
        if (amt != null) {
            val giftUsdc = amt.toDoubleOrNull()
            if (giftUsdc != null &&
                (if (solana != null) solana.gifts.add(message) else giftUsdc > 0.0)
            ) {
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
        vaultHandshake =
            ViewerVaultHandshake(
                viewerAddress,
                signer.authorizedSignerPublicKey.copyOf(),
                params.mppNetwork.takeIf { session.solana != null },
            )
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
        if (session.solana != null) {
            try {
                prepareSolanaSigner(session)
            } catch (err: Throwable) {
                rejectSolana(session, err)
                endPaymentSession(session)
                return
            }
        }

        Napier.d(
            "[VIEWER_MPP_CREATE_VIEWER] viewer=$viewerAddress network=${params.mppNetwork}",
            tag = TAG,
        )

        try {
            session.runtime
                .createViewer(
                    signer = session.signer,
                    consentHandler =
                        object : ConsentHandler {
                            override suspend fun requestConsent(terms: ConsentTerms): ConsentApproval =
                                ownedPayment(session) {
                                    try {
                                        requestConsent(session, terms)
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (err: Throwable) {
                                        // Recheck after an unavailable read without requesting another deposit.
                                        startRefresh(session)
                                        throw err
                                    }
                                }
                        },
                    clientConfig =
                        ClientConfig(
                            autoPaySegments = false,
                            expectedNetwork = params.mppNetwork.takeIf { session.solana != null },
                            expectedViewerAddress = viewerAddress.takeIf { session.solana != null },
                        ),
                ).also { viewer ->
                    session.viewer = viewer
                    liquidStreamViewer = viewer
                    viewer.rtcClient.onDataChannelOpen = {
                        if (activeStartParams === params) {
                            refreshVaultIdentity(viewerAddress, force = true)
                        }
                    }

                    viewer.rtcClient.onPaymentRequested = { request ->
                        if (session.solana != null) {
                            try {
                                session.runtime.checkAlive()
                                acceptSolanaRoute(session, request)
                                refreshVaultIdentity(viewerAddress)
                                startRefresh(session)
                            } catch (err: Throwable) {
                                rejectSolana(session, err)
                                endPaymentSession(session)
                                throw err // Abort before consent or direct payment.
                            }
                        } else {
                            trace("PAYMENT_REQUEST request=${request.id} amountMicro=${request.amount}")
                            if (activeStartParams === params) {
                                refreshVaultIdentity(viewerAddress)
                            }
                            Napier.d(
                                "[VIEWER_PAYMENT_REQUEST_RECEIVED] session=${request.id} payTo=${request.payTo} amount=${request.amount} asset=${request.asset}",
                                tag = TAG,
                            )
                        }
                    }

                    viewer.rtcClient.onPaymentReceipt = { receipt ->
                        trace(
                            "RECEIPT_QUEUED receiptSession=${receipt.sessionId} segment=${receipt.segmentIndex} amountMicro=${receipt.amount}",
                        )
                        launchSession(session) {
                            session.voucherMutex.withLock {
                                try {
                                    ensureCurrent(session)
                                    if (session.solana != null) {
                                        acceptSolanaReceipt(session, receipt)
                                    } else {
                                        handlePaymentReceipt(
                                            session = session,
                                            receiptSessionId = receipt.sessionId,
                                            receiptSegmentIndex = receipt.segmentIndex,
                                            receiptAmount = receipt.amount,
                                            receiptPayFrom = receipt.payFrom,
                                            receiptPayTo = receipt.payTo,
                                            txId = receipt.txId,
                                            vaultChannelId =
                                                if (receipt.billingMode ==
                                                    BillingMode.SESSION_VAULT
                                                ) {
                                                    receipt.channelId
                                                } else {
                                                    null
                                                },
                                        )
                                    }
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
                        launchSession(session) {
                            handleStreamGated(session)
                        }
                    }

                    Napier.d("[VIEWER_MPP_START] viewer=$viewerAddress network=${params.mppNetwork}", tag = TAG)
                    if (session.solana != null) viewer.rtcClient.onError = { reportSolana(session, it) }
                    viewer.start()
                    Napier.d("[VIEWER_MPP_STARTED] viewer=$viewerAddress", tag = TAG)
                }
        } catch (err: Throwable) {
            if (session.solana == null) throw err
            rejectSolana(session, err)
            endPaymentSession(session)
        }
    }

    fun startViewerOnChainRefresh(
        scope: CoroutineScope,
        viewerAddress: String,
        sessionVaultAppId: Long,
        authorizedSignerPublicKey: ByteArray? = null,
        setViewerSessionVaultProgress: (remainingBalanceMicroUsdc: Long, progressBalanceMicroUsdc: Long) -> Unit,
    ) {
        val session = paymentSession
        val solana = session?.solana
        if (solana != null) {
            if (!session.job.isActive || viewerAddress != session.params.viewerAddress) return
            session.progress = setViewerSessionVaultProgress
            if (viewerOnChainRefreshJob?.isActive == true) return
        }
        if (viewerAddress.isBlank()) {
            Napier.w("[VIEWER_SESSION_VAULT_REFRESH_SKIP] reason=blank_viewer", tag = TAG)
            return
        }
        // Napier.d("[VIEWER_SESSION_VAULT_REFRESH_START] viewer=$viewerAddress host=$sessionVaultHostAddress", tag = TAG)
        stopViewerOnChainRefresh()
        val refreshParams = activeStartParams
        viewerOnChainRefreshJob =
            (session?.scope ?: scope).launch {
                while (isActive) {
                    if ((solana == null || solana.route != null) && (session == null || session.mutex.tryLock())) {
                        var needsTopUp = false
                        try {
                            if (activeStartParams !== refreshParams || paymentSession !== session) return@launch
                            if (solana != null) {
                                refreshSolanaVault(session)
                            } else if (session != null && (session.pendingDeposit != null || externalConfirmationPending)) {
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
                                val balance =
                                    if (session != null) {
                                        readViewerBalance(session, remaining)
                                    } else {
                                        ViewerBalance(remaining, remaining)
                                    }
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
                            if (solana != null) {
                                reportSolana(session, err)
                            } else {
                                trace("POLL_ERROR errorType=${err::class.simpleName}")
                                Napier.e("[VIEWER_SESSION_VAULT_REFRESH_ERR] viewer=$viewerAddress", err, tag = TAG)
                            }
                        } finally {
                            session?.mutex?.unlock()
                        }
                        if (needsTopUp) liquidStreamViewer?.rtcClient?.onStreamGated?.invoke("Session balance at or below minimum")
                    } else {
                        trace("POLL_SKIPPED reason=payment_or_consent_lock")
                    }
                    delay(1000L.milliseconds)
                    if (solana != null) {
                        solana.voucherRefreshElapsed = (solana.voucherRefreshElapsed + 1000L).coerceAtMost(20_000L)
                    }
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
        launchSession(session) {
            val giftMicroUsdc =
                if (session.solana != null) {
                    require(giftUsdc.isFinite() && giftUsdc > 0 && giftUsdc * 1_000_000 < Long.MAX_VALUE.toDouble()) {
                        "Invalid Solana gift"
                    }
                    (giftUsdc * 1_000_000).roundToLong().also { require(it > 0L) }
                } else {
                    (giftUsdc * 1_000_000.0).roundToLong().coerceAtLeast(1L)
                }
            session.voucherMutex.withLock {
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
        if (session.solana != null) {
            val data = readSolanaData(session)
            drainSolanaDebits(session, data)
            reserveSolanaDebit(session, giftMicroUsdc, data)
            publishSolanaData(session, data)
            sendSolanaVoucher(session, data)
            return
        }
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
        val channelId =
            explicitChannel ?: params.channelIdProvider()?.copyOf() ?: run {
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

    private fun publishVaultSnapshot(
        session: PaymentSession,
        snapshot: MppPayments.SessionProgressSnapshot,
    ) {
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
            createVoucherJson(
                sessionId = obligation.sessionId,
                viewerAddress = obligation.viewerAddress,
                viewerPublicKey = params.signer.authorizedSignerPublicKey,
                creatorAddress = obligation.payTo,
                blocksConsumed = obligation.blocksConsumed,
                cumulativeAmount = obligation.cumulativeAmount,
                remainingMicroUsdc = progressSnapshot?.progressBalanceMicroUsdc ?: 0L,
                signedPayload = voucherSignature,
                chain =
                    VoucherChain.Algorand(
                        appId = params.sessionVaultAppId,
                        channelId = vaultChannelId ?: EscrowSessionVaultHybridManagerClient.channelId?.let(Base64::encode),
                        vaultOnly = vaultChannelId != null,
                    ),
            )
        Napier.e(
            "[SESSION_VAULT_VOUCHER_SEND_ATTEMPT] session=${obligation.sessionId} segment=${obligation.segmentIndex} claimedAmountMicroUsdc=${obligation.cumulativeAmount}",
            tag = TAG,
        )
        ensureCurrent(session)
        session.viewer.rtcClient.sendVoucher(voucherJson)
        trace("VOUCHER_SEND_ATTEMPT segment=${obligation.segmentIndex} cumulativeMicro=${obligation.cumulativeAmount}")
    }

    /** Own cancellation and processing cleanup once; only lock admission differs by policy. */
    private suspend fun <T> ownedPayment(
        session: PaymentSession,
        manual: Boolean = false,
        block: suspend () -> T,
    ): T =
        session.runtime.ownedRequest {
            val solana = session.solana != null
            if (solana) {
                check(session.paymentRequestMutex.tryLock()) { "Solana viewer payment is already in progress" }
            }
            try {
                if (manual && !solana) {
                    check(session.mutex.tryLock()) { "Viewer payment is already in progress" }
                } else {
                    // A Solana payment reserves admission while awaiting a polling RPC.
                    session.mutex.lock()
                }
                try {
                    ensureCurrent(session)
                    block()
                } finally {
                    try {
                        finishProcessing(session)
                    } finally {
                        session.mutex.unlock()
                    }
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                if (solana) reportSolana(session, err)
                throw err
            } finally {
                if (solana) {
                    session.paymentRequestMutex.unlock()
                    if (paymentSession === session && session.job.isActive) startRefresh(session)
                }
            }
        }

    private fun launchSession(
        session: PaymentSession,
        block: suspend () -> Unit,
    ) {
        if (!session.job.isActive) return
        session.scope.launch {
            try {
                ensureCurrent(session)
                block()
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                if (session.solana == null) throw err
                reportSolana(session, err)
            }
        }
    }

    private fun prepareSolanaSigner(session: PaymentSession) {
        val params = session.params
        val state = checkNotNull(session.solana)
        val signerKey = state.signerKey
        SolanaStreamVault.cluster(params.mppNetwork)
        require(Base58.decode(params.viewerAddress).size == 32) { "Invalid Solana viewer owner" }
        require(signerKey.size == 32 && signerKey.any { it != 0.toByte() }) { "Invalid linked Solana signer" }
        require(!Base58.decode(params.viewerAddress).contentEquals(signerKey)) { "A separate linked Solana signer is required" }
        checkNotNull(params.signSolanaTransaction) { "Solana owner approval callback is unavailable" }
        // Core can suspend before signing. Guard the actual signing boundary, not just callers.
        session.signer =
            object : MppWalletSigner by params.signer {
                override val authorizedSignerPublicKey: ByteArray get() = signerKey.copyOf()

                override suspend fun signMessage(message: ByteArray): ByteArray {
                    ensureCurrent(session)
                    return params.signer.signMessage(message).also { ensureCurrent(session) }
                }
            }
    }

    private fun acceptSolanaRoute(
        session: PaymentSession,
        request: PaymentRequest,
    ) {
        val params = session.params
        val state = checkNotNull(session.solana)
        val signerKey = state.signerKey
        require(request.network == params.mppNetwork) { "Solana payment network mismatch" }
        val vault = SolanaStreamVault.config(request.network)
        require(request.billingMode == BillingMode.SESSION_VAULT) {
            "Solana request billing mode mismatch: expected=${BillingMode.SESSION_VAULT}, received=${request.billingMode}"
        }
        require(request.asset == "USDC") {
            "Solana request asset mismatch: expected=USDC, received=${request.asset}"
        }
        require(request.meta.segmentDuration == 3) { "Solana stream billing requires 3-second segments" }
        require(request.sessionId.isNotBlank() && (request.amount.toLongOrNull() ?: -1L) >= 0) { "Invalid Solana payment request" }
        require(Base58.decode(request.payTo).size == 32) { "Invalid Solana creator" }
        require(state.expectedHost.isBlank() || request.payTo == state.expectedHost) { "Solana creator changed" }
        require(request.meta.viewerAddress == null || request.meta.viewerAddress == params.viewerAddress) { "Solana viewer mismatch" }
        val payload = requireNotNull(request.railPayload).jsonObject
        require(payload["solanaProgramId"]?.jsonPrimitive?.content == vault.programId) {
            "Solana vault program mismatch"
        }
        require(payload["solanaMint"]?.jsonPrimitive?.content == vault.usdcMint) {
            "Solana vault mint mismatch"
        }
        val feePayer = requireNotNull(payload["solanaFeePayer"]?.jsonPrimitive?.content) { "Missing Solana creator fee payer" }
        val feeKey = Base58.decode(feePayer)
        require(feeKey.size == 32 && feeKey.any { it != 0.toByte() }) { "Invalid Solana fee payer" }
        require(feePayer != request.payTo && feePayer != params.viewerAddress && !feeKey.contentEquals(signerKey)) {
            "Solana settlement requires a separate silent creator fee payer"
        }
        val channel = SolanaStreamVault.deriveChannel(params.viewerAddress, request.payTo, signerKey.copyOf(), params.mppNetwork)
        require(channel.contentEquals(Base64.decode(requireNotNull(request.channelId)))) { "Solana channel mismatch" }
        require(
            SolanaStreamVault
                .salt(params.viewerAddress, request.payTo, signerKey.copyOf())
                .contentEquals(Base64.decode(requireNotNull(request.salt))),
        ) { "Solana channel salt mismatch" }
        val next = SolanaRoute(request.sessionId, request.payTo, Base64.encode(channel), feePayer)
        require(state.route == null || state.route == next) { "Solana payment route changed" }
        state.route = next
    }

    private suspend fun refreshSolanaVault(session: PaymentSession) {
        val state = checkNotNull(session.solana)
        val data = readSolanaData(session)
        confirmSolanaFunding(session, data)
        session.voucherMutex.withLock {
            drainSolanaDebits(session, data)
            if (state.cumulative > data.lastSettled && state.voucherRefreshElapsed >= 20_000L) {
                // Same cumulative obligation; never cache an expiring blockhash.
                sendSolanaVoucher(session, data)
            }
        }
    }

    private suspend fun readSolanaData(session: PaymentSession): MppPayments.SessionDynamicData {
        ensureCurrent(session)
        val params = session.params
        val state = checkNotNull(session.solana)
        val target = checkNotNull(state.route) { "Solana payment route is not available yet" }
        // Only confirmed getAccountInfo(value=null) is zero; RPC/validation errors must fail.
        val result =
            SolanaStreamVault.read(
                params.viewerAddress,
                target.payTo,
                state.signerKey.copyOf(),
                params.mppNetwork,
                Base64.decode(target.channel),
            )
        ensureCurrent(session)
        val snapshot = result.getOrThrow()
        val data =
            MppPayments.SessionDynamicData(
                snapshot.totalDepositMicroUsdc,
                snapshot.lastSettledMicroUsdc,
                snapshot.totalDepositMicroUsdc - snapshot.progressBalanceMicroUsdc,
                snapshot.startRound ?: 0L,
            )
        require(data.totalDeposit >= 0 && data.lastSettled in 0..data.totalDeposit && data.latestVoucherAmount in 0..data.totalDeposit) {
            "Invalid Solana vault balance"
        }
        state.observed?.let {
            check(data.startRound == it.startRound || it.totalDeposit == 0L) { "Solana vault lifetime changed" }
            check(data.totalDeposit >= it.totalDeposit && data.lastSettled >= it.lastSettled) { "Stale Solana vault read" }
        }
        state.observed = data
        publishSolanaData(session, data)
        return data
    }

    private fun publishSolanaData(
        session: PaymentSession,
        data: MppPayments.SessionDynamicData,
    ) {
        session.runtime.checkAlive()
        val snapshot =
            MppPayments.computeSessionProgressSnapshot(
                data.copy(latestVoucherAmount = maxOf(data.latestVoucherAmount, checkNotNull(session.solana).cumulative)),
            )
        session.progress(snapshot.remainingSettledMicroUsdc, snapshot.progressBalanceMicroUsdc)
        session.runtime.checkAlive()
        publishVaultSnapshot(session, snapshot)
    }

    private suspend fun requestSolanaConsent(
        session: PaymentSession,
        value: ConsentTerms,
    ): ConsentApproval {
        ensureCurrent(session)
        val params = session.params
        val state = checkNotNull(session.solana)
        checkNotNull(state.route) { "Missing validated Solana payment route" }
        require(value.network == params.mppNetwork && value.billingMode == BillingMode.SESSION_VAULT && value.asset == "USDC") {
            "Solana consent route mismatch"
        }
        state.terms = value
        check(!pendingPayment || session.pendingDeposit != null) { "Solana payment is already pending" }
        var data = readSolanaData(session)
        if (session.pendingDeposit != null) {
            check(confirmSolanaFunding(session, data, resume = false)) { "Solana deposit confirmation is pending; do not pay again" }
        }
        val required = requireNotNull(value.amount.toLongOrNull()).also { require(it >= 0) }
        if (required > 0L && currentStreamCostMicroUsdc != 0L && data.totalDeposit - data.lastSettled <= session.minimumBalance()) {
            val approval = params.requestMppConsent(value)
            ensureCurrent(session)
            if (!approval.approved) return approval
            val cap = requireNotNull(approval.budgetCap) { "Solana deposit approval must specify an amount" }
            require(cap.asset == "USDC") { "Solana deposit asset mismatch" }
            val deposit = requireNotNull(cap.amount.toLongOrNull()) { "Invalid Solana deposit amount" }
            require(deposit > 0L) { "Solana deposit must be a positive exact increment" }
            fundSolana(session, deposit, extendBudget = true)
            data = readSolanaData(session)
        }
        val balance = spendableBalance(data, state.cumulative)
        if (balance < required) return ConsentApproval(false, autoPaySegments = false)
        val spent =
            session.viewer.rtcClient.spend.totalAmount
                .toLongOrNull() ?: 0L
        check(spent <= Long.MAX_VALUE - balance) { "Solana budget overflow" }
        return ConsentApproval(true, autoPaySegments = true, budgetCap = BudgetCap((spent + balance).toString(), "USDC"))
    }

    private suspend fun handleSolanaGated(session: PaymentSession) {
        val state = checkNotNull(session.solana)
        ensureCurrent(session)
        val data = readSolanaData(session)
        if (session.pendingDeposit != null) {
            confirmSolanaFunding(session, data)
            return
        }
        if (pendingPayment || data.totalDeposit - data.lastSettled > session.minimumBalance()) return
        val value = state.terms ?: return
        val approval =
            session.params.requestMppConsent(value.copy(amount = MppPayments.voucherSettleWindowMicroUsdc().toString()))
        ensureCurrent(session)
        if (!approval.approved) return
        val cap = requireNotNull(approval.budgetCap) { "Solana deposit approval must specify an amount" }
        require(cap.asset == "USDC")
        fundSolana(session, requireNotNull(cap.amount.toLongOrNull()), extendBudget = true)
    }

    private suspend fun fundSolana(
        session: PaymentSession,
        amount: Long,
        extendBudget: Boolean,
    ): Long {
        ensureCurrent(session)
        val params = session.params
        val state = checkNotNull(session.solana)
        require(amount > 0L)
        check(session.pendingDeposit == null) { "Solana deposit is awaiting confirmation" }
        val target = checkNotNull(state.route)
        val sign = checkNotNull(params.signSolanaTransaction) { "Solana owner approval callback is unavailable" }
        val baseline = readSolanaData(session)
        check(baseline.totalDeposit <= Long.MAX_VALUE - amount) { "Solana deposit overflow" }
        session.expectedTotalDeposit = baseline.totalDeposit + amount
        session.pendingDeposit = amount
        markPaymentPending()
        session.processing = true
        params.setViewerPaymentProcessing(true)
        var signatureReturned = false
        val owner =
            object : SolanaTransactionSigner {
                override val address = params.viewerAddress

                override suspend fun signMessage(message: ByteArray): ByteArray {
                    ensureCurrent(session)
                    return sign(address, message).also {
                        ensureCurrent(session)
                        require(it.size == 64) { "Invalid Solana owner signature" }
                        signatureReturned = true
                    }
                }
            }
        ensureCurrent(session)
        try {
            SolanaStreamVault.fund(params.mppNetwork, owner, target.payTo, state.signerKey.copyOf(), amount).getOrThrow()
        } catch (err: Throwable) {
            // Without a returned signature core cannot submit. Once signed, retain the exact
            // confirmation target even after cancellation or an ambiguous submission error.
            if (!signatureReturned && session.job.isActive) {
                session.pendingDeposit = null
                session.expectedTotalDeposit = null
                clearPendingPayment()
            }
            throw err
        }
        ensureCurrent(session)
        refreshVaultIdentity(params.viewerAddress, true)
        val data = readSolanaData(session)
        check(confirmSolanaFunding(session, data, resume = extendBudget)) { "Solana deposit confirmation is pending; do not pay again" }
        return data.totalDeposit - data.lastSettled
    }

    private fun confirmSolanaFunding(
        session: PaymentSession,
        data: MppPayments.SessionDynamicData,
        resume: Boolean = true,
    ): Boolean {
        val expected = session.expectedTotalDeposit ?: return false
        if (data.totalDeposit < expected) return false
        session.runtime.checkAlive()
        val state = checkNotNull(session.solana)
        session.pendingDeposit = null
        session.expectedTotalDeposit = null
        clearPendingPayment()
        // Initial consent alone can suppress resume while still returning approval. Polling
        // must restore the cap and notify the pinned request after an ambiguous confirmation.
        if (resume) {
            session.viewer.rtcClient.extendBudget(spendableBalance(data, state.cumulative), "USDC")
            session.runtime.checkAlive()
            session.viewer.rtcClient.notifyVaultFunded(checkNotNull(state.route).session)
        }
        refreshVaultIdentity(session.params.viewerAddress, true)
        return true
    }

    private suspend fun acceptSolanaReceipt(
        session: PaymentSession,
        receipt: PaymentReceipt,
    ) {
        ensureCurrent(session)
        val params = session.params
        val state = checkNotNull(session.solana)
        val target = checkNotNull(state.route) { "Solana receipt arrived before a validated route" }
        require(receipt.network == params.mppNetwork) {
            "Solana receipt network mismatch: expected=${params.mppNetwork}, received=${receipt.network}"
        }
        require(receipt.asset == "USDC") {
            "Solana receipt asset mismatch: expected=USDC, received=${receipt.asset}"
        }
        require(receipt.billingMode == BillingMode.SESSION_VAULT) {
            "Solana receipt billing mode mismatch: expected=${BillingMode.SESSION_VAULT}, received=${receipt.billingMode}"
        }
        require(receipt.sessionId == target.session && receipt.payTo == target.payTo && receipt.payFrom == params.viewerAddress) {
            "Solana receipt participants changed"
        }
        require(receipt.channelId == target.channel && (receipt.feePayer == null || receipt.feePayer == target.feePayer)) {
            "Solana receipt channel/fee payer mismatch"
        }
        val key = receipt.sessionId to receipt.segmentIndex
        if (key in state.receipts) return
        val debit = requireNotNull(receipt.amount.toLongOrNull())
        require(debit >= 0L && receipt.segmentIndex >= 0)
        state.receipts += key // Queue before RPC so a failed read cannot lose the receipt.
        state.pendingDebits.addLast(debit to true)
        val data = readSolanaData(session)
        drainSolanaDebits(session, data)
        if (state.cumulative > data.lastSettled) sendSolanaVoucher(session, data)
    }

    private fun reserveSolanaDebit(
        session: PaymentSession,
        amount: Long,
        data: MppPayments.SessionDynamicData,
    ) {
        val state = checkNotNull(session.solana)
        val base = maxOf(state.cumulative, data.lastSettled, data.latestVoucherAmount)
        require(amount >= 0 && amount <= data.totalDeposit - base) { "Insufficient Solana vault balance" }
        state.cumulative = base + amount
    }

    private fun drainSolanaDebits(
        session: PaymentSession,
        data: MppPayments.SessionDynamicData,
    ) {
        val state = checkNotNull(session.solana)
        while (state.pendingDebits.isNotEmpty()) {
            val (amount, isReceipt) = state.pendingDebits.first()
            reserveSolanaDebit(session, amount, data)
            state.pendingDebits.removeFirst()
            if (isReceipt) state.blocks++
        }
        publishSolanaData(session, data)
    }

    private suspend fun sendSolanaVoucher(
        session: PaymentSession,
        data: MppPayments.SessionDynamicData,
    ) {
        ensureCurrent(session)
        val params = session.params
        val state = checkNotNull(session.solana)
        val target = checkNotNull(state.route)
        val amount = state.cumulative
        val voucher =
            SolanaStreamVault.createVoucher(
                params.mppNetwork,
                params.viewerAddress,
                target.payTo,
                session.signer,
                Base64.decode(target.channel),
                amount,
                target.feePayer,
            )
        ensureCurrent(session)
        val wire =
            createVoucherJson(
                sessionId = target.session,
                viewerAddress = params.viewerAddress,
                viewerPublicKey = state.signerKey,
                creatorAddress = target.payTo,
                blocksConsumed = state.blocks,
                cumulativeAmount = amount,
                remainingMicroUsdc = spendableBalance(data, state.cumulative),
                signedPayload = voucher.partiallySignedTransaction,
                chain =
                    VoucherChain.Solana(
                        network = params.mppNetwork,
                        channelId = target.channel,
                        feePayer = target.feePayer,
                    ),
            )
        session.viewer.rtcClient.sendVoucher(wire)
        state.voucherRefreshElapsed = 0L
    }

    private fun reportSolana(
        session: PaymentSession,
        error: Throwable,
    ) {
        if (!session.job.isActive || error is CancellationException) return
        Napier.e("[SOLANA_VIEWER_PAYMENT_ERROR]", error, tag = "SolanaMppPaymentViewer")
        session.params.onPaymentError(error.message ?: "Solana payment is temporarily unavailable")
    }

    private fun rejectSolana(
        session: PaymentSession,
        error: Throwable,
    ) {
        if (!session.job.isActive || error is CancellationException) return
        Napier.e("[SOLANA_VIEWER_PROTOCOL_ERROR]", error, tag = "SolanaMppPaymentViewer")
        session.params.onStreamRejected("solana_viewer_protocol_error", error.message)
    }

    private fun endPaymentSession(session: PaymentSession) {
        // Keep a rejected Solana session pinned until explicit stop/restart. Clearing it here
        // would let a later manual top-up fall through to caller-provided Algorand closures.
        session.job.cancel()
        if (paymentSession !== session) return
        finishProcessing(session)
        stopViewerOnChainRefresh()
        liquidStreamViewer?.terminate()
        liquidStreamViewer = null
    }

    private fun cancelPaymentSession() {
        val session = paymentSession ?: return
        endPaymentSession(session)
        paymentSession = null
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
        session.runtime.ensureCurrent()
        if (paymentSession !== session) throw CancellationException("Viewer session ended")
    }

    private fun startRefresh(session: PaymentSession) {
        if (paymentSession !== session || !session.job.isActive) return
        val params = session.params
        startViewerOnChainRefresh(
            scope = session.scope,
            viewerAddress = params.viewerAddress,
            sessionVaultAppId = params.sessionVaultAppId,
            authorizedSignerPublicKey = params.signer.authorizedSignerPublicKey,
            setViewerSessionVaultProgress = session.progress,
        )
    }

    /** Display-only polling must not initiate another payment or outlive the consent flow. */
    private fun startConsentUiRefresh(session: PaymentSession) {
        session.consentUiRefreshJob?.cancel()
        session.consentUiRefreshJob =
            session.scope.launch {
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

    private fun observeVault(
        session: PaymentSession,
        channel: ByteArray,
    ) {
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

    private data class ViewerBalance(
        val onChainRemaining: Long,
        val spendable: Long,
    )

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
        val available =
            if (data == null) {
                remaining
            } else {
                minOf(
                    if (remaining == 0L) (data.totalDeposit - data.lastSettled).coerceAtLeast(0L) else remaining,
                    spendableBalance(data, viewerVoucherClaimedMicroUsdc),
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

    private suspend fun isAboveMinimum(
        session: PaymentSession,
        remaining: Long,
        source: String,
    ): Boolean {
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
        // Never execute an Algo funding/read closure for a Solana connection.
        topUpSolanaViewerSessionVault(viewerAddress, depositMicroUsdc)?.let { return it }
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
        return ownedPayment(session, manual = true) {
            ensureCurrent(session)
            if (session.pendingDeposit != null) {
                confirmFunding(session)
            } else {
                performFunding(session, depositMicroUsdc, gated = false, fund = fund)
            }
        }
    }

    /** Null means no Solana viewer session; failures are surfaced and thrown, not Algo fallback. */
    suspend fun topUpSolanaViewerSessionVault(
        viewerAddress: String,
        amount: Long,
    ): Long? {
        val session = paymentSession?.takeIf { it.solana != null } ?: return null
        return ownedPayment(session, manual = true) {
            require(viewerAddress == session.params.viewerAddress) { "Solana viewer changed" }
            require(amount > 0L) { "Solana deposit must be a positive exact increment" }
            if (session.pendingDeposit != null) {
                val data = readSolanaData(session)
                check(confirmSolanaFunding(session, data)) { "Solana deposit confirmation is pending; do not pay again" }
                data.totalDeposit - data.lastSettled
            } else {
                fundSolana(session, amount, extendBudget = true)
            }
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
        if (session.solana != null) return requestSolanaConsent(session, terms)
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
                    spendableBalance(data, viewerVoucherClaimedMicroUsdc),
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
        val solana = session.solana
        if (solana != null && (solana.terms?.amount?.toLongOrNull() == 0L || !session.paymentRequestMutex.tryLock())) return
        if (!session.mutex.tryLock()) {
            if (solana != null) session.paymentRequestMutex.unlock()
            trace("POPUP_SKIPPED reason=payment_or_consent_lock")
            return
        }
        try {
            if (solana != null) {
                handleSolanaGated(session)
                return
            }
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
            if (solana != null) throw err // launchSession reports Solana errors to the UI.
            trace("CONSENT_ERROR source=stream_gated errorType=${err::class.simpleName}")
            Napier.e("[VIEWER_STREAM_GATED_CONSENT_ERR] viewer=${session.params.viewerAddress}", err, tag = TAG)
        } finally {
            try {
                finishProcessing(session)
            } finally {
                session.mutex.unlock()
                if (solana != null) session.paymentRequestMutex.unlock()
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
            val topUpErrorType = topUpResult.exceptionOrNull()?.let { it::class.simpleName }
            trace("DEPOSIT_TX_RESULT route=top_up success=${topUpResult.isSuccess} errorType=$topUpErrorType")
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
        val openErrorType = openResult.exceptionOrNull()?.let { it::class.simpleName }
        trace("DEPOSIT_TX_RESULT route=open_session success=${openResult.isSuccess} errorType=$openErrorType")
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
