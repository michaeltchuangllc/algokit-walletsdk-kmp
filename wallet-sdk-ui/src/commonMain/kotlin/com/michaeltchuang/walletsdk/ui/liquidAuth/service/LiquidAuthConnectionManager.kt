package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
expect class LiquidAuthConnectionManager(
    platformContext: Any,
) {
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
     * Send a chat message to the connected peer via data channel.
     */
    fun sendChatMessage(message: ChatMessage)

    /**
     * Check if currently connected to a peer.
     */
    fun isConnected(): Boolean

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

    fun setupCreator(
        creatorAddress: String,
        network: String,
    )

    fun setAudioEnabled(enabled: Boolean)

    fun setVideoEnabled(enabled: Boolean)

    fun setIsPaidStreaming(enabled: Boolean)

    fun setStreamCost(cost: Long)

    fun setPayoutFrequency(tabId: String)
}

const val LIQUID_AUTH_CONNECTION_TYPE_POLL_INTERVAL_MS = 1000L

class LiquidAuthPollingJobController(
    private val scope: CoroutineScope,
    private val intervalMillis: Long = LIQUID_AUTH_CONNECTION_TYPE_POLL_INTERVAL_MS,
    private val runImmediately: Boolean = false,
    private val onPoll: suspend (pollCount: Int) -> Unit,
    private val onStop: () -> Unit = {},
) {
    private var job: Job? = null

    fun start() {
        stop(resetState = false)
        job =
            scope.launch {
                var pollCount = 0
                if (runImmediately) {
                    onPoll(pollCount)
                }
                while (isActive) {
                    pollCount++
                    onPoll(pollCount)
                    delay(intervalMillis)
                }
            }
    }

    fun stop(resetState: Boolean = true) {
        job?.cancel()
        job = null
        if (resetState) onStop()
    }
}

private const val SOLANA_USDC_DEVNET_MINT = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
private const val SOLANA_USDC_MAINNET_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

data class ResolvedLiquidAuthPaymentRequest(
    val network: String,
    val amount: String,
    val recipient: String,
    val asset: String,
    val gatingConfig: GatingConfig,
)

class LiquidAuthViewerHelloMessage(
    val viewerAddress: String?,
    val viewerPublicKey: ByteArray?,
)

class LiquidAuthPaymentVoucherMessage(
    val sessionId: String?,
    val viewerAddress: String?,
    val viewerPublicKeyBase64: String?,
    val viewerPublicKey: ByteArray?,
    val signatureBase64: String?,
    val totalAmountClaimedMicroUsdc: Long?,
    val channelIdBase64: String?,
    val channelId: ByteArray?,
)

class LiquidAuthHostTransportMessage(
    val reference: String?,
    val type: String?,
    val address: String?,
    val viewerHello: LiquidAuthViewerHelloMessage?,
    val paymentVoucher: LiquidAuthPaymentVoucherMessage?,
)

fun resolveLiquidAuthPaymentRequest(paymentRequest: PaymentRequest): ResolvedLiquidAuthPaymentRequest {
    val network = resolveLiquidAuthMppNetwork(paymentRequest.network)
    val isSolanaNetwork = network.startsWith("solana:", ignoreCase = true)
    val asset =
        if (isSolanaNetwork) {
            if (network == MppNetworks.SOLANA_MAINNET) SOLANA_USDC_MAINNET_MINT else SOLANA_USDC_DEVNET_MINT
        } else {
            paymentRequest.asset.takeIf { it.isNotBlank() } ?: "USDC"
        }
    val amount = paymentRequest.amount
    val recipient = paymentRequest.payTo
    return ResolvedLiquidAuthPaymentRequest(
        network = network,
        amount = amount,
        recipient = recipient,
        asset = asset,
        gatingConfig =
            GatingConfig(
                mode = GatingMode.PARTIAL_TIME,
                amount = amount,
                asset = asset,
                network = network,
                payTo = recipient,
                segmentDuration = 3,
                leadTime = 0,
            ),
    )
}

