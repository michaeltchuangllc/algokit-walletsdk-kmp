package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for platform-specific Liquid Auth connection manager.
 *
 * This handles:
 * - Starting/binding to the SignalService (Android)
 * - Detecting when a peer connects via WebRTC
 * - Notifying the ViewModel of connection state changes
 * - Tracking ICE connection type for quality/billing
 * - X402 payment messaging (payment requests, balance updates)
 */
interface LiquidAuthConnectionManager {
    /**
     * Flow of current ICE connection type.
     * Used for UI quality indicators and x402-style billing.
     */
    val connectionType: StateFlow<IceConnectionType>

    /**
     * Initialize the connection manager with the ViewModel.
     * Call this before starting the connection.
     */
    fun initialize(viewModel: LiquidAuthOfferViewModel)

    /**
     * Start listening for incoming peer connections.
     *
     * @param origin The liquid auth service origin URL
     * @param requestId The request ID for this offer session
     */
    fun startListening(
        origin: String,
        requestId: String,
    )

    /**
     * Stop listening and cleanup resources.
     */
    fun stopListening()

    /**
     * Send a message to the connected peer via data channel.
     */
    fun sendMessage(message: String)

    /**
     * Check if currently connected to a peer.
     */
    fun isConnected(): Boolean

    /**
     * Send a video frame to the connected peer.
     * The frame should be JPEG/PNG encoded bytes.
     *
     * @param frameId Unique frame identifier
     * @param timestamp Frame capture timestamp
     * @param frameData JPEG/PNG encoded frame bytes
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     * @param format Image format: "jpeg" or "png"
     */
    fun sendVideoFrame(
        frameId: String,
        timestamp: Long,
        frameData: ByteArray,
        width: Int,
        height: Int,
        format: String = "jpeg",
    )

    // ================= X402 Payment Methods =================

    /**
     * Send X402 payment request to client.
     * Requests 1 ALGO deposit to start paid streaming.
     */
    fun sendPaymentRequest(paymentRequest: PaymentRequest)

    /**
     * Start X402 block consumption timer.
     * Deducts 0.1 ALGO every 3 seconds (Algorand block time).
     */
    fun startBlockConsumption(sessionId: String)

    /**
     * Stop block consumption timer.
     */
    fun stopBlockConsumption()
}

/**
 * Factory function to create platform-specific LiquidAuthConnectionManager.
 * Call this from Compose with LocalContext.current
 */
expect fun createLiquidAuthConnectionManager(platformContext: Any): LiquidAuthConnectionManager
