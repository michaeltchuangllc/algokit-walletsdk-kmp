package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.account.domain.usecase.core.GetAccountASABalance
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.foundation.utils.LiquidStreamConstants
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.LiquidAuthOffer
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.GenerateLiquidAuthOfferUseCase
import com.michaeltchuang.walletsdk.core.network.domain.usecase.GetCurrentNetworkUseCase
import com.michaeltchuang.walletsdk.core.network.model.AlgorandNetwork
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.EnforcementMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequestMeta
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerDetails
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerProgress
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class LiquidAuthOfferViewModel(
    private val generateOfferUseCase: GenerateLiquidAuthOfferUseCase,
    private val stateDelegate: StateDelegate<OfferState>,
    private val eventDelegate: EventDelegate<OfferEvent>,
    private val getAccountASABalance: GetAccountASABalance,
    private val getCurrentBlockUseCase: GetCurrentBlockUseCase,
    private val getCurrentNetworkUseCase: GetCurrentNetworkUseCase,
    private val mppWalletSignerUseCase: MppWalletSignerUseCase,
) : ViewModel(),
    StateViewModel<LiquidAuthOfferViewModel.OfferState> by stateDelegate,
    EventViewModel<LiquidAuthOfferViewModel.OfferEvent> by eventDelegate {
    // ICE Connection type for UI quality indicators and billing (x402)
    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    val connectionType: StateFlow<IceConnectionType> = _connectionType

    // Android opts in during manager initialization. iOS keeps the single-peer path.
    var meshHostingEnabled: Boolean = false

    // The initial invitation stays in OfferState; subsequent invitations must not replace
    // Connected/WaitingForPayment/Streaming or their singleton payment session.
    private val _pendingMeshOffer = MutableStateFlow<OfferState.WaitingForConnection?>(null)
    val pendingMeshOffer: StateFlow<OfferState.WaitingForConnection?> = _pendingMeshOffer.asStateFlow()

    private val _meshViewerIds = MutableStateFlow<List<String>>(emptyList())
    val meshViewerIds: StateFlow<List<String>> = _meshViewerIds.asStateFlow()

    private val _meshViewerDetails = MutableStateFlow<Map<String, HostViewerDetails>>(emptyMap())
    private val meshViewerProgress = mutableMapOf<String, HostViewerProgress>()
    val meshViewerDetails: StateFlow<Map<String, HostViewerDetails>> = _meshViewerDetails.asStateFlow()

    private val _meshError = MutableStateFlow<String?>(null)
    val meshError: StateFlow<String?> = _meshError.asStateFlow()

    private var hasMeshViewerConnected = false
    private var offerGenerationJob: Job? = null

    // X402 Payment state
    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.NoPayment)
    val paymentState: StateFlow<PaymentState> = _paymentState

    // Host balance model:
    // - remainingBalanceMicroUsdc: settled on-chain remaining from Session Vault
    // - progressBarBalanceMicroUsdc: effective UI/progress balance (on-chain minus unsettled voucher)
    // - lastSettledMicroUsdc: total cumulative amount settled on-chain (smart contract source-of-truth)
    private val _remainingBalanceMicroUsdc = MutableStateFlow<Long?>(null)
    val remainingBalanceMicroUsdc: StateFlow<Long?> = _remainingBalanceMicroUsdc
    private val _progressBarBalanceMicroUsdc = MutableStateFlow<Long?>(null)
    val progressBarBalanceMicroUsdc: StateFlow<Long?> = _progressBarBalanceMicroUsdc
    private val _lastSettledMicroUsdc = MutableStateFlow<Long?>(null)
    val lastSettledMicroUsdc: StateFlow<Long?> = _lastSettledMicroUsdc

    // ASA balance check for QR visibility (null => not opted in / unavailable)
    private val _creatorAsaBalance = MutableStateFlow<String?>(null)
    val creatorAsaBalance: StateFlow<String?> = _creatorAsaBalance

    private val _isCheckingCreatorAsaBalance = MutableStateFlow(false)
    val isCheckingCreatorAsaBalance: StateFlow<Boolean> = _isCheckingCreatorAsaBalance

    // Current Algorand block number for UI display
    private val _currentBlockNumber = MutableStateFlow<Long?>(null)
    val currentBlockNumber: StateFlow<Long?> = _currentBlockNumber

    // Current network label for UI display
    private val currentNetworkFlow = MutableStateFlow(AlgorandNetwork.TESTNET)
    val currentNetworkLabel: StateFlow<String> =
        currentNetworkFlow
            .map { network ->
                network.displayName.uppercase()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "TESTNET")

    // Payment session ID
    private var paymentSessionId: String? = null

    // Dynamic cost per block for settlement
    var currentCostPerBlockMicroUsdc: Long = COST_PER_BLOCK_MICRO_USDC

    // Real-time block polling job (for UI block number updates)
    private var blockNumberPollingJob: Job? = null

    // Payment consumption monitor job (must be singleton to avoid double-deduction)
    private var blockchainMonitorJob: Job? = null

    private var creatorAddress: String? = null

    companion object {
        const val DEPOSIT_AMOUNT_MICRO_USDC = LiquidStreamConstants.DEPOSIT_AMOUNT_MICRO_USDC
        const val COST_PER_BLOCK_MICRO_USDC = LiquidStreamConstants.COST_PER_BLOCK_MICRO_USDC
        private const val USDC_ASSET = "USDC"
    }

    init {
        stateDelegate.setDefaultState(OfferState.Idle)
        observeCurrentNetwork()
    }

    /**
     * Generate a new liquid auth offer with QR code data
     */
    fun generateOffer(origin: String) {
        // Re-entering the screen or refreshing an invitation must not reset a live host.
        if (meshHostingEnabled && (hasMeshViewerConnected || getCurrentSessionId() != null)) {
            if (_pendingMeshOffer.value == null) refreshMeshInvitation(origin)
            return
        }
        offerGenerationJob?.cancel()
        stateDelegate.updateState { OfferState.Loading }
        offerGenerationJob =
            viewModelScope.launch {
                try {
                    val offer = generateOfferUseCase.generateOffer(origin)
                    stateDelegate.updateState {
                        OfferState.WaitingForConnection(
                            requestId = offer.requestId,
                            liquidAuthUrl = offer.liquidAuthUrl,
                            origin = offer.origin,
                        )
                    }
                    eventDelegate.sendEvent(OfferEvent.OfferGenerated(offer.requestId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stateDelegate.updateState {
                        OfferState.Error(e.message ?: "Failed to generate offer")
                    }
                    eventDelegate.sendEvent(
                        OfferEvent.ShowError(e.message ?: "Failed to generate offer"),
                    )
                }
            }
    }

    /**
     * One new invitation per viewer. Only the first viewer enters the legacy
     * connection/payment state machine; later viewers never replace its session.
     * Like the legacy callbacks, these methods are called on the UI thread.
     */
    fun onMeshViewerConnected(requestId: String) {
        if (!meshHostingEnabled || requestId.isBlank() || requestId in _meshViewerIds.value) return
        val origin = _pendingMeshOffer.value?.origin ?: getCurrentOffer()?.origin
        _meshViewerIds.value = _meshViewerIds.value + requestId
        if (!hasMeshViewerConnected) {
            hasMeshViewerConnected = true
            offerGenerationJob?.cancel()
            onClientConnected(requestId)
        }
        if (_pendingMeshOffer.value?.requestId == requestId) {
            _pendingMeshOffer.value = null
        }
        origin?.let(::refreshMeshInvitation)
    }

    /** A peer leaving, including the last peer, does not stop the host or close its vault. */
    fun onMeshViewerDisconnected(requestId: String) {
        if (!meshHostingEnabled) return
        _meshViewerIds.value = _meshViewerIds.value.filterNot { it == requestId }
        _meshViewerDetails.value = _meshViewerDetails.value - requestId
        meshViewerProgress.remove(requestId)
    }

    /**
     * Replace the full snapshot for a connected request, on the UI thread like membership callbacks.
     * Managers should copy meshViewerDetails.value[requestId] to preserve fields from other callbacks.
     * Late updates after disconnect/shutdown are ignored.
     */
    fun updateMeshViewerDetails(
        requestId: String,
        details: HostViewerDetails,
    ) {
        if (requestId !in _meshViewerIds.value) return
        val updated = meshViewerProgress.getOrPut(requestId) { HostViewerProgress() }.update(details)
        _meshViewerDetails.value = _meshViewerDetails.value + (requestId to updated)
    }

    /**
     * Surface invitation/extra-viewer payment failures without interrupting the host.
     * Legacy vault billing remains primary-only; the manager supplies the limitation
     * message for unsupported additional viewers instead of silently failing video.
     */
    fun onMeshViewerError(message: String) {
        _meshError.value = message.ifBlank { "Unable to connect this viewer. Please try a new invitation." }
    }

    /**
     * Invalidate only the failed invitation, never a newer replacement or a live peer.
     * The UI hides the failed QR and keeps manual Refresh available; do not auto-retry.
     */
    fun onMeshInvitationFailed(
        requestId: String,
        message: String,
    ) {
        val pending = _pendingMeshOffer.value
        if (pending?.requestId == requestId) {
            _pendingMeshOffer.compareAndSet(pending, null)
        }
        onMeshViewerError(message)
    }

    fun dismissMeshError() {
        _meshError.value = null
    }

    /**
     * Refresh only the invitation, never the live offer/payment state.
     * This is deliberately not generateOffer(): no Loading state or legacy OfferGenerated
     * event is emitted. The screen listens to pendingMeshOffer independently.
     */
    fun refreshMeshInvitation(origin: String) {
        if (!meshHostingEnabled) return
        try {
            // The use case generates a fresh UUID and matching URL on every call.
            val offer = generateOfferUseCase.generateOffer(origin)
            _pendingMeshOffer.value =
                OfferState.WaitingForConnection(
                    requestId = offer.requestId,
                    liquidAuthUrl = offer.liquidAuthUrl,
                    origin = offer.origin,
                )
        } catch (e: Exception) {
            onMeshViewerError(e.message ?: "Failed to generate invitation")
        }
    }

    /** Called on host shutdown, not on peer disconnect. Retains the platform opt-in. */
    fun clearMeshHosting() {
        offerGenerationJob?.cancel()
        offerGenerationJob = null
        _pendingMeshOffer.value = null
        _meshViewerIds.value = emptyList()
        _meshViewerDetails.value = emptyMap()
        meshViewerProgress.clear()
        dismissMeshError()
        hasMeshViewerConnected = false
        if (meshHostingEnabled) {
            stateDelegate.updateState { OfferState.Idle }
        }
    }

    /**
     * Called when a client successfully connects via WebRTC
     */
    fun onClientConnected(sessionId: String) {
        Napier.d("💰 onClientConnected called with sessionId=$sessionId")
        val currentState = state.value
        if (currentState is OfferState.WaitingForConnection) {
            Napier.d("💰 Transitioning from WaitingForConnection to Connected")
            stateDelegate.updateState {
                OfferState.Connected(
                    requestId = currentState.requestId,
                    liquidAuthUrl = currentState.liquidAuthUrl,
                    origin = currentState.origin,
                    sessionId = sessionId,
                )
            }
            Napier.d("💰 Emitting ClientConnected event")
            viewModelScope.launch {
                eventDelegate.sendEvent(OfferEvent.ClientConnected(sessionId))
            }
        }
    }

    /**
     * Request MPP payment from client before starting paid streaming.
     * Call this when enablePaidStreaming is true and client connects.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun requestPaymentFromClient(
        creatorAddress: String,
        network: String = "testnet",
    ) {
        val currentState = state.value
        Napier.d(
            "💰 requestPaymentFromClient called, currentState=${currentState::class.simpleName}, " +
                "creatorAddress=$creatorAddress, network=$network, sessionId=${getCurrentSessionId()}",
        )
        if (currentState !is OfferState.Connected) {
            viewModelScope.launch {
                eventDelegate.sendEvent(
                    OfferEvent.ShowError("Must be connected to request payment"),
                )
            }
            return
        } else {
            Napier.d("💰 State is Connected, proceeding with payment request")
        }

        // Generate payment session ID
        paymentSessionId = Uuid.random().toString()

        // Create payment request
        val paymentRequest =
            PaymentRequest(
                id = paymentSessionId!!,
                sessionId = getCurrentSessionId() ?: paymentSessionId!!,
                segmentIndex = 0,
                amount = currentCostPerBlockMicroUsdc.toString(),
                asset = USDC_ASSET,
                network = network,
                payTo = creatorAddress,
                ttl = 30,
                nonce = paymentSessionId!!,
                meta =
                    PaymentRequestMeta(
                        gatingMode = GatingMode.PARTIAL_TIME,
                        enforcement = EnforcementMode.TRACK,
                        segmentDuration = 3,
                        voucherSignature = null,
                    ),
            )
        Napier.d("💰 Created payment request: ${paymentRequest.id}, amount=${paymentRequest.amount}")

        _paymentState.value =
            PaymentState.WaitingForDeposit(
                paymentRequest = paymentRequest,
            )

        // Transition to waiting for payment state
        stateDelegate.updateState {
            OfferState.WaitingForPayment(
                requestId = currentState.requestId,
                liquidAuthUrl = currentState.liquidAuthUrl,
                origin = currentState.origin,
                sessionId = currentState.sessionId,
                paymentRequest = paymentRequest,
            )
        }
        Napier.d("💰 Transitioned to WaitingForPayment state")

        viewModelScope.launch {
            Napier.d("💰 Emitting OfferEvent.PaymentRequested for session=${paymentRequest.id}")
            eventDelegate.sendEvent(OfferEvent.PaymentRequested(paymentRequest))
            Napier.d("💰 OfferEvent.PaymentRequested emitted")
        }
        Napier.d("💰 Payment request ready to be sent")
    }

    /**
     * Start video streaming to the connected client
     */
    fun startVideoStreaming() {
        val currentState = state.value
        when (currentState) {
            is OfferState.Connected -> {
                stateDelegate.updateState {
                    OfferState.Streaming(
                        requestId = currentState.requestId,
                        liquidAuthUrl = currentState.liquidAuthUrl,
                        origin = currentState.origin,
                        sessionId = currentState.sessionId,
                    )
                }
                viewModelScope.launch {
                    eventDelegate.sendEvent(OfferEvent.VideoStreamingStarted)
                }
            }
            is OfferState.WaitingForPayment -> {
                stateDelegate.updateState {
                    OfferState.Streaming(
                        requestId = currentState.requestId,
                        liquidAuthUrl = currentState.liquidAuthUrl,
                        origin = currentState.origin,
                        sessionId = currentState.sessionId,
                    )
                }
                viewModelScope.launch {
                    eventDelegate.sendEvent(OfferEvent.VideoStreamingStarted)
                }
            }
            else -> { /* no-op */ }
        }
    }

    /**
     * Stop video streaming
     */
    fun stopVideoStreaming() {
        val currentState = state.value
        if (currentState is OfferState.Streaming) {
            stateDelegate.updateState {
                OfferState.Connected(
                    requestId = currentState.requestId,
                    liquidAuthUrl = currentState.liquidAuthUrl,
                    origin = currentState.origin,
                    sessionId = currentState.sessionId,
                )
            }
            viewModelScope.launch {
                eventDelegate.sendEvent(OfferEvent.VideoStreamingStopped)
            }
        }
    }

    /**
     * Called when client disconnects
     */
    fun onClientDisconnected(creatorAddress: String?) {
        blockchainMonitorJob?.cancel()
        blockchainMonitorJob = null
        val currentState = state.value
        when (currentState) {
            is OfferState.Connected -> {
                stateDelegate.updateState {
                    OfferState.WaitingForConnection(
                        requestId = currentState.requestId,
                        liquidAuthUrl = currentState.liquidAuthUrl,
                        origin = currentState.origin,
                    )
                }
                viewModelScope.launch {
                    eventDelegate.sendEvent(OfferEvent.ClientDisconnected)
                }
            }
            is OfferState.WaitingForPayment -> {
                stateDelegate.updateState {
                    OfferState.WaitingForConnection(
                        requestId = currentState.requestId,
                        liquidAuthUrl = currentState.liquidAuthUrl,
                        origin = currentState.origin,
                    )
                }
                viewModelScope.launch {
                    eventDelegate.sendEvent(OfferEvent.ClientDisconnected)
                }
            }
            is OfferState.Streaming -> {
                stateDelegate.updateState {
                    OfferState.WaitingForConnection(
                        requestId = currentState.requestId,
                        liquidAuthUrl = currentState.liquidAuthUrl,
                        origin = currentState.origin,
                    )
                }
                viewModelScope.launch {
                    eventDelegate.sendEvent(OfferEvent.ClientDisconnected)
                }
            }
            else -> { /* no-op */ }
        }
        closeSessionVault(creatorAddress)
    }

    /**
     * Regenerate the offer (creates new requestId)
     */
    fun regenerateOffer(origin: String) {
        if (meshHostingEnabled && (hasMeshViewerConnected || getCurrentSessionId() != null)) {
            refreshMeshInvitation(origin)
        } else {
            generateOffer(origin)
        }
    }

    /**
     * Get the current offer data if in a connection state
     */
    fun getCurrentOffer(): LiquidAuthOffer? =
        when (val currentState = state.value) {
            is OfferState.WaitingForConnection ->
                LiquidAuthOffer(
                    requestId = currentState.requestId,
                    liquidAuthUrl = currentState.liquidAuthUrl,
                    origin = currentState.origin,
                )
            is OfferState.Connected ->
                LiquidAuthOffer(
                    requestId = currentState.requestId,
                    liquidAuthUrl = currentState.liquidAuthUrl,
                    origin = currentState.origin,
                )
            is OfferState.WaitingForPayment ->
                LiquidAuthOffer(
                    requestId = currentState.requestId,
                    liquidAuthUrl = currentState.liquidAuthUrl,
                    origin = currentState.origin,
                )
            is OfferState.Streaming ->
                LiquidAuthOffer(
                    requestId = currentState.requestId,
                    liquidAuthUrl = currentState.liquidAuthUrl,
                    origin = currentState.origin,
                )
            else -> null
        }

    /**
     * Called when ICE connection type changes (for UI and billing)
     */
    fun onConnectionTypeChanged(type: IceConnectionType) {
        _connectionType.value = type
        viewModelScope.launch {
            eventDelegate.sendEvent(OfferEvent.ConnectionTypeChanged(type))
        }
    }

    /**
     * Handle incoming chat message
     */
    fun onChatMessageReceived(message: ChatMessage) {
        viewModelScope.launch {
            eventDelegate.sendEvent(OfferEvent.ChatMessageReceived(message))
        }
    }

    /**
     * Get current session ID if connected, waiting for payment, or streaming
     */
    fun getCurrentSessionId(): String? =
        when (val currentState = state.value) {
            is OfferState.Connected -> currentState.sessionId
            is OfferState.WaitingForPayment -> currentState.sessionId
            is OfferState.Streaming -> currentState.sessionId
            else -> null
        }

    // ================= X402 Payment Methods =================

    /**
     * Start paid streaming with X402 payment model
     * 1. Request 1 ALGO deposit from client
     * 2. Wait for signed transaction
     * 3. Start streaming with balance tracking
     */
    @OptIn(ExperimentalUuidApi::class)
    fun startPaidStreaming(
        creatorAddress: String,
        network: String = "testnet",
    ) {
        val currentState = state.value
        if (currentState !is OfferState.Connected) {
            viewModelScope.launch {
                eventDelegate.sendEvent(
                    OfferEvent.ShowError("Must be connected to start paid streaming"),
                )
            }
            return
        }

        // Generate payment session ID
        paymentSessionId = Uuid.random().toString()

        // Create payment request
        val paymentRequest =
            PaymentRequest(
                id = paymentSessionId!!,
                sessionId = getCurrentSessionId() ?: paymentSessionId!!,
                segmentIndex = 0,
                amount = currentCostPerBlockMicroUsdc.toString(),
                asset = USDC_ASSET,
                network = network,
                payTo = creatorAddress,
                ttl = 30,
                nonce = paymentSessionId!!,
                meta =
                    PaymentRequestMeta(
                        gatingMode = GatingMode.PARTIAL_TIME,
                        enforcement = EnforcementMode.TRACK,
                        segmentDuration = 3,
                        voucherSignature = null,
                    ),
            )

        _paymentState.value =
            PaymentState.WaitingForDeposit(
                paymentRequest = paymentRequest,
            )

        // Transition to streaming state (payment pending)
        stateDelegate.updateState {
            OfferState.Streaming(
                requestId = currentState.requestId,
                liquidAuthUrl = currentState.liquidAuthUrl,
                origin = currentState.origin,
                sessionId = currentState.sessionId,
                isPaid = true,
                paymentStatus = StreamingPaymentStatus.PaymentPending,
            )
        }

        viewModelScope.launch {
            eventDelegate.sendEvent(OfferEvent.PaymentRequested(paymentRequest))
        }
    }

    /**
     * Handle successful MPP settlement from creator-side paywall server.
     */
    fun onMppPaymentSettled(txId: String?) {
        viewModelScope.launch {
            handlePaymentConfirmed(txId ?: "")
        }
    }

    /**
     * Handle rejected MPP payment from creator-side paywall server.
     */
    fun onMppPaymentRejected(reason: String) {
        _paymentState.value = PaymentState.Error(reason)
        updateStreamingPaymentStatus(StreamingPaymentStatus.Error)
        viewModelScope.launch {
            eventDelegate.sendEvent(OfferEvent.ShowError(reason))
        }
    }

    /**
     * Consume one block of streaming (deduct 0.1 ALGO)
     * Called every block or periodically while streaming
     */
    fun consumeBlock(
        onChainRemainingMicroUsdc: Long? = null,
        progressBarBalanceMicroUsdc: Long? = null,
        lastSettledMicroUsdc: Long? = null,
        startRound: Long? = null,
        paidBlocks: Int = 0,
        freeBlocks: Int = 0,
    ) {
        val onChainRemaining =
            onChainRemainingMicroUsdc ?: run {
                // Require smart-contract source-of-truth for host balance.
                _remainingBalanceMicroUsdc.value = null
                _progressBarBalanceMicroUsdc.value = null
                _lastSettledMicroUsdc.value = null
                return
            }
        val progressBalance = progressBarBalanceMicroUsdc ?: onChainRemaining

        // Always update card-facing balances from on-chain, even if payment state is not yet streaming.
        _remainingBalanceMicroUsdc.value = onChainRemaining
        _progressBarBalanceMicroUsdc.value = progressBalance
        _lastSettledMicroUsdc.value = lastSettledMicroUsdc

        val currentPaymentState = _paymentState.value
        if (currentPaymentState !is PaymentState.StreamingWithBalance) {
            if (onChainRemaining > 0L) {
                _paymentState.value =
                    PaymentState.StreamingWithBalance(
                        initialDepositMicroUsdc = onChainRemaining,
                        remainingMicroUsdc = progressBalance,
                        blocksWatched = paidBlocks,
                        freeBlocksWatched = freeBlocks,
                    )
                updateStreamingPaymentStatus(StreamingPaymentStatus.Active)
            }
            return
        }

        val newBlocksWatched = paidBlocks.coerceAtLeast(currentPaymentState.blocksWatched)
        val newFreeBlocksWatched = freeBlocks.coerceAtLeast(currentPaymentState.freeBlocksWatched)

        if (progressBalance <= 0) {
            // Funds depleted - stop streaming immediately
            Napier.d("💰⛽ BALANCE DEPLETED! Stopping video stream... paid=$newBlocksWatched free=$newFreeBlocksWatched")

            // Stop the video streaming
            val currentState = state.value
            if (currentState is OfferState.Streaming) {
                stateDelegate.updateState {
                    OfferState.Connected(
                        requestId = currentState.requestId,
                        liquidAuthUrl = currentState.liquidAuthUrl,
                        origin = currentState.origin,
                        sessionId = currentState.sessionId,
                    )
                }
                Napier.d("💰⛽ Stream stopped - transitioned to Connected state")
            }

            _paymentState.value =
                PaymentState.Depleted(
                    totalBlocksWatched = newBlocksWatched,
                    totalFreeBlocksWatched = newFreeBlocksWatched,
                    totalConsumedMicroAlgos = currentPaymentState.initialDepositMicroUsdc,
                )
            _remainingBalanceMicroUsdc.value = 0
            _progressBarBalanceMicroUsdc.value = 0

            updateStreamingPaymentStatus(StreamingPaymentStatus.Depleted)

            viewModelScope.launch {
                eventDelegate.sendEvent(
                    OfferEvent.FundsDepleted(
                        totalBlocksWatched = newBlocksWatched,
                        totalFreeBlocksWatched = newFreeBlocksWatched,
                        totalConsumedMicroAlgos = currentPaymentState.initialDepositMicroUsdc,
                    ),
                )
            }
        } else {
            // Keep payment state/progress in sync with effective progress balance.
            _paymentState.value =
                currentPaymentState.copy(
                    remainingMicroUsdc = progressBalance,
                    blocksWatched = newBlocksWatched,
                    freeBlocksWatched = newFreeBlocksWatched,
                )
            // Source-of-truth on-chain remaining from Session Vault smart contract.
            _remainingBalanceMicroUsdc.value = onChainRemainingMicroUsdc
            // Progress bar balance = on-chain remaining minus unsettled voucher.
            _progressBarBalanceMicroUsdc.value = progressBalance

            // Send balance update event periodically (every 5 blocks)
            if (newBlocksWatched % 5 == 0) {
                viewModelScope.launch {
                    eventDelegate.sendEvent(
                        OfferEvent.BalanceUpdated(
                            remainingMicroAlgos = progressBalance,
                            blocksWatched = newBlocksWatched,
                            startRound = startRound,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Get current balance as ALGO (for UI display)
     */
    fun getRemainingBalanceUsdc(): Double? = _remainingBalanceMicroUsdc.value?.let { it / 1_000_000.0 }

    /**
     * Toggle between paid and free streaming mode while already streaming
     */
    fun setStreamingPaid(isPaid: Boolean) {
        val currentState = state.value
        if (currentState is OfferState.Streaming) {
            stateDelegate.updateState {
                currentState.copy(
                    isPaid = isPaid,
                    paymentStatus = if (isPaid) StreamingPaymentStatus.Active else StreamingPaymentStatus.Free,
                )
            }
        }
    }

    /**
     * Reset payment state (when stream ends)
     */
    fun resetPaymentState() {
        blockchainMonitorJob?.cancel()
        blockchainMonitorJob = null
        _paymentState.value = PaymentState.NoPayment
        _remainingBalanceMicroUsdc.value = null
        _progressBarBalanceMicroUsdc.value = null
        paymentSessionId = null
    }

    /**
     * Monitor Algorand blockchain blocks and consume block when new block is detected.
     * This replaces the local timer with real Algorand blockchain blocks.
     *
     * Polls every 1 second and only calls consumeBlock() when block number changes.
     */
    fun monitorBlockchainBlocks() {
        if (blockchainMonitorJob?.isActive == true) {
            Napier.d("🔗 Blockchain block monitoring already active - skipping duplicate start")
            return
        }

        blockchainMonitorJob =
            viewModelScope.launch {
                Napier.d("🔗 Starting blockchain block monitoring...")
                var lastBlockNumber: Long? = null

                try {
                    while (_paymentState.value is PaymentState.StreamingWithBalance) {
                        getCurrentBlockUseCase().collect { result ->
                            when (result) {
                                is com.michaeltchuang.walletsdk.utils.DataResource.Success -> {
                                    val currentBlock = result.data

                                    // Update current block number for UI
                                    _currentBlockNumber.value = currentBlock

                                    Napier.d("🔗 Current block: $currentBlock (last: $lastBlockNumber)")

                                    when {
                                        lastBlockNumber == null -> {
                                            // First poll - just store the block number
                                            lastBlockNumber = currentBlock
                                            Napier.d("🔗 Initial block stored: $currentBlock")
                                        }
                                        currentBlock > lastBlockNumber!! -> {
                                            val blocksAdvanced = (currentBlock - lastBlockNumber!!).toInt()
                                            Napier.d("🔗 New block(s) detected! Advanced by $blocksAdvanced blocks")
                                            lastBlockNumber = currentBlock
                                        }
                                        else -> {
                                            // Same block, no action needed
                                            Napier.d("🔗 Same block $currentBlock, no consumption")
                                        }
                                    }
                                }
                                is com.michaeltchuang.walletsdk.utils.DataResource.Error -> {
                                    Napier.e("🔗❌ Failed to get current block: ${result.exception}")
                                }
                                is com.michaeltchuang.walletsdk.utils.DataResource.Loading -> {
                                    // Loading state, ignore
                                }
                            }
                        }

                        // Wait 1 second before next poll (faster updates, ~60 req/min to Algonode)
                        delay(1000L.milliseconds)
                    }

                    Napier.d("🔗 Stopping blockchain block monitoring - no longer streaming with balance")
                } finally {
                    blockchainMonitorJob = null
                }
            }
    }

    private suspend fun handlePaymentConfirmed(txId: String) {
        Napier.d("💰🎉 Payment received on-chain!")

        val currentState = state.value
        val currentPaymentState = _paymentState.value
        val isInitialPaymentConfirmation =
            currentState is OfferState.WaitingForPayment || currentPaymentState !is PaymentState.StreamingWithBalance

        if (!isInitialPaymentConfirmation) {
            // Subsequent settlement ticks should NOT reset host balance back to initial deposit.
            updateStreamingPaymentStatus(StreamingPaymentStatus.Active)
            return
        }

        if (currentState is OfferState.WaitingForPayment) {
            Napier.d("💰 Transitioning from WaitingForPayment to Streaming state")
            stateDelegate.updateState {
                OfferState.Streaming(
                    requestId = currentState.requestId,
                    liquidAuthUrl = currentState.liquidAuthUrl,
                    origin = currentState.origin,
                    sessionId = currentState.sessionId,
                    isPaid = true,
                    paymentStatus = StreamingPaymentStatus.Active,
                )
            }
        }

        val (existingPaid, existingFree) =
            if (currentPaymentState is PaymentState.StreamingWithBalance) {
                currentPaymentState.blocksWatched to currentPaymentState.freeBlocksWatched
            } else if (currentPaymentState is PaymentState.Depleted) {
                currentPaymentState.totalBlocksWatched to currentPaymentState.totalFreeBlocksWatched
            } else {
                0 to 0
            }

        _paymentState.value =
            PaymentState.StreamingWithBalance(
                initialDepositMicroUsdc = DEPOSIT_AMOUNT_MICRO_USDC,
                remainingMicroUsdc = 0,
                blocksWatched = existingPaid,
                freeBlocksWatched = existingFree,
            )
        _remainingBalanceMicroUsdc.value = null
        _progressBarBalanceMicroUsdc.value = null

        eventDelegate.sendEvent(
            OfferEvent.PaymentReceived(
                amountMicroAlgos = DEPOSIT_AMOUNT_MICRO_USDC,
                txId = txId,
            ),
        )
    }

    private fun updateStreamingPaymentStatus(status: StreamingPaymentStatus) {
        val currentState = state.value
        if (currentState is OfferState.Streaming && currentState.isPaid) {
            stateDelegate.updateState {
                currentState.copy(paymentStatus = status)
            }
        }
    }

    private fun observeCurrentNetwork() {
        viewModelScope.launch {
            getCurrentNetworkUseCase().collect { network ->
                currentNetworkFlow.value = network
            }
        }
    }

    fun startRealtimeBlockNumberUpdates() {
        if (blockNumberPollingJob?.isActive == true) return
        blockNumberPollingJob =
            viewModelScope.launch {
                while (true) {
                    getCurrentBlockUseCase().collect { result ->
                        when (result) {
                            is com.michaeltchuang.walletsdk.utils.DataResource.Success -> {
                                _currentBlockNumber.value = result.data
                            }
                            is com.michaeltchuang.walletsdk.utils.DataResource.Error,
                            is com.michaeltchuang.walletsdk.utils.DataResource.Loading,
                            -> Unit
                        }
                    }
                    delay(1000L.milliseconds)
                }
            }
    }

    fun stopRealtimeBlockNumberUpdates() {
        blockNumberPollingJob?.cancel()
        blockNumberPollingJob = null
    }

    fun fetchAccountASABalance(
        address: String,
        assetId: Long,
    ) {
        if (assetId <= 0) {
            _creatorAsaBalance.value = null
            _isCheckingCreatorAsaBalance.value = false
            return
        }

        viewModelScope.launch {
            _isCheckingCreatorAsaBalance.value = true
            try {
                val balance = getAccountASABalance(address, assetId)
                _creatorAsaBalance.value = balance?.toString()
                Napier.d("Fetched ASA balance (LiquidAuth): ${balance?.toString() ?: "null"}")
            } catch (e: Exception) {
                Napier.d("Exception fetching ASA balance (LiquidAuth): ${e.message}")
                _creatorAsaBalance.value = null
            } finally {
                _isCheckingCreatorAsaBalance.value = false
            }
        }
    }

    private fun closeSessionVault(creatorAddress: String?) {
        if (creatorAddress == null) {
            Napier.e("Skipping session vault close: creatorAddress is missing")
            return
        }
        val channelId = EscrowSessionVaultHybridManagerClient.channelId
        if (channelId == null) {
            Napier.e("Skipping session vault close: channelId is missing")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Napier.e(
                    "Closing session vault: creator=$creatorAddress, channelId=$channelId",
                )

                val signer = mppWalletSignerUseCase(creatorAddress)
                if (signer == null) {
                    Napier.e(
                        "Failed to close session vault: signer not found for $creatorAddress",
                    )
                    return@launch
                }

                MppPayments
                    .closeSessionVault(
                        signer = signer,
                        channelId = channelId,
                    ).onSuccess { txId ->
                        Napier.e("Session vault closed successfully. txId=$txId")
                    }.onFailure { throwable ->
                        Napier.e(
                            "Failed to close session vault. channelId=$channelId, error=${throwable.message}",
                            throwable,
                        )
                    }
            } catch (t: Throwable) {
                Napier.e(
                    "Unexpected error while closing session vault. channelId=$channelId",
                    t,
                )
            }
        }
    }

    // ================= Payment State Sealed Classes =================

    sealed interface PaymentState {
        data object NoPayment : PaymentState

        data class WaitingForDeposit(
            val paymentRequest: PaymentRequest,
        ) : PaymentState

        data class StreamingWithBalance(
            val initialDepositMicroUsdc: Long,
            val remainingMicroUsdc: Long,
            val blocksWatched: Int,
            val freeBlocksWatched: Int = 0,
        ) : PaymentState

        data class Rejected(
            val dummy: Unit = Unit,
        ) : PaymentState

        data class Error(
            val message: String,
        ) : PaymentState

        data class Depleted(
            val totalBlocksWatched: Int,
            val totalFreeBlocksWatched: Int = 0,
            val totalConsumedMicroAlgos: Long,
        ) : PaymentState
    }

    enum class StreamingPaymentStatus {
        Free, // No payment required
        PaymentPending, // Waiting for client to sign deposit
        Active, // Payment received, streaming active
        Rejected, // Client rejected payment
        Error, // Payment error
        Depleted, // Funds exhausted
    }

    sealed interface OfferState {
        data object Idle : OfferState

        data object Loading : OfferState

        /**
         * Waiting for a client to scan the QR code and connect via WebRTC
         */
        data class WaitingForConnection(
            val requestId: String,
            val liquidAuthUrl: String,
            val origin: String,
        ) : OfferState

        /**
         * Client has connected via WebRTC, ready to stream
         */
        data class Connected(
            val requestId: String,
            val liquidAuthUrl: String,
            val origin: String,
            val sessionId: String,
        ) : OfferState

        /**
         * Waiting for X402 payment before streaming can begin
         */
        data class WaitingForPayment(
            val requestId: String,
            val liquidAuthUrl: String,
            val origin: String,
            val sessionId: String,
            val paymentRequest: PaymentRequest,
        ) : OfferState

        /**
         * Currently streaming video to the connected client
         */
        data class Streaming(
            val requestId: String,
            val liquidAuthUrl: String,
            val origin: String,
            val sessionId: String,
            val isPaid: Boolean = false,
            val paymentStatus: StreamingPaymentStatus = StreamingPaymentStatus.Free,
        ) : OfferState

        data class Error(
            val message: String,
        ) : OfferState
    }

    sealed interface OfferEvent {
        data class OfferGenerated(
            val requestId: String,
        ) : OfferEvent

        /**
         * A client has successfully connected via WebRTC
         */
        data class ClientConnected(
            val sessionId: String,
        ) : OfferEvent

        /**
         * The connected client has disconnected
         */
        data object ClientDisconnected : OfferEvent

        /**
         * Video streaming has started
         */
        data object VideoStreamingStarted : OfferEvent

        /**
         * Video streaming has stopped
         */
        data object VideoStreamingStopped : OfferEvent

        // ================= X402 Payment Events =================

        /**
         * Payment requested - 1 ALGO deposit requested from client
         */
        data class PaymentRequested(
            val paymentRequest: PaymentRequest,
        ) : OfferEvent

        /**
         * Payment received and verified - streaming can begin
         */
        data class PaymentReceived(
            val amountMicroAlgos: Long,
            val txId: String? = null,
        ) : OfferEvent

        /**
         * Balance updated during streaming
         */
        data class BalanceUpdated(
            val remainingMicroAlgos: Long,
            val blocksWatched: Int,
            val startRound: Long? = null,
        ) : OfferEvent

        /**
         * Funds depleted - streaming should stop
         */
        data class FundsDepleted(
            val totalBlocksWatched: Int,
            val totalFreeBlocksWatched: Int = 0,
            val totalConsumedMicroAlgos: Long,
        ) : OfferEvent

        /**
         * ICE Connection type changed (for quality indicators and billing)
         */
        data class ConnectionTypeChanged(
            val connectionType: IceConnectionType,
        ) : OfferEvent

        data class ShowError(
            val message: String,
        ) : OfferEvent

        data class ChatMessageReceived(
            val message: ChatMessage,
        ) : OfferEvent
    }
}
