package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.relayHostChat

import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamCreator
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.MppServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.CreatorVoucherClaimSnapshot
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.repository.MppVoucherRepository
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetMppVoucherNoteUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultHybridManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.HostViewerVaultReader
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.HostViewerDetails
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.displayName
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.parseIceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.LiquidStreamBlockConsumptionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.ViewerVaultBillingSession
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.FrameHeartbeatThrottle
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport.BroadcastRtcRtpSender
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.transport.CallbackRtcDataChannel
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_BATCH_BLOCK_COUNT
import com.michaeltchuang.walletsdk.ui.liquidStream.utils.PAYOUT_EVERY_256_BLOCKS_TAB_ID
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.mp.KoinPlatform.getKoin

// ── Swift-bridged global handlers ─────────────────────────────────────────────

var activeIOSBroadcastConnectionManager: LiquidAuthConnectionManager? = null
var activeIOSViewerConnectionManager: LiquidAuthConnectionManager? = null
var iosBroadcastStartHandler: ((origin: String, requestId: String) -> Unit)? = null
var iosBroadcastStopHandler: (() -> Unit)? = null

/** Opt in only after Swift installs invitation-keyed callbacks and shared native capture. */
var iosMeshHostingIntegrated: Boolean = false
var iosBroadcastViewerStopHandler: ((requestId: String) -> Unit)? = null

/** Explicit host chat only, sent once to every connected viewer (including the primary). */
var iosBroadcastHostChatSendHandler: ((message: String) -> Unit)? = null

/**
 * Sends a message on the GENERAL "liquid" DataChannel.
 * Used for: video frames, keep-alive pings, session-level messages.
 * Set once in Swift via `registerBroadcastHandlers`.
 */
var iosBroadcastSendMessageHandler: ((message: String) -> Unit)? = null

var iosBroadcastPaymentDCSendMessageHandler: ((message: String) -> Unit)? = null

var iosBroadcastGateVideoHandler: ((enabled: Boolean) -> Unit)? = null
var iosBroadcastSetAudioEnabledHandler: ((enabled: Boolean) -> Unit)? = null
var iosBroadcastSetVideoEnabledHandler: ((enabled: Boolean) -> Unit)? = null

/** Supplies a UIKit view that renders the local native WebRTC video track. */
var iosBroadcastVideoViewProvider: (() -> Any?)? = null

var iosBroadcastIsConnectedHandler: (() -> Boolean)? = null
var iosBroadcastDetectConnectionTypeHandler: (() -> String)? = null

var iosBroadcastMppSecretKey: String = "ios-host-mpp-secret"

var iosViewerStartHandler: ((origin: String, requestId: String) -> Unit)? = null
var iosViewerStopHandler: (() -> Unit)? = null
var iosViewerSendMessageHandler: ((message: String) -> Unit)? = null
var iosViewerPaymentDCSendMessageHandler: ((message: String) -> Unit)? = null
var iosViewerIsConnectedHandler: (() -> Boolean)? = null
var iosViewerDetectConnectionTypeHandler: (() -> String)? = null

/** Supplies a UIKit view that renders the remote native WebRTC video track. */
var iosViewerVideoViewProvider: (() -> Any?)? = null

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

