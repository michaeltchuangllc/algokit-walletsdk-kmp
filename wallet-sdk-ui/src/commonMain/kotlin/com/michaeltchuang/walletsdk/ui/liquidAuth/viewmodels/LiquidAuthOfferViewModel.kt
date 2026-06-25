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
import com.michaeltchuang.walletsdk.core.railmpp.core.EnforcementMode
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequestMeta
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.model.IceConnectionType
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    // X402 Payment state
    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.NoPayment)
    val paymentState: StateFlow<PaymentState> = _paymentState

    // Host balance model:
    // - remainingBalanceMicroUsdc: settled on-chain remaining from Session Vault
    // - progressBarBalanceMicroUsdc: effective UI/progress balance (on-chain minus unsettled voucher)
    private val _remainingBalanceMicroUsdc = MutableStateFlow<Long?>(null)
    val remainingBalanceMicroUsdc: StateFlow<Long?> = _remainingBalanceMicroUsdc
    private val _progressBarBalanceMicroUsdc = MutableStateFlow<Long?>(null)
    val progressBarBalanceMicroUsdc: StateFlow<Long?> = _progressBarBalanceMicroUsdc

    // ASA balance check for QR visibility (null => not opted in / unavailable)
    private val _creatorAsaBalance = MutableStateFlow<String?>(null)
    val creatorAsaBalance: StateFlow<String?> = _creatorAsaBalance

    private val _isCheckingCreatorAsaBalance = MutableStateFlow(false)
    val isCheckingCreatorAsaBalance: StateFlow<Boolean> = _isCheckingCreatorAsaBalance

    // Current Algorand block number for UI display
    private val _currentBlockNumber = MutableStateFlow<Long?>(null)
    val currentBlockNumber: StateFlow<Long?> = _currentBlockNumber

    // Current network label for UI display (TESTNET/MAINNET)
    private val _currentNetwork = MutableStateFlow(AlgorandNetwork.TESTNET)
    val currentNetworkLabel: StateFlow<String> =
        _currentNetwork
            .map { network ->
                when (network) {
                    AlgorandNetwork.MAINNET -> "MAINNET"
                    AlgorandNetwork.TESTNET -> "TESTNET"
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "TESTNET")

    // Payment session ID
    private var paymentSessionId: String? = null

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
        stateDelegate.updateState { OfferState.Loading }
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
     * Called when a client successfully connects via WebRTC
     */
    fun onClientConnected(sessionId: String) {
        println("💰 onClientConnected called with sessionId=$sessionId")
        val currentState = state.value
        if (currentState is OfferState.WaitingForConnection) {
            println("💰 Transitioning from WaitingForConnection to Connected")
            stateDelegate.updateState {
                OfferState.Connected(
                    requestId = currentState.requestId,
                    liquidAuthUrl = currentState.liquidAuthUrl,
                    origin = currentState.origin,
                    sessionId = sessionId,
                )
            }
            println("💰 Emitting ClientConnected event")
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
        println(
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
            println("💰 State is Connected, proceeding with payment request")
        }

        // Generate payment session ID
        paymentSessionId = Uuid.random().toString()

        // Create payment request
        val paymentRequest =
            PaymentRequest(
                id = paymentSessionId!!,
                sessionId = getCurrentSessionId() ?: paymentSessionId!!,
                segmentIndex = 0,
                amount = COST_PER_BLOCK_MICRO_USDC.toString(),
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
        println("💰 Created payment request: ${paymentRequest.id}, amount=${paymentRequest.amount}")

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
        println("💰 Transitioned to WaitingForPayment state")

        viewModelScope.launch {
            println("💰 Emitting OfferEvent.PaymentRequested for session=${paymentRequest.id}")
            eventDelegate.sendEvent(OfferEvent.PaymentRequested(paymentRequest))
            println("💰 OfferEvent.PaymentRequested emitted")
        }
        println("💰 Payment request ready to be sent")
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
        generateOffer(origin)
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
                amount = COST_PER_BLOCK_MICRO_USDC.toString(),
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
    ) {
        val onChainRemaining =
            onChainRemainingMicroUsdc ?: run {
                // Require smart-contract source-of-truth for host balance.
                _remainingBalanceMicroUsdc.value = null
                _progressBarBalanceMicroUsdc.value = null
                return
            }
        val progressBalance = progressBarBalanceMicroUsdc ?: onChainRemaining

        // Always update card-facing balances from on-chain, even if payment state is not yet streaming.
        _remainingBalanceMicroUsdc.value = onChainRemaining
        _progressBarBalanceMicroUsdc.value = progressBalance

        val currentPaymentState = _paymentState.value
        if (currentPaymentState !is PaymentState.StreamingWithBalance) {
            if (onChainRemaining > 0L) {
                _paymentState.value =
                    PaymentState.StreamingWithBalance(
                        initialDepositMicroUsdc = onChainRemaining,
                        remainingMicroUsdc = progressBalance,
                        blocksWatched = 0,
                    )
                updateStreamingPaymentStatus(StreamingPaymentStatus.Active)
            }
            return
        }

        val newBlocksWatched = currentPaymentState.blocksWatched + 1

        if (progressBalance <= 0) {
            // Funds depleted - stop streaming immediately
            println("💰⛽ BALANCE DEPLETED! Stopping video stream...")

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
                println("💰⛽ Stream stopped - transitioned to Connected state")
            }

            _paymentState.value =
                PaymentState.Depleted(
                    totalBlocksWatched = newBlocksWatched,
                    totalConsumedMicroAlgos = currentPaymentState.initialDepositMicroUsdc,
                )
            _remainingBalanceMicroUsdc.value = 0
            _progressBarBalanceMicroUsdc.value = 0

            updateStreamingPaymentStatus(StreamingPaymentStatus.Depleted)

            viewModelScope.launch {
                eventDelegate.sendEvent(
                    OfferEvent.FundsDepleted(
                        totalBlocksWatched = newBlocksWatched,
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
            println("🔗 Blockchain block monitoring already active - skipping duplicate start")
            return
        }

        blockchainMonitorJob =
            viewModelScope.launch {
                println("🔗 Starting blockchain block monitoring...")
                var lastBlockNumber: Long? = null

                try {
                    while (_paymentState.value is PaymentState.StreamingWithBalance) {
                        getCurrentBlockUseCase().collect { result ->
                            when (result) {
                                is com.michaeltchuang.walletsdk.utils.DataResource.Success -> {
                                    val currentBlock = result.data

                                    // Update current block number for UI
                                    _currentBlockNumber.value = currentBlock

                                    println("🔗 Current block: $currentBlock (last: $lastBlockNumber)")

                                    when {
                                        lastBlockNumber == null -> {
                                            // First poll - just store the block number
                                            lastBlockNumber = currentBlock
                                            println("🔗 Initial block stored: $currentBlock")
                                        }
                                        currentBlock > lastBlockNumber!! -> {
                                            val blocksAdvanced = (currentBlock - lastBlockNumber!!).toInt()
                                            println("🔗 New block(s) detected! Advanced by $blocksAdvanced blocks")
                                            lastBlockNumber = currentBlock
                                        }
                                        else -> {
                                            // Same block, no action needed
                                            println("🔗 Same block $currentBlock, no consumption")
                                        }
                                    }
                                }
                                is com.michaeltchuang.walletsdk.utils.DataResource.Error -> {
                                    println("🔗❌ Failed to get current block: ${result.exception}")
                                }
                                is com.michaeltchuang.walletsdk.utils.DataResource.Loading -> {
                                    // Loading state, ignore
                                }
                            }
                        }

                        // Wait 1 second before next poll (faster updates, ~60 req/min to Algonode)
                        delay(1000)
                    }

                    println("🔗 Stopping blockchain block monitoring - no longer streaming with balance")
                } finally {
                    blockchainMonitorJob = null
                }
            }
    }

    private suspend fun handlePaymentConfirmed(txId: String) {
        println("💰🎉 Payment received on-chain!")

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
            println("💰 Transitioning from WaitingForPayment to Streaming state")
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

        _paymentState.value =
            PaymentState.StreamingWithBalance(
                initialDepositMicroUsdc = DEPOSIT_AMOUNT_MICRO_USDC,
                remainingMicroUsdc = 0,
                blocksWatched = 0,
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
                _currentNetwork.value = network
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
                    delay(1000)
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
                println("Fetched ASA balance (LiquidAuth): ${balance?.toString() ?: "null"}")
            } catch (e: Exception) {
                println("Exception fetching ASA balance (LiquidAuth): ${e.message}")
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
        val channelId = EscrowSessionVaultManagerClient.channelId
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
        ) : PaymentState

        data class Rejected(
            val dummy: Unit = Unit,
        ) : PaymentState

        data class Error(
            val message: String,
        ) : PaymentState

        data class Depleted(
            val totalBlocksWatched: Int,
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
        ) : OfferEvent

        /**
         * Funds depleted - streaming should stop
         */
        data class FundsDepleted(
            val totalBlocksWatched: Int,
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
    }
}
