package com.michaeltchuang.walletsdk.ui.liquidStream

import com.michaeltchuang.walletsdk.core.railmpp.MppClientConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.BudgetCap
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentApproval
import com.michaeltchuang.walletsdk.core.railmpp.core.ConsentTerms
import com.michaeltchuang.walletsdk.core.railmpp.core.DCMessageType
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.utils.RailMppConstants
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.model.displayName
import com.michaeltchuang.walletsdk.ui.liquidStream.domain.usecases.SetupMppPaymentViewerUseCase
import kotlinx.coroutines.CompletableDeferred
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
 * Called by Swift when the viewer wants to deposit into the session vault.
 *
 * Swift must call this with the signed `MppWalletSigner` available, perform the deposit, and
 * then invoke the callback with the resulting on-chain remaining balance in micro-USDC (or
 * null on failure).
 */
var iosViewerDepositHandler: (
    (
        viewerAddress: String,
        hostAddress: String,
        depositMicroUsdc: Long,
        callback: (remainingMicroUsdc: Long?) -> Unit,
    ) -> Unit
)? = null

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
private const val BALANCE_POLL_INTERVAL_MS = 5_000L

class IosLiquidStreamViewerConnectionManager(
    private val getRemainingBalanceUseCase: GetRemainingSessionVaultBalanceUseCase,
    private val setupMppPaymentViewerUseCase: SetupMppPaymentViewerUseCase,
) {
    data class VideoFrame(
        val id: String,
        val timestamp: Long,
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val format: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VideoFrame) return false
            return id == other.id &&
                timestamp == other.timestamp &&
                data.contentEquals(other.data) &&
                width == other.width &&
                height == other.height &&
                format == other.format
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + format.hashCode()
            return result
        }
    }

    enum class ConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)

    @Suppress("unused")
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)

    @Suppress("unused")
    val connectionType: StateFlow<IceConnectionType> = _connectionType

    private val _latestVideoFrame = MutableStateFlow<VideoFrame?>(null)

    @Suppress("unused")
    val latestVideoFrame: StateFlow<VideoFrame?> = _latestVideoFrame

    private val _sessionId = MutableStateFlow("")

    @Suppress("unused")
    val sessionId: StateFlow<String> = _sessionId

    private val _viewerAddress = MutableStateFlow("")

    @Suppress("unused")
    val viewerAddress: StateFlow<String> = _viewerAddress

    private val _hostAddress = MutableStateFlow("")

    @Suppress("unused")
    val hostAddress: StateFlow<String> = _hostAddress

    private val _remainingBalanceMicroUsdc = MutableStateFlow(0L)

    @Suppress("unused")
    val remainingBalanceMicroUsdc: StateFlow<Long> = _remainingBalanceMicroUsdc

    private val _progressBalanceMicroUsdc = MutableStateFlow(0L)

    @Suppress("unused")
    val progressBalanceMicroUsdc: StateFlow<Long> = _progressBalanceMicroUsdc

    // ── MPP Consent flow ──────────────────────────────────────────────────────
    private val _pendingMppConsent = MutableStateFlow<ConsentTerms?>(null)

    /** Observed by Compose UI to show the deposit consent dialog. */
    val pendingMppConsent: StateFlow<ConsentTerms?> = _pendingMppConsent

    private val _isPaymentProcessing = MutableStateFlow(false)

    /** True while a deposit transaction is in-flight. */
    val isPaymentProcessing: StateFlow<Boolean> = _isPaymentProcessing

    // iOS host path consent continuation (session vault deposit).
    private var pendingConsentContinuation: CompletableDeferred<ConsentApproval>? = null

    // Android host path consent continuation (MPP direct payment — no deposit needed).
    private var paymentConsentContinuation: CompletableDeferred<ConsentApproval>? = null

    private var viewerAuthorizedSignerPublicKey: ByteArray? = null

    // ── Payment transport bridge (business logic lives in SetupMppPaymentViewerUseCase) ────────

    private var paymentDataChannel: IosRtcDataChannel? = null
    private var pendingPaymentRailSetup: (() -> Unit)? = null

    private var activeOrigin: String? = null
    private var activeRequestId: String? = null
    private var connectionTypePollingJob: Job? = null
    private var balancePollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)


    @Suppress("unused")
    private var viewerAuthorizedSignerKey: ByteArray? = null

    // ── Connection lifecycle ─────────────────────────────────────────────────

    fun connect(
        origin: String,
        requestId: String,
        viewerAddress: String = "",
    ) {
        val handler = iosViewerStartHandler
        if (handler == null) {
            println("$TAG: ⚠️ iosViewerStartHandler not set")
            return
        }
        activeIOSViewerConnectionManager = this
        activeOrigin = origin
        activeRequestId = requestId
        if (viewerAddress.isNotBlank()) _viewerAddress.value = viewerAddress
        _connectionState.value = ConnectionState.CONNECTING
        println("$TAG: connect() origin=$origin requestId=$requestId")
        handler(origin, requestId)
    }

    fun disconnect() {
        println("$TAG: disconnect()")
        stopConnectionTypePolling()
        stopBalancePolling()
        iosViewerStopHandler?.invoke()
        activeOrigin = null
        activeRequestId = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _latestVideoFrame.value = null
        _connectionType.value = IceConnectionType.UNKNOWN
        _sessionId.value = ""
        _remainingBalanceMicroUsdc.value = 0L
        _progressBalanceMicroUsdc.value = 0L
        _pendingMppConsent.value = null
        pendingConsentContinuation?.cancel()
        pendingConsentContinuation = null
        paymentConsentContinuation?.cancel()
        paymentConsentContinuation = null
        viewerAuthorizedSignerPublicKey = null
        setupMppPaymentViewerUseCase.stop()
        paymentDataChannel?.notifyClosed()
        paymentDataChannel = null
        pendingPaymentRailSetup = null
    }

    @Suppress("unused")
    fun isConnected(): Boolean = iosViewerIsConnectedHandler?.invoke() ?: false

    fun sendMessage(message: String) {
        val handler =
            iosViewerSendMessageHandler ?: run {
                println("$TAG: sendMessage skipped — handler not set")
                return
            }
        handler(message)
    }

    @Suppress("unused")
    fun notifyConnected() {
        println("$TAG: ✅ notifyConnected")
        _connectionState.value = ConnectionState.CONNECTED
        startConnectionTypePolling()
        // Send viewer hello message so the host knows who to charge.
        sendViewerHello()
        // Start balance polling if we already know viewer + host addresses
        maybeStartBalancePolling()
        runPendingPaymentRailSetupIfReady()
        // Open the DC bridge so SetupMppPaymentViewerUseCase / PaywalledRTCClient starts listening.
        paymentDataChannel?.notifyOpen()
    }

    @Suppress("unused")
    fun notifyDisconnected() {
        println("$TAG: notifyDisconnected")
        stopConnectionTypePolling()
        stopBalancePolling()
        _connectionState.value = ConnectionState.DISCONNECTED
        _latestVideoFrame.value = null
        paymentDataChannel?.notifyClosed()
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
        handleMessage(message)
    }

    @Suppress("unused")
    fun notifyConnectionTypeChanged(typeString: String) {
        val type = parseConnectionType(typeString)
        if (_connectionType.value != type) {
            _connectionType.value = type
            println("$TAG: 🌐 connection type → ${type.displayName()}")
        }
    }

    // ── MPP payment setup (matches Android: platform transport + common manager) ──────────────

    fun setupPaymentRail(
        mppClientConfig: MppClientConfig,
        authorizedSignerPublicKey: ByteArray? = null,
        signFido2Challenge: suspend (challenge: ByteArray, address: String) -> ByteArray?,
    ) {
        setupMppPaymentViewerUseCase.stop()
        paymentDataChannel?.notifyClosed()
        if (authorizedSignerPublicKey != null) {
            viewerAuthorizedSignerPublicKey = authorizedSignerPublicKey
        }

        val viewer = _viewerAddress.value
        val host = _hostAddress.value
        if (viewer.isBlank() || host.isBlank()) {
            pendingPaymentRailSetup = {
                setupPaymentRail(
                    mppClientConfig = mppClientConfig,
                    authorizedSignerPublicKey = authorizedSignerPublicKey,
                    signFido2Challenge = signFido2Challenge,
                )
            }
            println("$TAG: ⚠️ setupPaymentRail deferred — viewer='$viewer' host='$host'")
            return
        }
        pendingPaymentRailSetup = null

        val dataChannel =
            IosRtcDataChannel(sendMessageProvider = {
                iosViewerPaymentDCSendMessageHandler ?: iosViewerSendMessageHandler
            })
        paymentDataChannel = dataChannel

        setupMppPaymentViewerUseCase(
            SetupMppPaymentViewerUseCase.Params(
                dataChannel = dataChannel,
                viewerAddress = viewer,
                hostAddress = host,
                scope = scope,
                signer = mppClientConfig.signer,
                mppNetwork = mppClientConfig.network,
                requestMppConsent = ::requestMppConsent,
                setViewerSessionVaultProgress = ::updateSessionVaultProgress,
                signFido2Challenge = signFido2Challenge,
                sendMessage = ::sendMessage,
            ),
        )

        if (_connectionState.value == ConnectionState.CONNECTED) {
            dataChannel.notifyOpen()
        }

        println("$TAG: ✅ MPP payment rail set up through SetupMppPaymentViewerUseCase")
    }

    private fun runPendingPaymentRailSetupIfReady() {
        val setup = pendingPaymentRailSetup ?: return
        if (_viewerAddress.value.isBlank() || _hostAddress.value.isBlank()) return
        pendingPaymentRailSetup = null
        setup()
    }

    // ── Balance helpers ───────────────────────────────────────────────────────

    /** Called by Swift / Compose to push a freshly-fetched on-chain balance. */
    @Suppress("unused")
    fun updateRemainingBalance(microUsdc: Long) {
        updateSessionVaultProgress(microUsdc, maxOf(microUsdc, _progressBalanceMicroUsdc.value))
        println("$TAG: 💰 balance updated → ${microUsdc / 1_000_000.0} USDC")
    }

    private fun updateSessionVaultProgress(
        remainingBalanceMicroUsdc: Long,
        progressBalanceMicroUsdc: Long,
    ) {
        _remainingBalanceMicroUsdc.value = remainingBalanceMicroUsdc.coerceAtLeast(0L)
        _progressBalanceMicroUsdc.value = progressBalanceMicroUsdc.coerceAtLeast(0L)
    }

    private suspend fun requestMppConsent(terms: ConsentTerms): ConsentApproval {
        val deferred = CompletableDeferred<ConsentApproval>()
        paymentConsentContinuation = deferred
        _pendingMppConsent.value = terms
        println("$TAG: 🎭 payment consent dialog shown amount=${terms.amount} payTo=${terms.payTo}")
        return deferred.await()
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
        println("$TAG: 🏠 VIEWER_ADDR_SET addr=$address")
    }

    /**
     * Starts polling the on-chain session-vault balance every [BALANCE_POLL_INTERVAL_MS] ms.
     * Safe to call multiple times — stops any existing polling job first.
     *
     * IMPORTANT: always stores the viewer address even when the host address is not yet known
     * so that [sendViewerHello] (called from [notifyConnected]) can include the correct address.
     */
    @Suppress("unused")
    fun startBalancePollingSafe(
        viewerAddress: String,
        hostAddress: String,
    ) {
        // Always persist the viewer address so sendViewerHello works at connect time.
        if (viewerAddress.isNotBlank()) _viewerAddress.value = viewerAddress
        // Persist host address too if known.
        if (hostAddress.isNotBlank()) _hostAddress.value = hostAddress

        println(
            "$TAG: startBalancePollingSafe viewer=$viewerAddress host=$hostAddress " +
                "_viewerAddress='${_viewerAddress.value}' _hostAddress='${_hostAddress.value}'",
        )

        if (viewerAddress.isBlank() || hostAddress.isBlank()) {
            println("$TAG: startBalancePollingSafe — polling deferred (host address not yet known)")
            return
        }
        startBalancePolling(viewerAddress, hostAddress)
    }

    // ── MPP Consent API (called from Compose UI) ──────────────────────────────


    fun approveMppConsent(approval: ConsentApproval) {
        paymentConsentContinuation?.complete(approval)
    }

    /** Called by Compose UI when user taps "Cancel" on the consent dialog. */
    @Suppress("unused")
    fun rejectMppConsent() {
        println("$TAG: ❌ user rejected consent")
        val rejection = ConsentApproval(approved = false, autoPaySegments = false)
        // Cancel both possible pending continuations.
        paymentConsentContinuation?.complete(rejection)
        paymentConsentContinuation = null
        pendingConsentContinuation?.complete(rejection)
        pendingConsentContinuation = null
        _pendingMppConsent.value = null
        _isPaymentProcessing.value = false
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
                "keyProviderSet=${iosViewerPublicKeyProvider != null} " +
                "sendHandlerSet=${iosViewerSendMessageHandler != null}",
        )
        if (viewer.isBlank()) {
            println(
                "$TAG: ⚠️ HELLO_SKIP — viewerAddress is blank! " +
                    "Call setViewerAddress() or startBalancePollingSafe() BEFORE notifyConnected()",
            )
            return
        }
        val publicKeyBase64 = iosViewerPublicKeyProvider?.invoke(viewer)
        val keyField = if (!publicKeyBase64.isNullOrBlank()) ""","viewerPublicKey":"$publicKeyBase64"""" else ""
        val hello = """{"reference":"liquid:viewer:hello","viewer":"$viewer"$keyField}"""
        println("$TAG: 👋 HELLO_SEND viewer=$viewer keyPresent=${!publicKeyBase64.isNullOrBlank()}")
        sendMessage(hello)
    }

    private fun handleMessage(message: String) {
        when (val reference = message.jsonOptString("reference")) {
            "liquid:video:frame" -> handleVideoFrame(message)
            "liquid:payment:request" -> {
                // Legacy iOS host format (pre-PaywalledRTCServer).
                // Kept for backward compatibility with iOS hosts that haven't migrated yet.
                println("$TAG: 💳 PAYMENT_REQUEST_RECEIVED (legacy iOS host) viewer='${_viewerAddress.value}' host='${_hostAddress.value}'")
                handlePaymentRequest(message)
            }
            "ping" -> {
                println("$TAG: 🏓 ping — sending pong")
                sendMessage("""{"reference":"pong"}""")
            }
            null -> {
                // No "reference" field — this is a PaywalledRTCServer message (Android or iOS host).
                val msgType = message.jsonOptString("type")
                when (msgType) {
                    DCMessageType.SEGMENT_REQUEST,
                    DCMessageType.SEGMENT_ACCEPTED,
                    DCMessageType.SEGMENT_REJECTED,
                    DCMessageType.SESSION_TERMINATE,
                    -> {
                        // PaywalledRTCServer format — route through SetupMppPaymentViewerUseCase.
                        // Works for both Android hosts and iOS hosts with PaywalledRTCServer enabled.
                        if (msgType == DCMessageType.SEGMENT_REQUEST) {
                            // Capture host address from payTo inside payload if not yet known.
                            extractAndSetHostAddressFromSegmentRequest(message)
                            runPendingPaymentRailSetupIfReady()
                        }
                        val channel = paymentDataChannel
                        if (channel != null) {
                            println("$TAG: 📨 PAYWALLED_DC_MSG type=$msgType → SetupMppPaymentViewerUseCase")
                            channel.notifyMessage(message)
                        } else {
                            println("$TAG: ⚠️ PAYWALLED_DC_MSG type=$msgType — payment data channel not set up yet, dropping message")
                        }
                    }
                    else -> println("$TAG: 📨 MSG_NO_REF type=$msgType preview=${message.take(120)}")
                }
            }
            else -> println("$TAG: 📨 MSG_REF ref=$reference preview=${message.take(120)}")
        }
    }

    /**
     * Extracts `payTo` from a `segment:request` message (which lives inside the `payload`
     * object) and stores it as the host address so balance polling can start.
     */
    private fun extractAndSetHostAddressFromSegmentRequest(message: String) {
        val sessionId = message.jsonOptString("sessionId") ?: ""
        val payTo =
            message.jsonOptString("payTo") ?: run {
                val payloadStart = message.indexOf("\"payload\"")
                if (payloadStart >= 0) message.substring(payloadStart).jsonOptString("payTo") ?: "" else ""
            }
        if (payTo.isNotBlank() && _hostAddress.value != payTo) {
            _hostAddress.value = payTo
            println("$TAG: 💳 SEGMENT_REQUEST_HOST_SET host=$payTo")
            maybeStartBalancePolling()
        }
        if (sessionId.isNotBlank() && _sessionId.value != sessionId) _sessionId.value = sessionId
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun handleVideoFrame(message: String) {
        runCatching {
            val id = message.jsonOptString("id") ?: return@runCatching
            val timestamp = message.jsonOptLong("timestamp") ?: 0L
            val format = message.jsonOptString("format") ?: "jpeg"
            val width = message.jsonOptInt("width") ?: 640
            val height = message.jsonOptInt("height") ?: 480
            val base64Data = message.jsonOptString("data") ?: return@runCatching

            message.jsonOptString("hostAddress")?.let { addr ->
                if (addr.isNotBlank() && _hostAddress.value != addr) {
                    _hostAddress.value = addr
                    // Host address just became known — maybe start balance polling.
                    maybeStartBalancePolling()
                }
            }
            message.jsonOptString("sessionId")?.let { sid ->
                if (sid.isNotBlank() && _sessionId.value != sid) _sessionId.value = sid
            }

            val frameBytes = decodeBase64OrNull(base64Data) ?: return@runCatching

            _latestVideoFrame.value = VideoFrame(id, timestamp, frameBytes, width, height, format)
        }.onFailure { e ->
            println("$TAG: handleVideoFrame error: $e")
        }
    }

    private fun handlePaymentRequest(message: String) {
        runCatching {
            val sessionId =
                message.jsonOptString("id") ?: run {
                    println("$TAG: PAYMENT_REQUEST_NO_ID — missing 'id' field, raw=${message.take(200)}")
                    return@runCatching
                }
            val amount = message.jsonOptString("amount") ?: ""
            val payTo = message.jsonOptString("payTo") ?: ""
            val asset = message.jsonOptString("asset") ?: "USDC"

            println(
                "$TAG: 💰 PAYMENT_REQUEST_PARSE session=$sessionId amount=$amount payTo=$payTo " +
                    "currentViewer='${_viewerAddress.value}' currentHost='${_hostAddress.value}'",
            )

            if (_sessionId.value != sessionId) _sessionId.value = sessionId
            if (payTo.isNotBlank() && _hostAddress.value != payTo) {
                _hostAddress.value = payTo
                println("$TAG: 💰 PAYMENT_REQUEST_HOST_SET host=$payTo")
                maybeStartBalancePolling()
            }

            // Check existing on-chain balance first.  If there is already enough
            // balance in the vault, skip the consent dialog and start streaming.
            val viewer = _viewerAddress.value
            val host = _hostAddress.value

            println(
                "$TAG: 💰 PAYMENT_REQUEST_ADDRESSES viewer='$viewer' host='$host' " +
                    "depositHandlerSet=${iosViewerDepositHandler != null}",
            )

            if (viewer.isNotBlank() && host.isNotBlank()) {
                scope.launch {
                    val existing =
                        runCatching {
                            getRemainingBalanceUseCase(
                                GetRemainingSessionVaultBalanceUseCase.Params(
                                    viewerAddress = viewer,
                                    hostAddress = host,
                                    appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                    authorizedSignerPublicKey = viewerAuthorizedSignerPublicKey,
                                ),
                            ).getOrDefault(0L)
                        }.getOrDefault(0L)

                    if (existing > 0L) {
                        println(
                            "$TAG: 💰 existing vault balance=$existing — skipping consent dialog",
                        )
                        updateRemainingBalance(existing)
                        startBalancePolling(viewer, host)
                        // Signal that vault is already funded (no deposit needed).
                        pendingConsentContinuation?.complete(
                            ConsentApproval(
                                approved = true,
                                autoPaySegments = true,
                                budgetCap =
                                    BudgetCap(
                                        amount = existing.toString(),
                                        asset = "USDC",
                                    ),
                            ),
                        )
                        return@launch
                    }

                    // No existing balance — show the consent/deposit dialog.
                    val deferred = CompletableDeferred<ConsentApproval>()
                    pendingConsentContinuation = deferred
                    _pendingMppConsent.value =
                        ConsentTerms(
                            gatingMode = GatingMode.PARTIAL_TIME,
                            amount = amount.ifBlank { "1000000" },
                            asset = asset,
                            network = "algorand-testnet",
                            payTo = payTo,
                            segmentDuration = 3,
                        )
                    println("$TAG: 🎭 consent dialog shown for session=$sessionId")
                }
            } else {
                // Addresses not known yet — show generic consent dialog.
                val deferred = CompletableDeferred<ConsentApproval>()
                pendingConsentContinuation = deferred
                _pendingMppConsent.value =
                    ConsentTerms(
                        gatingMode = GatingMode.PARTIAL_TIME,
                        amount = amount.ifBlank { "1000000" },
                        asset = asset,
                        network = "algorand-testnet",
                        payTo = payTo,
                        segmentDuration = 3,
                    )
            }
        }.onFailure { e ->
            println("$TAG: ❌ handlePaymentRequest error: $e")
        }
    }

    /** Starts the on-chain balance polling loop (stops any existing loop first). */
    private fun startBalancePolling(
        viewerAddress: String,
        hostAddress: String,
    ) {
        if (viewerAddress.isBlank() || hostAddress.isBlank()) return
        stopBalancePolling()
        println("$TAG: ⏱ startBalancePolling viewer=$viewerAddress host=$hostAddress")
        println("$TAG: ⏱ BALANCE_POLL_START viewer=$viewerAddress host=$hostAddress")
        balancePollingJob =
            scope.launch {
                var tickCount = 0
                while (isActive) {
                    tickCount++
                    runCatching {
                        // Try native Swift handler first (faster, no extra algod call).
                        val fetchHandler = iosViewerFetchBalanceHandler
                        if (fetchHandler != null) {
                            fetchHandler(viewerAddress, hostAddress) { remaining ->
                                if (remaining != null) {
                                    _remainingBalanceMicroUsdc.value = remaining.coerceAtLeast(0L)
                                    if (remaining > _progressBalanceMicroUsdc.value) {
                                        _progressBalanceMicroUsdc.value = remaining.coerceAtLeast(0L)
                                    }
                                    println(
                                        "$TAG: 🔄 BALANCE_POLL_TICK #$tickCount (swift) → " +
                                            "${remaining / 1_000_000.0} USDC viewer=$viewerAddress",
                                    )
                                } else {
                                    println("$TAG: ⚠️ BALANCE_POLL_TICK #$tickCount swift returned null")
                                }
                            }
                        } else {
                            // Fall back to the Kotlin-native use case.
                            println("$TAG: 🔄 BALANCE_POLL_TICK #$tickCount (use-case) viewer=$viewerAddress host=$hostAddress")
                            val remaining =
                                getRemainingBalanceUseCase(
                                    GetRemainingSessionVaultBalanceUseCase.Params(
                                        viewerAddress = viewerAddress,
                                        hostAddress = hostAddress,
                                        appId = RailMppConstants.MPP_SESSION_VAULT_APP_ID,
                                        // Use the cached signer key so the channel ID matches the one
                                        // used when the session vault was opened / topped up.
                                        authorizedSignerPublicKey = viewerAuthorizedSignerPublicKey,
                                    ),
                                ).getOrDefault(0L)
                            _remainingBalanceMicroUsdc.value = remaining.coerceAtLeast(0L)
                            if (remaining > _progressBalanceMicroUsdc.value) {
                                _progressBalanceMicroUsdc.value = remaining.coerceAtLeast(0L)
                            }
                            println(
                                "$TAG: 🔄 BALANCE_POLL_TICK #$tickCount result → ${remaining / 1_000_000.0} USDC",
                            )
                        }
                    }.onFailure { e ->
                        println("$TAG: ❌ BALANCE_POLL_ERR tick=$tickCount: $e")
                    }
                    delay(BALANCE_POLL_INTERVAL_MS)
                }
            }
    }

    private fun stopBalancePolling() {
        balancePollingJob?.cancel()
        balancePollingJob = null
    }

    /** Starts balance polling if both viewer and host addresses are already known. */
    private fun maybeStartBalancePolling() {
        val viewer = _viewerAddress.value
        val host = _hostAddress.value
        if (viewer.isNotBlank() && host.isNotBlank() && balancePollingJob == null) {
            startBalancePolling(viewer, host)
        }
    }

    private fun startConnectionTypePolling() {
        connectionTypePollingJob?.cancel()
        connectionTypePollingJob =
            CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    iosViewerDetectConnectionTypeHandler?.let { notifyConnectionTypeChanged(it()) }
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

    private fun String.jsonOptInt(key: String): Int? =
        Regex(""""$key"\s*:\s*(-?\d+)""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64OrNull(value: String): ByteArray? =
        runCatching {
            // Unescape JSON-encoded forward slashes (\/ → /) before decoding.
            // The regex-based jsonOptString does not unescape JSON sequences,
            // so forward slashes in base64 data arrive as '\/' pairs which are
            // invalid base64 characters and would cause decode to return null.
            val normalised =
                value
                    .replace("\\/", "/")
                    .replace('-', '+')
                    .replace('_', '/')
                    .trimEnd('=')
            val padded = normalised + "=".repeat((4 - normalised.length % 4) % 4)
            Base64.decode(padded)
        }.getOrNull()
}
