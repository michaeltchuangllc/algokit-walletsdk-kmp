package com.michaeltchuang.walletsdk.ui.liquidAuth.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.algorand.algosdk.sdk.Sdk
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.account.domain.model.local.LocalAccount
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetAlgo25SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetFalcon24SecretKey
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetHdSeed
import com.michaeltchuang.walletsdk.core.account.domain.usecase.local.GetLocalAccount
import com.michaeltchuang.walletsdk.core.algosdk.signAlgo25ArbitraryData
import com.michaeltchuang.walletsdk.core.algosdk.signFalcon24Transaction
import com.michaeltchuang.walletsdk.core.algosdk.signHdKeyTransaction
import com.michaeltchuang.walletsdk.core.deeplink.utils.AssetConstants.USDC_TESTNET_ID
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService
import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamCreator
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.MppServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.MppWalletSigner
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.core.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.core.PAYMENT_CHANNEL_LABEL
import com.michaeltchuang.walletsdk.core.railmpp.core.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.core.ServerConfig
import com.michaeltchuang.walletsdk.ui.liquidAuth.configuration.IceServerConfig
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.displayName
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.AnswerScreenState
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.ConnectionStatusState
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.LiquidStreamBlockConsumptionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.LiquidStreamBlockConsumptionManager.CreatorVoucherClaimSnapshot
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Android implementation of LiquidAuthConnectionManager.
 *
 * Binds to SignalService and manages WebRTC peer connections.
 * Tracks ICE connection type for quality indicators and billing.
 */
