package com.michaeltchuang.walletsdk.core.railmpp.core

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.michaeltchuang.walletsdk.core.railmpp.core.PaywalledRTCServer.Companion.VIEWER_KEY_WAIT_TIMEOUT_MS
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentReceipt
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequestMeta
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.RailPayment
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.SessionStats
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.internal.mppNowMs
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * PaywalledRTCServer — provider-side payment-channel orchestration.
 *
 * Manages gating, segment timing, payment requests/verification, and optional
 * track enforcement over a platform-agnostic [RtcDataChannel].
 */
class PaywalledRTCServer
    @OptIn(ExperimentalUuidApi::class)
    constructor(
        private val paymentRail: PaymentRail,
        private var config: ServerConfig,
        private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
        private val nonceStore: NonceStore = InMemoryNonceStore(),
    ) {
        private companion object {
            const val TAG = "PaywalledRTCServer"
            const val DEFAULT_SEGMENT_DURATION_SECONDS = 30

            /**
             * How long to wait for [ServerConfig.viewerAuthorizedSignerPublicKey] to arrive
             * (via the viewer's first voucher message → [updateConfig]) before proceeding
             * without it. Required for an accurate funded-skip check.
             */
            const val VIEWER_KEY_WAIT_TIMEOUT_MS = 5_000L
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
        @OptIn(ExperimentalUuidApi::class)
        val sessionId: String = config.sessionId ?: Uuid.random().toString()
        private var dc: RtcDataChannel? = null
        private var senders: List<RtcRtpSender> = emptyList()
        private var segmentIndex = 0
        private var pendingRequest: PaymentRequest? = null
        private var gated = true
        private var started = false
        private var disposed = false
        private val stats = SessionStats(sessionId = sessionId)

        private var segmentTimer: Job? = null
        private var graceTimer: Job? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private var cachedChannelIdBase64: String? = null
        private var cachedSaltBase64: String? = null

        /**
         * Resolved as soon as [ServerConfig.viewerAuthorizedSignerPublicKey] becomes non-null.
         * On the first session the key is null at construction time — it arrives later via
         * the viewer's first voucher message ([updateConfig]). [handleDataChannelOpen] awaits
         * this (bounded by [VIEWER_KEY_WAIT_TIMEOUT_MS]) so the funded-skip check has the key.
         */
        private val viewerKeyDeferred: CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { deferred ->
                if (config.viewerAuthorizedSignerPublicKey != null) deferred.complete(Unit)
            }

        private val segmentDurationMs: Long
            get() = (config.gating.segmentDuration ?: DEFAULT_SEGMENT_DURATION_SECONDS) * 1000L

        // ─── Public API ─────────────────────────────────────────

        /**
         * Update gating config at runtime. Also resolves [viewerKeyDeferred] the first time
         * [ServerConfig.viewerAuthorizedSignerPublicKey] transitions null → non-null.
         */
        fun updateConfig(newConfig: ServerConfig) {
            val hadKey = config.viewerAuthorizedSignerPublicKey != null
            config = newConfig
            if (!hadKey && newConfig.viewerAuthorizedSignerPublicKey != null) {
                Napier.d("🔑 viewerAuthorizedSignerPublicKey received — resolving viewerKeyDeferred", tag = TAG)
                viewerKeyDeferred.complete(Unit)
            }
        }

        fun updateGating(gating: GatingConfig) {
            config = config.copy(gating = gating)
        }

        fun updateGracePeriod(seconds: Int) {
            config = config.copy(gracePeriod = seconds)
        }

        /** Start the paywalled session with an existing DataChannel and the RTP senders to gate. */
        fun listen(
            dataChannel: RtcDataChannel,
            rtpSenders: List<RtcRtpSender>,
        ) {
            if (started) return
            started = true

            this.dc = dataChannel
            this.senders = rtpSenders

            dataChannel.registerObserver(
                object : RtcDataChannelObserver {
                    override fun onStateChange() {
                        val state = dataChannel.state()
                        Napier.d("DC state: $state", tag = TAG)
                        when (state) {
                            RtcDataChannelState.OPEN -> scope.launch { handleDataChannelOpen() }
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

            // If DC is already open, start immediately.
            if (dataChannel.state() == RtcDataChannelState.OPEN) {
                scope.launch { handleDataChannelOpen() }
            }

            onSessionStarted?.invoke(sessionId)
        }

        /** Gate the stream — disable tracks. */
        fun gate() {
            gated = true
            for (sender in senders) {
                sender.setTrackEnabled(false)
            }
            onSegmentGated?.invoke(segmentIndex)
        }

        /** Ungate the stream — enable tracks. */
        fun ungate() {
            gated = false
            for (sender in senders) {
                sender.setTrackEnabled(true)
            }
            onSegmentResumed?.invoke(segmentIndex)
        }

        /** Terminate the session and notify the consumer. */
        fun terminate(reason: String? = null) {
            if (disposed) return
            disposed = true
            cancelTimers()
            gate()
            sendDC(
                buildJsonObject {
                    put("type", DCMessageType.SESSION_TERMINATE)
                    put("sessionId", sessionId)
                    put("payload", buildJsonObject { put("reason", reason ?: "") })
                },
            )
            try {
                dc?.close()
            } catch (_: Exception) {
            }
            onSessionTerminated?.invoke(sessionId)
        }

        // ─── Internal ───────────────────────────────────────────

        private fun handleDataChannelOpen() {
            if (disposed) return
            // Small delay to ensure the remote side has set up its onmessage handler.
            scope.launch {
                delay(100L.milliseconds)
                // If skipPaymentRequestWhenSessionFunded is enabled but the viewer's authorized
                // signer key is not yet known, wait for it before running the funded-skip check.
                val needsKey =
                    config.skipPaymentRequestWhenSessionFunded &&
                        !config.viewerAddress.isNullOrBlank() &&
                        config.viewerAuthorizedSignerPublicKey == null

                if (needsKey) {
                    Napier.d(
                        "⏳ Waiting up to ${VIEWER_KEY_WAIT_TIMEOUT_MS}ms for viewerAuthorizedSignerPublicKey before first payment check…",
                        tag = TAG,
                    )
                    val received = withTimeoutOrNull(VIEWER_KEY_WAIT_TIMEOUT_MS) { viewerKeyDeferred.await() }
                    if (received == null) {
                        Napier.w(
                            "⚠️ Timed out waiting for viewerAuthorizedSignerPublicKey — proceeding without it.",
                            tag = TAG,
                        )
                    } else {
                        Napier.d("✅ viewerAuthorizedSignerPublicKey ready — proceeding with payment flow", tag = TAG)
                    }
                }

                startPaymentFlow()
            }
        }

        /** Kick off the payment flow based on the configured [GatingMode]. */
        private fun startPaymentFlow() {
            if (disposed) return
            if (config.gating.mode == GatingMode.WHOLE_STREAM) {
                requestPayment()
            } else {
                ungate()
                val leadTime =
                    (config.gating.leadTime ?: config.gating.segmentDuration ?: DEFAULT_SEGMENT_DURATION_SECONDS) * 1000L
                scheduleSegmentTimer(leadTime) { requestPaymentWithGrace() }
            }
        }

        private fun handleDataChannelMessage(msgStr: String) {
            try {
                // Guard against plain-text keepalive strings (e.g. "ping") that are not JSON.
                val trimmed = msgStr.trim()
                if (!trimmed.startsWith("{")) {
                    Napier.d("[DC_PLAIN_MESSAGE_IGNORED] session=$sessionId message=$trimmed", tag = TAG)
                    return
                }

                val msg = Json.parseToJsonElement(msgStr).jsonObject
                val msgType = msg["type"]?.jsonPrimitive?.content
                Napier.d("[DC_MESSAGE_RECEIVED] session=$sessionId type=$msgType bytes=${msgStr.length}", tag = TAG)
                when (msgType) {
                    DCMessageType.SEGMENT_PAYMENT -> {
                        val payload = msg["payload"]
                        if (payload == null || payload is JsonNull) {
                            Napier.d("[SEGMENT_PAYMENT_DENIED] session=$sessionId segment=$segmentIndex", tag = TAG)
                            onPaymentRejected?.invoke("Consumer denied payment")
                            terminate("Payment denied")
                        } else {
                            val railPayment = railPaymentFromJson(payload.jsonObject)
                            Napier.d(
                                "[SEGMENT_PAYMENT_PAYLOAD_PARSED] session=$sessionId segment=$segmentIndex " +
                                    "nonce=${railPayment.nonce} railId=${railPayment.railId}",
                                tag = TAG,
                            )
                            scope.launch { handlePayment(railPayment) }
                        }
                    }

                    DCMessageType.VIEWER_VAULT_FUNDED -> {
                        Napier.d(
                            "[VIEWER_VAULT_FUNDED_RECEIVED] session=$sessionId segment=$segmentIndex gated=$gated pending=${pendingRequest != null}",
                            tag = TAG,
                        )
                        if (gated || pendingRequest != null) {
                            cancelTimers()
                            requestPayment()
                        }
                    }
                }
            } catch (e: Exception) {
                Napier.e("handleDataChannelMessage error", e, tag = TAG)
            }
        }

        private fun handleDisconnect() {
            cancelTimers()
            gate()
            onSessionTerminated?.invoke(sessionId)
        }

        private fun requestPayment() {
            scope.launch(Dispatchers.Default) {
                try {
                    val channelIdBase64 = resolveChannelIdBase64()

                    val shouldSkipPrompt = shouldSkipPaymentRequestBecauseSessionFunded()
                    Napier.d(
                        "[REQUEST_PAYMENT_SKIP_CHECK] session=$sessionId skip=$shouldSkipPrompt channelIdPresent=${channelIdBase64 != null}",
                        tag = TAG,
                    )
                    if (shouldSkipPrompt) {
                        Napier.d(
                            "💸 Skipping payment request: session vault still funded for viewer=${config.viewerAddress}",
                            tag = TAG,
                        )
                        val syntheticReceipt =
                            createSessionVaultReceipt(
                                txIdPrefix = "session-vault-funded-skip",
                                segmentIndex = segmentIndex,
                                amount = config.gating.amount,
                                asset = config.gating.asset,
                                payTo = config.gating.payTo,
                                payFrom = config.viewerAddress.orEmpty(),
                                network = config.gating.network,
                                channelId = channelIdBase64,
                            )
                        completePaidSegment(syntheticReceipt, config.gating.amount)
                        return@launch
                    }

                    val request =
                        paymentRail
                            .createPaymentRequest(
                                PaymentRailRequestParams(
                                    sessionId = sessionId,
                                    segmentIndex = segmentIndex,
                                    amount = config.gating.amount,
                                    asset = config.gating.asset,
                                    network = config.gating.network,
                                    payTo = config.gating.payTo,
                                    ttl = config.paymentTTL,
                                    meta =
                                        PaymentRequestMeta(
                                            gatingMode = config.gating.mode,
                                            enforcement = config.enforcement,
                                            segmentDuration = config.gating.segmentDuration,
                                            segmentBytes = config.gating.segmentBytes,
                                            viewerAddress = config.viewerAddress,
                                            voucherSignature = null,
                                        ),
                                ),
                            ).copy(channelId = channelIdBase64, salt = resolveSaltBase64())

                    pendingRequest = request
                    onPaymentRequested?.invoke(request)
                    Napier.d(
                        "[REQUEST_PAYMENT_SENT] session=$sessionId segment=${request.segmentIndex} nonce=${request.nonce} " +
                            "amount=${request.amount} asset=${request.asset} network=${request.network} payTo=${request.payTo} " +
                                "ttl=${request.ttl} channelId=${request.channelId} salt=${request.salt}",
                        tag = TAG,
                    )

                    sendDC(
                        buildJsonObject {
                            put("type", DCMessageType.SEGMENT_REQUEST)
                            put("sessionId", sessionId)
                            put("segmentIndex", segmentIndex)
                            put("payload", request.toJson())
                        },
                    )
                } catch (e: Throwable) {
                    Napier.e(
                        "[REQUEST_PAYMENT_FAILED] session=$sessionId segment=$segmentIndex amount=${config.gating.amount} " +
                            "asset=${config.gating.asset} network=${config.gating.network} payTo=${config.gating.payTo} error=${e.message}",
                        e,
                        tag = TAG,
                    )
                    onError?.invoke(e)
                }
            }
        }

        /**
         * Request payment with grace period — stream stays ungated while waiting.
         * First payment (segmentsPaid == 0) skips the grace timer — consent may take longer.
         */
        private fun requestPaymentWithGrace() {
            val gracePeriod = config.gracePeriod * 1000L
            if (gracePeriod <= 0) {
                gate()
                requestPayment()
                return
            }

            requestPayment()

            // First payment — no grace timer.
            if (stats.segmentsPaid == 0) return

            cancelGraceTimer()
            graceTimer =
                scope.launch {
                    delay(gracePeriod)
                    graceTimer = null
                    if (pendingRequest != null) {
                        gate()
                    }
                }
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun resolveChannelIdBase64(): String? {
            cachedChannelIdBase64?.let { return it }

            val viewer = config.viewerAddress?.takeIf { it.isNotBlank() } ?: run {
                Napier.w("[RESOLVE_CHANNEL_ID_FAILED] reason=blank_viewer session=$sessionId", tag = TAG)
                return null
            }
            val payTo = config.gating.payTo.takeIf { it.isNotBlank() } ?: run {
                Napier.w("[RESOLVE_CHANNEL_ID_FAILED] reason=blank_payTo session=$sessionId", tag = TAG)
                return null
            }
            val signerKey = config.viewerAuthorizedSignerPublicKey?.takeIf { it.isNotEmpty() } ?: run {
                Napier.w("[RESOLVE_CHANNEL_ID_FAILED] reason=blank_signer session=$sessionId viewer=$viewer payTo=$payTo", tag = TAG)
                return null
            }

            return runCatching {
                val channelId =
                    EscrowSessionVaultManagerClient
                        .initializeChannelId(
                            payerAddress = viewer,
                            payeeAddress = payTo,
                            authorizedSignerPublicKey = signerKey,
                        )
                Base64.encode(channelId)
            }.onFailure {
                Napier.e("[RESOLVE_CHANNEL_ID_FAILED] session=$sessionId viewer=$viewer payTo=$payTo", it, tag = TAG)
            }.getOrNull()?.also { cachedChannelIdBase64 = it }
        }
        @OptIn(ExperimentalEncodingApi::class)
        private fun resolveSaltBase64(): String? {
            cachedSaltBase64?.let { return it }
            return runCatching {
                Base64.encode(
                    EscrowSessionVaultManagerClient.defaultSalt
                        ?: error("defaultSalt is not configured"),
                )
            }.onFailure {
                Napier.e("[RESOLVE_SALT_FAILED] session=$sessionId", it, tag = TAG)
            }.getOrNull()?.also { cachedSaltBase64 = it }
        }

        private suspend fun shouldSkipPaymentRequestBecauseSessionFunded(): Boolean {
            if (!config.skipPaymentRequestWhenSessionFunded) return false
            val viewerAddress = config.viewerAddress?.takeIf { it.isNotBlank() } ?: return false
            val remaining =
                getRemainingSessionVaultBalanceUseCase(
                    GetRemainingSessionVaultBalanceUseCase.Params(
                        viewerAddress = viewerAddress,
                        hostAddress = config.gating.payTo,
                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                        authorizedSignerPublicKey = config.viewerAuthorizedSignerPublicKey,
                    ),
                ).getOrDefault(0L)
            return remaining > 0L
        }

        private suspend fun handlePayment(railPayment: RailPayment) {
            val request =
                pendingRequest ?: run {
                    Napier.e("[HANDLE_PAYMENT_NO_PENDING_REQUEST] session=$sessionId segment=$segmentIndex", tag = TAG)
                    onPaymentRejected?.invoke("No pending request")
                    return
                }

            onPaymentReceived?.invoke(railPayment)

            cancelGraceTimer()

            // Nonce check.
            if (railPayment.nonce != request.nonce) {
                Napier.e(
                    "[HANDLE_PAYMENT_NONCE_MISMATCH] session=$sessionId segment=$segmentIndex expected=${request.nonce} actual=${railPayment.nonce}",
                    tag = TAG,
                )
                onPaymentRejected?.invoke("Nonce mismatch")
                sendDC(
                    buildJsonObject {
                        put("type", DCMessageType.SEGMENT_REJECTED)
                        put("sessionId", sessionId)
                        put("segmentIndex", segmentIndex)
                        put("payload", buildJsonObject { put("reason", "nonce_mismatch") })
                    },
                )
                terminate("Nonce mismatch")
                return
            }

            // Replay protection.
            val isNew = nonceStore.checkAndStore(railPayment.nonce, config.paymentTTL)
            if (!isNew) {
                Napier.e("[HANDLE_PAYMENT_NONCE_REPLAY] session=$sessionId segment=$segmentIndex nonce=${railPayment.nonce}", tag = TAG)
                onPaymentRejected?.invoke("Nonce replay detected")
                sendDC(
                    buildJsonObject {
                        put("type", DCMessageType.SEGMENT_REJECTED)
                        put("sessionId", sessionId)
                        put("segmentIndex", segmentIndex)
                        put("payload", buildJsonObject { put("reason", "nonce_replay") })
                    },
                )
                return
            }

            val receipt =
                withContext(Dispatchers.Default) {
                    verifyAndSettleOrCreateFallbackReceipt(request, railPayment)
                }

            // Verify and settle via rail.
            try {
                pendingRequest = null
                completePaidSegment(receipt, request.amount)
            } catch (e: Throwable) {
                Napier.e(
                    "[HANDLE_PAYMENT_SETTLE_FAILED] session=$sessionId segment=$segmentIndex amount=${request.amount} " +
                        "asset=${request.asset} network=${request.network} payTo=${request.payTo} error=${e.message}",
                    e,
                    tag = TAG,
                )
                onError?.invoke(e)
                onPaymentRejected?.invoke(e.message ?: "Payment failed")
                terminate("Payment failed: ${e.message}")
            }
        }

        // ─── Helpers ────────────────────────────────────────────

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

        private suspend fun verifyAndSettleOrCreateFallbackReceipt(
            request: PaymentRequest,
            railPayment: RailPayment,
        ): PaymentReceipt =
            try {
                paymentRail.verifyAndSettle(railPayment, request)
            } catch (e: Throwable) {
                Napier.e(
                    "[HANDLE_PAYMENT_SETTLE_FALLBACK] session=$sessionId segment=$segmentIndex amount=${request.amount} " +
                        "asset=${request.asset} network=${request.network} payTo=${request.payTo} error=${e.message}",
                    e,
                    tag = TAG,
                )
                createSessionVaultReceipt(
                    txIdPrefix = "session-vault-fallback",
                    segmentIndex = request.segmentIndex,
                    amount = request.amount,
                    asset = request.asset,
                    payTo = request.payTo,
                    payFrom = config.viewerAddress.orEmpty(),
                    network = request.network,
                    sessionId = request.sessionId,
                )
            }

        private fun completePaidSegment(
            receipt: PaymentReceipt,
            amount: String,
        ) {
            stats.segmentsPaid++
            stats.totalAmountReceived =
                (BigInteger.parseString(stats.totalAmountReceived) + BigInteger.parseString(amount)).toString()

            onPaymentSettled?.invoke(receipt)

            if (gated) ungate()

            sendAcceptedReceipt(receipt)

            stats.segmentsDelivered++
            onSegmentStarted?.invoke(segmentIndex)

            scheduleNextSegmentIfNeeded()
        }

        private fun sendAcceptedReceipt(receipt: PaymentReceipt) {
            // Ensure the receipt always carries the creator-derived channel id for the viewer.
            val receiptWithChannelId =
                if (receipt.channelId != null) receipt else receipt.copy(channelId = resolveChannelIdBase64())
            sendDC(
                buildJsonObject {
                    put("type", DCMessageType.SEGMENT_ACCEPTED)
                    put("sessionId", sessionId)
                    put("segmentIndex", segmentIndex)
                    put("payload", receiptWithChannelId.toJson())
                },
            )
        }

        private fun scheduleNextSegmentIfNeeded() {
            if (config.gating.mode == GatingMode.WHOLE_STREAM) return

            segmentIndex++
            val duration = segmentDurationMs
            Napier.d("⏱️ Segment timer scheduled: session=$sessionId nextSegment=$segmentIndex in=${duration}ms", tag = TAG)
            scheduleSegmentTimer(duration) {
                Napier.d("⏱️ Segment timer tick: session=$sessionId segment=$segmentIndex", tag = TAG)
                requestPaymentWithGrace()
            }
        }

        private fun createSessionVaultReceipt(
            txIdPrefix: String,
            segmentIndex: Int,
            amount: String,
            asset: String,
            payTo: String,
            payFrom: String,
            network: String,
            sessionId: String = this.sessionId,
            channelId: String? = null,
        ): PaymentReceipt =
            PaymentReceipt(
                txId = "$txIdPrefix-${mppNowMs()}",
                sessionId = sessionId,
                segmentIndex = segmentIndex,
                amount = amount,
                asset = asset,
                payTo = payTo,
                payFrom = payFrom,
                feePayer = null,
                facilitator = null,
                network = network,
                timestamp = mppNowMs(),
                channelId = channelId,
            )

        private fun scheduleSegmentTimer(
            delayMs: Long,
            action: () -> Unit,
        ) {
            cancelSegmentTimer()
            segmentTimer =
                scope.launch {
                    delay(delayMs)
                    action()
                }
        }

        private fun cancelSegmentTimer() {
            segmentTimer?.cancel()
            segmentTimer = null
        }

        private fun cancelGraceTimer() {
            graceTimer?.cancel()
            graceTimer = null
        }

        private fun cancelTimers() {
            cancelSegmentTimer()
            cancelGraceTimer()
        }
    }