actual class LiquidAuthConnectionManager actual constructor(
    @Suppress("UNUSED_PARAMETER") platformContext: Any,
) {
    enum class ViewerConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    actual val connectionType: StateFlow<IceConnectionType> = _connectionType

    private var viewModel: LiquidAuthOfferViewModel? = null
    private var answerViewModel: AnswerViewModel? = null
    private val viewerFrameHeartbeatThrottle = FrameHeartbeatThrottle()
    private var activeRequestId: String? = null
    // The first connected mesh viewer owns the legacy payment rail for the host lifetime.
    // Never promote another viewer into its vault/session, even after it disconnects.
    private val hostInvitations = mutableSetOf<String>()
    private val retiredHostInvitations = mutableSetOf<String>()
    private val connectedHostViewers = mutableSetOf<String>()
    private val hostPaymentSenders = mutableMapOf<String, (String) -> Unit>()
    private class HostViewerIdentity(val address: String, val signerKey: ByteArray?, val channelId: ByteArray? = null)
    private val hostViewerIdentities = mutableMapOf<String, HostViewerIdentity>()
    private val hostViewerDetails = mutableMapOf<String, HostViewerDetails>()
    private val hostViewerBalanceJobs = mutableMapOf<String, Job>()
    private class AdditionalHostViewer {
        var creator: LiquidStreamCreator? = null
        var dataChannel: CallbackRtcDataChannel? = null
        var config: ServerConfig? = null
        var billing: ViewerVaultBillingSession? = null
        val pendingMessages = mutableListOf<String>()
        val voucherJobs = mutableSetOf<Job>()
    }
    private val additionalHostViewers = mutableMapOf<String, AdditionalHostViewer>()
    private val hostBillingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var additionalViewerBlockJob: Job? = null
    private var payoutFrequencyBlocks = 1
    private var hostGeneration = 0L
    private var paymentRequestJob: Job? = null
    private val meshHostingEnabled: Boolean
        get() = viewModel?.meshHostingEnabled == true
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
    private var viewerPaymentRailSetupKey: String? = null

    private val pendingViewerPaymentMessages = mutableListOf<String>()

    private val _viewerConnectionState = MutableStateFlow(ViewerConnectionState.IDLE)

    @Suppress("unused")
    val viewerConnectionState: StateFlow<ViewerConnectionState> = _viewerConnectionState

    private val _viewerConnectionType = MutableStateFlow(IceConnectionType.UNKNOWN)

    @Suppress("unused")
    val viewerConnectionType: StateFlow<IceConnectionType> = _viewerConnectionType

    private val _viewerSessionId = MutableStateFlow("")

    @Suppress("unused")
    val viewerSessionId: StateFlow<String> = _viewerSessionId

    private val _viewerAddress = MutableStateFlow<String?>(null)

    actual val viewerAddress: StateFlow<String?> = _viewerAddress

    private val _hostAddress = MutableStateFlow("")

    @Suppress("unused")
    val hostAddress: StateFlow<String> = _hostAddress

    private var activeViewerAddressForVault: String? = null
    private var activeViewerAuthorizedSignerKey: ByteArray? = null
    private var activePaymentSessionId: String? = null
    private var activePaymentRecipient: String? = null
    private var activePaymentAmount: String? = null
    private var activePaymentNetwork: String? = null
    private var activeCreatorVoucherClaimSnapshot: CreatorVoucherClaimSnapshot? = null
    private var isPaidStreamingEnabled: Boolean = true

    private val getRemainingBalanceUseCase: GetRemainingSessionVaultBalanceUseCase =
        getKoin().get()
    private val getMppVoucherNoteUseCase: GetMppVoucherNoteUseCase =
        getKoin().get()
    private val voucherRepository: MppVoucherRepository = getKoin().get()
    private val mppWalletSignerUseCase: MppWalletSignerUseCase = getKoin().get()
    private val platformServices = LiquidAuthPlatformServices()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val blockConsumptionManager =
        LiquidStreamBlockConsumptionManager(
            tag = TAG,
            getViewModel = { viewModel },
            getActiveViewerAddress = { activeViewerAddressForVault },
            getActiveCreatorAddress = { activePaymentRecipient },
            getCreatorVoucherClaimSnapshot = { activeCreatorVoucherClaimSnapshot },
            getIsPaidStreaming = { isPaidStreamingEnabled },
            buildCreatorWalletSigner = { creatorAddress -> mppWalletSignerUseCase(creatorAddress) },
            getMppVoucherNoteUseCase = getMppVoucherNoteUseCase,
            voucherRepository = voucherRepository,
        )

    // ── LiquidStreamCreator (host payment channel) ────────────────────────────

    private var streamCreator: LiquidStreamCreator? = null
    private var streamCreatorDataChannel: CallbackRtcDataChannel? = null

    private var isVideoGated = false

    /** Stored gating config for the current server session (used to rebuild [ServerConfig] on viewer-hello). */
    private var activeGatingConfig: GatingConfig? = null

    private val viewerReady = MutableStateFlow(false)

    actual fun initialize(viewModel: LiquidAuthOfferViewModel) {
        this.viewModel = viewModel
        viewModel.meshHostingEnabled = iosMeshHostingIntegrated
        activeIOSBroadcastConnectionManager = this
        println("$TAG: initialize() viewModel=$viewModel")
        blockConsumptionManager.processPendingSettlements()
    }

    fun enableMeshHosting() {
        if (iosMeshHostingIntegrated) viewModel?.meshHostingEnabled = true
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
        if (requestId.isBlank() || requestId in retiredHostInvitations || requestId in hostInvitations) return
        if (!meshHostingEnabled && activeRequestId != null && activeRequestId != requestId) {
            println("$TAG: requestId changed ($activeRequestId -> $requestId), restarting")
            stopListening()
        }
        hostInvitations.add(requestId)
        println("$TAG: startListening() origin=$origin requestId=$requestId")
        if (!meshHostingEnabled) activeRequestId = requestId
        try {
            // Swift adds one invitation to the existing host; it must not replace shared capture.
            handler(origin, requestId)
        } catch (error: Exception) {
            notifyBroadcastInvitationFailed(requestId, error.message ?: "Unable to start invitation")
        }
    }

    actual fun stopListening() {
        println("$TAG: stopListening() (activeRequestId=$activeRequestId)")
        additionalHostViewers.keys.toList().forEach { closeAdditionalViewer(it, "stopListening") }
        additionalViewerBlockJob?.cancel()
        additionalViewerBlockJob = null
        hostGeneration++
        paymentRequestJob?.cancel()
        paymentRequestJob = null
        retiredHostInvitations.addAll(hostInvitations)
        hostInvitations.clear()
        connectedHostViewers.clear()
        hostPaymentSenders.clear()
        hostViewerBalanceJobs.values.forEach { it.cancel() }
        hostViewerBalanceJobs.clear()
        hostViewerIdentities.clear()
        hostViewerDetails.clear()
        stopConnectionTypePolling()
        stopBlockConsumption()
        val creator = streamCreator
        streamCreator = null
        creator?.terminate("stopListening")
        platformServices.closeHostPaymentDataChannel()
        iosBroadcastPaymentDCSendMessageHandler = null
        iosBroadcastStopHandler?.invoke()
        streamCreatorDataChannel = null
        activeGatingConfig = null
        activeRequestId = null
        activeViewerAddressForVault = null
        activeViewerAuthorizedSignerKey = null
        activePaymentSessionId = null
        activePaymentRecipient = null
        activePaymentAmount = null
        activePaymentNetwork = null
        activeCreatorVoucherClaimSnapshot = null
        viewerReady.value = false
        isVideoGated = false
        viewModel?.clearMeshHosting()
        _connectionType.value = IceConnectionType.UNKNOWN
    }

    fun clearActiveViewerIfCurrent() {
        if (activeIOSViewerConnectionManager === this) {
            activeIOSViewerConnectionManager = null
        }
    }

    actual fun sendMessage(message: String) {
        if (meshHostingEnabled) {
            // Swift may retain host configuration even after the primary transport has gone.
            // This is NOT permission to fan out the primary's session envelope verbatim.
            val isHostConfiguration = runCatching {
                val envelope = Json.parseToJsonElement(message).jsonObject
                envelope["reference"]?.jsonPrimitive?.content == "liquid:stream:info" ||
                    envelope["type"]?.jsonPrimitive?.content == DCMessageType.STREAM_COST_UPDATE.value
            }.getOrDefault(false)
            if (isHostConfiguration) {
                // Rebuild each extra viewer's metadata with its own ID, never the primary ID.
                refreshAdditionalViewerInfo()
                if (activeRequestId == null && hostInvitations.isEmpty()) return
            } else if (activeRequestId !in connectedHostViewers) {
                return
            }
        }
        if (!platformServices.sendHostMessage(message)) {
            println("$TAG: sendMessage skipped — iosBroadcastSendMessageHandler not set")
        }
    }

    actual fun sendChatMessage(message: ChatMessage) {
        blockConsumptionManager.recordChatMessage(message)
        val envelope = buildJsonObject {
            put("type", DCMessageType.CHAT_MESSAGE.value)
            put("payload", Json.encodeToJsonElement(ChatMessage.serializer(), message))
        }.toString()
        val fanout = iosBroadcastHostChatSendHandler.takeIf { meshHostingEnabled }
        if (fanout != null) {
            // No primary session ID in a host-wide chat envelope. Never fan out session/payment messages.
            fanout(envelope)
        } else {
            relayViewerChat(message)
        }
    }

    private fun relayViewerChat(message: ChatMessage, source: LiquidStreamCreator? = null) {
        val recipients = listOfNotNull(streamCreator) +
            additionalHostViewers.toList()
                .filter { (requestId, _) -> requestId in connectedHostViewers }
                .mapNotNull { (_, viewer) -> viewer.creator }
        relayHostChat(
            message = message,
            recipients = recipients,
            source = source,
            send = { creator, chat -> creator.sendChatMessage(chat) },
            onFailure = { println("$TAG: Failed to relay chat: $it") },
        )
    }

    actual fun isConnected(): Boolean =
        if (meshHostingEnabled) connectedHostViewers.isNotEmpty() else platformServices.isHostConnected()

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
        _hostAddress.value = ""
        _viewerConnectionState.value = ViewerConnectionState.DISCONNECTED
        _viewerConnectionType.value = IceConnectionType.UNKNOWN
        _viewerSessionId.value = ""
        answerViewModel?.clearViewerConnectionState()
        answerViewModel?.setViewerSessionVaultProgress(0L, 0L)
        answerViewModel?.clearViewerConsent()
        viewerPaymentRailSetupKey = null
        pendingViewerPaymentMessages.clear()
        EscrowSessionVaultHybridManagerClient.channelId = null
        EscrowSessionVaultHybridManagerClient.hostAddress = null
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
        if (viewModel != null) {
            viewerPaymentRailSetupKey = null
            EscrowSessionVaultHybridManagerClient.channelId = null
            EscrowSessionVaultHybridManagerClient.hostAddress = null
        }
        maybeSetupViewerPaymentRail()
    }

    @Suppress("unused")
    fun notifyViewerConnected() {
        println("$TAG: notifyViewerConnected")
        _viewerConnectionState.value = ViewerConnectionState.CONNECTED
        startViewerConnectionTypePolling()
        startViewerOnChainRefreshIfReady()
        maybeSetupViewerPaymentRail()
        answerViewModel?.openViewerPaymentRail()
    }

    /**
     * Swift should call this whenever a frame is actually rendered on the remote video view
     * returned by [iosViewerVideoViewProvider] (e.g. from the `RTCVideoRenderer.renderFrame`
     * delegate callback backing that view). This keeps the shared stream-timeout watchdog in
     * [AnswerViewModel] (via `LiquidAuthViewerStateHolder`) alive while the host is actively
     * streaming, mirroring the Android native-track heartbeat. If the host stops streaming and
     * frames stop arriving, the watchdog fires after `STREAM_TIMEOUT_MS` and tears the viewer
     * down automatically.
     *
     * Safe to call on every single frame - [viewerFrameHeartbeatThrottle] (the same shared
     * throttle used by Android's `StreamHeartbeatVideoSink`) downsamples it to a low-rate
     * heartbeat, so Swift doesn't need to do its own throttling.
     */
    @Suppress("unused")
    fun notifyViewerVideoFrameReceived() {
        if (viewerFrameHeartbeatThrottle.onSignal()) {
            answerViewModel?.markStreamFrameReceived()
        }
    }

    @Suppress("unused")
    fun notifyViewerDisconnected() {
        println("$TAG: notifyViewerDisconnected")
        stopViewerConnectionTypePolling()
        answerViewModel?.stopMppPaymentViewer()
        _viewerConnectionState.value = ViewerConnectionState.DISCONNECTED
        answerViewModel?.clearVideoFrame()
        viewerPaymentRailSetupKey = null
        pendingViewerPaymentMessages.clear()
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
        activePaymentRecipient = paymentRequest.payTo
        activePaymentAmount = paymentRequest.amount
        resolveLiquidAuthPaymentRequest(paymentRequest).let {
            activePaymentNetwork = it.network
            activeGatingConfig = it.gatingConfig
        }
        refreshAdditionalViewerInfo()
        if (meshHostingEnabled && activeRequestId !in connectedHostViewers) return
        println(
            "$TAG: 💰 sendPaymentRequest — session=${paymentRequest.id} " +
                "amount=${paymentRequest.amount} payTo=${paymentRequest.payTo}",
        )

        // Don't pre-seed activePaymentSessionId from paymentRequest.id here —
        // when using PaywalledRTCServer, the actual session ID comes from creator.sessionId
        // (set after creator.start()). For the legacy path we keep it from paymentRequest.id.
        activePaymentRecipient = paymentRequest.payTo
        activePaymentAmount = paymentRequest.amount
        paymentRequestJob?.cancel()
        val generation = hostGeneration
        paymentRequestJob = scope.launch(Dispatchers.Main) {
            viewerReady.first { it }
            if (generation == hostGeneration &&
                (!meshHostingEnabled || activeRequestId in connectedHostViewers)
            ) {
                startPaywalledRTCServer(paymentRequest)
            }
        }
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
        activePaymentNetwork = resolvedPaymentRequest.network

        val dataChannel = createPrimaryPaymentDataChannel()
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
        creator.rtcServer.onViewerHello = { viewer, viewerPublicKeyBase64 ->
            val helloJson = """{"type":"segment:handshake","viewer":"$viewer","viewerPublicKey":"$viewerPublicKeyBase64"}"""
            if (streamCreator === creator) tryCaptureViewerAddressFromMessage(helloJson)
        }
        creator.rtcServer.onVoucherReceived = { voucherJson ->
            if (streamCreator === creator) tryCaptureViewerAddressFromMessage(voucherJson)
        }
        creator.rtcServer.onPaymentRequested = { req ->
            println("$TAG: [Creator] onPaymentRequested segment=${req.segmentIndex} amount=${req.amount}")
        }
        creator.rtcServer.onPaymentSettled = settled@{ receipt ->
            if (streamCreator !== creator) return@settled
            println("$TAG: [Creator] onPaymentSettled session=${receipt.sessionId} segment=${receipt.segmentIndex}")
            // Sync activePaymentSessionId to the PaywalledRTCServer's actual session.
            activePaymentSessionId = receipt.sessionId
            viewModel?.startVideoStreaming()
            if (isPaidStreamingEnabled) {
                startBlockConsumption(receipt.sessionId)
            }
        }
        creator.rtcServer.onPaymentRejected = { reason ->
            println("$TAG: [Creator] onPaymentRejected reason=$reason")
        }
        creator.rtcServer.onSegmentStarted = { idx -> println("$TAG: [Creator] onSegmentStarted idx=$idx") }
        creator.rtcServer.onSegmentGated = { idx ->
            if (streamCreator === creator) isVideoGated = true
            println("$TAG: [Creator] onSegmentGated idx=$idx — frame sending paused")
        }
        creator.rtcServer.onSegmentResumed = { idx ->
            if (streamCreator === creator) isVideoGated = false
            println("$TAG: [Creator] onSegmentResumed idx=$idx — frame sending resumed")
        }
        creator.rtcServer.onChatMessageReceived = { message ->
            if (streamCreator === creator) {
                blockConsumptionManager.recordChatMessage(message)
                viewModel?.onChatMessageReceived(message)
                relayViewerChat(message, creator)
            }
        }
        creator.rtcServer.onSessionTerminated = { sid -> println("$TAG: [Creator] onSessionTerminated session=$sid") }
        creator.rtcServer.onError = { err -> println("$TAG: [Creator] error: $err") }

        streamCreator = creator
        println("$TAG: LiquidStreamCreator created — calling start()")
        creator.start()
        // Sync activePaymentSessionId to the PaywalledRTCServer's session ID right away.
        activePaymentSessionId = creator.sessionId
        refreshAdditionalViewerInfo()
        println("$TAG: Creator session=${creator.sessionId} (synced to activePaymentSessionId)")

        // If DC is already open (viewer connected before sendPaymentRequest), open immediately.
        if ((!meshHostingEnabled && isConnected()) ||
            (activeRequestId in connectedHostViewers && activeRequestId in hostPaymentSenders)
        ) {
            dataChannel.notifyOpen()
        }
    }

    /**
     * Start the block-driven on-chain session-vault consumption + settlement loop for
     * the active viewer, delegated to the shared [LiquidStreamBlockConsumptionManager]
     * (identical implementation used by Android).
     */
    actual fun startBlockConsumption(sessionId: String) {
        if (meshHostingEnabled && activeRequestId !in connectedHostViewers) return
        val targetSessionId = activePaymentSessionId ?: sessionId
        println("$TAG: startBlockConsumption session=$targetSessionId")
        blockConsumptionManager.start(targetSessionId)
    }

    actual fun stopBlockConsumption() {
        println("$TAG: stopBlockConsumption")
        blockConsumptionManager.stop()
        if (additionalHostViewers.values.any { it.billing != null }) startAdditionalViewerBlocks()
    }

    actual fun setIsPaidStreaming(enabled: Boolean) {
        println("$TAG: 💰 setIsPaidStreaming: $enabled")
        isPaidStreamingEnabled = enabled
        if (!enabled) {
            setStreamCost(0L)
            stopBlockConsumption()
            // Clear any stale claim snapshot so we don't settle old vouchers when resuming to Paid.
            activeCreatorVoucherClaimSnapshot = null
            activePaymentSessionId?.let { sessionId ->
                scope.launch {
                    try {
                        voucherRepository.deleteVoucherBySessionId(sessionId)
                        println("$TAG: 💰 Cleared pending vouchers for session $sessionId during switch to Free")
                    } catch (e: Exception) {
                        println("$TAG: ❌ Failed to clear vouchers: $e")
                    }
                }
            }
        } else {
            // Resume if we have a session
            activePaymentSessionId?.let { startBlockConsumption(it) }
        }
        refreshAdditionalViewerInfo()
    }

    actual fun setStreamCost(cost: Long) {
        println("$TAG: 💰 setStreamCost: $cost")
        activePaymentAmount = cost.toString()
        streamCreator?.let {
            updateCreatorViewerSignerConfig(activeViewerAddressForVault, activeViewerAuthorizedSignerKey ?: ByteArray(0))
        }
        // Extras update independently, including after the primary disconnects.
        refreshAdditionalViewerInfo()
    }

    actual fun setPayoutFrequency(tabId: String) {
        val blocks =
            if (tabId == PAYOUT_EVERY_256_BLOCKS_TAB_ID) {
                PAYOUT_BATCH_BLOCK_COUNT
            } else {
                1
            }
        println("$TAG: 💰 setPayoutFrequency: tab=$tabId blocks=$blocks")
        blockConsumptionManager.payoutFrequencyBlocks = blocks
        payoutFrequencyBlocks = blocks
        additionalHostViewers.values.forEach { it.billing?.updatePayoutFrequencyBlocks(blocks) }
    }

    actual fun setupCreator(
        creatorAddress: String,
        network: String,
    ) {
        // This entry point is the common screen's explicit chat-only/free path.
        isPaidStreamingEnabled = false
        val sameCreator = streamCreator != null && activePaymentRecipient == creatorAddress
        activePaymentRecipient = creatorAddress
        activePaymentNetwork = resolveLiquidAuthMppNetwork(network)
        refreshAdditionalViewerInfo()
        if (sameCreator) {
            return
        }
        if (meshHostingEnabled && activeRequestId !in connectedHostViewers) return

        val resolvedNetwork = resolveLiquidAuthMppNetwork(network)
        val sessionId = activePaymentSessionId ?: "chat-session-${activeRequestId ?: ""}"

        val serverConfig =
            ServerConfig(
                sessionId = sessionId,
                gating =
                    GatingConfig(
                        mode = GatingMode.PARTIAL_TIME,
                        amount = "0", // Free by default
                        asset = "USDC",
                        network = resolvedNetwork,
                        payTo = creatorAddress,
                    ),
                gracePeriod = 5,
                viewerAddress = activeViewerAddressForVault,
                viewerAuthorizedSignerPublicKey = activeViewerAuthorizedSignerKey,
                skipPaymentRequestWhenSessionFunded = true,
            )

        streamCreator?.terminate("replaced")
        val dataChannel = createPrimaryPaymentDataChannel()
        streamCreatorDataChannel = dataChannel

        val creator =
            LiquidStreamCreator(
                dataChannel = dataChannel,
                rtpSenders = emptyList(),
                mppServerConfig =
                    MppServerConfig(
                        network = resolvedNetwork,
                        recipient = creatorAddress,
                        secretKey = iosBroadcastMppSecretKey,
                    ),
                serverConfig = serverConfig,
                getRemainingSessionVaultBalanceUseCase = getRemainingBalanceUseCase,
            )

        creator.onChatMessageReceived = { message ->
            if (streamCreator === creator) {
                blockConsumptionManager.recordChatMessage(message)
                viewModel?.onChatMessageReceived(message)
                relayViewerChat(message, creator)
            }
        }

        creator.rtcServer.onViewerHello = { viewer, viewerPublicKeyBase64 ->
            val helloJson = """{"type":"segment:handshake","viewer":"$viewer","viewerPublicKey":"$viewerPublicKeyBase64"}"""
            if (streamCreator === creator) tryCaptureViewerAddressFromMessage(helloJson)
        }

        streamCreator = creator
        creator.start()
        activePaymentRecipient = creatorAddress
        activePaymentNetwork = resolvedNetwork
        activePaymentSessionId = sessionId
        if (activeRequestId in connectedHostViewers &&
            (!meshHostingEnabled || activeRequestId in hostPaymentSenders)
        ) dataChannel.notifyOpen()
        refreshAdditionalViewerInfo()

        println("$TAG: 💬 Chat initialized for creator=$creatorAddress")
    }

    actual fun setAudioEnabled(enabled: Boolean) {
        iosBroadcastSetAudioEnabledHandler?.invoke(enabled)
            ?: println("$TAG: setAudioEnabled($enabled) skipped — Swift media handler not set")
    }

    actual fun setVideoEnabled(enabled: Boolean) {
        iosBroadcastSetVideoEnabledHandler?.invoke(enabled)
            ?: println("$TAG: setVideoEnabled($enabled) skipped — Swift media handler not set")
    }

    @Suppress("unused")
    fun notifyClientConnected(requestId: String) {
        if (meshHostingEnabled) {
            notifyBroadcastViewerConnected(requestId)
            return
        }
        if (requestId != activeRequestId || requestId !in hostInvitations) return
        connectedHostViewers.add(requestId)
        println("$TAG: notifyClientConnected requestId=$requestId")
        viewModel?.onClientConnected(requestId)
        startConnectionTypePolling()
        // Open the host DC so LiquidStreamCreator (if already started) begins the handshake.
        streamCreatorDataChannel?.notifyOpen()
    }

    @Suppress("unused")
    fun notifyClientDisconnected() {
        if (meshHostingEnabled) {
            // Unkeyed callbacks cannot identify which peer left. Swift must use the keyed API.
            println("$TAG: ignored unkeyed mesh disconnect")
            return
        }
        println("$TAG: notifyClientDisconnected")
        connectedHostViewers.clear()
        paymentRequestJob?.cancel()
        viewerReady.value = false
        stopConnectionTypePolling()
        stopBlockConsumption()
        platformServices.closeHostPaymentDataChannel()
        streamCreatorDataChannel?.notifyClosed()
        val creator = streamCreator
        streamCreator = null
        creator?.terminate("viewer_disconnected")
        streamCreatorDataChannel = null
        viewModel?.onClientDisconnected(activePaymentRecipient)
    }

    /** Swift calls all keyed lifecycle/message/registration callbacks on the main thread. */
    fun notifyBroadcastViewerConnected(requestId: String) {
        if (!meshHostingEnabled) {
            notifyClientConnected(requestId)
            return
        }
        if (requestId !in hostInvitations || requestId in connectedHostViewers) return
        // Match the VM and Swift: invitation creation/order does not reserve the primary.
        // Set this before notifying the VM's legacy state machine.
        if (activeRequestId == null) activeRequestId = requestId
        connectedHostViewers.add(requestId)
        if (requestId != activeRequestId) additionalHostViewers.getOrPut(requestId) { AdditionalHostViewer() }
        if (requestId == activeRequestId) {
            startConnectionTypePolling()
            if (requestId in hostPaymentSenders) streamCreatorDataChannel?.notifyOpen()
        } else {
            sendAdditionalViewerInfo(requestId)
        }
        viewModel?.onMeshViewerConnected(requestId)
        publishHostViewerDetails(requestId, HostViewerDetails())
    }

    fun notifyBroadcastViewerDisconnected(requestId: String) {
        if (requestId !in hostInvitations) return
        if (!meshHostingEnabled) {
            if (requestId == activeRequestId) notifyClientDisconnected()
            return
        }
        hostInvitations.remove(requestId)
        retiredHostInvitations.add(requestId)
        closeAdditionalViewer(requestId, "viewer_disconnected")
        connectedHostViewers.remove(requestId)
        hostPaymentSenders.remove(requestId)
        hostViewerBalanceJobs.remove(requestId)?.cancel()
        hostViewerIdentities.remove(requestId)
        hostViewerDetails.remove(requestId)
        if (requestId == activeRequestId) {
            paymentRequestJob?.cancel()
            viewerReady.value = false
            stopConnectionTypePolling()
            stopBlockConsumption()
            val creator = streamCreator
            streamCreator = null
            streamCreatorDataChannel?.notifyClosed()
            streamCreatorDataChannel = null
            creator?.terminate("viewer_disconnected")
            iosBroadcastPaymentDCSendMessageHandler = null
        }
        // Keep the original VM session, vault and capture alive, including after the last peer leaves.
        viewModel?.onMeshViewerDisconnected(requestId)
    }

    fun notifyBroadcastInvitationFailed(
        requestId: String,
        message: String,
    ) {
        if (requestId !in hostInvitations) return
        val initialInvitationFailed =
            !meshHostingEnabled &&
                requestId == activeRequestId && requestId !in connectedHostViewers && activePaymentSessionId == null
        notifyBroadcastViewerDisconnected(requestId)
        if (initialInvitationFailed) activeRequestId = null
        viewModel?.onMeshInvitationFailed(requestId, message)
        iosBroadcastViewerStopHandler?.invoke(requestId)
    }

    fun setBroadcastViewerPaymentSendHandler(
        requestId: String,
        handler: (String) -> Unit,
    ) {
        if (requestId !in hostInvitations) return
        // Native onOpen registration is the keyed readiness signal.
        hostPaymentSenders[requestId] = handler
        if (!meshHostingEnabled && requestId == activeRequestId) {
            iosBroadcastPaymentDCSendMessageHandler = handler
        }
        if (requestId in connectedHostViewers) {
            if (requestId == activeRequestId) streamCreatorDataChannel?.notifyOpen() else sendAdditionalViewerInfo(requestId)
        }
    }

    fun notifyBroadcastViewerMessageReceived(
        requestId: String,
        message: String,
    ) {
        if (requestId !in connectedHostViewers || requestId !in hostInvitations) return
        captureHostViewerIdentity(requestId, message)
        if (requestId == activeRequestId) {
            notifyHostMessageReceived(message)
        } else {
            val peer = additionalHostViewers.getOrPut(requestId) { AdditionalHostViewer() }
            ensureAdditionalViewerCreator(requestId, peer)
            if (peer.creator == null) {
                if (peer.pendingMessages.size < 64) peer.pendingMessages.add(message)
            } else {
                deliverAdditionalViewerMessage(requestId, peer, message)
            }
        }
    }

    /** Invitation-local identity; never feeds an extra viewer into the primary payment rail. */
    private fun captureHostViewerIdentity(requestId: String, message: String) {
        if (!meshHostingEnabled || requestId !in connectedHostViewers || requestId !in hostInvitations) return
        runCatching {
            val envelope = Json.parseToJsonElement(message).jsonObject
            val parsed = parseLiquidAuthHostTransportMessage(message)
            val previous = hostViewerIdentities[requestId]
            parsed.paymentVoucher?.let { voucher ->
                // Untrusted identity/channel hint, verified independently by the billing
                // engine and readChannel. Never configure the singleton payment channel.
                if (requestId == activeRequestId) return
                val voucherAddress = voucher.viewerAddress?.takeIf { it.isNotBlank() }
                val voucherKey = voucher.viewerPublicKey?.takeIf { it.isNotEmpty() }
                if (previous != null && voucherAddress != null && previous.address != voucherAddress) return
                if (previous?.signerKey != null && voucherKey != null && !previous.signerKey.contentEquals(voucherKey)) return
                val address = previous?.address ?: voucherAddress ?: return
                val key = previous?.signerKey ?: voucherKey
                val channelId = voucher.channelId?.takeIf { it.size == 32 } ?: previous?.channelId
                updateHostViewerIdentity(requestId, address, key, channelId)
                return
            }
            // Credential identity and segment hello are the identity-bearing messages.
            // In particular, do not interpret vouchers as instructions to update global escrow.
            if (parsed.viewerHello == null && parsed.type != "credential" && parsed.type != null) return
            if (parsed.reference != null) return
            val address = (
                if (parsed.viewerHello != null) envelope["viewer"] else envelope["address"]
            )?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() } ?: return
            val incomingKey = (
                parsed.viewerHello?.viewerPublicKey
                    ?: if (parsed.viewerHello == null) envelope["publicKey"]?.jsonPrimitive?.content?.decodeLiquidAuthBase64OrNull() else null
            )?.takeIf { it.isNotEmpty() }
            val signerKey = incomingKey ?: previous?.takeIf { it.address == address }?.signerKey
            // The payment DC and credential DC both arrive through this keyed handler.
            // A hello can advertise the actual vault without a voucher, receipt or session ID.
            val channelId = parsed.viewerHello?.channelId?.takeIf { it.size == 32 }
                ?: previous?.channelId
            updateHostViewerIdentity(requestId, address, signerKey, channelId)
        }.onFailure { Napier.w("$TAG: ignored malformed viewer identity: $requestId") }
    }

    private fun updateHostViewerIdentity(requestId: String, address: String, signerKey: ByteArray?, channelId: ByteArray?) {
        val previous = hostViewerIdentities[requestId]
        // Lock identity across all metadata transports for this invitation. A channel hint
        // remains untrusted: readChannel validates its on-chain parties and signer.
        if (previous != null && previous.address != address) return
        if (previous?.signerKey != null && !previous.signerKey.contentEquals(signerKey)) return
        if (previous != null && previous.address == address &&
            previous.signerKey.contentEquals(signerKey) && previous.channelId.contentEquals(channelId)
        ) return
        hostViewerBalanceJobs.remove(requestId)?.cancel()
        hostViewerIdentities[requestId] = HostViewerIdentity(address, signerKey?.copyOf(), channelId?.copyOf())
        val details = hostViewerDetails[requestId] ?: HostViewerDetails()
        publishHostViewerDetails(
            requestId,
            details.copy(
                viewerAddress = address,
                remainingBalanceMicroUsdc = null,
                lastSettledMicroUsdc = null,
                progressBalanceMicroUsdc = null,
                totalDepositMicroUsdc = null,
            ),
        )
        startHostViewerBalancePolling(requestId)
        additionalHostViewers[requestId]?.let { peer ->
            updateAdditionalViewerConfig(requestId, peer)
            ensureAdditionalViewerBilling(requestId, peer)
        }
    }

    private fun publishHostViewerDetails(requestId: String, details: HostViewerDetails) {
        if (!meshHostingEnabled || requestId !in connectedHostViewers || requestId !in hostInvitations) return
        hostViewerDetails[requestId] = details
        viewModel?.updateMeshViewerDetails(requestId, details)
    }

    private fun startHostViewerBalancePolling(requestId: String) {
        // Primary financial display continues to use its existing block-consumption flow.
        if (!meshHostingEnabled || requestId == activeRequestId || requestId !in connectedHostViewers) return
        val identity = hostViewerIdentities[requestId] ?: return
        val signerKey = identity.signerKey ?: return
        val channelId = identity.channelId
        val generation = hostGeneration
        hostViewerBalanceJobs[requestId] = scope.launch(Dispatchers.Main) {
            var previousCreator: String? = null
            var previousNetwork: String? = null
            var previousSalt: ByteArray? = null
            while (isActive && generation == hostGeneration &&
                requestId in connectedHostViewers && hostViewerIdentities[requestId] === identity
            ) {
                val peerConfig = additionalHostViewers[requestId]?.config
                val creator = (peerConfig?.gating?.payTo ?: activePaymentRecipient)?.takeIf { it.isNotBlank() }
                val network = (peerConfig?.gating?.network ?: activePaymentNetwork)?.takeIf { it.isNotBlank() }
                val salt = if (channelId == null) EscrowSessionVaultHybridManagerClient.defaultSalt?.copyOf() else null
                if (creator != previousCreator || network != previousNetwork || !salt.contentEquals(previousSalt)) {
                    val details = hostViewerDetails[requestId] ?: HostViewerDetails(viewerAddress = identity.address)
                    publishHostViewerDetails(
                        requestId,
                        details.copy(
                            remainingBalanceMicroUsdc = null,
                            lastSettledMicroUsdc = null,
                            progressBalanceMicroUsdc = null,
                            totalDepositMicroUsdc = null,
                        ),
                    )
                    previousCreator = creator
                    previousNetwork = network
                    previousSalt = salt
                }
                if (creator != null && network != null && (channelId != null || salt != null)) {
                    try {
                        val snapshot = withTimeoutOrNull(10_000L) {
                            val result = if (channelId != null) {
                                HostViewerVaultReader.readChannel(
                                    channelId = channelId.copyOf(),
                                    viewerAddress = identity.address,
                                    creatorAddress = creator,
                                    authorizedSignerPublicKey = signerKey.copyOf(),
                                    network = network,
                                )
                            } else {
                                HostViewerVaultReader.read(
                                    viewerAddress = identity.address,
                                    creatorAddress = creator,
                                    authorizedSignerPublicKey = signerKey.copyOf(),
                                    network = network,
                                    salt = checkNotNull(salt),
                                )
                            }
                            result.getOrNull()
                        }
                        // A suspended read may outlive removal, stop, identity/key replacement,
                        // or a host/network/salt change. Never publish it into another vault.
                        if (!isActive || generation != hostGeneration ||
                            requestId !in connectedHostViewers ||
                            hostViewerIdentities[requestId] !== identity
                        ) return@launch
                        val currentConfig = additionalHostViewers[requestId]?.config
                        if (snapshot != null &&
                            creator == (currentConfig?.gating?.payTo ?: activePaymentRecipient) &&
                            network == (currentConfig?.gating?.network ?: activePaymentNetwork) &&
                            (channelId != null || salt.contentEquals(EscrowSessionVaultHybridManagerClient.defaultSalt))
                        ) {
                            val details = hostViewerDetails[requestId] ?: HostViewerDetails(viewerAddress = identity.address)
                            publishHostViewerDetails(
                                requestId,
                                details.copy(
                                    remainingBalanceMicroUsdc = snapshot.remainingBalanceMicroUsdc,
                                    lastSettledMicroUsdc = snapshot.lastSettledMicroUsdc,
                                    progressBalanceMicroUsdc = snapshot.progressBalanceMicroUsdc,
                                    totalDepositMicroUsdc = snapshot.totalDepositMicroUsdc,
                                ),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // An unavailable read is not an empty vault: keep unknown/last-known.
                        Napier.w("$TAG: extra-viewer vault read unavailable: $requestId")
                    }
                }
                delay(3_000L)
            }
        }
    }

    /** Swift delivers selected-pair stats for this invitation on the main thread. */
    fun notifyBroadcastViewerConnectionType(requestId: String, type: String) {
        if (requestId !in connectedHostViewers || requestId !in hostInvitations) return
        publishHostViewerDetails(
            requestId,
            (hostViewerDetails[requestId] ?: HostViewerDetails()).copy(connectionType = parseIceConnectionType(type)),
        )
    }

    fun notifyLegacyBroadcastMessageReceived(message: String) {
        if (meshHostingEnabled) {
            println("$TAG: ignored unkeyed mesh message")
            return
        }
        if (activeRequestId != null) notifyHostMessageReceived(message)
    }

    private fun createPrimaryPaymentDataChannel(): CallbackRtcDataChannel {
        if (!meshHostingEnabled) return platformServices.createHostPaymentDataChannel()
        val requestId = activeRequestId
        val generation = hostGeneration
        return CallbackRtcDataChannel(
            sendMessageProvider = {
                if (generation == hostGeneration && requestId in connectedHostViewers) {
                    hostPaymentSenders[requestId]
                } else {
                    null
                }
            },
            logTag = "IOSPrimaryPaymentDc",
        )
    }

    private fun refreshAdditionalViewerInfo() {
        connectedHostViewers.toList().filter { it != activeRequestId }.forEach(::sendAdditionalViewerInfo)
    }

    private fun ensureAdditionalViewerCreator(requestId: String, peer: AdditionalHostViewer) {
        if (peer.creator != null || requestId !in connectedHostViewers || requestId !in hostPaymentSenders) return
        val recipient = activePaymentRecipient?.takeIf { it.isNotBlank() } ?: return
        val network = activePaymentNetwork ?: return
        val identity = hostViewerIdentities[requestId]
        val cost = if (isPaidStreamingEnabled) activePaymentAmount ?: "0" else "0"
        val config = ServerConfig(
            sessionId = "mesh-free-$requestId",
            gating = (activeGatingConfig ?: GatingConfig(
                mode = GatingMode.PARTIAL_TIME,
                amount = cost,
                asset = "USDC",
                network = network,
                payTo = recipient,
            )).copy(amount = cost, network = network, payTo = recipient),
            viewerAddress = identity?.address,
            viewerAuthorizedSignerPublicKey = identity?.signerKey?.copyOf(),
            skipPaymentRequestWhenSessionFunded = true,
            vaultOnlyBilling = true,
        )
        val generation = hostGeneration
        val channel = CallbackRtcDataChannel(
            sendMessageProvider = {
                if (generation == hostGeneration && additionalHostViewers[requestId] === peer &&
                    requestId in connectedHostViewers
                ) hostPaymentSenders[requestId] else null
            },
            logTag = "IOSViewerPaymentDc",
        )
        val creator = LiquidStreamCreator(
            dataChannel = channel,
            // Peer billing must not gate shared capture.
            rtpSenders = emptyList(),
            mppServerConfig = MppServerConfig(
                network = network,
                recipient = recipient,
                secretKey = iosBroadcastMppSecretKey,
            ),
            serverConfig = config,
            getRemainingSessionVaultBalanceUseCase = getRemainingBalanceUseCase,
        )
        peer.config = config
        peer.dataChannel = channel
        peer.creator = creator
        creator.onChatMessageReceived = { message ->
            if (additionalHostViewers[requestId] === peer) {
                viewModel?.onChatMessageReceived(message)
                relayViewerChat(message, creator)
            }
        }
        creator.rtcServer.onError = { error -> Napier.w("$TAG: extra-viewer protocol error ($requestId): $error") }
        creator.start()
        ensureAdditionalViewerBilling(requestId, peer)
        sendAdditionalViewerMetadata(requestId, peer)
        channel.notifyOpen()
        val pending = peer.pendingMessages.toList()
        peer.pendingMessages.clear()
        pending.forEach { deliverAdditionalViewerMessage(requestId, peer, it) }
    }

    private fun updateAdditionalViewerConfig(requestId: String, peer: AdditionalHostViewer) {
        val previous = peer.config ?: return
        val identity = hostViewerIdentities[requestId]
        val cost = if (isPaidStreamingEnabled) activePaymentAmount ?: "0" else "0"
        val config = previous.copy(
            gating = previous.gating.copy(amount = cost),
            viewerAddress = identity?.address,
            viewerAuthorizedSignerPublicKey = identity?.signerKey?.copyOf(),
        )
        peer.config = config
        peer.creator?.updateConfig(config)
    }

    private fun ensureAdditionalViewerBilling(requestId: String, peer: AdditionalHostViewer) {
        if (peer.billing != null || additionalHostViewers[requestId] !== peer) return
        val creator = peer.creator ?: return
        val config = peer.config ?: return
        val identity = hostViewerIdentities[requestId] ?: return
        val signer = identity.signerKey?.takeIf { it.isNotEmpty() } ?: return
        peer.billing = ViewerVaultBillingSession(
            scope = hostBillingScope,
            sessionId = creator.sessionId,
            viewerAddress = identity.address,
            creatorAddress = config.gating.payTo,
            network = config.gating.network,
            signerPublicKey = signer.copyOf(),
            buildCreatorWalletSigner = { mppWalletSignerUseCase(it) },
            onSnapshot = { snapshot ->
                if (additionalHostViewers[requestId] === peer) {
                    val details = hostViewerDetails[requestId] ?: HostViewerDetails(viewerAddress = identity.address)
                    publishHostViewerDetails(
                        requestId,
                        details.copy(
                            remainingBalanceMicroUsdc = snapshot.remainingBalanceMicroUsdc,
                            lastSettledMicroUsdc = snapshot.lastSettledMicroUsdc,
                            progressBalanceMicroUsdc = snapshot.progressBalanceMicroUsdc,
                            totalDepositMicroUsdc = snapshot.totalDepositMicroUsdc,
                        ),
                    )
                }
            },
            onError = { error -> Napier.w("$TAG: extra-viewer billing error ($requestId): ${error.message}") },
            payoutFrequencyBlocks = payoutFrequencyBlocks,
        )
        startAdditionalViewerBlocks()
        viewModel?.currentBlockNumber?.value?.let { peer.billing?.onBlock(it) }
    }

    private fun startAdditionalViewerBlocks() {
        val vm = viewModel ?: return
        vm.startRealtimeBlockNumberUpdates()
        if (additionalViewerBlockJob?.isActive == true) return
        additionalViewerBlockJob = hostBillingScope.launch {
            vm.currentBlockNumber.collect { block ->
                if (block != null) additionalHostViewers.values.toList().forEach { it.billing?.onBlock(block) }
            }
        }
    }

    private fun deliverAdditionalViewerMessage(requestId: String, peer: AdditionalHostViewer, message: String) {
        if (additionalHostViewers[requestId] !== peer) return
        val parsed = parseLiquidAuthHostTransportMessage(message)
        ensureAdditionalViewerBilling(requestId, peer)
        parsed.paymentVoucher?.let { voucher ->
            val billing = peer.billing ?: return@let
            val job = hostBillingScope.launch(start = CoroutineStart.UNDISPATCHED) {
                billing.acceptVoucher(voucher)
            }
            peer.voucherJobs.add(job)
            job.invokeOnCompletion { hostBillingScope.launch { peer.voucherJobs.remove(job) } }
        }
        if (parsed.type != null) peer.dataChannel?.notifyMessage(message)
    }

    private fun closeAdditionalViewer(requestId: String, reason: String) {
        val peer = additionalHostViewers.remove(requestId) ?: return
        peer.pendingMessages.clear()
        peer.creator?.terminate(reason)
        peer.dataChannel?.notifyClosed()
        val intake = peer.voucherJobs.toList()
        val billing = peer.billing
        hostBillingScope.launch {
            try {
                withTimeoutOrNull(30_000L) { intake.joinAll() }
            } finally {
                intake.forEach { it.cancel() }
                billing?.close()
            }
        }
        if (additionalHostViewers.isEmpty()) {
            additionalViewerBlockJob?.cancel()
            additionalViewerBlockJob = null
        }
    }

    private fun sendAdditionalViewerInfo(requestId: String) {
        if (!meshHostingEnabled || requestId == activeRequestId || requestId !in connectedHostViewers) return
        val peer = additionalHostViewers.getOrPut(requestId) { AdditionalHostViewer() }
        val alreadyStarted = peer.creator != null
        ensureAdditionalViewerCreator(requestId, peer)
        // Creation sends bootstrap exactly once, before opening the protocol observer.
        if (!alreadyStarted && peer.creator != null) return
        updateAdditionalViewerConfig(requestId, peer)
        ensureAdditionalViewerBilling(requestId, peer)
        sendAdditionalViewerMetadata(requestId, peer)
    }

    private fun sendAdditionalViewerMetadata(requestId: String, peer: AdditionalHostViewer) {
        val sender = hostPaymentSenders[requestId] ?: return
        val config = peer.config ?: return
        val recipient = config.gating.payTo
        val sessionId = peer.creator?.sessionId ?: return
        val cost = config.gating.amount.toLongOrNull() ?: 0L
        sender(
            buildJsonObject {
                put("reference", "liquid:stream:info")
                put("hostAddress", recipient)
                put("sessionId", sessionId)
            }.toString(),
        )
        sender(
            buildJsonObject {
                put("type", DCMessageType.STREAM_COST_UPDATE.value)
                put("sessionId", sessionId)
                put("payload", buildJsonObject { put("costMicroUsdc", cost) })
            }.toString(),
        )
    }

    @Suppress("unused")
    fun notifyMessageReceived(message: String) {
        if (activeIOSViewerConnectionManager === this || answerViewModel != null) {
            notifyViewerMessageReceived(message)
            return
        }
        notifyLegacyBroadcastMessageReceived(message)
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
        val viewModel = answerViewModel
        if (viewModel != null) {
            // Shared transport currently ignores stream-info and chat envelopes; accept these
            // explicitly for native mesh viewers without opening a second payment session.
            if (ref == "liquid:stream:info") {
                viewModel.applyViewerSharedMessageState(message)
                return
            }
            if (parseLiquidAuthHostTransportMessage(message).type == DCMessageType.CHAT_MESSAGE.value) {
                deliverViewerPaymentMessage(message)
                return
            }
            viewModel.handleViewerTransportMessage(
                message = message,
                onPongRequested = { sendViewerMessage("""{"reference":"pong"}""") },
                onPaymentMessage = { paymentMessage -> deliverViewerPaymentMessage(paymentMessage) },
                onHostDiscovered = { host ->
                    if (!host.isNullOrBlank() && _hostAddress.value != host) setViewerHostAddress(host)
                    startViewerOnChainRefreshIfReady()
                    maybeSetupViewerPaymentRail()
                },
            )
        } else {
            println("$TAG: VIEWER_MSG_BUFFERED (AnswerViewModel not yet attached) preview=${message.take(120)}")
            val parsed = parseLiquidAuthHostTransportMessage(message)
            parsed.address?.let { if (it.isNotBlank()) setViewerHostAddress(it) }
            deliverViewerPaymentMessage(message)
        }
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
            streamCreatorDataChannel?.notifyMessage(message)
            return
        }

        // Only call onClientConnected for connection-establishment messages, NOT for protocol
        // messages that have a `reference` field.
        // Calling it for every such message causes repeated spurious ViewModel callbacks.
        if (!meshHostingEnabled && parsed.reference == null) {
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
        EscrowSessionVaultHybridManagerClient.hostAddress = address
        maybeSetupViewerPaymentRail()
    }

    fun onPaymentDataChannelReady() {
        println("$TAG: onPaymentDataChannelReady -> opening viewer payment rail")
        answerViewModel?.openViewerPaymentRail()
        flushPendingViewerPaymentMessages()
    }

    private fun maybeSetupViewerPaymentRail() {
        val viewModel = answerViewModel ?: return
        val viewer = _viewerAddress.value.orEmpty()
        val host = _hostAddress.value
        if (viewer.isBlank()) return
        val setupKey = viewer
        if (viewerPaymentRailSetupKey == setupKey) return
        viewerPaymentRailSetupKey = setupKey
        scope.launch {
            val configured =
                viewModel.setupViewerPaymentRail(
                    viewerAddress = viewer,
                    hostAddress = host,
                    scope = scope,
                )
            if (!configured) {
                viewerPaymentRailSetupKey = null
            } else if (iosViewerPaymentDCSendMessageHandler != null) {
                viewModel.openViewerPaymentRail()
                flushPendingViewerPaymentMessages()
            } else {
                println("$TAG: setupViewerPaymentRail complete — waiting for iosViewerPaymentDCSendMessageHandler")
            }
        }
    }

    private fun deliverViewerPaymentMessage(message: String): Boolean {
        val delivered = answerViewModel?.handlePlatformPaymentMessage(message) == true
        if (!delivered) {
            pendingViewerPaymentMessages.add(message)
            println(
                "$TAG: viewer payment rail not ready — buffered payment message " +
                    "(pending=${pendingViewerPaymentMessages.size}) preview=${message.take(120)}",
            )
        }
        return delivered
    }

    private fun flushPendingViewerPaymentMessages() {
        if (pendingViewerPaymentMessages.isEmpty()) return
        val viewModel = answerViewModel ?: return
        val buffered = pendingViewerPaymentMessages.toList()
        pendingViewerPaymentMessages.clear()
        buffered.forEach { message ->
            val delivered = viewModel.handlePlatformPaymentMessage(message)
            Napier.d(
                "$TAG: replayed buffered viewer payment message delivered=$delivered " +
                    "preview=${message.take(120)}",
            )
        }
    }

    private fun startViewerOnChainRefreshIfReady() {
        val viewModel = answerViewModel ?: return
        val viewer = _viewerAddress.value
        val host = _hostAddress.value
        if (viewer?.isNotBlank() == true && host.isNotBlank()) {
            viewModel.startViewerOnChainRefresh(viewer)
        }
    }

    private fun tryCaptureViewerAddressFromMessage(msg: String) {
        runCatching {
            val parsed = parseLiquidAuthHostTransportMessage(msg)

            parsed.viewerHello?.let { hello ->
                val helloViewer = hello.viewerAddress

                if (!helloViewer.isNullOrBlank() && helloViewer != activeViewerAddressForVault) {
                    activeViewerAddressForVault = helloViewer
                    Napier.d("$TAG: [VIEWER_HELLO_ADDR] viewer=$helloViewer")
                }
                if (!helloViewer.isNullOrBlank()) viewerReady.value = true

                val signerKey = hello.viewerPublicKey
                if (signerKey != null) {
                    activeViewerAuthorizedSignerKey = signerKey
                    Napier.d(
                        "$TAG: [VIEWER_HELLO_KEY] viewer=$helloViewer " +
                            "keyLen=${signerKey.size} session=$activePaymentSessionId",
                    )
                    updateCreatorViewerSignerConfig(helloViewer, signerKey)
                } else {
                    Napier.d(
                        "$TAG: [VIEWER_HELLO_NO_KEY] viewer=$helloViewer — " +
                            "viewerPublicKey absent. Balance polling will wait.",
                    )
                }

                if (!helloViewer.isNullOrBlank() && activeViewerAuthorizedSignerKey != null) {
                    val sessionForPoll = activePaymentSessionId ?: ""
                    println("$TAG: [VIEWER_HELLO] starting balance polling viewer=$helloViewer session=$sessionForPoll")
                    startBlockConsumption(sessionForPoll)
                }
            }

            parsed.paymentVoucher?.let { voucher ->
                val signature = voucher.signatureBase64
                val claimedAmount = voucher.totalAmountClaimedMicroUsdc
                val voucherSessionId = voucher.sessionId
                val voucherViewer = voucher.viewerAddress
                val voucherViewerPublicKey = voucher.viewerPublicKeyBase64
                voucher.channelId?.let { decodedChannelId ->
                    EscrowSessionVaultHybridManagerClient.channelId = decodedChannelId
                    Napier.d("$TAG: [VOUCHER_CHANNEL_ID_CAPTURED] len=${decodedChannelId.size}")
                }

                if (signature == null ||
                    claimedAmount == null ||
                    voucherSessionId == null ||
                    voucherViewer == null ||
                    voucherViewerPublicKey == null
                ) {
                    Napier.d(
                        "$TAG: [VOUCHER_SKIP] reason=invalid_payload " +
                            "session=${voucher.sessionId} claimedAmount=$claimedAmount",
                    )
                } else {
                    val activeSession = activePaymentSessionId
                    if (activeSession != null && voucherSessionId != activeSession) {
                        Napier.d(
                            "$TAG: [VOUCHER_SKIP] reason=session_mismatch " +
                                "voucherSession=$voucherSessionId activeSession=$activeSession",
                        )
                    } else {
                        val previousClaimedAmount =
                            activeCreatorVoucherClaimSnapshot?.totalAmountClaimedMicroUsdc
                        if (previousClaimedAmount != null && claimedAmount < previousClaimedAmount) {
                            Napier.d(
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
                                Napier.d("$TAG: [VOUCHER_VIEWER_ADDR_UPDATE] viewer=$voucherViewer")
                            }
                            if (activeViewerAuthorizedSignerKey == null) {
                                val voucherSignerKey = voucher.viewerPublicKey
                                if (voucherSignerKey != null) {
                                    activeViewerAuthorizedSignerKey = voucherSignerKey
                                    Napier.d(
                                        "$TAG: [VOUCHER_SIGNER_KEY_CAPTURED] viewer=$voucherViewer " +
                                            "keyLen=${voucherSignerKey.size}",
                                    )
                                }
                            }
                            Napier.d(
                                "$TAG: [VOUCHER_CAPTURED] session=$voucherSessionId " +
                                    "sigLen=${signature.length} claimedMicroUsdc=$claimedAmount",
                            )
                            if (isPaidStreamingEnabled || claimedAmount > 0L) {
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
                                    Napier.d("$TAG: [VOUCHER_VIEWER_ADDR_UPDATE] viewer=$voucherViewer")
                                }
                                if (activeViewerAuthorizedSignerKey == null) {
                                    val voucherSignerKey = voucher.viewerPublicKey
                                    if (voucherSignerKey != null) {
                                        activeViewerAuthorizedSignerKey = voucherSignerKey
                                        Napier.d(
                                            "$TAG: [VOUCHER_SIGNER_KEY_CAPTURED] viewer=$voucherViewer " +
                                                "keyLen=${voucherSignerKey.size}",
                                        )
                                    }
                                }
                                startBlockConsumption(voucherSessionId)
                                blockConsumptionManager.triggerSettlementFromViewerVoucher(
                                    voucherSessionId,
                                    force = true,
                                )
                            } else {
                                println("$TAG: [VOUCHER_IGNORE] reason=free_mode session=$voucherSessionId")
                            }
                        }
                    }
                }
            }

            val candidate = parsed.address
            if (candidate != null && candidate != activeViewerAddressForVault) {
                activeViewerAddressForVault = candidate
                viewerReady.value = true
                println("$TAG: viewer address captured from message: $candidate")
            }
        }.onFailure { e ->
            Napier.e("$TAG: tryCaptureViewerAddressFromMessage error: $e")
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
        Napier.d("$TAG: starting connection-type polling")
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
