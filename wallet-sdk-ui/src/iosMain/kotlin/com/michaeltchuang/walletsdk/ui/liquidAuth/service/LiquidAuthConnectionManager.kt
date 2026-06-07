package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.IosSessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.usecases.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.model.displayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

var activeIOSBroadcastConnectionManager: IOSLiquidAuthConnectionManager? = null
var iosBroadcastStartHandler: ((origin: String, requestId: String) -> Unit)? = null
var iosBroadcastStopHandler: (() -> Unit)? = null
var iosBroadcastSendMessageHandler: ((message: String) -> Unit)? = null
var iosBroadcastIsConnectedHandler: (() -> Boolean)? = null
var iosBroadcastDetectConnectionTypeHandler: (() -> String)? = null

private const val TAG = "IOSLiquidAuthCM"
private const val CONNECTION_TYPE_POLL_INTERVAL_MS = 1000L

/** Polling interval for the host-side on-chain balance during paid streaming (ms). */
private const val HOST_BALANCE_POLL_INTERVAL_MS = 5_000L

class IOSLiquidAuthConnectionManager : LiquidAuthConnectionManager {

    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    override val connectionType: StateFlow<IceConnectionType> = _connectionType

    private var viewModel: LiquidAuthOfferViewModel? = null
    private var activeRequestId: String? = null
    private var connectionTypePollingJob: Job? = null
    private var blockConsumptionJob: Job? = null

    private var activeViewerAddressForVault: String? = null
    private var activeViewerAuthorizedSignerKey: ByteArray? = null
    private var activePaymentSessionId: String? = null
    private var activePaymentRecipient: String? = null
    private var activePaymentAmount: String? = null
    private var activeCreatorVoucherClaimSnapshot: CreatorVoucherClaimSnapshot? = null

    private val getRemainingBalanceUseCase = GetRemainingSessionVaultBalanceUseCase(
        IosSessionVaultBalanceRepository(),
    )
    private val scope = CoroutineScope(Dispatchers.Default)

    data class CreatorVoucherClaimSnapshot(
        val sessionId: String,
        val viewerAddress: String,
        val viewerPublicKeyBase64: String,
        val signatureBase64: String,
        val totalAmountClaimedMicroUsdc: Long,
    )

    override fun initialize(viewModel: LiquidAuthOfferViewModel) {
        this.viewModel = viewModel
        activeIOSBroadcastConnectionManager = this
        println("$TAG: initialize() viewModel=$viewModel")
    }

    override fun startListening(
        origin: String,
        requestId: String,
    ) {
        val handler = iosBroadcastStartHandler
        if (handler == null) {
            println("$TAG: ⚠️ iosBroadcastStartHandler not set!")
            return
        }
        if (viewModel == null) {
            println("$TAG: ❌ viewModel is null — call initialize() first")
            return
        }
        if (isConnected() && activeRequestId == requestId) {
            println("$TAG: already connected for requestId=$requestId, skipping")
            return
        }
        if (activeRequestId != null && activeRequestId != requestId) {
            println("$TAG: 🔁 requestId changed ($activeRequestId → $requestId), restarting")
            stopListening()
        }
        println("$TAG: startListening() origin=$origin requestId=$requestId")
        activeRequestId = requestId
        handler(origin, requestId)
    }

    override fun stopListening() {
        println("$TAG: stopListening() (activeRequestId=$activeRequestId)")
        stopConnectionTypePolling()
        stopBlockConsumption()
        iosBroadcastStopHandler?.invoke()
        activeRequestId = null
        activeViewerAddressForVault = null
        activeViewerAuthorizedSignerKey = null
        activePaymentSessionId = null
        activePaymentRecipient = null
        activePaymentAmount = null
        activeCreatorVoucherClaimSnapshot = null
        _connectionType.value = IceConnectionType.UNKNOWN
    }

