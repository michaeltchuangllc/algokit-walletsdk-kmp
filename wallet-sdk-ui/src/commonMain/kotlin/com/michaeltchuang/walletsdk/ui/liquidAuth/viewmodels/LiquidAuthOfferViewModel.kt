package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.LiquidAuthOffer
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.GenerateLiquidAuthOfferUseCase
import com.michaeltchuang.walletsdk.core.network.usecase.GetCurrentBlockUseCase
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.SendSignedTransactionUseCase
import com.michaeltchuang.walletsdk.core.transaction.domain.usecase.SubmitSolanaSignedTransactionUseCase
import com.michaeltchuang.walletsdk.core.transaction.model.SignedTransactionDetail
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.X402PaymentMessages
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * ViewModel for Liquid Auth Offer flow with WebRTC video streaming support
 *
 * This generates QR codes for dApps to scan, detects when they connect via WebRTC,
 * and supports streaming video back to the connected client.
 *
 * X402 Payment Model:
 * - Free streaming: No payment required
 * - Paid streaming: 1 ALGO deposit, 0.1 ALGO per block watched
 */
class LiquidAuthOfferViewModel(
    private val generateOfferUseCase: GenerateLiquidAuthOfferUseCase,
    private val stateDelegate: StateDelegate<OfferState>,
    private val eventDelegate: EventDelegate<OfferEvent>,
    private val sendSignedTransactionUseCase: SendSignedTransactionUseCase,
    private val submitSolanaSignedTransactionUseCase: SubmitSolanaSignedTransactionUseCase,
    private val getCurrentBlockUseCase: GetCurrentBlockUseCase,
) : ViewModel(),
    StateViewModel<LiquidAuthOfferViewModel.OfferState> by stateDelegate,
    EventViewModel<LiquidAuthOfferViewModel.OfferEvent> by eventDelegate {
    init {
        stateDelegate.setDefaultState(OfferState.Idle)
    }

    // ICE Connection type for UI quality indicators and billing (x402)
    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    val connectionType: StateFlow<IceConnectionType> = _connectionType

    // X402 Payment state
    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.NoPayment)
    val paymentState: StateFlow<PaymentState> = _paymentState

    // Current balance for paid streaming (in microAlgos)
    private val _remainingBalanceMicroAlgos = MutableStateFlow<Long?>(null)
    val remainingBalanceMicroAlgos: StateFlow<Long?> = _remainingBalanceMicroAlgos

    // Current Algorand block number for UI display
    private val _currentBlockNumber = MutableStateFlow<Long?>(null)
    val currentBlockNumber: StateFlow<Long?> = _currentBlockNumber

    // Payment session ID
    private var paymentSessionId: String? = null

    // Cost per block (0.1 ALGO = 100,000 microAlgos)
    companion object {
        const val DEPOSIT_AMOUNT_MICRO_ALGOS = 1_000_000L // 1 ALGO
        const val COST_PER_BLOCK_MICRO_ALGOS = 100_000L // 0.1 ALGO
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
     * Request X402 payment from client before starting paid streaming.
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
            X402PaymentMessages.PaymentRequest(
                id = paymentSessionId!!,
                amountMicroAlgos = DEPOSIT_AMOUNT_MICRO_ALGOS,
                creatorAddress = creatorAddress,
                network = network,
            )
        println("💰 Created payment request: ${paymentRequest.id}, amount=${paymentRequest.amountMicroAlgos}")

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
    fun onClientDisconnected() {
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
            X402PaymentMessages.PaymentRequest(
                id = paymentSessionId!!,
                amountMicroAlgos = DEPOSIT_AMOUNT_MICRO_ALGOS,
                creatorAddress = creatorAddress,
                network = network,
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
     * Handle payment response from client
     * Called when client sends back signed transaction
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun onPaymentResponse(response: X402PaymentMessages.PaymentResponse) {
        println("💰 onPaymentResponse called with status=${response.status}, client=${response.clientAddress}")
        when (response.status) {
            X402PaymentMessages.PaymentResponse.Status.SIGNED -> {
                println("💰 Payment SIGNED - submitting to blockchain...")

                viewModelScope.launch {
                    try {
                        val signedBytes = Base64.decode(response.signedTransactionB64)

                        if (response.clientAddress.length in 32..44) {
                            val txId = submitSolanaSignedTransactionUseCase(signedBytes)
                            println("💰🎉 SOLANA TRANSACTION SUBMITTED! Signature: $txId")
                            handlePaymentConfirmed(txId)
                        } else {
                            val signedTxn = SignedTransactionDetail.ExternalTransaction(signedBytes)
                            println("💰 Submitting transaction to Algorand network...")
                            sendSignedTransactionUseCase
                                .sendSignedTransaction(signedTxn)
                                .collect { result ->
                                    when (result) {
                                        is com.michaeltchuang.walletsdk.utils.DataResource.Success -> {
                                            println("💰🎉 TRANSACTION CONFIRMED! TxID: ${result.data}")
                                            handlePaymentConfirmed(result.data)
                                        }
                                        is com.michaeltchuang.walletsdk.utils.DataResource.Error -> {
                                            println("💰❌ Transaction submission failed: ${result.exception}")
                                            _paymentState.value =
                                                PaymentState.Error(
                                                    "Transaction failed: ${result.exception?.message}",
                                                )
                                            updateStreamingPaymentStatus(StreamingPaymentStatus.Error)
                                        }
                                        is com.michaeltchuang.walletsdk.utils.DataResource.Loading -> {
                                            println("💰⏳ Submitting transaction...")
                                        }
                                    }
                                }
                        }
                    } catch (e: Exception) {
                        println("💰❌ Failed to submit transaction: $e")
                        _paymentState.value = PaymentState.Error("Failed to submit: ${e.message}")
                        updateStreamingPaymentStatus(StreamingPaymentStatus.Error)
                    }
                }
            }
            X402PaymentMessages.PaymentResponse.Status.REJECTED -> {
                println("💰 Payment REJECTED by client")
                _paymentState.value = PaymentState.Rejected()
                updateStreamingPaymentStatus(StreamingPaymentStatus.Rejected)
                println("💰 PaymentState updated to Rejected")
                viewModelScope.launch {
                    println("💰 Sending PaymentRejected event...")
                    eventDelegate.sendEvent(OfferEvent.PaymentRejected)
                    println("💰 PaymentRejected event sent")
                }
            }
            X402PaymentMessages.PaymentResponse.Status.ERROR -> {
                println("💰 Payment ERROR: ${response.errorMessage}")
                _paymentState.value = PaymentState.Error(response.errorMessage ?: "Unknown error")
                updateStreamingPaymentStatus(StreamingPaymentStatus.Error)
                println("💰 PaymentState updated to Error")
                viewModelScope.launch {
                    println("💰 Sending ShowError event for payment error...")
                    eventDelegate.sendEvent(
                        OfferEvent.ShowError(
                            response.errorMessage ?: "Payment error",
                        ),
                    )
                    println("💰 ShowError event sent")
                }
            }
        }
    }

    /**
     * Consume one block of streaming (deduct 0.1 ALGO)
     * Called every block or periodically while streaming
     */
    fun consumeBlock() {
        val currentPaymentState = _paymentState.value
        if (currentPaymentState !is PaymentState.StreamingWithBalance) {
            return // Not in paid streaming mode
        }

        val newBalance = currentPaymentState.remainingMicroAlgos - COST_PER_BLOCK_MICRO_ALGOS
        val newBlocksWatched = currentPaymentState.blocksWatched + 1

        if (newBalance <= 0) {
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
                    totalConsumedMicroAlgos = currentPaymentState.initialDepositMicroAlgos,
                )
            _remainingBalanceMicroAlgos.value = 0

            updateStreamingPaymentStatus(StreamingPaymentStatus.Depleted)

            viewModelScope.launch {
                eventDelegate.sendEvent(
                    OfferEvent.FundsDepleted(
                        totalBlocksWatched = newBlocksWatched,
                        totalConsumedMicroAlgos = currentPaymentState.initialDepositMicroAlgos,
                    ),
                )
            }
        } else {
            // Deduct from balance
            _paymentState.value =
                currentPaymentState.copy(
                    remainingMicroAlgos = newBalance,
                    blocksWatched = newBlocksWatched,
                )
            _remainingBalanceMicroAlgos.value = newBalance

            // Send balance update event periodically (every 5 blocks)
            if (newBlocksWatched % 5 == 0) {
                viewModelScope.launch {
                    eventDelegate.sendEvent(
                        OfferEvent.BalanceUpdated(
                            remainingMicroAlgos = newBalance,
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
    fun getRemainingBalanceAlgos(): Double? = _remainingBalanceMicroAlgos.value?.let { it / 1_000_000.0 }

    /**
     * Reset payment state (when stream ends)
     */
    fun resetPaymentState() {
        _paymentState.value = PaymentState.NoPayment
        _remainingBalanceMicroAlgos.value = null
        paymentSessionId = null
    }

    /**
     * Monitor Algorand blockchain blocks and consume block when new block is detected.
     * This replaces the local timer with real Algorand blockchain blocks.
     *
     * Polls every 1 second and only calls consumeBlock() when block number changes.
     */
    fun monitorBlockchainBlocks() {
        viewModelScope.launch {
            println("🔗 Starting blockchain block monitoring...")
            var lastBlockNumber: Long? = null

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
                                    // New block detected - consume it
                                    val blocksAdvanced = (currentBlock - lastBlockNumber!!).toInt()
                                    println("🔗 New block(s) detected! Advanced by $blocksAdvanced blocks")

                                    // Consume block for each new block (usually 1, but could be more if we missed some)
                                    repeat(blocksAdvanced) {
                                        consumeBlock()
                                    }

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
        }
    }

    private suspend fun handlePaymentConfirmed(txId: String) {
        println("💰🎉 Payment received on-chain!")

        val currentState = state.value
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
                initialDepositMicroAlgos = DEPOSIT_AMOUNT_MICRO_ALGOS,
                remainingMicroAlgos = DEPOSIT_AMOUNT_MICRO_ALGOS,
                blocksWatched = 0,
            )
        _remainingBalanceMicroAlgos.value = DEPOSIT_AMOUNT_MICRO_ALGOS

        eventDelegate.sendEvent(
            OfferEvent.PaymentReceived(
                amountMicroAlgos = DEPOSIT_AMOUNT_MICRO_ALGOS,
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

    // ================= Payment State Sealed Classes =================

    sealed interface PaymentState {
        data object NoPayment : PaymentState

        data class WaitingForDeposit(
            val paymentRequest: X402PaymentMessages.PaymentRequest,
        ) : PaymentState

        data class StreamingWithBalance(
            val initialDepositMicroAlgos: Long,
            val remainingMicroAlgos: Long,
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
            val paymentRequest: X402PaymentMessages.PaymentRequest,
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
            val paymentRequest: X402PaymentMessages.PaymentRequest,
        ) : OfferEvent

        /**
         * Payment received and verified - streaming can begin
         */
        data class PaymentReceived(
            val amountMicroAlgos: Long,
            val txId: String? = null,
        ) : OfferEvent

        /**
         * Payment rejected by client
         */
        data object PaymentRejected : OfferEvent

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
