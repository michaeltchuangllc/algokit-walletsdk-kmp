package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamCreator
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.MppServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.core.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.displayName
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.parseIceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport.BroadcastRtcRtpSender
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport.CallbackRtcDataChannel
import kotlinx.coroutines.CoroutineScope
import org.koin.mp.KoinPlatform.getKoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.io.encoding.ExperimentalEncodingApi

// ── Swift-bridged global handlers ─────────────────────────────────────────────

var activeIOSBroadcastConnectionManager: LiquidAuthConnectionManager? = null
var activeIOSViewerConnectionManager: LiquidAuthConnectionManager? = null
var iosBroadcastStartHandler: ((origin: String, requestId: String) -> Unit)? = null
var iosBroadcastStopHandler: (() -> Unit)? = null

/**
 * Sends a message on the GENERAL "liquid" DataChannel.
 * Used for: video frames, keep-alive pings, session-level messages.
 * Set once in Swift via `registerBroadcastHandlers`.
 */
var iosBroadcastSendMessageHandler: ((message: String) -> Unit)? = null

var iosBroadcastPaymentDCSendMessageHandler: ((message: String) -> Unit)? = null

var iosBroadcastGateVideoHandler: ((enabled: Boolean) -> Unit)? = null

var iosBroadcastIsConnectedHandler: (() -> Boolean)? = null
var iosBroadcastDetectConnectionTypeHandler: (() -> String)? = null

var iosBroadcastClaimVoucherHandler: (
    (
        sessionId: String,
        viewerAddress: String,
        hostAddress: String,
        claimedMicroUsdc: Long,
        signatureBase64: String,
        viewerPublicKeyBase64: String,
        channelIdBase64: String?,
    ) -> Unit
)? = null

var iosBroadcastMppSecretKey: String = "ios-host-mpp-secret"

var iosViewerStartHandler: ((origin: String, requestId: String) -> Unit)? = null
var iosViewerStopHandler: (() -> Unit)? = null
var iosViewerSendMessageHandler: ((message: String) -> Unit)? = null
var iosViewerPaymentDCSendMessageHandler: ((message: String) -> Unit)? = null
var iosViewerIsConnectedHandler: (() -> Boolean)? = null
var iosViewerDetectConnectionTypeHandler: (() -> String)? = null

/**
 * Called by Kotlin to retrieve the base64-encoded Ed25519 public key for the viewer's address.
 * Swift must set this before the viewer connects so the hello message can be sent.
 */
var iosViewerPublicKeyProvider: ((viewerAddress: String) -> String?)? = null

/**
 * Called by Swift to retrieve the current on-chain remaining balance for the viewer's session
 * vault. The callback receives the balance in micro-USDC or null on failure.
 */
var iosViewerFetchBalanceHandler: (
    (
        viewerAddress: String,
        hostAddress: String,
        callback: (remainingMicroUsdc: Long?) -> Unit,
    ) -> Unit
)? = null

private const val TAG = "IOSLiquidAuthCM"
/** Polling interval for the host-side on-chain balance during paid streaming (ms). */
private const val HOST_BALANCE_POLL_INTERVAL_MS = 5_000L

