package com.michaeltchuang.walletsdk.ui.liquidStream

import com.michaeltchuang.walletsdk.ui.liquidAuth.service.AnswerPlatformServices
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.AnswerViewModel
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

var activeIOSViewerConnectionManager: IosLiquidStreamViewerConnectionManager? = null
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
 * vault.  The callback receives the balance in micro-USDC or null on failure.
 */
var iosViewerFetchBalanceHandler: (
    (
        viewerAddress: String,
        hostAddress: String,
        callback: (remainingMicroUsdc: Long?) -> Unit,
    ) -> Unit
)? = null

private const val TAG = "IOSLiquidStreamViewerCM"
private const val CONNECTION_TYPE_POLL_INTERVAL_MS = 1000L

class IosLiquidStreamViewerConnectionManager(
    private val platformServices: AnswerPlatformServices,
) {
    enum class ConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)

    @Suppress("unused")
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)

    @Suppress("unused")
    val connectionType: StateFlow<IceConnectionType> = _connectionType

    private var answerViewModel: AnswerViewModel? = null
    private var paymentRailSetupKey: String? = null

    private val _sessionId = MutableStateFlow("")

    @Suppress("unused")
    val sessionId: StateFlow<String> = _sessionId

    private val _viewerAddress = MutableStateFlow("")

    @Suppress("unused")
    val viewerAddress: StateFlow<String> = _viewerAddress

    private val _hostAddress = MutableStateFlow("")

    @Suppress("unused")
    val hostAddress: StateFlow<String> = _hostAddress

    private var activeOrigin: String? = null
    private var activeRequestId: String? = null
    private var connectionTypePollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)


    // ── Connection lifecycle ─────────────────────────────────────────────────

    fun connect(
        origin: String,
        requestId: String,
        viewerAddress: String = "",
    ) {
        activeIOSViewerConnectionManager = this
        activeOrigin = origin
        activeRequestId = requestId
        if (viewerAddress.isNotBlank()) setViewerAddress(viewerAddress)
        _connectionState.value = ConnectionState.CONNECTING
        println("$TAG: connect() origin=$origin requestId=$requestId")
        if (!platformServices.startViewerConnection(origin, requestId)) {
            println("$TAG: ⚠️ iosViewerStartHandler not set")
        }
    }

    fun disconnect() {
        println("$TAG: disconnect()")
        stopConnectionTypePolling()
        answerViewModel?.stopMppPaymentViewer()
        platformServices.stopViewerConnection()
        activeOrigin = null
        activeRequestId = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectionType.value = IceConnectionType.UNKNOWN
        _sessionId.value = ""
        answerViewModel?.clearViewerConnectionState()
        answerViewModel?.setViewerSessionVaultProgress(0L, 0L)
        answerViewModel?.clearViewerConsent()
        paymentRailSetupKey = null
        answerViewModel?.closeViewerPaymentRail()
    }

    @Suppress("unused")
    fun isConnected(): Boolean = platformServices.isViewerConnected()

    fun sendMessage(message: String) {
        if (!platformServices.sendViewerMessage(message)) {
            println("$TAG: sendMessage skipped — handler not set")
        }
    }

    fun attachAnswerViewModel(viewModel: AnswerViewModel?) {
        answerViewModel = viewModel
        maybeSetupViewerPaymentRail()
    }

    @Suppress("unused")
    fun notifyConnected() {
        println("$TAG: ✅ notifyConnected")
        _connectionState.value = ConnectionState.CONNECTED
        startConnectionTypePolling()
        // Send viewer hello message so the host knows who to charge.
        sendViewerHello()
        startViewerOnChainRefreshIfReady()
        maybeSetupViewerPaymentRail()
        // Open the DC bridge so SetupMppPaymentViewerUseCase / PaywalledRTCClient starts listening.
        answerViewModel?.openViewerPaymentRail()
    }

    @Suppress("unused")
    fun notifyDisconnected() {
        println("$TAG: notifyDisconnected")
        stopConnectionTypePolling()
        answerViewModel?.stopMppPaymentViewer()
        _connectionState.value = ConnectionState.DISCONNECTED
        answerViewModel?.clearVideoFrame()
        answerViewModel?.closeViewerPaymentRail()
    }

    @Suppress("unused")
    fun notifyMessageReceived(message: String) {
        // Skip logging for high-frequency video-frame messages to avoid log spam.
        val ref = message.jsonOptString("reference") ?: "(no-ref)"
        if (ref != "liquid:video:frame") {
            println(
                "$TAG: 📩 MSG_RECV ref=$ref len=${message.length} " +
                    "viewer='${_viewerAddress.value}' host='${_hostAddress.value}' " +
                    "preview=${message.take(160)}",
            )
        }
        answerViewModel?.handleViewerTransportMessage(
            message = message,
            onPongRequested = { sendMessage("""{"reference":"pong"}""") },
            onLegacyPaymentRequest = ::handlePaymentRequest,
            onPaymentMessage = { paymentMessage -> answerViewModel?.handlePlatformPaymentMessage(paymentMessage) == true },
            onHostDiscovered = { host ->
                if (!host.isNullOrBlank() && _hostAddress.value != host) setHostAddress(host)
                startViewerOnChainRefreshIfReady()
                maybeSetupViewerPaymentRail()
            },
        ) ?: println("$TAG: ⚠️ message dropped — AnswerViewModel not attached")
    }

    @Suppress("unused")
    fun notifyConnectionTypeChanged(typeString: String) {
        val type = parseConnectionType(typeString)
        if (_connectionType.value != type) {
            _connectionType.value = type
            answerViewModel?.setConnectionType(type)
            println("$TAG: 🌐 connection type → ${type.displayName()}")
        }
    }

    // ── Balance helpers ───────────────────────────────────────────────────────

    /** Called by Swift / Compose to push a freshly-fetched on-chain balance. */
    @Suppress("unused")
    fun updateRemainingBalance(microUsdc: Long) {
        answerViewModel?.setViewerSessionVaultBalance(microUsdc)
        println("$TAG: 💰 balance updated → ${microUsdc / 1_000_000.0} USDC")
    }

    private fun setHostAddress(address: String) {
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
        if (paymentRailSetupKey == setupKey) return
        paymentRailSetupKey = setupKey
        scope.launch {
            val configured = viewModel.setupViewerPaymentRail(
                viewerAddress = viewer,
                hostAddress = host,
                scope = scope,
            )
            if (!configured) paymentRailSetupKey = null
        }
    }

    /**
     * Sets the viewer's Algorand address on the manager so that [sendViewerHello]
     * and [handlePaymentRequest] have the correct address even before the host address
     * is known.  Called from App.ios.kt before [notifyConnected].
     */
    @Suppress("unused")
    fun setViewerAddress(address: String) {
        if (address.isBlank()) return
        _viewerAddress.value = address
        answerViewModel?.setViewerAddress(address)
        println("$TAG: 🏠 VIEWER_ADDR_SET addr=$address")
    }

    /**
     * Persists viewer/host addresses and starts the common viewer refresh path when ready.
     *
     * IMPORTANT: always stores the viewer address so [sendViewerHello] can include it.
     */
    @Suppress("unused")
    fun startBalancePollingSafe(
        viewerAddress: String,
        hostAddress: String,
    ) {
        // Always persist the viewer address so sendViewerHello works at connect time.
        if (viewerAddress.isNotBlank()) setViewerAddress(viewerAddress)
        // Persist host address too if known.
        if (hostAddress.isNotBlank()) setHostAddress(hostAddress)

        println(
            "$TAG: startBalancePollingSafe viewer=$viewerAddress host=$hostAddress " +
                "_viewerAddress='${_viewerAddress.value}' _hostAddress='${_hostAddress.value}'",
        )

        if (viewerAddress.isBlank() || hostAddress.isBlank()) {
            println("$TAG: startBalancePollingSafe — polling deferred (host address not yet known)")
            return
        }
        startViewerOnChainRefreshIfReady()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Sends a `liquid:viewer:hello` message to the host so the host knows the viewer's
     * Algorand address and Ed25519 public key for on-chain session-vault balance lookups.
     */
    private fun sendViewerHello() {
        val viewer = _viewerAddress.value
        println(
            "$TAG: 👋 HELLO_ATTEMPT viewer='$viewer' " +
                "keyProviderSet=${platformServices.hasViewerPublicKeyProvider()} ",
        )
        if (viewer.isBlank()) {
            println(
                "$TAG: ⚠️ HELLO_SKIP — viewerAddress is blank! " +
                    "Call setViewerAddress() or startBalancePollingSafe() BEFORE notifyConnected()",
            )
            return
        }
        val publicKeyBase64 = platformServices.getViewerPublicKey(viewer)
        val keyField = if (!publicKeyBase64.isNullOrBlank()) ""","viewerPublicKey":"$publicKeyBase64"""" else ""
        val hello = """{"reference":"liquid:viewer:hello","viewer":"$viewer"$keyField}"""
        println("$TAG: 👋 HELLO_SEND viewer=$viewer keyPresent=${!publicKeyBase64.isNullOrBlank()}")
        sendMessage(hello)
    }

    private fun handlePaymentRequest(message: String) {
        println("$TAG: 💳 PAYMENT_REQUEST_RECEIVED (legacy iOS host) preview=${message.take(160)}")
    }

    private fun startViewerOnChainRefreshIfReady() {
        val viewModel = answerViewModel ?: return
        val viewer = _viewerAddress.value
        val host = _hostAddress.value
        if (viewer.isNotBlank() && host.isNotBlank()) {
            viewModel.startViewerOnChainRefresh(viewer, host)
        }
    }

    private fun startConnectionTypePolling() {
        connectionTypePollingJob?.cancel()
        connectionTypePollingJob =
            CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    platformServices.detectViewerConnectionType()?.let { notifyConnectionTypeChanged(it) }
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

}
