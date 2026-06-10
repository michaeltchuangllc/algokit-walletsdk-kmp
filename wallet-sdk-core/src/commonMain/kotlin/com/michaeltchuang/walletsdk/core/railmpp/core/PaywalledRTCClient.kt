package com.michaeltchuang.walletsdk.core.railmpp.core

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.railmpp.internal.ensureCryptoProvider
import com.michaeltchuang.walletsdk.core.railmpp.internal.mppNowMs
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
                put("type", DCMessageType.VIEWER_VAULT_FUNDED)
                put("sessionId", sessionId)
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

            if (msg["reference"]?.jsonPrimitive?.content == LiquidDcMessages.REF_PAYMENT_REQUEST) {
                val env = LiquidDcMessages.parsePaymentRequest(msgStr)
                if (env != null) {
                    @OptIn(ExperimentalUuidApi::class)
                    val request =
                        PaymentRequest(
                            id = env.id,
                            sessionId = env.sessionId ?: env.id,
                            segmentIndex = env.segmentIndex ?: 0,
                            amount = env.amount,
                            asset = env.asset,
                            network = env.network,
                            payTo = env.payTo,
                            ttl = 30,
                            nonce = env.nonce.ifBlank { Uuid.random().toString() },
                            meta =
                                PaymentRequestMeta(
                                    gatingMode =
                                        if (env.gatingMode != null) {
                                            GatingMode.fromString(env.gatingMode)
                                        } else {
                                            GatingMode.PARTIAL_TIME
                                        },
                                    enforcement = EnforcementMode.TRACK,
                                    segmentDuration = env.segmentDuration,
                                ),
                            railPayload = null,
                        )
                    Napier.d(
                        "[VIEWER_IOS_PAYMENT_REQUEST_RECEIVED] session=${request.sessionId} " +
                            "segment=${request.segmentIndex} nonce=${request.nonce} " +
                            "amount=${request.amount} asset=${request.asset} payTo=${request.payTo}",
                        tag = TAG,
                    )
                    scope.launch { handlePaymentRequest(request) }
                } else {
                    Napier.w("[VIEWER_IOS_PAYMENT_REQUEST_PARSE_FAILED] raw=${msgStr.take(200)}", tag = TAG)
                }
                return
            }

            when (msg["type"]?.jsonPrimitive?.content) {
                DCMessageType.SEGMENT_REQUEST -> {
                    val payload = msg["payload"]!!.jsonObject
                    // Merge envelope fields if not present in the payload.
                    val merged =
                        buildJsonObject {
                            payload.forEach { (k, v) -> put(k, v) }
                            if (!payload.containsKey("sessionId")) {
                                put("sessionId", msg["sessionId"]!!.jsonPrimitive.content)
                            }
                            if (!payload.containsKey("segmentIndex")) {
                                put("segmentIndex", msg["segmentIndex"]!!.jsonPrimitive.int)
                            }
                        }
                    val request = paymentRequestFromJson(merged)
                    Napier.d(
                        "[VIEWER_SEGMENT_REQUEST_RECEIVED] session=${request.sessionId} segment=${request.segmentIndex} " +
                            "nonce=${request.nonce} amount=${request.amount} asset=${request.asset} network=${request.network} payTo=${request.payTo}",
                        tag = TAG,
                    )
                    scope.launch { handlePaymentRequest(request) }
                }

                DCMessageType.SEGMENT_ACCEPTED -> {
                    val payload = msg["payload"]!!.jsonObject
                    val receipt =
                        PaymentReceipt(
                            txId = payload.optStr("txId", "?"),
                            sessionId = payload.optStr("sessionId", ""),
                            segmentIndex = payload.optInt("segmentIndex", 0),
                            amount = payload.optStr("amount", "0"),
                            asset = payload.optStr("asset", ""),
                            payTo = payload.optStr("payTo", ""),
                            network = payload.optStr("network", ""),
                            timestamp = payload.optLong("timestamp", mppNowMs()),
                        )
                    onPaymentReceipt?.invoke(receipt)
                }

                DCMessageType.SEGMENT_REJECTED -> {
                    val reason = (msg["payload"] as? JsonObject)?.optStr("reason", "rejected") ?: "rejected"
                    onStreamGated?.invoke(reason)
                }

                DCMessageType.SESSION_TERMINATE -> {
                    onSessionTerminated?.invoke()
                }
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
                    payTo = request.payTo,
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
                        put("type", DCMessageType.SEGMENT_PAYMENT)
                        put("sessionId", request.sessionId)
                        put("segmentIndex", request.segmentIndex)
                        put("payload", JsonNull)
                    },
                )
                return
            }

            consentApproval = approval
            spend.asset = request.asset
            onConsentApproved?.invoke(approval)
        }

        // Budget cap.
        consentApproval!!.budgetCap?.let { cap ->
            val newTotal = BigInteger.parseString(spend.totalAmount) + BigInteger.parseString(request.amount)
            if (newTotal > BigInteger.parseString(cap.amount)) {
                onBudgetExceeded?.invoke(spend)
                onStreamGated?.invoke("Budget exceeded")
                return
            }
        }

        // Max auto-pay segments.
        consentApproval!!.maxAutoPaySegments?.let { max ->
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
                    put("type", DCMessageType.SEGMENT_PAYMENT)
                    put("sessionId", request.sessionId)
                    put("segmentIndex", request.segmentIndex)
                    put("payload", railPayment.toJson())
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

private fun JsonObject.optInt(
    key: String,
    default: Int,
): Int = this[key]?.jsonPrimitive?.int ?: default

private fun JsonObject.optLong(
    key: String,
    default: Long,
): Long = this[key]?.jsonPrimitive?.long ?: default
