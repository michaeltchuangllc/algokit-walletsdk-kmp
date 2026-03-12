package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel

/**
 * Interface for platform-specific Liquid Auth connection manager.
 *
 * This handles:
 * - Starting/binding to the SignalService (Android)
 * - Detecting when a peer connects via WebRTC
 * - Notifying the ViewModel of connection state changes
 */
interface LiquidAuthConnectionManager {

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
    fun startListening(origin: String, requestId: String)

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
}

/**
 * Factory function to create platform-specific LiquidAuthConnectionManager.
 * Call this from Compose with LocalContext.current
 */
expect fun createLiquidAuthConnectionManager(platformContext: Any): LiquidAuthConnectionManager
