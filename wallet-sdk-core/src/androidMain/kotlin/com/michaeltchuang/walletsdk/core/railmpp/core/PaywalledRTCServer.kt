package com.michaeltchuang.walletsdk.core.railmpp.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import org.webrtc.RtpSender
import java.nio.ByteBuffer
import java.util.UUID

/**
 * PaywalledRTCServer — provider-side WebRTC wrapper.
 *
 * Manages gating, segment timing, payment requests/verification,
 * and optional track enforcement.
 *
 * Note: Android SDK supports track enforcement only. Crypto enforcement
 * (Encoded Transforms) is web-only; set it and it'll fall back to track.
 *
 * Usage:
 * ```
 * val server = PaywalledRTCServer(
 *     peerConnection = pc,
 *     paymentRail = X402PaymentRail(...),
 *     config = ServerConfig(gating = GatingConfig(...))
 * )
 * server.onPaymentSettled = { receipt -> ... }
 * server.onGated = { ... }
 * server.listen(dataChannel, rtpSenders)
 * ```
 */
class PaywalledRTCServer(
    private val peerConnection: PeerConnection,
    private val paymentRail: PaymentRail,
    private var config: ServerConfig,
    private val nonceStore: NonceStore = InMemoryNonceStore()
) {
    companion object {
        private const val TAG = "PaywalledRTCServer"
    }

    // ─── Callbacks ──────────────────────────────────────────
    var onSessionStarted: ((sessionId: String) -> Unit)? = null
    var onPaymentRequested: ((PaymentRequest) -> Unit)? = null
    var onPaymentReceived: ((RailPayment) -> Unit)? = null
    var onPaymentSettled: ((PaymentReceipt) -> Unit)? = null
    var onPaymentRejected: ((reason: String) -> Unit)? = null
    var onSegmentStarted: ((segmentIndex: Int) -> Unit)? = null
    var onSegmentGated: ((segmentIndex: Int) -> Unit)? = null
    var onSegmentResumed: ((segmentIndex: Int) -> Unit)? = null
    var onSessionTerminated: ((sessionId: String) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    // ─── State ──────────────────────────────────────────────
    val sessionId: String = config.sessionId ?: UUID.randomUUID().toString()
    private var dc: DataChannel? = null
    private var senders: List<RtpSender> = emptyList()
    private var segmentIndex = 0
    private var pendingRequest: PaymentRequest? = null
    private var gated = true
    private var started = false
    private var disposed = false
    private val stats = SessionStats(sessionId = sessionId)

    private val handler = Handler(Looper.getMainLooper())
    private var segmentTimer: Runnable? = null
    private var graceTimer: Runnable? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ─── Public API ─────────────────────────────────────────

    /**
     * Update gating config at runtime (e.g., hybrid scenario switching from whole-stream to partial).
     */
    fun updateConfig(newConfig: ServerConfig) {
        config = newConfig
    }

    fun updateGating(gating: GatingConfig) {
        config = config.copy(gating = gating)
    }

    fun updateGracePeriod(seconds: Int) {
        config = config.copy(gracePeriod = seconds)
    }

    /**
     * Start the paywalled session with an existing DataChannel and the list of RTP senders
     * to gate (typically the video + audio senders you added via `pc.addTrack`).
     */
    fun listen(dataChannel: DataChannel, rtpSenders: List<RtpSender>) {
        if (started) return
        started = true

        this.dc = dataChannel
        this.senders = rtpSenders

        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}
            override fun onStateChange() {
                val state = dataChannel.state()
                Log.d(TAG, "DC state: $state")
                if (state == DataChannel.State.OPEN) {
                    handler.post { handleDataChannelOpen() }
                } else if (state == DataChannel.State.CLOSED) {
                    handler.post { handleDisconnect() }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                try {
                    buffer?.data?.let { byteBuffer ->
                        val bytes = ByteArray(byteBuffer.remaining())
                        byteBuffer.get(bytes)
                        handler.post { handleDataChannelMessage(String(bytes)) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onMessage error", e)
                }
            }
        })

        // If DC is already open, start immediately
        if (dataChannel.state() == DataChannel.State.OPEN) {
            handler.post { handleDataChannelOpen() }
        }

        onSessionStarted?.invoke(sessionId)
    }

    /**
     * Gate the stream — disable tracks.
     */
    fun gate() {
        gated = true
        for (sender in senders) {
            sender.track()?.setEnabled(false)
        }
        onSegmentGated?.invoke(segmentIndex)
    }

    /**
     * Ungate the stream — enable tracks.
     */
    fun ungate() {
        gated = false
        for (sender in senders) {
            sender.track()?.setEnabled(true)
        }
        onSegmentResumed?.invoke(segmentIndex)
    }

    /**
     * Terminate the session and notify the consumer.
     */
    fun terminate(reason: String? = null) {
        if (disposed) return
        disposed = true
        cancelTimers()
        gate()
        sendDC(JSONObject().apply {
            put("type", DCMessageType.SESSION_TERMINATE)
            put("sessionId", sessionId)
            put("payload", JSONObject().apply { put("reason", reason ?: "") })
        })
        try { dc?.close() } catch (_: Exception) {}
        onSessionTerminated?.invoke(sessionId)
    }

    // ─── Internal ───────────────────────────────────────────

    private fun handleDataChannelOpen() {
        if (disposed) return
        // Small delay to ensure the remote side has set up its onmessage handler
        handler.postDelayed({
            if (config.gating.mode == GatingMode.WHOLE_STREAM) {
                requestPayment()
            } else {
                ungate()
                val leadTime = ((config.gating.leadTime ?: config.gating.segmentDuration ?: 30) * 1000L)
                scheduleSegmentTimer(leadTime) { requestPaymentWithGrace() }
            }
        }, 100)
    }

    private fun handleDataChannelMessage(msgStr: String) {
        try {
            val msg = JSONObject(msgStr)
            val msgType = msg.getString("type")
            Log.e(TAG, "[DC_MESSAGE_RECEIVED] session=$sessionId type=$msgType bytes=${msgStr.length}")
            when (msgType) {
                DCMessageType.SEGMENT_PAYMENT -> {
                    if (msg.isNull("payload")) {
                        Log.e(TAG, "[SEGMENT_PAYMENT_DENIED] session=$sessionId segment=$segmentIndex")
                        onPaymentRejected?.invoke("Consumer denied payment")
                        terminate("Payment denied")
                    } else {
                        val railPayment = railPaymentFromJson(msg.getJSONObject("payload"))
                        Log.e(TAG, "[SEGMENT_PAYMENT_PAYLOAD_PARSED] session=$sessionId segment=$segmentIndex nonce=${railPayment.nonce} railId=${railPayment.railId}")
                        scope.launch { handlePayment(railPayment) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleDataChannelMessage error", e)
        }
    }

    private fun handleDisconnect() {
        cancelTimers()
        gate()
        onSessionTerminated?.invoke(sessionId)
    }

    private fun requestPayment() {
        scope.launch(Dispatchers.IO) {
            try {
                val shouldSkipPrompt = shouldSkipPaymentRequestBecauseSessionFunded()
                if (shouldSkipPrompt) {
                    Log.d(
                        TAG,
                        "💸 Skipping payment request: session vault still funded for viewer=${config.viewerAddress}",
                    )
                    if (gated) ungate()
                    if (config.gating.mode != GatingMode.WHOLE_STREAM) {
                        val duration = (config.gating.segmentDuration ?: 30) * 1000L
                        scheduleSegmentTimer(duration) {
                            requestPaymentWithGrace()
                        }
                    }
                    return@launch
                }

                val request = paymentRail.createPaymentRequest(PaymentRailRequestParams(
                    sessionId = sessionId,
                    segmentIndex = segmentIndex,
                    amount = config.gating.amount,
                    asset = config.gating.asset,
                    network = config.gating.network,
                    payTo = config.gating.payTo,
                    ttl = config.paymentTTL,
                    meta = PaymentRequestMeta(
                        gatingMode = config.gating.mode,
                        enforcement = config.enforcement,
                        segmentDuration = config.gating.segmentDuration,
                        segmentBytes = config.gating.segmentBytes,
                        viewerAddress = config.viewerAddress,
                        voucherSignature = null,
                    )
                ))

                pendingRequest = request
                onPaymentRequested?.invoke(request)
                Log.e(TAG, "[REQUEST_PAYMENT_SENT] session=$sessionId segment=${request.segmentIndex} nonce=${request.nonce} amount=${request.amount} asset=${request.asset} network=${request.network} payTo=${request.payTo} ttl=${request.ttl}")
                Log.d(
                    TAG,
                    "💸 Segment payment requested: session=$sessionId segment=${request.segmentIndex} amount=${request.amount} asset=${request.asset} ttl=${request.ttl}s segmentDuration=${request.meta.segmentDuration ?: -1}s",
                )

                sendDC(JSONObject().apply {
                    put("type", DCMessageType.SEGMENT_REQUEST)
                    put("sessionId", sessionId)
                    put("segmentIndex", segmentIndex)
                    put("payload", request.toJson())
                })
            } catch (e: Throwable) {
                Log.e(TAG, "[REQUEST_PAYMENT_FAILED] session=$sessionId segment=$segmentIndex amount=${config.gating.amount} asset=${config.gating.asset} network=${config.gating.network} payTo=${config.gating.payTo} error=${e.message}", e)
                onError?.invoke(e)
            }
        }
    }

    /**
     * Request payment with grace period — stream stays ungated while waiting.
     * First payment (segmentsPaid == 0) skips grace timer — consent may take longer.
     */
    private fun requestPaymentWithGrace() {
        val gracePeriod = config.gracePeriod * 1000L
        if (gracePeriod <= 0) {
            gate()
            requestPayment()
            return
        }

        requestPayment()

        // First payment — no grace timer
        if (stats.segmentsPaid == 0) return

        graceTimer = Runnable {
            graceTimer = null
            if (pendingRequest != null) {
                gate()
            }
        }
        handler.postDelayed(graceTimer!!, gracePeriod)
    }

    private fun shouldSkipPaymentRequestBecauseSessionFunded(): Boolean {
        if (!config.skipPaymentRequestWhenSessionFunded) return false
        val viewerAddress = config.viewerAddress?.takeIf { it.isNotBlank() } ?: return false
        val remaining =
            MppPayments.getRemainingBalanceFromSessionVault(
                viewerAddress = viewerAddress,
                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
            )
        return (remaining ?: 0L) > 0L
    }

    private suspend fun handlePayment(railPayment: RailPayment) {
        val request = pendingRequest ?: run {
            Log.e(TAG, "[HANDLE_PAYMENT_NO_PENDING_REQUEST] session=$sessionId segment=$segmentIndex")
            onPaymentRejected?.invoke("No pending request")
            return
        }

        onPaymentReceived?.invoke(railPayment)

        graceTimer?.let { handler.removeCallbacks(it); graceTimer = null }

        // Nonce check
        if (railPayment.nonce != request.nonce) {
            Log.e(TAG, "[HANDLE_PAYMENT_NONCE_MISMATCH] session=$sessionId segment=$segmentIndex expected=${request.nonce} actual=${railPayment.nonce}")
            onPaymentRejected?.invoke("Nonce mismatch")
            sendDC(JSONObject().apply {
                put("type", DCMessageType.SEGMENT_REJECTED)
                put("sessionId", sessionId)
                put("segmentIndex", segmentIndex)
                put("payload", JSONObject().apply { put("reason", "nonce_mismatch") })
            })
            terminate("Nonce mismatch")
            return
        }

        // Replay protection
        val isNew = nonceStore.checkAndStore(railPayment.nonce, config.paymentTTL)
        if (!isNew) {
            Log.e(TAG, "[HANDLE_PAYMENT_NONCE_REPLAY] session=$sessionId segment=$segmentIndex nonce=${railPayment.nonce}")
            onPaymentRejected?.invoke("Nonce replay detected")
            sendDC(JSONObject().apply {
                put("type", DCMessageType.SEGMENT_REJECTED)
                put("sessionId", sessionId)
                put("segmentIndex", segmentIndex)
                put("payload", JSONObject().apply { put("reason", "nonce_replay") })
            })
            return
        }

        // Verify and settle via rail
        try {
            val receipt = withContext(Dispatchers.IO) {
                paymentRail.verifyAndSettle(railPayment, request)
            }

            stats.segmentsPaid++
            stats.totalAmountReceived = (
                stats.totalAmountReceived.toBigInteger() + request.amount.toBigInteger()
            ).toString()

            pendingRequest = null
            onPaymentSettled?.invoke(receipt)

            if (gated) ungate()

            sendDC(JSONObject().apply {
                put("type", DCMessageType.SEGMENT_ACCEPTED)
                put("sessionId", sessionId)
                put("segmentIndex", segmentIndex)
                put("payload", receipt.toJson())
            })

            stats.segmentsDelivered++
            onSegmentStarted?.invoke(segmentIndex)

            // Schedule next segment (use current config — may have been updated in onPaymentSettled)
            if (config.gating.mode != GatingMode.WHOLE_STREAM) {
                segmentIndex++
                val duration = (config.gating.segmentDuration ?: 30) * 1000L
                Log.d(
                    TAG,
                    "⏱️ Segment timer scheduled: session=$sessionId nextSegment=$segmentIndex in=${duration}ms (segmentDuration=${config.gating.segmentDuration ?: 30}s)",
                )
                scheduleSegmentTimer(duration) {
                    Log.d(TAG, "⏱️ Segment timer tick: session=$sessionId segment=$segmentIndex")
                    requestPaymentWithGrace()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[HANDLE_PAYMENT_SETTLE_FAILED] session=$sessionId segment=$segmentIndex amount=${request.amount} asset=${request.asset} network=${request.network} payTo=${request.payTo} error=${e.message}", e)
            onError?.invoke(e)
            onPaymentRejected?.invoke(e.message ?: "Payment failed")
            terminate("Payment failed: ${e.message}")
        }
    }

    // ─── Helpers ────────────────────────────────────────────

    private fun sendDC(msg: JSONObject) {
        try {
            val dc = this.dc ?: return
            if (dc.state() == DataChannel.State.OPEN) {
                val buffer = ByteBuffer.wrap(msg.toString().toByteArray())
                dc.send(DataChannel.Buffer(buffer, false))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendDC failed", e)
        }
    }

    private fun scheduleSegmentTimer(delayMs: Long, action: () -> Unit) {
        cancelSegmentTimer()
        segmentTimer = Runnable { action() }
        handler.postDelayed(segmentTimer!!, delayMs)
    }

    private fun cancelSegmentTimer() {
        segmentTimer?.let { handler.removeCallbacks(it); segmentTimer = null }
    }

    private fun cancelTimers() {
        cancelSegmentTimer()
        graceTimer?.let { handler.removeCallbacks(it); graceTimer = null }
    }
}
