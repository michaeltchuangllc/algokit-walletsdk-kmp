package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.MppServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.core.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.data.repository.IosSessionVaultBalanceRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecases.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import com.michaeltchuang.walletsdk.ui.liquidStream.IOSLiquidStreamCreator
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

// ── Swift-bridged global handlers ─────────────────────────────────────────────

var activeIOSBroadcastConnectionManager: IOSLiquidAuthConnectionManager? = null
var iosBroadcastStartHandler: ((origin: String, requestId: String) -> Unit)? = null
var iosBroadcastStopHandler: (() -> Unit)? = null

/**
 * Sends a message on the GENERAL "liquid" DataChannel.
 * Used for: video frames, keep-alive pings, session-level messages.
 * Set once in Swift via `registerBroadcastHandlers`.
 */
var iosBroadcastSendMessageHandler: ((message: String) -> Unit)? = null

var iosBroadcastPaymentDCSendMessageHandler: ((message: String) -> Unit)? = null

var iosBroadcastIsConnectedHandler: (() -> Boolean)? = null
var iosBroadcastDetectConnectionTypeHandler: (() -> String)? = null

var iosBroadcastClaimVoucherHandler: ((
    sessionId: String,
    viewerAddress: String,
    hostAddress: String,
    claimedMicroUsdc: Long,
    signatureBase64: String,
    viewerPublicKeyBase64: String,
) -> Unit)? = null


var iosBroadcastMppSecretKey: String = "ios-host-mpp-secret"

