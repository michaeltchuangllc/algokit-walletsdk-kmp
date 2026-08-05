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
import io.github.algorandecosystem.sdk.BytesArray
import io.github.algorandecosystem.sdk.Sdk
import com.algorand.algosdk.transaction.SignedTransaction
import com.algorand.algosdk.transaction.Transaction
import com.algorand.algosdk.util.Encoder
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService
import com.michaeltchuang.walletsdk.core.railmpp.LiquidStreamCreator
import com.michaeltchuang.walletsdk.core.railmpp.MppNetworks
import com.michaeltchuang.walletsdk.core.railmpp.MppServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ChatMessage
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.GatingMode
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.PaymentRequest
import com.michaeltchuang.walletsdk.core.railmpp.domain.model.ServerConfig
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetRemainingSessionVaultBalanceUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.GetSessionVaultConfigUseCase
import com.michaeltchuang.walletsdk.core.railmpp.domain.usecase.MppWalletSignerUseCase
import com.michaeltchuang.walletsdk.core.railmpp.smartcontract.EscrowSessionVaultManagerClient
import com.michaeltchuang.walletsdk.core.railmpp.utils.MppPayments
import com.michaeltchuang.walletsdk.core.utils.GoMobileDispatcher
import com.michaeltchuang.walletsdk.ui.liquidAuth.configuration.IceServerConfig
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.AnswerScreenState
import com.michaeltchuang.walletsdk.ui.liquidAuth.state.ConnectionStatusState
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.LiquidStreamBlockConsumptionManager
import com.michaeltchuang.walletsdk.ui.liquidAuth.utils.LiquidStreamBlockConsumptionManager.CreatorVoucherClaimSnapshot
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.domain.model.displayName
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.java.KoinJavaComponent
import java.math.BigInteger
import kotlin.io.encoding.Base64

/**
 * Android implementation of LiquidAuthConnectionManager.
 *
 * Binds to SignalService and manages WebRTC peer connections.
 * Tracks ICE connection type for quality indicators and billing.
 */