    override fun sendMessage(message: String) {
        val handler = iosBroadcastSendMessageHandler
        if (handler == null) {
            println("$TAG: sendMessage skipped — iosBroadcastSendMessageHandler not set")
            return
        }
        handler(message)
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun sendVideoFrame(
        frameId: String,
        timestamp: Long,
        frameData: ByteArray,
        width: Int,
        height: Int,
        format: String,
    ) {
        if (!isConnected()) {
            println("$TAG: sendVideoFrame skipped — not connected")
            return
        }
        try {
            val base64Data = Base64.encode(frameData)
            val hostAddress = activePaymentRecipient.orEmpty()
            val sessionId = activePaymentSessionId.orEmpty()
            val hostJsonField = if (hostAddress.isNotBlank()) ""","hostAddress":"$hostAddress"""" else ""
            val sessionJsonField = if (sessionId.isNotBlank()) ""","sessionId":"$sessionId"""" else ""
            val jsonMessage =
                """{"reference":"liquid:video:frame","id":"$frameId","timestamp":$timestamp,"format":"$format","data":"$base64Data","width":$width,"height":$height$hostJsonField$sessionJsonField}"""
            println("$TAG: 🎥 sendVideoFrame ${width}x$height (${frameData.size} bytes)")
            sendMessage(jsonMessage)
        } catch (e: Exception) {
            println("$TAG: ❌ sendVideoFrame failed: $e")
        }
    }

    override fun isConnected(): Boolean = iosBroadcastIsConnectedHandler?.invoke() ?: false

    /**
     * Serialize the payment request as a JSON message and send it to the viewer via the
     * data channel.  The viewer's [IOSLiquidStreamViewerConnectionManager] will receive it
     * and show a consent/deposit dialog.
     *
     * Unlike Android (which waits for [onPaymentSettled] from [LiquidStreamCreator]),
     * the iOS host has no PaywalledRTCServer, so we:
     *  1. Start streaming immediately (the balance card enforces gating once funds are tracked)
     *  2. Start the on-chain balance polling loop immediately (same as Android line 307/317)
     */
    override fun sendPaymentRequest(paymentRequest: PaymentRequest) {
        println(
            "$TAG: 💰 sendPaymentRequest — session=${paymentRequest.id} " +
                "amount=${paymentRequest.amount} payTo=${paymentRequest.payTo}",
        )

        // Lock in payment session fields (do not overwrite if already set).
        activePaymentSessionId = activePaymentSessionId ?: paymentRequest.id
        activePaymentRecipient = paymentRequest.payTo
        activePaymentAmount = paymentRequest.amount

        // Build and send the JSON payment request to the viewer (iOS viewers and any
        // viewer listening on the main data channel will receive it).
        val json = buildPaymentRequestJson(paymentRequest)
        println("$TAG: 📤 sending payment request to viewer (${json.length} chars)")
        sendMessage(json)

        // Transition the host to streaming state immediately.
        // (On Android this happens via LiquidStreamCreator.onPaymentSettled; on iOS we have
        // no PaywalledRTCServer so we start optimistically and track balance on-chain.)
        viewModel?.startVideoStreaming()

        // Start on-chain balance polling now — mirrors Android host's line 307.
        // If the viewer has a pre-existing deposit the balance will show on the first tick;
        // if not it shows 0.0 USDC (accurate) rather than N/A (null).
        startBlockConsumption(activePaymentSessionId ?: paymentRequest.id)
    }

    /**
     * Start polling the on-chain session-vault balance for the active viewer.
     * Calls [LiquidAuthOfferViewModel.consumeBlock] on each poll tick so the host
     * UI reflects the live remaining balance.
     */
    override fun startBlockConsumption(sessionId: String) {
        if (blockConsumptionJob?.isActive == true) {
            println("$TAG: startBlockConsumption — already running (session=$sessionId)")
            return
        }
        val targetSession = activePaymentSessionId ?: sessionId
        println("$TAG: 🔄 startBlockConsumption session=$targetSession")

        var hostTick = 0
        blockConsumptionJob = scope.launch {
            while (isActive) {
                hostTick++
                val viewerAddr = activeViewerAddressForVault
                val hostAddr = activePaymentRecipient

                val signerKey = activeViewerAuthorizedSignerKey
                if (!viewerAddr.isNullOrBlank() && !hostAddr.isNullOrBlank() && signerKey != null) {
                    runCatching {
                        val remaining = getRemainingBalanceUseCase(
                            GetRemainingSessionVaultBalanceUseCase.Params(
                                viewerAddress = viewerAddr,
                                hostAddress = hostAddr,
                                appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                authorizedSignerPublicKey = signerKey,
                            ),
                        ).getOrDefault(0L)

                        println(
                            "$TAG: 💰 HOST_BALANCE_TICK #$hostTick → ${remaining / 1_000_000.0} USDC " +
                                "viewer=$viewerAddr host=$hostAddr session=$activePaymentSessionId",
                        )
                        viewModel?.consumeBlock(
                            onChainRemainingMicroUsdc = remaining,
                            progressBarBalanceMicroUsdc = remaining,
                        )
                    }.onFailure { e ->
                        println("$TAG: ❌ HOST_BALANCE_ERR tick=$hostTick: $e viewer=$viewerAddr host=$hostAddr")
                    }
                } else {
                    // ── Log clearly which prerequisite is still missing ────────────────
                    // The authorizedSignerPublicKey is REQUIRED for correct channelId
                    // derivation.  Without it the wrong vault would be queried.
                    val missingWhat = when {
                        viewerAddr.isNullOrBlank() -> "viewer-hello-message"
                        signerKey == null -> "viewer-signer-key (viewerPublicKey from hello)"
                        else -> "host-address"
                    }
                    println(
                        "$TAG: ⏳ HOST_BALANCE_WAITING tick=$hostTick — " +
                            "viewer='$viewerAddr' host='$hostAddr' signerKey=${signerKey != null} " +
                            "session='$activePaymentSessionId' NEED: $missingWhat",
                    )
                }

                delay(HOST_BALANCE_POLL_INTERVAL_MS)
            }
        }
    }

    override fun stopBlockConsumption() {
        println("$TAG: ⏹ stopBlockConsumption")
        blockConsumptionJob?.cancel()
        blockConsumptionJob = null
    }

    @Suppress("unused")
    fun notifyClientConnected(requestId: String) {
        println("$TAG: ✅ notifyClientConnected requestId=$requestId")
        viewModel?.onClientConnected(requestId)
        startConnectionTypePolling()
    }

    @Suppress("unused")
    fun notifyClientDisconnected() {
        println("$TAG: notifyClientDisconnected")
        stopConnectionTypePolling()
        stopBlockConsumption()
        viewModel?.onClientDisconnected()
    }

    @Suppress("unused")
    fun notifyMessageReceived(message: String) {
        val ref = message.jsonOptString("reference") ?: "(no-ref)"
        println(
            "$TAG: 📩 HOST_MSG_RECV ref=$ref len=${message.length} " +
                "viewer='$activeViewerAddressForVault' host='$activePaymentRecipient' " +
                "session='$activePaymentSessionId' preview=${message.take(160)}",
        )
        tryCaptureViewerAddressFromMessage(message)
        val requestId = activeRequestId
        if (requestId != null && isConnected()) {
            viewModel?.onClientConnected(requestId)
        }
    }

    @Suppress("unused")
    fun notifyConnectionTypeChanged(typeString: String) {
        val type = parseConnectionType(typeString)
        if (_connectionType.value != type) {
            _connectionType.value = type
            println("$TAG: 🌐 connection type → ${type.displayName()}")
            viewModel?.onConnectionTypeChanged(type)
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /** Builds the `liquid:payment:request` JSON that the viewer expects. */
    private fun buildPaymentRequestJson(req: PaymentRequest): String {
        val meta = req.meta
        val metaJson = if (meta != null) {
            ""","meta":{"gatingMode":"${meta.gatingMode}","enforcement":"${meta.enforcement}","segmentDuration":${meta.segmentDuration}}"""
        } else {
            ""
        }
        return """{"reference":"liquid:payment:request","id":"${req.id}","amount":"${req.amount}","asset":"${req.asset}","network":"${req.network}","payTo":"${req.payTo}","ttl":${req.ttl},"nonce":"${req.nonce}"$metaJson}"""
    }

    private fun tryCaptureViewerAddressFromMessage(msg: String) {
        runCatching {
            val voucherRef = msg.jsonOptString("reference") ?: return@runCatching

            if (voucherRef == "liquid:viewer:hello") {
                val helloViewer = msg.jsonOptString("viewer")

                // ── Always record the viewer address, even if the public key is absent ───
                if (!helloViewer.isNullOrBlank() && helloViewer != activeViewerAddressForVault) {
                    activeViewerAddressForVault = helloViewer
                    println("$TAG: [VIEWER_HELLO_ADDR] viewer=$helloViewer")
                }

                // ── Capture the authorized-signer public key ───────────────────────────
                // The key is REQUIRED for correct channelId derivation in the session vault.
                // Without it, getRemainingBalance would use the wrong channelId and return 0.
                val helloPublicKeyBase64 = msg.jsonOptString("viewerPublicKey")
                val signerKey = if (helloPublicKeyBase64 != null) decodeBase64OrNull(helloPublicKeyBase64) else null
                if (signerKey != null) {
                    activeViewerAuthorizedSignerKey = signerKey
                    println(
                        "$TAG: [VIEWER_HELLO_KEY] viewer=$helloViewer " +
                            "keyLen=${signerKey.size} session=$activePaymentSessionId",
                    )
                } else {
                    println(
                        "$TAG: ⚠️ [VIEWER_HELLO_NO_KEY] viewer=$helloViewer — " +
                            "viewerPublicKey absent or invalid. Balance polling will wait until " +
                            "a hello with key OR a payment voucher arrives.",
                    )
                }

                // ── Start balance polling only when BOTH address AND key are ready ─────
                // The polling loop passes authorizedSignerPublicKey directly to the channel-id
                // derivation. If the key is wrong or missing the vault won't be found on-chain.
                if (!helloViewer.isNullOrBlank() && activeViewerAuthorizedSignerKey != null) {
                    val sessionForPoll = activePaymentSessionId ?: ""
                    if (blockConsumptionJob?.isActive != true) {
                        println("$TAG: 🔄 [VIEWER_HELLO] starting balance polling viewer=$helloViewer session=$sessionForPoll")
                        startBlockConsumption(sessionForPoll)
                    } else {
                        println("$TAG: 🔄 [VIEWER_HELLO] polling already running — viewer=$helloViewer key updated")
                    }
                }
            }

            if (voucherRef == "liquid:payment:voucher") {
                val signature = msg.jsonOptString("signature")
                val claimedAmount = msg.jsonOptLong("totalAmountClaimedMicroUsdc")
                val voucherSessionId = msg.jsonOptString("id")
                val voucherViewer = msg.jsonOptString("viewer")
                val voucherViewerPublicKey = msg.jsonOptString("viewerPublicKey")

                if (signature == null || claimedAmount == null || voucherSessionId == null ||
                    voucherViewer == null || voucherViewerPublicKey == null
                ) {
                    println(
                        "$TAG: [VOUCHER_SKIP] reason=invalid_payload " +
                            "session=${msg.jsonOptString("id")} claimedAmount=$claimedAmount",
                    )
                } else {
                    val activeSession = activePaymentSessionId
                    if (activeSession != null && voucherSessionId != activeSession) {
                        println(
                            "$TAG: [VOUCHER_SKIP] reason=session_mismatch " +
                                "voucherSession=$voucherSessionId activeSession=$activeSession",
                        )
                    } else {
                        val previousClaimedAmount =
                            activeCreatorVoucherClaimSnapshot?.totalAmountClaimedMicroUsdc
                        if (previousClaimedAmount != null && claimedAmount < previousClaimedAmount) {
                            println(
                                "$TAG: [VOUCHER_STALE_SKIP] session=$voucherSessionId " +
                                    "claimed=$claimedAmount previous=$previousClaimedAmount",
                            )
                        } else {
                            activeCreatorVoucherClaimSnapshot =
                                CreatorVoucherClaimSnapshot(
                                    sessionId = voucherSessionId,
                                    viewerAddress = voucherViewer,
                                    viewerPublicKeyBase64 = voucherViewerPublicKey,
                                    signatureBase64 = signature,
                                    totalAmountClaimedMicroUsdc = claimedAmount,
                                )
                            // Capture viewer address from voucher (authoritative).
                            if (voucherViewer != activeViewerAddressForVault) {
                                activeViewerAddressForVault = voucherViewer
                                println("$TAG: [VOUCHER_VIEWER_ADDR_UPDATE] viewer=$voucherViewer")
                            }
                            // ── Capture authorized signer key from voucher ─────────────────
                            // The voucher always carries viewerPublicKey.  If the hello arrived
                            // without a key (e.g. viewer's getPublicKeyForAlgorandWallet returned
                            // null), the signer key will be null at this point.  Setting it here
                            // means the NEXT balance-poll tick will use the correct channelId.
                            if (activeViewerAuthorizedSignerKey == null) {
                                val voucherSignerKey = decodeBase64OrNull(voucherViewerPublicKey)
                                if (voucherSignerKey != null) {
                                    activeViewerAuthorizedSignerKey = voucherSignerKey
                                    println(
                                        "$TAG: [VOUCHER_SIGNER_KEY_CAPTURED] viewer=$voucherViewer " +
                                            "keyLen=${voucherSignerKey.size}",
                                    )
                                }
                            }
                            println(
                                "$TAG: [VOUCHER_CAPTURED] session=$voucherSessionId " +
                                    "sigLen=${signature.length} claimedMicroUsdc=$claimedAmount " +
                                    "viewer=$voucherViewer signerKeySet=${activeViewerAuthorizedSignerKey != null}",
                            )
                            // Trigger / ensure block consumption is running.
                            startBlockConsumption(voucherSessionId)
                        }
                    }
                }
            }

            val candidate = msg.jsonOptString("address")
            if (candidate != null && candidate != activeViewerAddressForVault) {
                activeViewerAddressForVault = candidate
                println("$TAG: 🔑 viewer address captured from message: $candidate")
            }
        }.onFailure { e ->
            println("$TAG: ⚠️ tryCaptureViewerAddressFromMessage error: $e")
        }
    }

    private fun startConnectionTypePolling() {
        println("$TAG: 🔄 starting connection-type polling")
        connectionTypePollingJob?.cancel()
        connectionTypePollingJob =
            CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    val detectHandler = iosBroadcastDetectConnectionTypeHandler
                    if (detectHandler != null) {
                        notifyConnectionTypeChanged(detectHandler())
                    }
                    delay(CONNECTION_TYPE_POLL_INTERVAL_MS)
                }
            }
    }

    private fun stopConnectionTypePolling() {
        connectionTypePollingJob?.cancel()
        connectionTypePollingJob = null
        _connectionType.value = IceConnectionType.UNKNOWN
    }

    private fun parseConnectionType(typeString: String): IceConnectionType =
        when (typeString.trim().lowercase()) {
            "local" -> IceConnectionType.LOCAL
            "stun" -> IceConnectionType.STUN
            "relay" -> IceConnectionType.RELAY
            "failed" -> IceConnectionType.FAILED
            else -> IceConnectionType.UNKNOWN
        }

    private fun String.jsonOptString(key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }

    private fun String.jsonOptLong(key: String): Long? =
        Regex(""""$key"\s*:\s*(-?\d+)""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64OrNull(value: String): ByteArray? =
        runCatching {
            // Unescape JSON-encoded forward slashes (\/ → /) BEFORE base64 decoding.
            // jsonOptString() uses a regex extractor that does not unescape JSON sequences,
            // so a Falcon24/HD key encoded with base64 standard alphabet (containing '/')
            // arrives here with literal '\/' pairs that are invalid base64 characters.
            val normalised = value
                .replace("\\/", "/")
                .replace('-', '+')
                .replace('_', '/')
                .trimEnd('=')
            val padded = normalised + "=".repeat((4 - normalised.length % 4) % 4)
            Base64.decode(padded)
        }.getOrNull()
}

actual fun createLiquidAuthConnectionManager(platformContext: Any): LiquidAuthConnectionManager =
    IOSLiquidAuthConnectionManager()