fun resolveLiquidAuthMppNetwork(network: String): String {
    val n = network.lowercase()
    return when {
        network == MppNetworks.SOLANA_MAINNET ||
            n.contains("solana") &&
            (n.contains("mainnet") || n.contains("mainnet-beta")) -> MppNetworks.SOLANA_MAINNET
        network == MppNetworks.SOLANA_DEVNET || n.contains("solana") && n.contains("devnet") -> MppNetworks.SOLANA_DEVNET
        network == MppNetworks.SOLANA_TESTNET || n.contains("solana") && n.contains("testnet") -> MppNetworks.SOLANA_TESTNET
        n.contains("mainnet") || network == MppNetworks.ALGORAND_MAINNET -> MppNetworks.ALGORAND_MAINNET
        n.contains("futurenet") || n.contains("fnet") || network == MppNetworks.ALGORAND_FUTURENET -> MppNetworks.ALGORAND_FUTURENET
        else -> MppNetworks.ALGORAND_TESTNET
    }
}

fun parseLiquidAuthHostTransportMessage(message: String): LiquidAuthHostTransportMessage {
    val reference = message.jsonOptString("reference")
    val type = message.jsonOptString("type")
    val viewerHello =
        if (type == DCMessageType.SEGMENT_HANDSHAKE.value) {
            val viewerPublicKeyBase64 = message.jsonOptString("viewerPublicKey")
            LiquidAuthViewerHelloMessage(
                viewerAddress = message.jsonOptString("viewer"),
                viewerPublicKey = viewerPublicKeyBase64?.decodeLiquidAuthBase64OrNull(),
            )
        } else {
            null
        }
    val paymentVoucher =
        if (type == DCMessageType.SEGMENT_VOUCHER.value) {
            val viewerPublicKeyBase64 = message.jsonOptString("viewerPublicKey")
            val channelIdBase64 = message.jsonOptString("channelId")
            LiquidAuthPaymentVoucherMessage(
                sessionId = message.jsonOptString("id"),
                viewerAddress = message.jsonOptString("viewer"),
                viewerPublicKeyBase64 = viewerPublicKeyBase64,
                viewerPublicKey = viewerPublicKeyBase64?.decodeLiquidAuthBase64OrNull(),
                signatureBase64 = message.jsonOptString("signature"),
                totalAmountClaimedMicroUsdc = message.jsonOptLong("totalAmountClaimedMicroUsdc"),
                channelIdBase64 = channelIdBase64,
                channelId = channelIdBase64?.decodeLiquidAuthBase64OrNull(),
            )
        } else {
            null
        }

    return LiquidAuthHostTransportMessage(
        reference = reference,
        type = type,
        address = message.jsonOptString("address"),
        viewerHello = viewerHello,
        paymentVoucher = paymentVoucher,
    )
}

fun String.jsonOptString(key: String): String? =
    Regex(""""$key"\s*:\s*"([^"]*)"""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotEmpty() }

fun String.jsonOptLong(key: String): Long? =
    Regex(""""$key"\s*:\s*(-?\d+)""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()

@OptIn(ExperimentalEncodingApi::class)
fun String.decodeLiquidAuthBase64OrNull(): ByteArray? =
    runCatching {
        val normalised =
            replace("\\/", "/")
                .replace('-', '+')
                .replace('_', '/')
                .trimEnd('=')
        val padded = normalised + "=".repeat((4 - normalised.length % 4) % 4)
        Base64.decode(padded)
    }.getOrNull()

/**
 * Factory function to create the default LiquidAuthConnectionManager.
 * Call this from Compose with LocalContext.current on Android, or Unit on iOS.
 */
fun createLiquidAuthConnectionManager(platformContext: Any): LiquidAuthConnectionManager = LiquidAuthConnectionManager(platformContext)
