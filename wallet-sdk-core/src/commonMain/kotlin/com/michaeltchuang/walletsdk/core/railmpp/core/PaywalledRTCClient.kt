package com.michaeltchuang.walletsdk.core.railmpp.core

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.RailPayment
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.SpendSummary
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.SpendTransaction
import com.michaeltchuang.walletsdk.core.railmpp.internal.ensureCryptoProvider
import com.michaeltchuang.walletsdk.core.railmpp.internal.mppNowMs
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


private val receiptDecodeJson = Json { ignoreUnknownKeys = true }

/**
 * PaywalledRTCClient — consumer-side payment-channel orchestration.
 *
 * Manages consent, auto-pay, and budget tracking for the consumer over a
 * platform-agnostic [RtcDataChannel].
 */
class PaywalledRTCClient(
    private val paymentRail: PaymentRail,
    private val consent: ConsentHandler,
    private val config: ClientConfig = ClientConfig(),
) {
    private companion object {
        const val TAG = "PaywalledRTCClient"
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
    var onChatMessageReceived: ((ChatMessage) -> Unit)? = null
    var onSessionTerminated: (() -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    // ─── State ──────────────────────────────────────────────
    private var dc: RtcDataChannel? = null
    private var consentApproval: ConsentApproval? = null
    private var started = false
    private var disposed = false

    val spend = SpendSummary()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        ensureCryptoProvider()
    }

    // ─── Public API ─────────────────────────────────────────

    /** Connect the client using an existing DataChannel (typically created by the consumer). */
    fun connect(dataChannel: RtcDataChannel) {
        if (started) return
        started = true

        this.dc = dataChannel
        dataChannel.registerObserver(
            object : RtcDataChannelObserver {
                override fun onStateChange() {
                    val state = dataChannel.state()
                    Napier.d("DC state: $state", tag = TAG)
                    when (state) {
                        RtcDataChannelState.OPEN -> scope.launch { onDataChannelOpen?.invoke() }
                        RtcDataChannelState.CLOSED -> scope.launch { handleDisconnect() }
                        else -> {}
                    }
                }

                override fun onMessage(data: ByteArray) {
                    val text = data.decodeToString()
                    scope.launch { handleDataChannelMessage(text) }
                }
            },
        )

        // Race guard: if DC was already OPEN before we attached the observer,
        // onStateChange never fires. Fire the callback immediately.
        if (dataChannel.state() == RtcDataChannelState.OPEN) {
            scope.launch { onDataChannelOpen?.invoke() }
        }
    }

    fun setAutoPay(enabled: Boolean) {
        consentApproval = consentApproval?.copy(autoPaySegments = enabled)
    }

    fun getAutoPay(): Boolean = consentApproval?.autoPaySegments ?: false

    /**
     * Extends the viewer's budget cap after a session-vault top-up.
     *
     * The new cap is `currentSpend + additionalMicroUsdc` so the next segment payment
     * passes the budget check even though spend has already accumulated. Also clears
     * [ConsentApproval.maxAutoPaySegments] so the segment-count limit does not re-trigger consent.
     */
    fun extendBudget(
        additionalMicroUsdc: Long,
        asset: String,
    ) {
        val currentSpend = spend.totalAmount.toLongOrNull() ?: 0L
        val newCap = currentSpend + additionalMicroUsdc
        consentApproval =
            consentApproval?.copy(
                budgetCap = BudgetCap(amount = newCap.toString(), asset = asset),
                maxAutoPaySegments = null,
            ) ?: ConsentApproval(
                approved = true,
                autoPaySegments = true,
                budgetCap = BudgetCap(amount = newCap.toString(), asset = asset),
            )
        Napier.d(
            "[VIEWER_BUDGET_EXTENDED] currentSpend=$currentSpend additional=$additionalMicroUsdc newCap=$newCap asset=$asset",
            tag = TAG,
        )
    }

    /**
     * Signals the server that the viewer topped up the session vault so it should
     * re-issue the pending [DCMessageType.SEGMENT_REQUEST].
     */
    fun notifyVaultFunded(sessionId: String) {
        Napier.d("[VIEWER_VAULT_FUNDED_NOTIFY] sessionId=$sessionId", tag = TAG)
        sendDC(
            buildJsonObject {
                put(DCFieldKey.TYPE, DCMessageType.VIEWER_VAULT_FUNDED.value)
                put(DCFieldKey.SESSION_ID, sessionId)
            },
        )
    }

    fun sendVoucher(json: String) {
        try {
            val dc = this.dc ?: return
            if (dc.state() == RtcDataChannelState.OPEN) {
                dc.send(json.encodeToByteArray())
            }
        } catch (e: Exception) {
            Napier.e("sendVoucher failed", e, tag = TAG)
        }
    }

    fun sendHello(
        viewer: String,
        viewerPublicKey: String,
    ) {
        sendDC(
            buildJsonObject {
                put(DCFieldKey.TYPE, DCMessageType.SEGMENT_HANDSHAKE.value)
                put("viewer", viewer)
                put("viewerPublicKey", viewerPublicKey)
            },
        )
    }

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

    fun sendChatMessage(message: ChatMessage) {
        sendDC(
            buildJsonObject {
                put(DCFieldKey.TYPE, DCMessageType.CHAT_MESSAGE.value)
                put(DCFieldKey.PAYLOAD, Json.encodeToJsonElement(ChatMessage.serializer(), message))
            },
        )
    }

    // ─── Internal ───────────────────────────────────────────

    private fun handleDataChannelMessage(msgStr: String) {
        try {
            // Guard against plain-text keepalive strings (e.g. "ping") that are not JSON.
            val trimmed = msgStr.trim()
            if (!trimmed.startsWith("{")) {
                Napier.d("[DC_PLAIN_MESSAGE_IGNORED] message=$trimmed", tag = TAG)
                return
            }

            val msg = Json.parseToJsonElement(msgStr).jsonObject
            val rawType = msg[DCFieldKey.TYPE]?.jsonPrimitive?.content
            val msgType = DCMessageType.fromStringOrNull(rawType)
            Napier.d("[DC_MESSAGE_RECEIVED] type=$rawType bytes=${msgStr.length}", tag = TAG)
            when (msgType) {
                DCMessageType.SEGMENT_REQUEST -> {
                    val payload = msg[DCFieldKey.PAYLOAD]!!.jsonObject
                    // Merge envelope fields if not present in the payload.
                    val merged =
                        buildJsonObject {
                            payload.forEach { (k, v) -> put(k, v) }
                            if (!payload.containsKey(DCFieldKey.SESSION_ID)) {
                                put(DCFieldKey.SESSION_ID, msg[DCFieldKey.SESSION_ID]!!.jsonPrimitive.content)
                            }
                            if (!payload.containsKey(DCFieldKey.SEGMENT_INDEX)) {
                                put(DCFieldKey.SEGMENT_INDEX, msg[DCFieldKey.SEGMENT_INDEX]!!.jsonPrimitive.int)
                            }
                        }
                    val request = paymentRequestFromJson(merged)
                    captureChannelId(request.channelId)
                    captureSalt(request.salt)
                    EscrowSessionVaultManagerClient.hostAddress = request.payTo
                    Napier.d(
                        "[VIEWER_SEGMENT_REQUEST_RECEIVED] session=${request.sessionId} segment=${request.segmentIndex} " +
                            "nonce=${request.nonce} amount=${request.amount} asset=${request.asset} network=${request.network}" +
                                " payTo=${request.payTo} channelId=${request.channelId}" +
                                " salt=${request.salt}",
                        tag = TAG,
                    )
                    scope.launch { handlePaymentRequest(request) }
                }

                DCMessageType.SEGMENT_ACCEPTED -> {
                    val payload = msg[DCFieldKey.PAYLOAD]!!.jsonObject
                    val receipt = receiptDecodeJson.decodeFromJsonElement<PaymentReceipt>(payload)
                    captureChannelId(receipt.channelId)
                    onPaymentReceipt?.invoke(receipt)
                }

                DCMessageType.SEGMENT_REJECTED -> {
                    val reason = (msg[DCFieldKey.PAYLOAD] as? JsonObject)?.optStr("reason", "rejected") ?: "rejected"
                    onStreamGated?.invoke(reason)
                }

                DCMessageType.SESSION_TERMINATE -> {
                    onSessionTerminated?.invoke()
                }

                DCMessageType.CHAT_MESSAGE -> {
                    val payload = msg[DCFieldKey.PAYLOAD]?.jsonObject
                    if (payload != null) {
                        val chatMsg = Json.decodeFromJsonElement(ChatMessage.serializer(), payload)
                        onChatMessageReceived?.invoke(chatMsg)
                    }
                }
                else -> Unit
            }
        } catch (e: Exception) {
            Napier.e("handleDataChannelMessage error", e, tag = TAG)
        }
    }

    private suspend fun handlePaymentRequest(request: PaymentRequest) {
        onPaymentRequested?.invoke(request)

        // Auto-pay configured + usage payment (not access gate) → skip consent entirely.
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

        // Request consent if: first payment and no auto-approval, OR auto-pay is off.
        val needsConsent = consentApproval == null || !consentApproval!!.autoPaySegments

        if (needsConsent) {
            val terms =
                ConsentTerms(
                    gatingMode = request.meta.gatingMode,
                    amount = request.amount,
                    asset = request.asset,
                    network = request.network,
                    segmentDuration = request.meta.segmentDuration,
                    segmentBytes = request.meta.segmentBytes,
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
                Napier.d("[VIEWER_CONSENT_DENIED] session=${request.sessionId} segment=${request.segmentIndex}", tag = TAG)
                onConsentDenied?.invoke()
                sendDC(
                    buildJsonObject {
                        put(DCFieldKey.TYPE, DCMessageType.SEGMENT_PAYMENT.value)
                        put(DCFieldKey.SESSION_ID, request.sessionId)
                        put(DCFieldKey.SEGMENT_INDEX, request.segmentIndex)
                        put(DCFieldKey.PAYLOAD, JsonNull)
                    },
                )
                return
            }

            consentApproval = approval
            spend.asset = request.asset
            onConsentApproved?.invoke(approval)
        }

        // Budget cap.
        consentApproval?.budgetCap?.let { cap ->
            val newTotal = BigInteger.parseString(spend.totalAmount) + BigInteger.parseString(request.amount)
            if (newTotal > BigInteger.parseString(cap.amount)) {
                onBudgetExceeded?.invoke(spend)
                onStreamGated?.invoke("Budget exceeded")
                return
            }
        }

        // Max auto-pay segments.
        consentApproval?.maxAutoPaySegments?.let { max ->
            if (spend.segmentsPaid >= max) {
                consentApproval = null
                handlePaymentRequest(request)
                return
            }
        }

        // Create and sign payment via the rail.
        try {
            val railPayment =
                withContext(Dispatchers.Default) {
                    ensureCryptoProvider()
                    paymentRail.createRailPayment(request)
                }
            onPaymentSubmitted?.invoke(railPayment)

            spend.segmentsPaid++
            spend.totalAmount =
                (BigInteger.parseString(spend.totalAmount) + BigInteger.parseString(request.amount)).toString()
            spend.transactions.add(
                SpendTransaction(
                    txId = "pending",
                    amount = request.amount,
                    segmentIndex = request.segmentIndex,
                    timestamp = mppNowMs(),
                ),
            )

            sendDC(
                buildJsonObject {
                    put(DCFieldKey.TYPE, DCMessageType.SEGMENT_PAYMENT.value)
                    put(DCFieldKey.SESSION_ID, request.sessionId)
                    put(DCFieldKey.SEGMENT_INDEX, request.segmentIndex)
                    put(DCFieldKey.PAYLOAD, railPayment.toJson())
                },
            )
            Napier.d(
                "[VIEWER_SEGMENT_PAYMENT_SENT] session=${request.sessionId} segment=${request.segmentIndex} nonce=${railPayment.nonce}",
                tag = TAG,
            )
        } catch (e: Throwable) {
            Napier.e(
                "[VIEWER_PAYMENT_FAILED] session=${request.sessionId} segment=${request.segmentIndex} nonce=${request.nonce} error=${e.message}",
                e,
                tag = TAG,
            )
            onError?.invoke(e)
            onStreamGated?.invoke("Payment failed: ${e.message}")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun captureChannelId(channelIdBase64: String?) {
        val encoded = channelIdBase64?.takeIf { it.isNotBlank() } ?: return
        runCatching { Base64.decode(encoded) }
            .onSuccess { decoded ->
                if (decoded.isNotEmpty()) {
                    EscrowSessionVaultManagerClient.channelId = decoded
                    Napier.d("[VIEWER_CHANNEL_ID_CAPTURED] len=${decoded.size}", tag = TAG)
                }
            }.onFailure {
                Napier.e("[VIEWER_CHANNEL_ID_DECODE_FAILED]", it, tag = TAG)
            }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun captureSalt(saltBase64: String?) {
        val encoded = saltBase64?.takeIf { it.isNotBlank() } ?: return
        runCatching { Base64.decode(encoded) }
            .onSuccess { decoded ->
                if (decoded.isNotEmpty()) {
                    EscrowSessionVaultManagerClient.salt = decoded
                    Napier.d("[VIEWER_SALT_CAPTURED] len=${decoded.size}", tag = TAG)
                }
            }.onFailure {
                Napier.e("[VIEWER_SALT_DECODE_FAILED]", it, tag = TAG)
            }
    }

    private fun handleDisconnect() {
        onSessionTerminated?.invoke()
    }

    private fun sendDC(msg: JsonObject) {
        try {
            val dc = this.dc ?: return
            if (dc.state() == RtcDataChannelState.OPEN) {
                dc.send(msg.toString().encodeToByteArray())
            }
        } catch (e: Exception) {
            Napier.e("sendDC failed", e, tag = TAG)
        }
    }
}

// ─── JSON accessors mirroring org.json opt* semantics ───────────────────────

private fun JsonObject.optStr(
    key: String,
    default: String,
): String = this[key]?.jsonPrimitive?.content ?: default


