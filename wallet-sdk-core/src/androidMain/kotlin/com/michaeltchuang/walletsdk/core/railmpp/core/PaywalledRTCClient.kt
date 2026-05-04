package com.michaeltchuang.walletsdk.core.railmpp.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer

/**
 * PaywalledRTCClient — consumer-side WebRTC wrapper.
 *
 * Manages consent, auto-pay, and budget tracking for the consumer.
 *
 * Usage:
 * ```
 * val client = PaywalledRTCClient(
 *     peerConnection = pc,
 *     paymentRail = X402PaymentRail(...),
 *     consent = MyConsentHandler(),
 *     config = ClientConfig(autoPaySegments = true, budgetCap = ...)
 * )
 * client.onStreamStarted = { ... }
 * client.onPaymentReceipt = { receipt -> ... }
 * client.connect(dataChannel)
 * ```
 */
class PaywalledRTCClient(
    private val peerConnection: PeerConnection,
    private val paymentRail: PaymentRail,
    private val consent: ConsentHandler,
    private val config: ClientConfig = ClientConfig(),
) {
    companion object {
        private const val TAG = "PaywalledRTCClient"
    }

    // ─── Callbacks ──────────────────────────────────────────
    var onPaymentRequested: ((PaymentRequest) -> Unit)? = null
    var onPaymentSubmitted: ((RailPayment) -> Unit)? = null
    var onPaymentReceipt: ((PaymentReceipt) -> Unit)? = null
    var onConsentRequested: ((ConsentTerms) -> Unit)? = null
    var onConsentApproved: ((ConsentApproval) -> Unit)? = null
    var onConsentDenied: (() -> Unit)? = null
    var onDataChannelOpen: (() -> Unit)? = null
    var onStreamStarted: (() -> Unit)? = null
    var onStreamGated: ((reason: String) -> Unit)? = null
    var onStreamResumed: (() -> Unit)? = null
    var onBudgetExceeded: ((SpendSummary) -> Unit)? = null
    var onSessionTerminated: (() -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    // ─── State ──────────────────────────────────────────────
    private var dc: DataChannel? = null
    private var consentApproval: ConsentApproval? = null
    private var started = false
    private var disposed = false

    val spend = SpendSummary()

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ─── Public API ─────────────────────────────────────────

    /**
     * Connect the client using an existing DataChannel (typically created by the consumer).
     */
    fun connect(dataChannel: DataChannel) {
        if (started) return
        started = true

        this.dc = dataChannel
        dataChannel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(amount: Long) {}

                override fun onStateChange() {
                    val state = dataChannel.state()
                    Log.d(TAG, "DC state: $state")
                    when (state) {
                        DataChannel.State.OPEN -> handler.post { onDataChannelOpen?.invoke() }
                        DataChannel.State.CLOSED -> handler.post { handleDisconnect() }
                        else -> {}
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
            },
        )

        // Race guard: if DC was already OPEN before we attached the observer,
        // onStateChange never fires. Fire the callback immediately.
        if (dataChannel.state() == DataChannel.State.OPEN) {
            handler.post { onDataChannelOpen?.invoke() }
        }
    }

    fun setAutoPay(enabled: Boolean) {
        consentApproval = consentApproval?.copy(autoPaySegments = enabled)
    }

    fun getAutoPay(): Boolean = consentApproval?.autoPaySegments ?: false

    fun terminate() {
        if (disposed) return
        disposed = true
        consentApproval = null
        started = false
        try {
            dc?.close()
        } catch (_: Exception) {
        }
        onSessionTerminated?.invoke()
    }

    // ─── Internal ───────────────────────────────────────────

    private fun handleDataChannelMessage(msgStr: String) {
        try {
            val msg = JSONObject(msgStr)
            when (msg.getString("type")) {
                DCMessageType.SEGMENT_REQUEST -> {
                    val payload = msg.getJSONObject("payload")
                    // Merge envelope fields if not in payload
                    if (!payload.has("sessionId")) payload.put("sessionId", msg.getString("sessionId"))
                    if (!payload.has("segmentIndex")) payload.put("segmentIndex", msg.getInt("segmentIndex"))
                    val request = paymentRequestFromJson(payload)
                    Log.e(
                        TAG,
                        "[VIEWER_SEGMENT_REQUEST_RECEIVED] session=${request.sessionId} segment=${request.segmentIndex} nonce=${request.nonce} amount=${request.amount} asset=${request.asset} network=${request.network} payTo=${request.payTo}",
                    )
                    scope.launch { handlePaymentRequest(request) }
                }
                DCMessageType.SEGMENT_ACCEPTED -> {
                    val payload = msg.getJSONObject("payload")
                    val receipt =
                        PaymentReceipt(
                            txId = payload.optString("txId", "?"),
                            sessionId = payload.optString("sessionId", ""),
                            segmentIndex = payload.optInt("segmentIndex", 0),
                            amount = payload.optString("amount", "0"),
                            asset = payload.optString("asset", ""),
                            payTo = payload.optString("payTo", ""),
                            network = payload.optString("network", ""),
                            timestamp = payload.optLong("timestamp", System.currentTimeMillis()),
                        )
                    onPaymentReceipt?.invoke(receipt)
                }
                DCMessageType.SEGMENT_REJECTED -> {
                    val reason = msg.optJSONObject("payload")?.optString("reason") ?: "rejected"
                    onStreamGated?.invoke(reason)
                }
                DCMessageType.SESSION_TERMINATE -> {
                    onSessionTerminated?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleDataChannelMessage error", e)
        }
    }

    private suspend fun handlePaymentRequest(request: PaymentRequest) {
        Log.e(
            TAG,
            "[VIEWER_HANDLE_PAYMENT_START] session=${request.sessionId} segment=${request.segmentIndex} nonce=${request.nonce} amount=${request.amount} asset=${request.asset}",
        )
        onPaymentRequested?.invoke(request)

        // Auto-pay configured + usage payment (not access gate) → skip consent entirely
        if (consentApproval == null &&
            config.autoPaySegments &&
            request.meta.gatingMode != GatingMode.WHOLE_STREAM
        ) {
            consentApproval =
                ConsentApproval(
                    approved = true,
                    autoPaySegments = true,
                    budgetCap = config.budgetCap,
                    maxAutoPaySegments = config.maxAutoPaySegments,
                )
            spend.asset = request.asset
        }

        // Request consent if: first payment and no auto-approval, OR auto-pay is off
        val needsConsent = consentApproval == null || !consentApproval!!.autoPaySegments

        if (needsConsent) {
            val terms =
                ConsentTerms(
                    gatingMode = request.meta.gatingMode,
                    amount = request.amount,
                    asset = request.asset,
                    network = request.network,
                    payTo = request.payTo,
                    segmentDuration = request.meta.segmentDuration,
                    segmentBytes = request.meta.segmentBytes,
                )
            Log.e(
                TAG,
                "[VIEWER_CONSENT_REQUESTED] session=${request.sessionId} segment=${request.segmentIndex} amount=${terms.amount} asset=${terms.asset} network=${terms.network}",
            )
            onConsentRequested?.invoke(terms)
            val approval =
                try {
                    consent.requestConsent(terms)
                } catch (e: Exception) {
                    onError?.invoke(e)
                    return
                }

            if (!approval.approved) {
                Log.e(TAG, "[VIEWER_CONSENT_DENIED] session=${request.sessionId} segment=${request.segmentIndex}")
                onConsentDenied?.invoke()
                sendDC(
                    JSONObject().apply {
                        put("type", DCMessageType.SEGMENT_PAYMENT)
                        put("sessionId", request.sessionId)
                        put("segmentIndex", request.segmentIndex)
                        put("payload", JSONObject.NULL)
                    },
                )
                return
            }

            consentApproval = approval
            spend.asset = request.asset
            onConsentApproved?.invoke(approval)
        }

        // Budget cap
        consentApproval!!.budgetCap?.let { cap ->
            val newTotal = spend.totalAmount.toBigInteger() + request.amount.toBigInteger()
            if (newTotal > cap.amount.toBigInteger()) {
                onBudgetExceeded?.invoke(spend)
                onStreamGated?.invoke("Budget exceeded")
                return
            }
        }

        // Max auto-pay segments
        consentApproval!!.maxAutoPaySegments?.let { max ->
            if (spend.segmentsPaid >= max) {
                consentApproval = null
                handlePaymentRequest(request)
                return
            }
        }

        // Create and sign payment via the rail
        try {
            val railPayment =
                withContext(Dispatchers.IO) {
                    paymentRail.createRailPayment(request)
                }
            Log.e(
                TAG,
                "[VIEWER_RAIL_PAYMENT_CREATED] session=${request.sessionId} segment=${request.segmentIndex} nonce=${railPayment.nonce} railId=${railPayment.railId}",
            )
            onPaymentSubmitted?.invoke(railPayment)

            spend.segmentsPaid++
            spend.totalAmount = (spend.totalAmount.toBigInteger() + request.amount.toBigInteger()).toString()
            spend.transactions.add(
                SpendTransaction(
                    txId = "pending",
                    amount = request.amount,
                    segmentIndex = request.segmentIndex,
                    timestamp = System.currentTimeMillis(),
                ),
            )

            sendDC(
                JSONObject().apply {
                    put("type", DCMessageType.SEGMENT_PAYMENT)
                    put("sessionId", request.sessionId)
                    put("segmentIndex", request.segmentIndex)
                    put("payload", railPayment.toJson())
                },
            )
            Log.e(
                TAG,
                "[VIEWER_SEGMENT_PAYMENT_SENT] session=${request.sessionId} segment=${request.segmentIndex} nonce=${railPayment.nonce}",
            )
        } catch (e: Throwable) {
            Log.e(
                TAG,
                "[VIEWER_PAYMENT_FAILED] session=${request.sessionId} segment=${request.segmentIndex} nonce=${request.nonce} error=${e.message}",
                e,
            )
            onError?.invoke(e)
            onStreamGated?.invoke("Payment failed: ${e.message}")
        }
    }

    private fun handleDisconnect() {
        onSessionTerminated?.invoke()
    }

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
}
