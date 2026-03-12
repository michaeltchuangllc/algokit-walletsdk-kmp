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
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService
import com.michaeltchuang.walletsdk.ui.liquidAuth.IceServerConfig
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.IceConnectionType
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.X402PaymentMessages
import com.michaeltchuang.walletsdk.ui.liquidAuth.model.displayName
import com.michaeltchuang.walletsdk.ui.liquidAuth.payments.AlgorandX402Payments
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
import java.util.Timer
import java.util.TimerTask

/**
 * Android implementation of LiquidAuthConnectionManager.
 *
 * Binds to SignalService and manages WebRTC peer connections.
 * Tracks ICE connection type for quality indicators and billing.
 */
class AndroidLiquidAuthConnectionManager(
    private val context: Context
) : LiquidAuthConnectionManager {

    companion object {
        private const val TAG = "AndroidLiquidAuthCM"
        private const val NOTIFICATION_ID = 1338
        private const val CHANNEL_ID = "liquid_auth_broadcast"
        private const val CONNECTION_TYPE_POLL_INTERVAL_MS = 2000L // Check every 2 seconds
    }

    private var viewModel: LiquidAuthOfferViewModel? = null
    private var signalService: SignalService? = null
    private var serviceConnection: ServiceConnection? = null
    private var isBound = false
    private var connectionTypePollingJob: Job? = null

    // Connection type state flow - exposed for UI and billing
    private val _connectionType = MutableStateFlow(IceConnectionType.UNKNOWN)
    override val connectionType: StateFlow<IceConnectionType> = _connectionType

    // X402 Block consumption timer
    private var blockConsumptionTimer: Timer? = null
    private var blocksConsumed: Int = 0
    private var currentSessionId: String? = null

    override fun initialize(viewModel: LiquidAuthOfferViewModel) {
        Log.d(TAG, "🔌 initialize() called with viewModel=$viewModel")
        this.viewModel = viewModel
        Log.d(TAG, "🔌 viewModel set, this.viewModel=${this.viewModel}")
    }

    /**
     * Start X402 block consumption timer
     * Calls consumeBlock() every 3 seconds (Algorand block time)
     */
    override fun startBlockConsumption(sessionId: String) {
        Log.d(TAG, "💰 Starting X402 block consumption for session: $sessionId")
        stopBlockConsumption() // Stop any existing timer
        
        currentSessionId = sessionId
        blocksConsumed = 0
        
        blockConsumptionTimer = Timer("X402BlockTimer").apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    consumeBlock()
                }
            }, 0, 3000) // Every 3 seconds
        }
    }

    /**
     * Stop block consumption timer
     */
    override fun stopBlockConsumption() {
        Log.d(TAG, "💰 Stopping X402 block consumption")
        blockConsumptionTimer?.cancel()
        blockConsumptionTimer = null
        currentSessionId = null
        blocksConsumed = 0
    }

    /**
     * Consume one block (deduct 0.1 ALGO)
     * Called every 3 seconds while streaming
     */
    private fun consumeBlock() {
        val viewModel = this.viewModel ?: return
        val sessionId = this.currentSessionId ?: return

        // Consume block in ViewModel
        viewModel.consumeBlock()
        blocksConsumed++

        // Check if depleted
        if (AlgorandX402Payments.isFundsDepleted(blocksConsumed)) {
            Log.d(TAG, "💰 Funds depleted after $blocksConsumed blocks")
            stopBlockConsumption()
            
            // Send depleted message to client
            val depletedMsg = X402PaymentMessages.FundsDepleted(
                id = sessionId,
                totalBlocksWatched = blocksConsumed,
                totalConsumedMicroAlgos = blocksConsumed * 100_000L,
            )
            sendMessage(depletedMsg.toJson())
            return
        }

                    // Send balance update every block (3 seconds for maximum wow factor)
            val balanceMsg = AlgorandX402Payments.createBalanceUpdate(sessionId, blocksConsumed)
            sendMessage(balanceMsg.toJson())
            Log.d(TAG, "💰 Sent balance update: ${balanceMsg.remainingAlgos()} ALGO remaining")
    }

    /**
     * Send payment request to client
     */
    override fun sendPaymentRequest(paymentRequest: X402PaymentMessages.PaymentRequest) {
        Log.d(TAG, "💰 Sending payment request: ${paymentRequest.amountMicroAlgos} microAlgos")
        sendMessage(paymentRequest.toJson())
    }

    /**
     * Start polling for connection type changes.
     * This monitors the ICE connection and updates the flow.
     */
    private fun startConnectionTypePolling() {
        Log.d(TAG, "🔄 Starting connection type polling")
        connectionTypePollingJob?.cancel()
        connectionTypePollingJob = CoroutineScope(Dispatchers.Default).launch {
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
                val mappedType = when (type) {
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

    override fun startListening(origin: String, requestId: String) {
        Log.d(TAG, "🔌 startListening() called - isBound=$isBound, viewModel=$viewModel")
        if (isBound) {
            Log.d(TAG, "Already bound to service, skipping")
            return
        }

        if (viewModel == null) {
            Log.e(TAG, "❌ Cannot start listening - viewModel is null! Call initialize() first")
            return
        }

        Log.d(TAG, "Starting SignalService for requestId: $requestId")

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
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
    }

    private fun setupSignalService(
        service: SignalService,
        origin: String,
        requestId: String
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
            activityClass = activityClass
        )

        // Connect as "offer" type (waiting for peer to answer)
        Log.d(TAG, "🔌 Launching peer connection coroutine...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "🔌 Calling service.peer() with requestId=$requestId")
                service.peer(
                    requestId = requestId,
                    type = "offer",
                    iceServers = IceServerConfig.iceServers
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
                            
                            // Check if it's a payment response
                            if (msg.contains("\"status\"") && msg.contains("\"signedTransactionB64\"")) {
                                Log.d(TAG, "💰 Payment response detected!")
                                try {
                                    val response = X402PaymentMessages.PaymentResponse.fromJson(msg)
                                    Log.d(TAG, "💰 Payment response parsed: ${response.status}, client=${response.clientAddress}")
                                    viewModel?.onPaymentResponse(response)
                                    Log.d(TAG, "💰 onPaymentResponse called successfully")
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Failed to parse payment response: $e")
                                }
                                return@handleMessages
                            }
                            
                            // Check if it's a balance update request from client (not used yet)
                            if (msg.contains("\"reference\":\"liquid:payment:balance\"")) {
                                Log.d(TAG, "💰 Balance update request from client - ignoring")
                                return@handleMessages
                            }
                            
                            // If we receive any other message, connection is open
                            if (service.dataChannel?.state()?.toString() == "OPEN") {
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
                        activityClass = activityClass
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Liquid Auth Broadcast",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Liquid Auth Broadcast")
            .setContentText("Waiting for peer to connect...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    override fun stopListening() {
        Log.d(TAG, "Stopping SignalService")
        stopConnectionTypePolling()
        stopBlockConsumption()
        serviceConnection?.let {
            try {
                context.unbindService(it)
            } catch (_: IllegalArgumentException) {
                // Service not bound
            }
        }
        signalService?.stop()
        signalService = null
        serviceConnection = null
        isBound = false
    }

    override fun sendMessage(message: String) {
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
            Log.w(TAG, "Cannot send video frame - not connected")
            return
        }

        try {
            // Create JSON video frame message
            val base64Data = java.util.Base64.getEncoder().encodeToString(frameData)
            val jsonMessage = """{"reference":"liquid:video:frame","id":"$frameId","timestamp":$timestamp,"format":"$format","data":"$base64Data","width":$width,"height":$height}"""

            Log.d(TAG, "🎥 Sending video frame: ${width}x${height}, ${frameData.size} bytes")
            signalService?.send(jsonMessage)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send video frame: $e")
        }
    }

    override fun isConnected(): Boolean {
        val dataChannelState = signalService?.dataChannel?.state()?.toString()
        return dataChannelState == "OPEN"
    }
}

/**
 * Android actual implementation of factory function.
 */
actual fun createLiquidAuthConnectionManager(platformContext: Any): LiquidAuthConnectionManager {
    return AndroidLiquidAuthConnectionManager(platformContext as Context)
}
