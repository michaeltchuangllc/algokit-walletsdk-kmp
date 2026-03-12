package com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaeltchuang.walletsdk.core.foundation.EventDelegate
import com.michaeltchuang.walletsdk.core.foundation.EventViewModel
import com.michaeltchuang.walletsdk.core.foundation.StateDelegate
import com.michaeltchuang.walletsdk.core.foundation.StateViewModel
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.LiquidAuthOffer
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.usecase.GenerateLiquidAuthOfferUseCase
import kotlinx.coroutines.launch

/**
 * ViewModel for Liquid Auth Offer flow with WebRTC video streaming support
 *
 * This generates QR codes for dApps to scan, detects when they connect via WebRTC,
 * and supports streaming video back to the connected client.
 */
class LiquidAuthOfferViewModel(
    private val generateOfferUseCase: GenerateLiquidAuthOfferUseCase,
    private val stateDelegate: StateDelegate<OfferState>,
    private val eventDelegate: EventDelegate<OfferEvent>,
) : ViewModel(),
    StateViewModel<LiquidAuthOfferViewModel.OfferState> by stateDelegate,
    EventViewModel<LiquidAuthOfferViewModel.OfferEvent> by eventDelegate {

    init {
        stateDelegate.setDefaultState(OfferState.Idle)
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
        val currentState = state.value
        if (currentState is OfferState.WaitingForConnection) {
            stateDelegate.updateState {
                OfferState.Connected(
                    requestId = currentState.requestId,
                    liquidAuthUrl = currentState.liquidAuthUrl,
                    origin = currentState.origin,
                    sessionId = sessionId,
                )
            }
            viewModelScope.launch {
                eventDelegate.sendEvent(OfferEvent.ClientConnected(sessionId))
            }
        }
    }

    /**
     * Start video streaming to the connected client
     */
    fun startVideoStreaming() {
        val currentState = state.value
        if (currentState is OfferState.Connected) {
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
    fun getCurrentOffer(): LiquidAuthOffer? {
        return when (val currentState = state.value) {
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
            is OfferState.Streaming ->
                LiquidAuthOffer(
                    requestId = currentState.requestId,
                    liquidAuthUrl = currentState.liquidAuthUrl,
                    origin = currentState.origin,
                )
            else -> null
        }
    }

    /**
     * Get current session ID if connected or streaming
     */
    fun getCurrentSessionId(): String? {
        return when (val currentState = state.value) {
            is OfferState.Connected -> currentState.sessionId
            is OfferState.Streaming -> currentState.sessionId
            else -> null
        }
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
         * Currently streaming video to the connected client
         */
        data class Streaming(
            val requestId: String,
            val liquidAuthUrl: String,
            val origin: String,
            val sessionId: String,
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

        data class ShowError(
            val message: String,
        ) : OfferEvent
    }
}