class AndroidLiquidAuthConnectionManager(
    private val context: Context,
    private val getLocalAccount: GetLocalAccount,
    private val getAlgo25SecretKey: GetAlgo25SecretKey,
    private val getFalcon24SecretKey: GetFalcon24SecretKey,
    private val getHdSeed: GetHdSeed,
) : LiquidAuthConnectionManager {
    companion object {
        private const val TAG = "AndroidLiquidAuthCM"
        private const val NOTIFICATION_ID = 1338
        private const val CHANNEL_ID = "liquid_auth_broadcast"
        private const val CONNECTION_TYPE_POLL_INTERVAL_MS = 1000L // Check every 1 second
        private const val SOLANA_USDC_DEVNET_MINT = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
        private const val SOLANA_USDC_MAINNET_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    }

    private var viewModel: LiquidAuthOfferViewModel? = null
    private var signalService: SignalService? = null
    private var serviceConnection: ServiceConnection? = null
    private var isBound = false
    private var activeRequestId: String? = null
    private var connectionTypePollingJob: Job? = null
    private var liquidStreamCreator: LiquidStreamCreator? = null
    private var activePaymentSessionId: String? = null
    private var activePaymentRecipient: String? = null
    private var activeViewerAddressForVault: String? = null
    private var activeCreatorVoucherClaimSnapshot: CreatorVoucherClaimSnapshot? = null

    // Connection type state flow - exposed for UI and billing
    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    override val connectionType: StateFlow<IceConnectionType> = _connectionType

    private val blockConsumptionManager =
        LiquidStreamBlockConsumptionManager(
            tag = TAG,
            getViewModel = { viewModel },
            getActiveViewerAddress = { activeViewerAddressForVault },
            getActiveCreatorAddress = { activePaymentRecipient },
            getCreatorVoucherClaimSnapshot = { activeCreatorVoucherClaimSnapshot },
            buildCreatorWalletSigner = { creatorAddress -> buildCreatorWalletSigner(creatorAddress) },
            sendMessage = { message -> sendMessage(message) },
        )

    override fun initialize(viewModel: LiquidAuthOfferViewModel) {
        Log.d(TAG, "🔌 initialize() called with viewModel=$viewModel")
        this.viewModel = viewModel
        Log.d(TAG, "🔌 viewModel set, this.viewModel=${this.viewModel}")
    }

    /**
     * Start X402 block consumption
     */
    override fun startBlockConsumption(sessionId: String) {
        val targetSessionId = activePaymentSessionId ?: sessionId
        Log.e(
            TAG,
            "[SESSION_VAULT_START_BLOCK] requested=$sessionId activePaymentSession=$activePaymentSessionId target=$targetSessionId",
        )
        if (activePaymentSessionId != null && activePaymentSessionId != sessionId) {
            Log.e(
                TAG,
                "[SESSION_VAULT_SESSION_MISMATCH] requested=$sessionId usingActivePaymentSession=$targetSessionId",
            )
        }
        blockConsumptionManager.start(targetSessionId)
    }

    /**
     * Stop block consumption
     */
    override fun stopBlockConsumption() {
        blockConsumptionManager.stop()
    }

    /**
     * Send payment request to client
     */
    override fun sendPaymentRequest(paymentRequest: PaymentRequest) {
        val service = signalService
        val peerConnection = service?.peerConnection
        if (service == null || peerConnection == null) {
            val message = "MPP payment rail unavailable: peer connection is not ready"
            Log.e(TAG, message)
            viewModel?.onMppPaymentRejected(message)
            return
        }

        val paymentChannel = service.getDataChannel(PAYMENT_CHANNEL_LABEL) ?: service.createDataChannel(PAYMENT_CHANNEL_LABEL)
        if (paymentChannel == null) {
            val message = "MPP payment channel unavailable"
            Log.e(TAG, message)
            viewModel?.onMppPaymentRejected(message)
            return
        }

        try {
            activeCreatorVoucherClaimSnapshot = null
            if (activeViewerAddressForVault.isNullOrBlank()) {
                Log.e(TAG, "[SESSION_VAULT_VIEWER_SET_FROM_REQUEST] viewer=$activeViewerAddressForVault session=${paymentRequest.id}")
            }
            val network = toMppNetwork(paymentRequest.network)
            val amount = paymentRequest.amount
            val recipient = paymentRequest.payTo
            val isSolanaNetwork = network.startsWith("solana:", ignoreCase = true)
            val asset =
                if (isSolanaNetwork) {
                    if (network == MppNetworks.SOLANA_MAINNET) SOLANA_USDC_MAINNET_MINT else SOLANA_USDC_DEVNET_MINT
                } else {
                    USDC_TESTNET_ID.toString()
                }
            Log.d(TAG, "💰 Building MPP payment request: network=$network recipient=$recipient asset=$asset amount=$amount")
            val serverConfig =
                ServerConfig(
                    gating =
                        GatingConfig(
                            mode = GatingMode.PARTIAL_TIME,
                            amount = amount,
                            asset = asset,
                            network = network,
                            payTo = recipient,
                            segmentDuration = 3,
                            leadTime = 0,
                        ),
                    gracePeriod = 5,
                    viewerAddress = activeViewerAddressForVault,
                    skipPaymentRequestWhenSessionFunded = true,
                )

            val current = liquidStreamCreator
            val shouldRecreate =
                current == null ||
                    activePaymentSessionId != paymentRequest.id ||
                    activePaymentRecipient != recipient

            if (shouldRecreate) {
                current?.terminate("replaced")
                val creator =
                    LiquidStreamCreator(
                        peerConnection = peerConnection,
                        dataChannel = paymentChannel,
                        rtpSenders = emptyList(),
                        mppServerConfig =
                            MppServerConfig(
                                network = network,
                                recipient = recipient,
                                secretKey = "liquid-auth-mpp-${activeRequestId ?: paymentRequest.id}",
                            ),
                        serverConfig = serverConfig.copy(sessionId = paymentRequest.id),
                    )
                creator.rtcServer.onPaymentSettled = { receipt ->
                    receipt.payFrom
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            if (it != activeViewerAddressForVault) {
                                Log.e(TAG, "[SESSION_VAULT_VIEWER_SET_FROM_RECEIPT] viewer=$it txId=${receipt.txId}")
                            }
                            activeViewerAddressForVault = it
                        }

                    Log.e(
                        TAG,
                        "[SESSION_VAULT_ON_PAYMENT_SETTLED] txId=${receipt.txId} payFrom=${receipt.payFrom} viewerForVault=$activeViewerAddressForVault session=${receipt.sessionId} activePaymentSession=$activePaymentSessionId",
                    )
                    viewModel?.onMppPaymentSettled(receipt.txId)

                    val targetSession = activePaymentSessionId ?: receipt.sessionId
                    if (targetSession.isNotBlank()) {
                        Log.e(
                            TAG,
                            "[SESSION_VAULT_FORCE_START_BLOCK] txId=${receipt.txId} targetSession=$targetSession",
                        )
                        startBlockConsumption(targetSession)
                    } else {
                        Log.e(
                            TAG,
                            "[SESSION_VAULT_FORCE_START_BLOCK_SKIP] reason=missing_session txId=${receipt.txId}",
                        )
                    }
                }
                creator.rtcServer.onPaymentRejected = { reason ->
                    Log.e(TAG, "💰 MPP payment rejected: $reason")
                    viewModel?.onMppPaymentRejected(reason)
                }
                creator.rtcServer.onError = { e ->
                    Log.e(TAG, "💰 MPP creator error", e)
                    viewModel?.onMppPaymentRejected(e.message ?: "MPP creator error")
                }
                creator.start()
                liquidStreamCreator = creator
                activePaymentSessionId = paymentRequest.id
                activePaymentRecipient = recipient
                Log.e(
                    TAG,
                    "[SESSION_VAULT_BOOTSTRAP_START_BLOCK] source=creator_initialized session=${paymentRequest.id} viewer=$activeViewerAddressForVault recipient=$recipient",
                )
                startBlockConsumption(paymentRequest.id)
            } else {
                current.updateConfig(serverConfig.copy(sessionId = paymentRequest.id))
                activePaymentSessionId = paymentRequest.id
                activePaymentRecipient = recipient
                Log.e(
                    TAG,
                    "[SESSION_VAULT_BOOTSTRAP_START_BLOCK] source=creator_reused session=${paymentRequest.id} viewer=$activeViewerAddressForVault recipient=$recipient",
                )
                startBlockConsumption(paymentRequest.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "💰 Failed to initialize MPP creator", e)
            viewModel?.onMppPaymentRejected(e.message ?: "MPP initialization failed")
        }
    }

    private fun toMppNetwork(network: String): String {
        val n = network.lowercase()
        return when {
            network == MppNetworks.SOLANA_MAINNET ||
                n.contains(
                    "solana",
                ) &&
                (n.contains("mainnet") || n.contains("mainnet-beta")) -> MppNetworks.SOLANA_MAINNET
            network == MppNetworks.SOLANA_DEVNET || n.contains("solana") && n.contains("devnet") -> MppNetworks.SOLANA_DEVNET
            network == MppNetworks.SOLANA_TESTNET || n.contains("solana") && n.contains("testnet") -> MppNetworks.SOLANA_TESTNET
            n.contains("mainnet") || network == MppNetworks.ALGORAND_MAINNET -> MppNetworks.ALGORAND_MAINNET
            else -> MppNetworks.ALGORAND_TESTNET
        }
    }

    /**
     * Start polling for connection type changes.
     * This monitors the ICE connection and updates the flow.
     */
    private fun startConnectionTypePolling() {
        Log.d(TAG, "🔄 Starting connection type polling")
        connectionTypePollingJob?.cancel()
        connectionTypePollingJob =
            CoroutineScope(Dispatchers.Default).launch {
                // Immediate first detection
                detectAndUpdateConnectionType()

                var pollCount = 0
                while (isActive) {
                    pollCount++
                    if (pollCount % 5 == 0) { // Log every 5th poll (10 seconds)
                        Log.d(TAG, "🔄 Connection type poll #$pollCount, service=$signalService")
                    }
                    detectAndUpdateConnectionType()
                    delay(CONNECTION_TYPE_POLL_INTERVAL_MS)
                }
            }
    }

    /**
     * Stop polling for connection type.
     */
    private fun stopConnectionTypePolling() {
        connectionTypePollingJob?.cancel()
        connectionTypePollingJob = null
        _connectionType.value = IceConnectionType.UNKNOWN
    }

    /**
     * Detect the current ICE connection type from WebRTC stats.
     */
    private fun detectAndUpdateConnectionType() {
        Log.d(TAG, "🔍 Trying to detect connection type... signalService=$signalService")
        signalService?.let { service ->
            Log.d(TAG, "🔍 SignalService available, peerConnection=${service.peerConnection}")
            // Map SignalService.IceConnectionType to our UI model
            service.detectConnectionType { type ->
                Log.d(TAG, "🔍 Raw connection type from service: $type")
                val mappedType =
                    when (type) {
                        SignalService.IceConnectionType.LOCAL -> IceConnectionType.LOCAL
                        SignalService.IceConnectionType.STUN -> IceConnectionType.STUN
                        SignalService.IceConnectionType.RELAY -> IceConnectionType.RELAY
                        SignalService.IceConnectionType.FAILED -> IceConnectionType.FAILED
                        SignalService.IceConnectionType.UNKNOWN -> IceConnectionType.UNKNOWN
                    }

                if (_connectionType.value != mappedType) {
                    _connectionType.value = mappedType
                    Log.d(TAG, "🌐 Connection type changed: ${mappedType.displayName()}")

                    // Notify view model for any connection-type specific logic
                    viewModel?.onConnectionTypeChanged(mappedType)
                }
            }
        } ?: Log.w(TAG, "⚠️ Cannot detect - signalService is null")
    }

    override fun startListening(
        origin: String,
        requestId: String,
    ) {
        Log.d(
            TAG,
            "🔌 startListening() called - isBound=$isBound, activeRequestId=$activeRequestId, newRequestId=$requestId, viewModel=$viewModel",
        )

        // Prevent broadcast from interfering with active viewer session.
        // Both viewer and broadcaster share the same SignalService, and
        // SignalService.start() disconnects any existing WebRTC client.
        if (AnswerScreenState.isVisible || ConnectionStatusState.isVisible) {
            Log.w(
                TAG,
                "⛔ Active viewer session detected (AnswerScreenState.isVisible=${AnswerScreenState.isVisible}, " +
                    "ConnectionStatusState.isVisible=${ConnectionStatusState.isVisible}). " +
                    "Skipping broadcast start to avoid disconnecting viewer.",
            )
            return
        }

        if (isBound) {
            if (activeRequestId == requestId) {
                Log.d(TAG, "Already bound to service for same requestId, skipping")
                return
            }
            Log.d(TAG, "🔁 RequestId changed while bound ($activeRequestId -> $requestId), restarting service binding")
            stopListening()
        }

        if (viewModel == null) {
            Log.e(TAG, "❌ Cannot start listening - viewModel is null! Call initialize() first")
            return
        }

        Log.d(TAG, "Starting SignalService for requestId: $requestId")

        serviceConnection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    Log.d(TAG, "🔌 onServiceConnected called")
                    val localBinder = binder as? SignalService.LocalBinder
                    signalService = localBinder?.getServerInstance()
                    Log.d(TAG, "SignalService connected, service=$signalService")

                    signalService?.let { service ->
                        Log.d(TAG, "🔌 Calling setupSignalService...")
                        setupSignalService(service, origin, requestId)
                    } ?: Log.e(TAG, "❌ SignalService is null after connection!")
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.d(TAG, "SignalService disconnected")
                    signalService = null
                    isBound = false
                    viewModel?.onClientDisconnected()
                }
            }

        // Start and bind to service
        val intent = Intent(context, SignalService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        isBound = true
        activeRequestId = requestId
        Log.d(TAG, "🔌 Service bind initiated for requestId=$activeRequestId")
    }

    private fun setupSignalService(
        service: SignalService,
        origin: String,
        requestId: String,
    ) {
        Log.d(TAG, "🔌 setupSignalService() called, requestId=$requestId, viewModel=$viewModel")

        val notificationBuilder = createNotificationBuilder()
        val activity = context as? Activity
        val activityClass = activity?.javaClass

        if (activityClass == null) {
            Log.e(TAG, "❌ Context is not an Activity, cannot setup SignalService properly")
            return
        }

        Log.d(TAG, "🔌 Starting service with origin=$origin")
        // Start the service
        service.start(
            url = origin,
            httpClient = OkHttpClient.Builder().build(),
            notificationBuilder = notificationBuilder,
            notificationId = NOTIFICATION_ID,
            activityClass = activityClass,
        )

        // Connect as "offer" type (waiting for peer to answer)
        Log.d(TAG, "🔌 Launching peer connection coroutine...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "🔌 Calling service.peer() with requestId=$requestId")
                service.peer(
                    requestId = requestId,
                    type = "offer",
                    iceServers = IceServerConfig.iceServers,
                )
                Log.d(TAG, "🔌 service.peer() returned - peer connection established")

                // Handle data channel state changes
                activity.let { act ->
                    // Check if already connected (state change might have already fired)
                    val currentState = service.dataChannel?.state()?.toString()
                    Log.d(TAG, "🔌 Initial data channel state: $currentState")
                    if (currentState == "OPEN") {
                        Log.d(TAG, "🔌 Data channel already open! Calling onClientConnected...")
                        viewModel?.onClientConnected(requestId)
                        Log.d(TAG, "🔌 Starting connection type polling...")
                        startConnectionTypePolling()
                    }

                    Log.d(TAG, "🔌 Setting up handleMessages...")
                    service.handleMessages(
                        activity = act,
                        onMessage = { msg ->
                            Log.d(TAG, "📨 Received message: ${msg.take(150)}...")
                            tryCaptureViewerAddressFromMessage(msg)

                            // If we receive any other message, connection is open
                            val currentDcState = service.dataChannel?.state()?.toString()
                            Log.d(TAG, "📨 Non-payment message received; dcState=$currentDcState, requestId=$requestId")
                            if (currentDcState == "OPEN") {
                                Log.d(TAG, "📨 Triggering onClientConnected from onMessage fallback")
                                viewModel?.onClientConnected(requestId)
                            }
                        },
                        onStateChange = { state ->
                            Log.d(TAG, "🔌 Data channel state changed: $state")
                            when (state) {
                                "OPEN" -> {
                                    Log.d(TAG, "🔌 Data channel OPEN! Calling onClientConnected...")
                                    viewModel?.onClientConnected(requestId)
                                    Log.d(TAG, "🔌 Starting connection type polling...")
                                    startConnectionTypePolling()
                                    Log.d(TAG, "🔌 Connection setup complete!")
                                }
                                "CLOSED", "CLOSING" -> {
                                    Log.d(TAG, "🔌 Data channel closed/disconnecting")
                                    stopConnectionTypePolling()
                                    stopBlockConsumption()
                                    viewModel?.onClientDisconnected()
                                }
                            }
                        },
                        notificationBuilder = notificationBuilder,
                        notificationId = NOTIFICATION_ID,
                        activityClass = activityClass,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up peer connection", e)
            }
        }
    }

    private fun createNotificationBuilder(): NotificationCompat.Builder {
        // Create notification channel for Android O+
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Liquid Auth Broadcast",
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager.createNotificationChannel(channel)

        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setContentTitle("Liquid Auth Broadcast")
            .setContentText("Waiting for peer to connect...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun tryCaptureViewerAddressFromMessage(msg: String) {
        runCatching {
            val json = JSONObject(msg)

            val voucherRef = json.optString("reference", "")
            if (voucherRef == "liquid:payment:voucher") {
                val signature = json.optString("signature", "").takeIf { it.isNotBlank() }
                val claimedAmount =
                    json
                        .optLong("totalAmountClaimedMicroUsdc", -1L)
                        .takeIf { it >= 0L }
                val voucherSessionId = json.optString("id", "").takeIf { it.isNotBlank() }
                val voucherViewer = json.optString("viewer", "").takeIf { it.isNotBlank() }

                if (signature == null || claimedAmount == null || voucherSessionId == null || voucherViewer == null) {
                    Log.e(
                        TAG,
                        "[SESSION_VAULT_VIEWER_VOUCHER_SIG_SKIP] reason=invalid_payload session=${json.optString(
                            "id",
                            "",
                        )} claimedAmountMicroUsdc=$claimedAmount viewer=$voucherViewer",
                    )
                } else {
                    val activeSession = activePaymentSessionId
                    if (activeSession != null && voucherSessionId != activeSession) {
                        Log.e(
                            TAG,
                            "[SESSION_VAULT_VIEWER_VOUCHER_SIG_SKIP] reason=session_mismatch voucherSession=$voucherSessionId activeSession=$activeSession",
                        )
                    } else {
                        val previousClaimedAmount = activeCreatorVoucherClaimSnapshot?.totalAmountClaimedMicroUsdc
                        if (previousClaimedAmount != null && claimedAmount < previousClaimedAmount) {
                            Log.e(
                                TAG,
                                "[SESSION_VAULT_VIEWER_VOUCHER_SIG_STALE_SKIP] session=$voucherSessionId claimedAmountMicroUsdc=$claimedAmount previousClaimedAmountMicroUsdc=$previousClaimedAmount",
                            )
                        } else {
                            activeCreatorVoucherClaimSnapshot =
                                CreatorVoucherClaimSnapshot(
                                    sessionId = voucherSessionId,
                                    viewerAddress = voucherViewer,
                                    signatureBase64 = signature,
                                    totalAmountClaimedMicroUsdc = claimedAmount,
                                )
                            Log.e(
                                TAG,
                                "[SESSION_VAULT_VIEWER_VOUCHER_SIG] session=$voucherSessionId sigLen=${signature.length} claimedAmountMicroUsdc=$claimedAmount viewer=$voucherViewer",
                            )
                        }
                    }
                }
            }

            val candidate = json.optString("address", "").takeIf { it.isNotBlank() }
            if (candidate != null && candidate != activeViewerAddressForVault) {
                activeViewerAddressForVault = candidate
                Log.d(TAG, "🔑 Captured viewer address from LiquidAuth message: $candidate")
            }
        }
    }

    override fun stopListening() {
        Log.d(TAG, "Stopping SignalService (activeRequestId=$activeRequestId)")
        stopConnectionTypePolling()
        stopBlockConsumption()
        liquidStreamCreator?.terminate("stop_listening")
        liquidStreamCreator = null
        activePaymentSessionId = null
        activePaymentRecipient = null
        activeViewerAddressForVault = null
        activeCreatorVoucherClaimSnapshot = null
        serviceConnection?.let {
            try {
                context.unbindService(it)
            } catch (_: IllegalArgumentException) {
                // Service not bound
            }
        }

        // Only stop the shared SignalService if no viewer session is still active.
        // Otherwise we would kill the viewer's WebRTC connection.
        val viewerStillActive = AnswerScreenState.isVisible || ConnectionStatusState.isVisible
        if (!viewerStillActive) {
            signalService?.stop()
        } else {
            Log.w(
                TAG,
                "⏸️ Viewer session still active (AnswerScreenState.isVisible=${AnswerScreenState.isVisible}, " +
                    "ConnectionStatusState.isVisible=${ConnectionStatusState.isVisible}). " +
                    "Skipping signalService.stop() to preserve viewer connection.",
            )
        }

        signalService = null
        serviceConnection = null
        isBound = false
        activeRequestId = null
    }

    override fun sendMessage(message: String) {
        val dataChannelState = signalService?.dataChannel?.state()?.toString()
        val isOpen = dataChannelState == "OPEN"
        Log.d(
            TAG,
            "📤 sendMessage called: dcState=$dataChannelState, isOpen=$isOpen, bytes=${message.length}, preview=${message.take(120)}",
        )
        signalService?.send(message)
    }

    override fun sendVideoFrame(
        frameId: String,
        timestamp: Long,
        frameData: ByteArray,
        width: Int,
        height: Int,
        format: String,
    ) {
        if (!isConnected()) {
            Log.w(
                TAG,
                "Cannot send video frame - not connected",
            )
            return
        }

        try {
            // Create JSON video frame message
            val base64Data =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(frameData)
            val hostAddress = activePaymentRecipient.orEmpty()
            val sessionId = activePaymentSessionId.orEmpty()
            val hostJsonField = if (hostAddress.isNotBlank()) ",\"hostAddress\":\"$hostAddress\"" else ""
            val sessionJsonField = if (sessionId.isNotBlank()) ",\"sessionId\":\"$sessionId\"" else ""
            val jsonMessage =
                """
                {"reference":"liquid:video:frame","id":"$frameId","timestamp":$timestamp,"format":"$format","data":"$base64Data","width":$width,"height":$height$hostJsonField$sessionJsonField}
                """.trimIndent()

            Log.d(TAG, "🎥 Sending video frame: ${width}x$height, ${frameData.size} bytes")
            signalService?.send(jsonMessage)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send video frame: $e")
        }
    }

    override fun isConnected(): Boolean {
        val dataChannelState = signalService?.dataChannel?.state()?.toString()
        return dataChannelState == "OPEN"
    }

    private suspend fun buildCreatorWalletSigner(creatorAddress: String): MppWalletSigner? {
        val localAccount = getLocalAccount(creatorAddress) ?: return null
        if (localAccount is LocalAccount.SeedVault) return null

        return object : MppWalletSigner {
            override val address: String = creatorAddress

            override suspend fun signTransaction(txn: com.algorand.algosdk.transaction.Transaction): ByteArray =
                when (localAccount) {
                    is LocalAccount.Algo25 -> {
                        val secretKey =
                            getAlgo25SecretKey(creatorAddress)
                                ?: error("Missing Algo25 key for $creatorAddress")
                        val txnBytes = Encoder.encodeToMsgPack(txn)
                        val signature =
                            signAlgo25ArbitraryData(txn.bytesToSign(), secretKey)
                                ?: error("Algo25 arbitrary signing failed")
                        Sdk.attachSignature(signature, txnBytes)
                    }

                    is LocalAccount.HdKey -> {
                        val seed =
                            getHdSeed(localAccount.seedId)
                                ?: error("Missing HD seed for $creatorAddress")
                        signHdKeyTransaction(
                            transactionByteArray = Encoder.encodeToMsgPack(txn),
                            seed = seed,
                            account = localAccount.account,
                            change = localAccount.change,
                            key = localAccount.keyIndex,
                        ) ?: error("HD signing failed")
                    }

                    is LocalAccount.Falcon24 -> {
                        val secretKey =
                            getFalcon24SecretKey(creatorAddress)
                                ?: error("Missing Falcon24 key for $creatorAddress")
                        signFalcon24Transaction(
                            transactionByteArray = Encoder.encodeToMsgPack(txn),
                            publicKey = localAccount.publicKey,
                            privateKey = secretKey,
                        ) ?: error("Falcon24 signing failed")
                    }

                    else -> error("Unsupported account for Algorand Session Vault claim signing")
                }
        }
    }
}

/**
 * Android actual implementation of factory function.
 */
actual fun createLiquidAuthConnectionManager(platformContext: Any): LiquidAuthConnectionManager {
    val context = platformContext as Context
    val koin =
        org.koin.java.KoinJavaComponent
            .getKoin()
    return AndroidLiquidAuthConnectionManager(
        context = context,
        getLocalAccount = koin.get(clazz = GetLocalAccount::class),
        getAlgo25SecretKey = koin.get(clazz = GetAlgo25SecretKey::class),
        getFalcon24SecretKey = koin.get(clazz = GetFalcon24SecretKey::class),
        getHdSeed = koin.get(clazz = GetHdSeed::class),
    )
}