actual class LiquidAuthConnectionManager actual constructor(
    @Suppress("UNUSED_PARAMETER") platformContext: Any,
) {
    enum class ViewerConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    actual val connectionType: StateFlow<IceConnectionType> = _connectionType

    private var viewModel: LiquidAuthOfferViewModel? = null
    private var answerViewModel: AnswerViewModel? = null
    private var activeRequestId: String? = null
    private var activeViewerOrigin: String? = null
    private var activeViewerRequestId: String? = null
    private val connectionTypePollingController =
        LiquidAuthPollingJobController(
            scope = CoroutineScope(Dispatchers.Default),
            onPoll = {
                platformServices.detectHostConnectionType()?.let { notifyConnectionTypeChanged(it) }
            },
            onStop = { _connectionType.value = IceConnectionType.UNKNOWN },
        )
    private val viewerConnectionTypePollingController =
        LiquidAuthPollingJobController(
            scope = CoroutineScope(Dispatchers.Default),
            onPoll = {
                platformServices.detectViewerConnectionType()?.let { notifyViewerConnectionTypeChanged(it) }
            },
            onStop = { _viewerConnectionType.value = IceConnectionType.UNKNOWN },
        )
    private var blockConsumptionJob: Job? = null
    private var viewerPaymentRailSetupKey: String? = null

    private val _viewerConnectionState = MutableStateFlow(ViewerConnectionState.IDLE)

    @Suppress("unused")
    val viewerConnectionState: StateFlow<ViewerConnectionState> = _viewerConnectionState

    private val _viewerConnectionType = MutableStateFlow(IceConnectionType.UNKNOWN)

    @Suppress("unused")
    val viewerConnectionType: StateFlow<IceConnectionType> = _viewerConnectionType

    private val _viewerSessionId = MutableStateFlow("")

    @Suppress("unused")
    val viewerSessionId: StateFlow<String> = _viewerSessionId

    private val _viewerAddress = MutableStateFlow("")

    @Suppress("unused")
    val viewerAddress: StateFlow<String> = _viewerAddress

    private val _hostAddress = MutableStateFlow("")

    @Suppress("unused")
    val hostAddress: StateFlow<String> = _hostAddress

    private var activeViewerAddressForVault: String? = null
    private var activeViewerAuthorizedSignerKey: ByteArray? = null
    private var activePaymentSessionId: String? = null
    private var activePaymentRecipient: String? = null
    private var activePaymentAmount: String? = null
    private var activeCreatorVoucherClaimSnapshot: CreatorVoucherClaimSnapshot? = null

    private val getRemainingBalanceUseCase: GetRemainingSessionVaultBalanceUseCase =
        getKoin().get()
    private val platformServices = LiquidAuthPlatformServices()
    private val scope = CoroutineScope(Dispatchers.Default)

    // ── LiquidStreamCreator (host payment channel) ────────────────────────────

    private var streamCreator: LiquidStreamCreator? = null
    private var streamCreatorDataChannel: CallbackRtcDataChannel? = null

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

    actual fun initialize(viewModel: LiquidAuthOfferViewModel) {
        this.viewModel = viewModel
        activeIOSBroadcastConnectionManager = this
        println("$TAG: initialize() viewModel=$viewModel")
    }

    actual fun startListening(
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

    actual fun stopListening() {
        println("$TAG: stopListening() (activeRequestId=$activeRequestId)")
        stopConnectionTypePolling()
        stopBlockConsumption()
        iosBroadcastStopHandler?.invoke()
        streamCreator?.terminate("stopListening")
        streamCreator = null
        platformServices.closeHostPaymentDataChannel()
        streamCreatorDataChannel = null
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

    fun clearActiveViewerIfCurrent() {
        if (activeIOSViewerConnectionManager === this) {
            activeIOSViewerConnectionManager = null
        }
    }

    actual fun sendMessage(message: String) {
        if (!platformServices.sendHostMessage(message)) {
            println("$TAG: sendMessage skipped — iosBroadcastSendMessageHandler not set")
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    actual fun sendVideoFrame(
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
            val jsonMessage =
                buildLiquidAuthVideoFrameMessage(
                    frameId = frameId,
                    timestamp = timestamp,
                    frameData = frameData,
                    width = width,
                    height = height,
                    format = format,
                    hostAddress = activePaymentRecipient.orEmpty(),
                    sessionId = activePaymentSessionId.orEmpty(),
                )
            sendMessage(jsonMessage)
        } catch (e: Exception) {
            println("$TAG: sendVideoFrame failed: $e")
        }
    }

    actual fun isConnected(): Boolean = platformServices.isHostConnected()

    // ── Viewer connection lifecycle ───────────────────────────────────────────

    @Suppress("unused")
    fun connectViewer(
        origin: String,
        requestId: String,
        viewerAddress: String = "",
    ) {
        activeIOSViewerConnectionManager = this
        activeViewerOrigin = origin
        activeViewerRequestId = requestId
        if (viewerAddress.isNotBlank()) setViewerAddress(viewerAddress)
        _viewerConnectionState.value = ViewerConnectionState.CONNECTING
        println("$TAG: connectViewer() origin=$origin requestId=$requestId")
        if (!platformServices.startViewerConnection(origin, requestId)) {
            println("$TAG: iosViewerStartHandler not set")
        }
    }

    fun disconnectViewer() {
        println("$TAG: disconnectViewer()")
        stopViewerConnectionTypePolling()
        answerViewModel?.stopMppPaymentViewer()
        platformServices.stopViewerConnection()
        activeViewerOrigin = null
        activeViewerRequestId = null
        _viewerConnectionState.value = ViewerConnectionState.DISCONNECTED
        _viewerConnectionType.value = IceConnectionType.UNKNOWN
        _viewerSessionId.value = ""
        answerViewModel?.clearViewerConnectionState()
        answerViewModel?.setViewerSessionVaultProgress(0L, 0L)
        answerViewModel?.clearViewerConsent()
        viewerPaymentRailSetupKey = null
        answerViewModel?.closeViewerPaymentRail()
    }

    @Suppress("unused")
    fun isViewerConnected(): Boolean = platformServices.isViewerConnected()

    fun sendViewerMessage(message: String) {
        if (!platformServices.sendViewerMessage(message)) {
            println("$TAG: sendViewerMessage skipped — handler not set")
        }
    }

    fun attachAnswerViewModel(viewModel: AnswerViewModel?) {
        answerViewModel = viewModel
        maybeSetupViewerPaymentRail()
    }

    @Suppress("unused")
    fun notifyViewerConnected() {
        println("$TAG: notifyViewerConnected")
        _viewerConnectionState.value = ViewerConnectionState.CONNECTED
        startViewerConnectionTypePolling()
        sendViewerHello()
        startViewerOnChainRefreshIfReady()
        maybeSetupViewerPaymentRail()
        answerViewModel?.openViewerPaymentRail()
    }

    @Suppress("unused")
    fun notifyViewerDisconnected() {
        println("$TAG: notifyViewerDisconnected")
        stopViewerConnectionTypePolling()
        answerViewModel?.stopMppPaymentViewer()
        _viewerConnectionState.value = ViewerConnectionState.DISCONNECTED
        answerViewModel?.clearVideoFrame()
        answerViewModel?.closeViewerPaymentRail()
    }

    fun setViewerAddress(address: String) {
        if (address.isBlank()) return
        _viewerAddress.value = address
        answerViewModel?.setViewerAddress(address)
        println("$TAG: VIEWER_ADDR_SET addr=$address")
    }

    fun startViewerBalancePollingSafe(
        viewerAddress: String,
        hostAddress: String,
    ) {
        if (viewerAddress.isNotBlank()) setViewerAddress(viewerAddress)
        if (hostAddress.isNotBlank()) setViewerHostAddress(hostAddress)

        println(
            "$TAG: startViewerBalancePollingSafe viewer=$viewerAddress host=$hostAddress " +
                "_viewerAddress='${_viewerAddress.value}' _hostAddress='${_hostAddress.value}'",
        )

        if (viewerAddress.isBlank() || hostAddress.isBlank()) {
            println("$TAG: startViewerBalancePollingSafe — polling deferred (host address not yet known)")
            return
        }
        startViewerOnChainRefreshIfReady()
    }

    actual fun sendPaymentRequest(paymentRequest: PaymentRequest) {
        println(
            "$TAG: 💰 sendPaymentRequest — session=${paymentRequest.id} " +
                "amount=${paymentRequest.amount} payTo=${paymentRequest.payTo}",
        )

        // Don't pre-seed activePaymentSessionId from paymentRequest.id here —
        // when using PaywalledRTCServer, the actual session ID comes from creator.sessionId
        // (set after creator.start()). For the legacy path we keep it from paymentRequest.id.
        activePaymentRecipient = paymentRequest.payTo
        activePaymentAmount = paymentRequest.amount
        startPaywalledRTCServer(paymentRequest)
    }

    private fun startPaywalledRTCServer(paymentRequest: PaymentRequest) {
        if (streamCreator != null) {
            println("$TAG: LiquidStreamCreator already active — skipping duplicate")
            return
        }

        val resolvedPaymentRequest = resolveLiquidAuthPaymentRequest(paymentRequest)

        val mppServerConfig =
            MppServerConfig(
                network = resolvedPaymentRequest.network,
                recipient = paymentRequest.payTo,
                secretKey = iosBroadcastMppSecretKey,
            )

        val gatingConfig = resolvedPaymentRequest.gatingConfig

        val serverConfig =
            ServerConfig(
                sessionId = paymentRequest.sessionId,
                gating = gatingConfig,
                enforcement = paymentRequest.meta.enforcement,
                viewerAddress = activeViewerAddressForVault,
                viewerAuthorizedSignerPublicKey = activeViewerAuthorizedSignerKey,
                skipPaymentRequestWhenSessionFunded = true,
            )

        activeGatingConfig = gatingConfig

        val dataChannel = platformServices.createHostPaymentDataChannel()
        streamCreatorDataChannel = dataChannel

        val creator =
            LiquidStreamCreator(
                dataChannel = dataChannel,
                rtpSenders = listOf(BroadcastRtcRtpSender()),
                mppServerConfig = mppServerConfig,
                serverConfig = serverConfig,
                getRemainingSessionVaultBalanceUseCase = getRemainingBalanceUseCase,
            )

        creator.rtcServer.onSessionStarted = { sid ->
            println("$TAG: [Creator] onSessionStarted session=$sid")
        }
        creator.rtcServer.onPaymentRequested = { req ->
            println("$TAG: [Creator] onPaymentRequested segment=${req.segmentIndex} amount=${req.amount}")
        }
        creator.rtcServer.onPaymentSettled = { receipt ->
            println("$TAG: [Creator] onPaymentSettled session=${receipt.sessionId} segment=${receipt.segmentIndex}")
            // Sync activePaymentSessionId to the PaywalledRTCServer's actual session.
            activePaymentSessionId = receipt.sessionId
            viewModel?.startVideoStreaming()
            startBlockConsumption(receipt.sessionId)
        }
        creator.rtcServer.onPaymentRejected = { reason ->
            println("$TAG: [Creator] onPaymentRejected reason=$reason")
        }
        creator.rtcServer.onSegmentStarted = { idx -> println("$TAG: [Creator] onSegmentStarted idx=$idx") }
        creator.rtcServer.onSegmentGated = { idx ->
            isVideoGated = true
            println("$TAG: [Creator] onSegmentGated idx=$idx — frame sending paused")
        }
        creator.rtcServer.onSegmentResumed = { idx ->
            isVideoGated = false
            println("$TAG: [Creator] onSegmentResumed idx=$idx — frame sending resumed")
        }
        creator.rtcServer.onSessionTerminated = { sid -> println("$TAG: [Creator] onSessionTerminated session=$sid") }
        creator.rtcServer.onError = { err -> println("$TAG: [Creator] error: $err") }

        streamCreator = creator
        println("$TAG: LiquidStreamCreator created — calling start()")
        creator.start()
        // Sync activePaymentSessionId to the PaywalledRTCServer's session ID right away.
        activePaymentSessionId = creator.sessionId
        println("$TAG: Creator session=${creator.sessionId} (synced to activePaymentSessionId)")

        // If DC is already open (viewer connected before sendPaymentRequest), open immediately.
        if (isConnected()) {
            dataChannel.notifyOpen()
        }
    }

    /**
     * Start polling the on-chain session-vault balance for the active viewer.
     * Calls [LiquidAuthOfferViewModel.consumeBlock] on each poll tick so the host
     * UI reflects the live remaining balance.
     */
    actual fun startBlockConsumption(sessionId: String) {
        if (blockConsumptionJob?.isActive == true) {
            println("$TAG: startBlockConsumption — already running (session=$sessionId)")
            return
        }
        val targetSession = activePaymentSessionId ?: sessionId
        println("$TAG: startBlockConsumption session=$targetSession")

        var hostTick = 0
        blockConsumptionJob =
            scope.launch {
                while (isActive) {
                    hostTick++
                    val viewerAddr = activeViewerAddressForVault
                    val hostAddr = activePaymentRecipient
                    val signerKey = activeViewerAuthorizedSignerKey

                    if (!viewerAddr.isNullOrBlank() && !hostAddr.isNullOrBlank() && signerKey != null) {
                        runCatching {
                            val remaining =
                                getRemainingBalanceUseCase(
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
                        val missingWhat =
                            when {
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

    actual fun stopBlockConsumption() {
        println("$TAG: stopBlockConsumption")
        blockConsumptionJob?.cancel()
        blockConsumptionJob = null
    }

    @Suppress("unused")
    fun notifyClientConnected(requestId: String) {
        println("$TAG: notifyClientConnected requestId=$requestId")
        viewModel?.onClientConnected(requestId)
        startConnectionTypePolling()
        // Open the host DC so LiquidStreamCreator (if already started) begins the handshake.
        platformServices.openHostPaymentDataChannel()
    }

    @Suppress("unused")
    fun notifyClientDisconnected() {
        println("$TAG: notifyClientDisconnected")
        stopConnectionTypePolling()
        stopBlockConsumption()
        platformServices.closeHostPaymentDataChannel()
        streamCreator = null
        streamCreatorDataChannel = null
        viewModel?.onClientDisconnected(activePaymentRecipient)
    }

    @Suppress("unused")
    fun notifyMessageReceived(message: String) {
        if (answerViewModel != null) {
            notifyViewerMessageReceived(message)
            return
        }
        notifyHostMessageReceived(message)
    }

    fun notifyViewerMessageReceived(message: String) {
        val ref = parseLiquidAuthHostTransportMessage(message).reference ?: "(no-ref)"
        if (ref != "liquid:video:frame") {
            println(
                "$TAG: VIEWER_MSG_RECV ref=$ref len=${message.length} " +
                    "viewer='${_viewerAddress.value}' host='${_hostAddress.value}' " +
                    "preview=${message.take(160)}",
            )
        }
        answerViewModel?.handleViewerTransportMessage(
            message = message,
            onPongRequested = { sendViewerMessage("""{"reference":"pong"}""") },
            onLegacyPaymentRequest = ::handleViewerPaymentRequest,
            onPaymentMessage = { paymentMessage -> answerViewModel?.handlePlatformPaymentMessage(paymentMessage) == true },
            onHostDiscovered = { host ->
                if (!host.isNullOrBlank() && _hostAddress.value != host) setViewerHostAddress(host)
                startViewerOnChainRefreshIfReady()
                maybeSetupViewerPaymentRail()
            },
        ) ?: println("$TAG: message dropped — AnswerViewModel not attached")
    }

    private fun notifyHostMessageReceived(message: String) {
        val parsed = parseLiquidAuthHostTransportMessage(message)
        val ref = parsed.reference ?: "(no-ref)"
        val msgType = parsed.type
        println(
            "$TAG: HOST_MSG_RECV ref=$ref type=$msgType len=${message.length} " +
                "viewer='$activeViewerAddressForVault' host='$activePaymentRecipient' " +
                "session='$activePaymentSessionId' preview=${message.take(160)}",
        )

        // Always capture viewer address / signer key for balance polling.
        tryCaptureViewerAddressFromMessage(message)

        // Route `"type"`-keyed messages (PaywalledRTCClient protocol) to LiquidStreamCreator.
        if (msgType != null && streamCreator != null) {
            println("$TAG: routing type-keyed DC msg to LiquidStreamCreator (type=$msgType)")
            platformServices.notifyHostPaymentMessage(message)
            return
        }

        // Only call onClientConnected for connection-establishment messages, NOT for protocol
        // messages that have a `reference` field (like liquid:payment:voucher, liquid:viewer:hello).
        // Calling it for every voucher causes repeated spurious ViewModel callbacks.
        if (parsed.reference == null) {
            val requestId = activeRequestId
            if (requestId != null && isConnected()) {
                viewModel?.onClientConnected(requestId)
            }
        }
    }

    @Suppress("unused")
    fun notifyConnectionTypeChanged(typeString: String) {
        val type = parseIceConnectionType(typeString)
        if (_connectionType.value != type) {
            _connectionType.value = type
            println("$TAG: connection type -> ${type.displayName()}")
            viewModel?.onConnectionTypeChanged(type)
        }
    }

    @Suppress("unused")
    fun notifyViewerConnectionTypeChanged(typeString: String) {
        val type = parseIceConnectionType(typeString)
        if (_viewerConnectionType.value != type) {
            _viewerConnectionType.value = type
            answerViewModel?.setConnectionType(type)
            println("$TAG: viewer connection type -> ${type.displayName()}")
        }
    }

    @Suppress("unused")
    fun updateRemainingBalance(microUsdc: Long) {
        answerViewModel?.setViewerSessionVaultBalance(microUsdc)
        println("$TAG: viewer balance updated -> ${microUsdc / 1_000_000.0} USDC")
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun setViewerHostAddress(address: String) {
        if (address.isBlank()) return
        _hostAddress.value = address
        answerViewModel?.setHostAddress(address)
        maybeSetupViewerPaymentRail()
    }

    private fun maybeSetupViewerPaymentRail() {
        val viewModel = answerViewModel ?: return
        val viewer = _viewerAddress.value
        val host = _hostAddress.value
        if (viewer.isBlank() || host.isBlank()) return
        val setupKey = "$viewer:$host"
        if (viewerPaymentRailSetupKey == setupKey) return
        viewerPaymentRailSetupKey = setupKey
        scope.launch {
            val configured = viewModel.setupViewerPaymentRail(
                viewerAddress = viewer,
                hostAddress = host,
                scope = scope,
            )
            if (!configured) viewerPaymentRailSetupKey = null
        }
    }

    private fun sendViewerHello() {
        val viewer = _viewerAddress.value
        println(
            "$TAG: HELLO_ATTEMPT viewer='$viewer' " +
                "keyProviderSet=${platformServices.hasViewerPublicKeyProvider()} ",
        )
        if (viewer.isBlank()) {
            println(
                "$TAG: HELLO_SKIP — viewerAddress is blank! " +
                    "Call setViewerAddress() or startViewerBalancePollingSafe() BEFORE notifyViewerConnected()",
            )
            return
        }
        val publicKeyBase64 = platformServices.getViewerPublicKey(viewer)
        val keyField = if (!publicKeyBase64.isNullOrBlank()) """,\"viewerPublicKey\":\"$publicKeyBase64\""" else ""
        val hello = """{"reference":"liquid:viewer:hello","viewer":"$viewer"$keyField}"""
        println("$TAG: HELLO_SEND viewer=$viewer keyPresent=${!publicKeyBase64.isNullOrBlank()}")
        sendViewerMessage(hello)
    }

    private fun handleViewerPaymentRequest(message: String) {
        println("$TAG: PAYMENT_REQUEST_RECEIVED (legacy iOS host) preview=${message.take(160)}")
    }

    private fun startViewerOnChainRefreshIfReady() {
        val viewModel = answerViewModel ?: return
        val viewer = _viewerAddress.value
        val host = _hostAddress.value
        if (viewer.isNotBlank() && host.isNotBlank()) {
            viewModel.startViewerOnChainRefresh(viewer, host)
        }
    }

    private fun tryCaptureViewerAddressFromMessage(msg: String) {
        runCatching {
            val parsed = parseLiquidAuthHostTransportMessage(msg)

            parsed.viewerHello?.let { hello ->
                val helloViewer = hello.viewerAddress

                if (!helloViewer.isNullOrBlank() && helloViewer != activeViewerAddressForVault) {
                    activeViewerAddressForVault = helloViewer
                    println("$TAG: [VIEWER_HELLO_ADDR] viewer=$helloViewer")
                }

                val signerKey = hello.viewerPublicKey
                if (signerKey != null) {
                    activeViewerAuthorizedSignerKey = signerKey
                    println(
                        "$TAG: [VIEWER_HELLO_KEY] viewer=$helloViewer " +
                            "keyLen=${signerKey.size} session=$activePaymentSessionId",
                    )
                    updateCreatorViewerSignerConfig(helloViewer, signerKey)
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

            parsed.paymentVoucher?.let { voucher ->
                val signature = voucher.signatureBase64
                val claimedAmount = voucher.totalAmountClaimedMicroUsdc
                val voucherSessionId = voucher.sessionId
                val voucherViewer = voucher.viewerAddress
                val voucherViewerPublicKey = voucher.viewerPublicKeyBase64
                val voucherChannelId = voucher.channelIdBase64
                voucher.channelId?.let { decodedChannelId ->
                    EscrowSessionVaultManagerClient.channelId = decodedChannelId
                    println("$TAG: [VOUCHER_CHANNEL_ID_CAPTURED] len=${decodedChannelId.size}")
                }

                if (signature == null ||
                    claimedAmount == null ||
                    voucherSessionId == null ||
                    voucherViewer == null ||
                    voucherViewerPublicKey == null
                ) {
                    println(
                        "$TAG: [VOUCHER_SKIP] reason=invalid_payload " +
                            "session=${voucher.sessionId} claimedAmount=$claimedAmount",
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
                                val voucherSignerKey = voucher.viewerPublicKey
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
                                            voucherChannelId,
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

            val candidate = parsed.address
            if (candidate != null && candidate != activeViewerAddressForVault) {
                activeViewerAddressForVault = candidate
                println("$TAG: viewer address captured from message: $candidate")
            }
        }.onFailure { e ->
            println("$TAG: tryCaptureViewerAddressFromMessage error: $e")
        }
    }

    private fun updateCreatorViewerSignerConfig(
        viewerAddress: String?,
        signerKey: ByteArray,
    ) {
        val fallbackNetwork = activeGatingConfig?.network ?: MppNetworks.ALGORAND_TESTNET
        val currentGating =
            activeGatingConfig ?: GatingConfig(
                mode = GatingMode.PARTIAL_TIME,
                amount = activePaymentAmount ?: "0",
                asset = "USDC",
                network = fallbackNetwork,
                payTo = activePaymentRecipient ?: "",
            )
        streamCreator?.updateConfig(
            ServerConfig(
                sessionId = activePaymentSessionId,
                gating = currentGating,
                viewerAddress = viewerAddress,
                viewerAuthorizedSignerPublicKey = signerKey,
                skipPaymentRequestWhenSessionFunded = true,
            ),
        )
    }

    private fun startConnectionTypePolling() {
        println("$TAG: starting connection-type polling")
        connectionTypePollingController.start()
    }

    private fun stopConnectionTypePolling() {
        connectionTypePollingController.stop()
    }

    private fun startViewerConnectionTypePolling() {
        viewerConnectionTypePollingController.start()
    }

    private fun stopViewerConnectionTypePolling() {
        viewerConnectionTypePollingController.stop()
    }
}