actual class LiquidAuthConnectionManager actual constructor(
    platformContext: Any,
) {
    companion object {
        private const val TAG = "CommonLiquidAuthCM"
        private const val NOTIFICATION_ID = 1338
        private const val CHANNEL_ID = "liquid_auth_broadcast"
    }

    private val context = platformContext as Context
    private val koin = KoinJavaComponent.getKoin()
    private val mppWalletSignerUseCase: MppWalletSignerUseCase = koin.get(clazz = MppWalletSignerUseCase::class)
    private val getRemainingSessionVaultBalanceUseCase: GetRemainingSessionVaultBalanceUseCase =
        koin.get(clazz = GetRemainingSessionVaultBalanceUseCase::class)
    private val getSessionVaultConfigUseCase: GetSessionVaultConfigUseCase =
        koin.get(clazz = GetSessionVaultConfigUseCase::class)
    private var viewModel: LiquidAuthOfferViewModel? = null
    private val platformServices: LiquidAuthPlatformServices = KoinJavaComponent.get(LiquidAuthPlatformServices::class.java)
    private var signalService: SignalService? = null
    private var serviceConnection: ServiceConnection? = null
    private var isBound = false
    private var activeRequestId: String? = null
    private val connectionTypePollingController =
        LiquidAuthPollingJobController(
            scope = CoroutineScope(Dispatchers.Default),
            runImmediately = true,
            onPoll = { pollCount ->
                if (pollCount > 0 && pollCount % 5 == 0) {
                    Log.d(TAG, "🔄 Connection type poll #$pollCount, service=$signalService")
                }
                detectAndUpdateConnectionType()
            },
            onStop = { _connectionType.value = IceConnectionType.UNKNOWN },
        )
    private var liquidStreamCreator: LiquidStreamCreator? = null
    private var activePaymentSessionId: String? = null
    private var activePaymentRecipient: String? = null
    private var activePaymentAmount: String? = null
    private var activePaymentNetwork: String? = null
    private var activeViewerAddressForVault: String? = null
    private var activeCreatorVoucherClaimSnapshot: CreatorVoucherClaimSnapshot? = null

    /**
     * Viewer's authorized-signer public key received via the early [segment:handshake]
     * message.  Stored separately so it survives the race where the hello arrives before
     * [liquidStreamCreator] is constructed (i.e. before [sendPaymentRequest] is called).
     *
     * [sendPaymentRequest] picks this up as a fallback when
     * [activeCreatorVoucherClaimSnapshot] does not yet carry the key.
     */
    private var activeViewerAuthorizedSignerKey: ByteArray? = null

    // Connection type state flow - exposed for UI and billing
    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    actual val connectionType: StateFlow<IceConnectionType> = _connectionType

    private val blockConsumptionManager =
        LiquidStreamBlockConsumptionManager(
            tag = TAG,
            getViewModel = { viewModel },
            getActiveViewerAddress = { activeViewerAddressForVault },
            getActiveCreatorAddress = { activePaymentRecipient },
            getActivePaymentNetwork = { activePaymentNetwork },
            getCreatorVoucherClaimSnapshot = { activeCreatorVoucherClaimSnapshot },
            buildCreatorWalletSigner = { creatorAddress -> mppWalletSignerUseCase(creatorAddress) },
            getSessionVaultConfigUseCase = getSessionVaultConfigUseCase,
        )

    actual fun initialize(viewModel: LiquidAuthOfferViewModel) {
        Log.d(TAG, "🔌 initialize() called with viewModel=$viewModel")
        this.viewModel = viewModel
        Log.d(TAG, "🔌 viewModel set, this.viewModel=${this.viewModel}")
    }

    /**
     * Start X402 block consumption
     */
    actual fun startBlockConsumption(sessionId: String) {
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
    actual fun stopBlockConsumption() {
        blockConsumptionManager.stop()
    }

    /**
     * Send payment request to client
     */
    actual fun sendPaymentRequest(paymentRequest: PaymentRequest) {
        val service = signalService
        if (!platformServices.isHostPeerConnectionReady(service)) {
            val message = "MPP payment rail unavailable: peer connection is not ready"
            Log.e(TAG, message)
            viewModel?.onMppPaymentRejected(message)
            return
        }

        val paymentChannel = platformServices.getOrCreateHostPaymentDataChannel(service)
        if (paymentChannel == null) {
            val message = "MPP payment channel unavailable"
            Log.e(TAG, message)
            viewModel?.onMppPaymentRejected(message)
            return
        }

        try {
            activeCreatorVoucherClaimSnapshot = null
            if (activeViewerAddressForVault.isNullOrBlank()) {
                Log.e(
                    TAG,
                    "[SESSION_VAULT_VIEWER_SET_FROM_REQUEST] viewer=$activeViewerAddressForVault session=${paymentRequest.id}",
                )
            }
            val resolvedPaymentRequest = resolveLiquidAuthPaymentRequest(paymentRequest)
            val network = resolvedPaymentRequest.network
            val amount = resolvedPaymentRequest.amount
            val recipient = resolvedPaymentRequest.recipient
            // Keep one creator-side payment session id stable for the active connection.
            // If incoming requests churn ids for the same stream, lock to active id.
            val resolvedSessionId = activePaymentSessionId ?: paymentRequest.id
            if (activePaymentSessionId != null && activePaymentSessionId != paymentRequest.id) {
                Log.w(
                    TAG,
                    "[SESSION_VAULT_SESSION_LOCKED] incoming=${paymentRequest.id} active=$activePaymentSessionId using=$resolvedSessionId",
                )
            }
            val asset = resolvedPaymentRequest.asset
            Log.d(
                TAG,
                "💰 Building MPP payment request: network=$network recipient=$recipient asset=$asset amount=$amount",
            )
            val serverConfig =
                ServerConfig(
                    sessionId = resolvedSessionId,
                    gating = resolvedPaymentRequest.gatingConfig,
                    gracePeriod = 5,
                    viewerAddress = activeViewerAddressForVault,
                    // Prefer the key from the most-recent voucher (authoritative).
                    // Fall back to the early hello key for first-connection scenarios where
                    // no voucher exists yet but the viewer already sent segment:handshake.
                    viewerAuthorizedSignerPublicKey =
                        activeCreatorVoucherClaimSnapshot
                            ?.viewerPublicKeyBase64
                            ?.takeIf { it.isNotBlank() }
                            ?.let { encoded ->
                                runCatching {
                                    Base64.decode(encoded)
                                }.getOrNull()
                            }
                            ?: activeViewerAuthorizedSignerKey,
                    skipPaymentRequestWhenSessionFunded = true,
                )

            val current = liquidStreamCreator
            val shouldRecreate =
                current == null ||
                    activePaymentRecipient != recipient

            if (shouldRecreate) {
                current?.terminate("replaced")
                val creator =
                    LiquidStreamCreator(
                        dataChannel = platformServices.createHostPaymentWebRtcDataChannel(paymentChannel),
                        rtpSenders = emptyList(),
                        mppServerConfig =
                            MppServerConfig(
                                network = network,
                                recipient = recipient,
                                secretKey = "liquid-auth-mpp-${activeRequestId ?: paymentRequest.id}",
                            ),
                        serverConfig = serverConfig,
                        getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
                    )
                creator.rtcServer.onViewerHello = { viewer, viewerPublicKeyBase64 ->
                    val helloJson = """{"type":"segment:handshake","viewer":"$viewer","viewerPublicKey":"$viewerPublicKeyBase64"}"""
                    tryCaptureViewerAddressFromMessage(helloJson)
                }
                creator.rtcServer.onVoucherReceived = { voucherJson ->
                    tryCaptureViewerAddressFromMessage(voucherJson)
                }
                creator.rtcServer.onPaymentSettled = { receipt ->
                    receipt.payFrom
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            if (it != activeViewerAddressForVault) {
                                Log.e(
                                    TAG,
                                    "[SESSION_VAULT_VIEWER_SET_FROM_RECEIPT] viewer=$it txId=${receipt.txId}",
                                )
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
                creator.onChatMessageReceived = { message ->
                    viewModel?.onChatMessageReceived(message)
                }
                creator.start()
                liquidStreamCreator = creator
                activePaymentSessionId = resolvedSessionId
                activePaymentRecipient = recipient
                activePaymentAmount = amount
                activePaymentNetwork = network
                Log.e(
                    TAG,
                    "[SESSION_VAULT_BOOTSTRAP_START_BLOCK] source=creator_initialized session=$resolvedSessionId viewer=$activeViewerAddressForVault recipient=$recipient",
                )
                // Notify the viewer of the creator's payment address + session so it can
                // set up its payment flow (replaces the old `liquid:video:frame` piggyback).
                sendCreatorSessionInfo(recipient, resolvedSessionId)
                startBlockConsumption(resolvedSessionId)
            } else {
                current.updateConfig(serverConfig)
                activePaymentSessionId = resolvedSessionId
                activePaymentRecipient = recipient
                activePaymentAmount = amount
                activePaymentNetwork = network
                Log.e(
                    TAG,
                    "[SESSION_VAULT_BOOTSTRAP_START_BLOCK] source=creator_reused session=$resolvedSessionId viewer=$activeViewerAddressForVault recipient=$recipient",
                )
                sendCreatorSessionInfo(recipient, resolvedSessionId)
                startBlockConsumption(resolvedSessionId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "💰 Failed to initialize MPP creator", e)
            viewModel?.onMppPaymentRejected(e.message ?: "MPP initialization failed")
        }
    }

    actual fun setupCreator(creatorAddress: String, network: String) {
        val service = signalService ?: return
        if (!platformServices.isHostPeerConnectionReady(service)) return

        val paymentChannel = platformServices.getOrCreateHostPaymentDataChannel(service) ?: return

        try {
            val current = liquidStreamCreator
            if (current != null && activePaymentRecipient == creatorAddress) return

            val resolvedNetwork = resolveLiquidAuthMppNetwork(network)
            val sessionId = activePaymentSessionId ?: "chat-session-${activeRequestId ?: System.currentTimeMillis()}"
            
            val serverConfig =
                ServerConfig(
                    sessionId = sessionId,
                    gating = GatingConfig(
                        mode = GatingMode.PARTIAL_TIME,
                        amount = "0", // Free by default until requested
                        asset = "USDC",
                        network = resolvedNetwork,
                        payTo = creatorAddress,
                    ),
                    gracePeriod = 5,
                    viewerAddress = activeViewerAddressForVault,
                    viewerAuthorizedSignerPublicKey = activeViewerAuthorizedSignerKey,
                    skipPaymentRequestWhenSessionFunded = true,
                )

            current?.terminate("replaced")
            val creator =
                LiquidStreamCreator(
                    dataChannel = platformServices.createHostPaymentWebRtcDataChannel(paymentChannel),
                    rtpSenders = emptyList(),
                    mppServerConfig =
                        MppServerConfig(
                            network = resolvedNetwork,
                            recipient = creatorAddress,
                            secretKey = "liquid-auth-chat-${activeRequestId ?: sessionId}",
                        ),
                    serverConfig = serverConfig,
                    getRemainingSessionVaultBalanceUseCase = getRemainingSessionVaultBalanceUseCase,
                )
            
            creator.onChatMessageReceived = { message ->
                viewModel?.onChatMessageReceived(message)
            }
            
            creator.rtcServer.onViewerHello = { viewer, viewerPublicKeyBase64 ->
                val helloJson = """{"type":"segment:handshake","viewer":"$viewer","viewerPublicKey":"$viewerPublicKeyBase64"}"""
                tryCaptureViewerAddressFromMessage(helloJson)
            }
            
            creator.start()
            liquidStreamCreator = creator
            activePaymentRecipient = creatorAddress
            activePaymentNetwork = resolvedNetwork
            activePaymentSessionId = sessionId
            
            Log.d(TAG, "💬 Chat initialized for creator=$creatorAddress network=$resolvedNetwork")
        } catch (e: Exception) {
            Log.e(TAG, "💰 Failed to setup chat creator", e)
        }
    }

    /**
     * Start polling for connection type changes.
     * This monitors the ICE connection and updates the flow.
     */
    private fun startConnectionTypePolling() {
        Log.d(TAG, "🔄 Starting connection type polling")
        connectionTypePollingController.start()
    }

    /**
     * Stop polling for connection type.
     */
    private fun stopConnectionTypePolling() {
        connectionTypePollingController.stop()
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

    actual fun startListening(
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
            Log.d(
                TAG,
                "🔁 RequestId changed while bound ($activeRequestId -> $requestId), restarting service binding",
            )
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
                    viewModel?.onClientDisconnected(activePaymentRecipient)
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
                    // Creator/host streams its camera + microphone via native WebRTC media tracks.
                    enableMedia = true,
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
                            Log.d(
                                TAG,
                                "📨 Non-payment message received; dcState=$currentDcState, requestId=$requestId",
                            )
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
                                    viewModel?.onClientDisconnected(activePaymentRecipient)
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
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            val parsed = parseLiquidAuthHostTransportMessage(msg)
            Log.e(TAG, "[SESSION_VAULT_VIEWER_VOUCHER_SIG] voucherRef=${parsed.reference.orEmpty()}")

            parsed.viewerHello?.let { hello ->
                val helloViewer = hello.viewerAddress
                val signerKey = hello.viewerPublicKey
                if (signerKey != null) {
                    if (helloViewer != null && helloViewer != activeViewerAddressForVault) {
                        activeViewerAddressForVault = helloViewer
                        Log.e(TAG, "[SESSION_VAULT_VIEWER_HELLO_ADDR] viewer=$helloViewer")
                    }
                    activeViewerAuthorizedSignerKey = signerKey

                    Napier.e(
                        "[SESSION_VAULT_VIEWER_HELLO_KEY] viewer=$helloViewer keyLen=${signerKey.size} session=$activePaymentSessionId creatorReady=${liquidStreamCreator != null}",
                    )

                    // If the creator already exists, push the key immediately so
                    // PaywalledRTCServer.viewerKeyDeferred resolves without waiting.
                    updateCreatorViewerSignerConfig(signerKey)
                }
            }

            parsed.paymentVoucher?.let { voucher ->
                voucher.channelIdBase64?.let { Napier.e("channelId=$it", tag = TAG) }
                voucher.channelId?.let { EscrowSessionVaultManagerClient.channelId = it }
                val signature = voucher.signatureBase64
                val claimedAmount = voucher.totalAmountClaimedMicroUsdc?.takeIf { it >= 0L }
                val voucherSessionId = voucher.sessionId
                val voucherViewer = voucher.viewerAddress
                val voucherViewerPublicKey = voucher.viewerPublicKeyBase64

                if (signature == null ||
                    claimedAmount == null ||
                    voucherSessionId == null ||
                    voucherViewer == null ||
                    voucherViewerPublicKey == null
                ) {
                    Log.e(
                        TAG,
                        "[SESSION_VAULT_VIEWER_VOUCHER_SIG_SKIP] reason=invalid_payload session=${voucher.sessionId} claimedAmountMicroUsdc=$claimedAmount viewer=$voucherViewer",
                    )
                } else {
                    val activeSession = activePaymentSessionId
                    if (activeSession != null && voucherSessionId != activeSession) {
                        Log.e(
                            TAG,
                            "[SESSION_VAULT_VIEWER_VOUCHER_SIG_SKIP] reason=session_mismatch voucherSession=$voucherSessionId activeSession=$activeSession",
                        )
                    } else {
                        val previousClaimedAmount =
                            activeCreatorVoucherClaimSnapshot?.totalAmountClaimedMicroUsdc
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
                                    viewerPublicKeyBase64 = voucherViewerPublicKey,
                                    signatureBase64 = signature,
                                    totalAmountClaimedMicroUsdc = claimedAmount,
                                )
                            val signerKey = voucher.viewerPublicKey
                            updateCreatorViewerSignerConfig(signerKey)
                            Log.e(
                                TAG,
                                "[SESSION_VAULT_VIEWER_VOUCHER_SIG] session=$voucherSessionId sigLen=${signature.length} claimedAmountMicroUsdc=$claimedAmount viewer=$voucherViewer signerKeyPresent=${signerKey != null}",
                            )
                            startBlockConsumption(voucherSessionId)
                            blockConsumptionManager.triggerSettlementFromViewerVoucher(
                                voucherSessionId,
                            )
                        }
                    }
                }
            }

            val candidate = parsed.address
            if (candidate != null && candidate != activeViewerAddressForVault) {
                activeViewerAddressForVault = candidate
                Log.d(TAG, "🔑 Captured viewer address from LiquidAuth message: $candidate")
            }
        }
    }

    private fun updateCreatorViewerSignerConfig(signerKey: ByteArray?) {
        liquidStreamCreator?.updateConfig(
            ServerConfig(
                sessionId = activePaymentSessionId,
                gating =
                    GatingConfig(
                        mode = GatingMode.PARTIAL_TIME,
                        amount =
                            activePaymentAmount
                                ?: MppPayments.voucherSettleWindowMicroUsdc().toString(),
                        asset = "USDC",
                        network = activePaymentNetwork ?: MppNetworks.ALGORAND_TESTNET,
                        payTo = activePaymentRecipient.orEmpty(),
                        segmentDuration = 3,
                        leadTime = 0,
                    ),
                gracePeriod = 5,
                viewerAddress = activeViewerAddressForVault,
                viewerAuthorizedSignerPublicKey = signerKey,
                skipPaymentRequestWhenSessionFunded = true,
            ),
        )
    }

    actual fun stopListening() {
        Log.d(TAG, "Stopping SignalService (activeRequestId=$activeRequestId)")
        stopConnectionTypePolling()
        stopBlockConsumption()
        liquidStreamCreator?.terminate("stop_listening")
        liquidStreamCreator = null
        activePaymentSessionId = null
        activePaymentRecipient = null
        activePaymentAmount = null
        activePaymentNetwork = null
        activeViewerAddressForVault = null
        activeCreatorVoucherClaimSnapshot = null
        activeViewerAuthorizedSignerKey = null
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

    actual fun sendMessage(message: String) {
        val dataChannelState = platformServices.hostDataChannelState(signalService)
        val isOpen = dataChannelState == "OPEN"
        Log.d(
            TAG,
            "📤 sendMessage called: dcState=$dataChannelState, isOpen=$isOpen, bytes=${message.length}, preview=${
                message.take(
                    120,
                )
            }",
        )
        platformServices.sendHostMessage(signalService, message)
    }

    actual fun sendChatMessage(message: ChatMessage) {
        liquidStreamCreator?.sendChatMessage(message)
    }

    private fun sendCreatorSessionInfo(hostAddress: String, sessionId: String) {
        if (hostAddress.isBlank()) {
            Log.w(TAG, "sendCreatorSessionInfo: skipping — hostAddress is blank")
            return
        }
        val json = """{"reference":"liquid:stream:info","hostAddress":"$hostAddress","sessionId":"$sessionId"}"""
        Log.d(TAG, "[CREATOR_SESSION_INFO_SENT] host=$hostAddress session=$sessionId")
        platformServices.sendHostMessage(signalService, json)
    }

    actual fun sendVideoFrame(
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

            Log.d(TAG, "🎥 Sending video frame: ${width}x$height, ${frameData.size} bytes")
            platformServices.sendHostMessage(signalService, jsonMessage)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send video frame: $e")
        }
    }

    actual fun isConnected(): Boolean = platformServices.isHostConnected(signalService)

    // ── Native WebRTC media track rendering (creator/host) ──────────────────────

    /** Shared EGL context used to initialize a `SurfaceViewRenderer`. */
    fun getStreamEglBaseContext(): org.webrtc.EglBase.Context? = signalService?.eglBaseContext

    /** Local camera track for the creator self-preview. */
    fun getLocalVideoTrack(): org.webrtc.VideoTrack? = signalService?.localVideoTrack

    /** Toggle the creator camera between front and back. */
    fun switchCamera() {
        signalService?.switchCamera()
    }

    actual fun setAudioEnabled(enabled: Boolean) {
        signalService?.setAudioEnabled(enabled)
    }

    actual fun setVideoEnabled(enabled: Boolean) {
        signalService?.setVideoEnabled(enabled)
    }

    private suspend fun signFalconTxnFromBundle(
        txn: Transaction,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray =
        signFalconTxnGroupFromBundle(
            txns = listOf(txn),
            publicKey = publicKey,
            privateKey = privateKey,
        ).first()

    private fun decodeFalconBundlePiece(encoded: String): ByteArray? {
        val trimmed = encoded.trim()
        if (trimmed.isEmpty()) return null

        fun addPadding(s: String): String {
            val rem = s.length % 4
            return if (rem == 0) s else s + "=".repeat(4 - rem)
        }

        val candidates =
            listOf(trimmed, addPadding(trimmed))
                .flatMap { value ->
                    listOf(value, value.replace('+', '-').replace('/', '_'))
                }.distinct()

        candidates.forEach { candidate ->
            runCatching { Base64.decode(candidate) }.getOrNull()?.let { return it }
            runCatching { Base64.decode(candidate) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun matchesExpectedTransaction(
        expected: Transaction,
        actual: Transaction,
    ): Boolean {
        if (expected.type?.toString() != actual.type?.toString()) return false
        if (expected.sender?.toString() != actual.sender?.toString()) return false

        return when (expected.type?.toString()) {
            "pay" -> {
                expected.receiver?.toString() == actual.receiver?.toString() &&
                    (expected.amount ?: BigInteger.ZERO) == (actual.amount ?: BigInteger.ZERO)
            }

            "axfer" -> {
                expected.assetReceiver?.toString() == actual.assetReceiver?.toString() &&
                    (expected.assetAmount ?: BigInteger.ZERO) == (
                        actual.assetAmount
                            ?: BigInteger.ZERO
                    ) &&
                    expected.assetIndex.toLong() == actual.assetIndex.toLong()
            }

            "appl" -> {
                expected.applicationId.toLong() == actual.applicationId.toLong() &&
                    (
                        expected.applicationArgs
                            ?: emptyList<ByteArray>()
                    ) == (
                        actual.applicationArgs
                            ?: emptyList<ByteArray>()
                    )
            }

            else -> true
        }
    }

    private suspend fun signFalconTxnGroupFromBundle(
        txns: List<Transaction>,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): List<ByteArray> {
        if (txns.isEmpty()) return emptyList()
        if (publicKey.isEmpty() || privateKey.isEmpty()) {
            Log.e(
                TAG,
                "[FALCON_BUNDLE_SKIP] reason=empty_key publicKeyLen=${publicKey.size} privateKeyLen=${privateKey.size}",
            )
            return emptyList()
        }

        Log.e(
            TAG,
            "[FALCON_BUNDLE_TRACE] inputTxnCount=${txns.size} firstGroup=${txns.firstOrNull()?.group}",
        )

        return GoMobileDispatcher.withGoThread {
            val expectedTxns = txns.map { Encoder.encodeToMsgPack(it) }
            val expectedTxIds = txns.map { it.txID() }
            val txnList = BytesArray().apply { expectedTxns.forEach { append(it.copyOf()) } }
            val resultCsv =
                try {
                    Sdk.signFalconLsigBundle(
                        txnList,
                        publicKey.copyOf(),
                        privateKey.copyOf(),
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "[FALCON_BUNDLE_SIGN_FAILED] error=${t.message}", t)
                    return@withGoThread emptyList()
                }

            val rawSigned =
                resultCsv
                    .split(",")
                    .filter { it.isNotBlank() }
                    .mapNotNull { decodeFalconBundlePiece(it) }

            val decodedSigned =
                rawSigned
                    .mapNotNull { signedBytes ->
                        runCatching {
                            val signed =
                                Encoder.decodeFromMsgPack(
                                    signedBytes,
                                    SignedTransaction::class.java,
                                )
                            val signedTxn = signed.tx ?: return@runCatching null
                            Triple(signedTxn.txID(), signedTxn, signedBytes)
                        }.getOrNull()
                    }

            val expectedFirstGroup = txns.firstOrNull()?.group?.toString()
            val decodedFirstGroup =
                decodedSigned
                    .firstOrNull()
                    ?.second
                    ?.group
                    ?.toString()
            val decodedAllGrouped =
                decodedSigned.all {
                    it.second.group != null &&
                        it.second.group
                            .toString()
                            .isNotBlank()
                }

            Log.e(
                TAG,
                "[FALCON_BUNDLE_TRACE] rawSignedCount=${rawSigned.size} decodedSignedCount=${decodedSigned.size} expectedTxnCount=${txns.size} expectedFirstGroup=$expectedFirstGroup decodedFirstGroup=$decodedFirstGroup decodedAllGrouped=$decodedAllGrouped",
            )

            if (txns.firstOrNull()?.group == null ||
                txns
                    .firstOrNull()
                    ?.group
                    .toString()
                    .isBlank()
            ) {
                if (rawSigned.size > txns.size) {
                    Log.e(
                        TAG,
                        "[FALCON_BUNDLE_TRACE] returningRawSigned=true returnedCount=${rawSigned.size}",
                    )
                    return@withGoThread rawSigned
                }
            }

            val remaining = decodedSigned.toMutableList()
            val out = mutableListOf<ByteArray>()

            expectedTxIds.forEachIndexed { index, expectedTxId ->
                val txIdMatchIndex = remaining.indexOfFirst { it.first == expectedTxId }
                if (txIdMatchIndex >= 0) {
                    out += remaining.removeAt(txIdMatchIndex).third
                } else {
                    val expectedTxn = txns[index]
                    val semanticMatchIndex =
                        remaining.indexOfFirst { (_, actualTxn, _) ->
                            matchesExpectedTransaction(expectedTxn, actualTxn)
                        }
                    if (semanticMatchIndex >= 0) {
                        out += remaining.removeAt(semanticMatchIndex).third
                    } else {
                        Log.e(
                            TAG,
                            "[FALCON_BUNDLE_TRACE] missing signed txn for txId=$expectedTxId",
                        )
                        return@withGoThread emptyList()
                    }
                }
            }

            Log.e(
                TAG,
                "[FALCON_BUNDLE_TRACE] returningFiltered=true returnedCount=${out.size} filteredOut=${rawSigned.size - out.size}",
            )
            out
        }
    }
}

/**
 * Android actual implementation of factory function.
 */