var iosBroadcastUsePaywalledRTCServer: Boolean = true

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

    // ── IOSLiquidStreamCreator (host payment channel) ─────────────────────────

    private var streamCreator: IOSLiquidStreamCreator? = null

    private var isVideoGated = false

    /** Stored gating config for the current server session (used to rebuild [ServerConfig] on viewer-hello). */
    private var activeGatingConfig: GatingConfig? = null

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
            println("$TAG: iosBroadcastStartHandler not set!")
            return
        }
        if (viewModel == null) {
            println("$TAG: viewModel is null — call initialize() first")
            return
        }
        if (isConnected() && activeRequestId == requestId) {
            println("$TAG: already connected for requestId=$requestId, skipping")
            return
        }
        if (activeRequestId != null && activeRequestId != requestId) {
            println("$TAG: requestId changed ($activeRequestId -> $requestId), restarting")
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
        streamCreator?.terminate("stopListening")
        streamCreator = null
        activeGatingConfig = null
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
            sendMessage(jsonMessage)
        } catch (e: Exception) {
            println("$TAG: sendVideoFrame failed: $e")
        }
    }

    override fun isConnected(): Boolean = iosBroadcastIsConnectedHandler?.invoke() ?: false

    override fun sendPaymentRequest(paymentRequest: PaymentRequest) {
        println(
            "$TAG: 💰 sendPaymentRequest — session=${paymentRequest.id} " +
                "amount=${paymentRequest.amount} payTo=${paymentRequest.payTo}",
        )

        // Don't pre-seed activePaymentSessionId from paymentRequest.id here —
        // when using PaywalledRTCServer, the actual session ID comes from creator.sessionId
        // (set after creator.start()). For the legacy path we keep it from paymentRequest.id.
        activePaymentRecipient = paymentRequest.payTo
        activePaymentAmount = paymentRequest.amount

        if (iosBroadcastUsePaywalledRTCServer) {
            startPaywalledRTCServer(paymentRequest)
        } else {
            // ── Legacy path (iOS-only format) ─────────────────────────────────
            activePaymentSessionId = activePaymentSessionId ?: paymentRequest.id
            val json = buildPaymentRequestJson(paymentRequest)
            println("$TAG: sending legacy payment request (${json.length} chars)")
            sendMessage(json)
            viewModel?.startVideoStreaming()
            startBlockConsumption(activePaymentSessionId ?: paymentRequest.id)
        }
    }

    private fun startPaywalledRTCServer(paymentRequest: PaymentRequest) {
        if (streamCreator != null) {
            println("$TAG: IOSLiquidStreamCreator already active — skipping duplicate")
            return
        }

        val networkString = when {
            paymentRequest.network.contains("testnet", ignoreCase = true) -> MppNetworks.ALGORAND_TESTNET
            paymentRequest.network.contains("mainnet", ignoreCase = true) -> MppNetworks.ALGORAND_MAINNET
            else -> MppNetworks.ALGORAND_TESTNET
        }

        val mppServerConfig = MppServerConfig(
            network = networkString,
            recipient = paymentRequest.payTo,
            secretKey = iosBroadcastMppSecretKey,
        )

        val gatingConfig = GatingConfig(
            mode = paymentRequest.meta.gatingMode,
            amount = paymentRequest.amount,
            asset = paymentRequest.asset,
            network = paymentRequest.network,
            payTo = paymentRequest.payTo,
            segmentDuration = paymentRequest.meta.segmentDuration,
        )

        val serverConfig = ServerConfig(
            sessionId = paymentRequest.sessionId,
            gating = gatingConfig,
            enforcement = paymentRequest.meta.enforcement,
            viewerAddress = activeViewerAddressForVault,
            viewerAuthorizedSignerPublicKey = activeViewerAuthorizedSignerKey,
            skipPaymentRequestWhenSessionFunded = true,
        )

        activeGatingConfig = gatingConfig

        val creator = IOSLiquidStreamCreator(
            mppServerConfig = mppServerConfig,
            serverConfig = serverConfig,
        )

        creator.onSessionStarted = { sid ->
            println("$TAG: [Creator] onSessionStarted session=$sid")
        }
        creator.onPaymentRequested = { req ->
            println("$TAG: [Creator] onPaymentRequested segment=${req.segmentIndex} amount=${req.amount}")
        }
        creator.onPaymentSettled = { receipt ->
            println("$TAG: [Creator] onPaymentSettled session=${receipt.sessionId} segment=${receipt.segmentIndex}")
            // Sync activePaymentSessionId to the PaywalledRTCServer's actual session.
            activePaymentSessionId = receipt.sessionId
            viewModel?.startVideoStreaming()
            startBlockConsumption(receipt.sessionId)
        }
        creator.onPaymentRejected = { reason ->
            println("$TAG: [Creator] onPaymentRejected reason=$reason")
        }
        creator.onSegmentStarted = { idx -> println("$TAG: [Creator] onSegmentStarted idx=$idx") }
        creator.onSegmentGated = { idx ->
            isVideoGated = true
            println("$TAG: [Creator] onSegmentGated idx=$idx — frame sending paused")
        }
        creator.onSegmentResumed = { idx ->
            isVideoGated = false
            println("$TAG: [Creator] onSegmentResumed idx=$idx — frame sending resumed")
        }
        creator.onSessionTerminated = { sid -> println("$TAG: [Creator] onSessionTerminated session=$sid") }
        creator.onError = { err -> println("$TAG: [Creator] error: $err") }

        streamCreator = creator
        println("$TAG: IOSLiquidStreamCreator created — calling start()")
        creator.start()
        // Sync activePaymentSessionId to the PaywalledRTCServer's session ID right away.
        activePaymentSessionId = creator.sessionId
        println("$TAG: Creator session=${creator.sessionId} (synced to activePaymentSessionId)")

        // If DC is already open (viewer connected before sendPaymentRequest), open immediately.
        if (isConnected()) {
            creator.notifyViewerConnected()
        }
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
        println("$TAG: startBlockConsumption session=$targetSession")

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
                            "$TAG: HOST_BALANCE_TICK #$hostTick -> ${remaining / 1_000_000.0} USDC " +
                                "viewer=$viewerAddr host=$hostAddr session=$activePaymentSessionId",
                        )
                        viewModel?.consumeBlock(
                            onChainRemainingMicroUsdc = remaining,
                            progressBarBalanceMicroUsdc = remaining,
                        )
                    }.onFailure { e ->
                        println("$TAG: HOST_BALANCE_ERR tick=$hostTick: $e viewer=$viewerAddr host=$hostAddr")
                    }
                } else {
                    val missingWhat = when {
                        viewerAddr.isNullOrBlank() -> "viewer-hello-message"
                        signerKey == null -> "viewer-signer-key"
                        else -> "host-address"
                    }
                    println(
                        "$TAG: HOST_BALANCE_WAITING tick=$hostTick — " +
                            "viewer='$viewerAddr' host='$hostAddr' signerKey=${signerKey != null} " +
                            "NEED: $missingWhat",
                    )
                }

                delay(HOST_BALANCE_POLL_INTERVAL_MS)
            }
        }
    }

    override fun stopBlockConsumption() {
        println("$TAG: stopBlockConsumption")
        blockConsumptionJob?.cancel()
        blockConsumptionJob = null
    }

    @Suppress("unused")
    fun notifyClientConnected(requestId: String) {
        println("$TAG: notifyClientConnected requestId=$requestId")
        viewModel?.onClientConnected(requestId)
        startConnectionTypePolling()
        // Open the host DC so IOSLiquidStreamCreator (if already started) begins the handshake.
        streamCreator?.notifyViewerConnected()
    }

    @Suppress("unused")
    fun notifyClientDisconnected() {
        println("$TAG: notifyClientDisconnected")
        stopConnectionTypePolling()
        stopBlockConsumption()
        streamCreator?.notifyViewerDisconnected()
        streamCreator = null
        viewModel?.onClientDisconnected()
    }

    @Suppress("unused")
    fun notifyMessageReceived(message: String) {
        val ref = message.jsonOptString("reference") ?: "(no-ref)"
        val msgType = message.jsonOptString("type")
        println(
            "$TAG: HOST_MSG_RECV ref=$ref type=$msgType len=${message.length} " +
                "viewer='$activeViewerAddressForVault' host='$activePaymentRecipient' " +
                "session='$activePaymentSessionId' preview=${message.take(160)}",
        )

        // Always capture viewer address / signer key for balance polling.
        tryCaptureViewerAddressFromMessage(message)

        // Route `"type"`-keyed messages (PaywalledRTCClient protocol) to IOSLiquidStreamCreator.
        if (msgType != null && streamCreator != null) {
            println("$TAG: routing type-keyed DC msg to IOSLiquidStreamCreator (type=$msgType)")
            streamCreator?.notifyMessageReceived(message)
            return
        }

        // Only call onClientConnected for connection-establishment messages, NOT for protocol
        // messages that have a `reference` field (like liquid:payment:voucher, liquid:viewer:hello).
        // Calling it for every voucher causes repeated spurious ViewModel callbacks.
        val hasReference = message.jsonOptString("reference") != null
        if (!hasReference) {
            val requestId = activeRequestId
            if (requestId != null && isConnected()) {
                viewModel?.onClientConnected(requestId)
            }
        }
    }

    @Suppress("unused")
    fun notifyConnectionTypeChanged(typeString: String) {
        val type = parseConnectionType(typeString)
        if (_connectionType.value != type) {
            _connectionType.value = type
            println("$TAG: connection type -> ${type.displayName()}")
            viewModel?.onConnectionTypeChanged(type)
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /** Builds the legacy `liquid:payment:request` JSON (used when [iosBroadcastUsePaywalledRTCServer] is false). */
    private fun buildPaymentRequestJson(req: PaymentRequest): String {
        val meta = req.meta
        val metaJson =
            ""","meta":{"gatingMode":"${meta.gatingMode}","enforcement":"${meta.enforcement}","segmentDuration":${meta.segmentDuration}}"""
        return """{"reference":"liquid:payment:request","id":"${req.id}","amount":"${req.amount}","asset":"${req.asset}","network":"${req.network}","payTo":"${req.payTo}","ttl":${req.ttl},"nonce":"${req.nonce}"$metaJson}"""
    }

    private fun tryCaptureViewerAddressFromMessage(msg: String) {
        runCatching {
            val voucherRef = msg.jsonOptString("reference") ?: return@runCatching

            if (voucherRef == "liquid:viewer:hello") {
                val helloViewer = msg.jsonOptString("viewer")

                if (!helloViewer.isNullOrBlank() && helloViewer != activeViewerAddressForVault) {
                    activeViewerAddressForVault = helloViewer
                    println("$TAG: [VIEWER_HELLO_ADDR] viewer=$helloViewer")
                }

                val helloPublicKeyBase64 = msg.jsonOptString("viewerPublicKey")
                val signerKey = if (helloPublicKeyBase64 != null) decodeBase64OrNull(helloPublicKeyBase64) else null
                if (signerKey != null) {
                    activeViewerAuthorizedSignerKey = signerKey
                    println(
                        "$TAG: [VIEWER_HELLO_KEY] viewer=$helloViewer " +
                            "keyLen=${signerKey.size} session=$activePaymentSessionId",
                    )
                    // Update the server's config with the viewer's key so skipPaymentRequestWhenSessionFunded works.
                    val currentGating = activeGatingConfig ?: GatingConfig(
                        mode = GatingMode.PARTIAL_TIME,
                        amount = activePaymentAmount ?: "0",
                        asset = "USDC",
                        network = MppNetworks.ALGORAND_TESTNET,
                        payTo = activePaymentRecipient ?: "",
                    )
                    streamCreator?.updateConfig(
                        ServerConfig(
                            sessionId = activePaymentSessionId,
                            gating = currentGating,
                            viewerAddress = helloViewer,
                            viewerAuthorizedSignerPublicKey = signerKey,
                            skipPaymentRequestWhenSessionFunded = true,
                        ),
                    )
                } else {
                    println(
                        "$TAG: [VIEWER_HELLO_NO_KEY] viewer=$helloViewer — " +
                            "viewerPublicKey absent. Balance polling will wait.",
                    )
                }

                if (!helloViewer.isNullOrBlank() && activeViewerAuthorizedSignerKey != null) {
                    val sessionForPoll = activePaymentSessionId ?: ""
                    if (blockConsumptionJob?.isActive != true) {
                        println("$TAG: [VIEWER_HELLO] starting balance polling viewer=$helloViewer session=$sessionForPoll")
                        startBlockConsumption(sessionForPoll)
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
                            if (voucherViewer != activeViewerAddressForVault) {
                                activeViewerAddressForVault = voucherViewer
                                println("$TAG: [VOUCHER_VIEWER_ADDR_UPDATE] viewer=$voucherViewer")
                            }
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
                                    "sigLen=${signature.length} claimedMicroUsdc=$claimedAmount",
                            )
                            startBlockConsumption(voucherSessionId)

                            // Trigger on-chain settlement (update lastSettled in session vault).
                            // Swift must set iosBroadcastClaimVoucherHandler to call
                            // MppPayments.claimVoucherFromViewer() with the host wallet signer.
                            val hostAddr = activePaymentRecipient
                            val claimHandler = iosBroadcastClaimVoucherHandler
                            if (hostAddr != null && claimHandler != null) {
                                println(
                                    "$TAG: [VOUCHER_CLAIM_TRIGGER] session=$voucherSessionId " +
                                        "viewer=$voucherViewer host=$hostAddr claimed=$claimedAmount",
                                )
                                scope.launch {
                                    runCatching {
                                        claimHandler(
                                            voucherSessionId,
                                            voucherViewer,
                                            hostAddr,
                                            claimedAmount,
                                            signature,
                                            voucherViewerPublicKey,
                                        )
                                    }.onFailure { e ->
                                        println("$TAG: [VOUCHER_CLAIM_ERROR] $e")
                                    }
                                }
                            } else {
                                println(
                                    "$TAG: [VOUCHER_CLAIM_SKIP] hostAddr=$hostAddr " +
                                        "handler=${claimHandler != null} — set iosBroadcastClaimVoucherHandler",
                                )
                            }
                        }
                    }
                }
            }

            val candidate = msg.jsonOptString("address")
            if (candidate != null && candidate != activeViewerAddressForVault) {
                activeViewerAddressForVault = candidate
                println("$TAG: viewer address captured from message: $candidate")
            }
        }.onFailure { e ->
            println("$TAG: tryCaptureViewerAddressFromMessage error: $e")
        }
    }

    private fun startConnectionTypePolling() {
        println("$TAG: starting connection-type polling")
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
