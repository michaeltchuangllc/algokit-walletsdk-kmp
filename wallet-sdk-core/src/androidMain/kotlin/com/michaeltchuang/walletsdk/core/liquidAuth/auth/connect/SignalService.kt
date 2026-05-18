package com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect

import android.app.Activity
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TaskStackBuilder
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import org.webrtc.DataChannel
import org.webrtc.PeerConnection

class SignalService : Service() {
    companion object {
        const val TAG = "auth.connect.Service"
        const val LIQUID_NOTIFICATION_ID = 1337
    }

    // Connection type tracking for UI/quality indicators
    enum class IceConnectionType {
        UNKNOWN, // Not yet determined
        LOCAL, // host - direct local network
        STUN, // srflx - NAT traversal via STUN
        RELAY, // relay - TURN relay server
        FAILED, // Connection failed
    }

    // Last known deep-link referrer
    var lastKnownReferer: String? = null
    var isDeepLink: Boolean = true

    // Connection type state
    var connectionType: IceConnectionType = IceConnectionType.UNKNOWN
        private set
    var onConnectionTypeChange: ((IceConnectionType) -> Unit)? = null

    // Liquid Signal Components
    var signalClient: SignalClient? = null
    var peerClient: PeerApi? = null

    // Native WebRTC Components
    var dataChannel: DataChannel? = null
    var paymentDataChannel: DataChannel? = null
    var peerConnection: PeerConnection? = null

    // Simple service binding
    inner class LocalBinder : Binder() {
        fun getServerInstance(): SignalService = this@SignalService
    }

    // Service Binder
    var mBinder: IBinder = LocalBinder()

    /**
     * Handle Service Binding
     */
    override fun onBind(intent: Intent): IBinder = mBinder

    /**
     * Start the Service in the Foreground
     */
    fun startForeground(
        notificationBuilder: Builder,
        notificationId: Int,
    ) {
        try {
            ServiceCompat.startForeground(
                this,
                notificationId,
                notificationBuilder
                    .build(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.e(TAG, "Foreground service not allowed")
            }
        }
    }

    /**
     * Notify the User
     */
    fun notify(
        notificationBuilder: Builder,
        notificationId: Int,
    ) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            notificationId,
            notificationBuilder.build(),
        )
    }

    /**
     * Start the Liquid WebRTC Service
     *
     * This creates a SignalClient and connects to the Signal Server
     */
    fun start(
        url: String,
        httpClient: OkHttpClient,
        notificationBuilder: Builder,
        notificationId: Int,
        activityClass: Class<out Activity>?,
    ) {
        val builder =
            activityClass?.let {
                createPendingIntent(it, 0)?.let { pendingIntent ->
                    notificationBuilder.setContentIntent(pendingIntent)
                }
            } ?: notificationBuilder
        startForeground(builder, notificationId)
        val isInitialized = signalClient != null
        if (isInitialized) {
            signalClient?.disconnect()
        }
        signalClient = SignalClient(url, this@SignalService, httpClient)
    }

    /**
     * Stop the Liquid WebRTC Service
     */
    fun stop() {
        signalClient?.disconnect() // peerClient.destroy() already closes/disposes all channels & peerConnection
        signalClient = null
        peerConnection = null
        dataChannel = null
        paymentDataChannel = null
        peerClient = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Connect to a Peer by Request ID
     */
    suspend fun peer(
        requestId: String,
        type: String,
        iceServers: List<PeerConnection.IceServer>,
    ) {
        dataChannel = signalClient?.peer(requestId, type, iceServers)
        peerClient = signalClient?.peerClient
        peerConnection = peerClient?.peerConnection
        paymentDataChannel = null
    }

    fun createDataChannel(label: String): DataChannel? = peerClient?.createAdditionalDataChannel(label)

    fun getDataChannel(label: String): DataChannel? = peerClient?.getAdditionalDataChannel(label)

    /**
     * Create a PendingIntent
     *
     * This PendingIntent is used to open the SignTransactionActivity when a transaction message is received
     */
    fun createPendingIntent(
        activityClass: Class<out Activity>?,
        requestCode: Int = 0,
        msg: String? = null,
    ): PendingIntent? {
        if (activityClass == null) return null
        val answerIntent = Intent(this@SignalService, activityClass)
        answerIntent.setFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        msg?.let {
            answerIntent.putExtra("msg", it)
        }
        return TaskStackBuilder.create(this@SignalService).run {
            addNextIntentWithParentStack(answerIntent)
            getPendingIntent(
                requestCode,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    /**
     * Handle Messages and State Changes
     *
     * When the activity is visible, it will call back to the onMessage function.
     * Otherwise, it will create a notification with a PendingIntent for the AnswerActivity
     */
    fun handleMessages(
        activity: Activity,
        onMessage: (msg: String) -> Unit,
        onStateChange: ((state: String?) -> Unit)? = null,
        notificationBuilder: Builder,
        notificationId: Int = LIQUID_NOTIFICATION_ID,
        activityClass: Class<out Activity>?,
    ) {
        var requestCode = 1
        var serviceIntentRequestCode = 0
        // If the Data Channel is available, handle messages
        dataChannel?.let {
            // Handle Data Channel Messages
            signalClient?.handleDataChannel(it, { msg ->
                // Always forward message to active callback so in-app viewer can render frames
                // even when window focus is transiently lost (e.g., sheets/overlays/PiP transitions).
                onMessage(msg)

                if (!activity.hasWindowFocus()) {
                    Log.d(TAG, "DataChannel Message: $msg")
                    val builder = notificationBuilder.setContentText(msg)
                    createPendingIntent(activityClass, requestCode, msg)?.let { pendingIntent ->
                        builder.setContentIntent(pendingIntent)
                    }
                    notify(builder, notificationId)
                    requestCode += 1
                }
            }, { state ->
                if (state == "CLOSED" || state == "CLOSING") {
                    val builder =
                        notificationBuilder
                            .setContentText("Tap to open the app.")
                            .setOnlyAlertOnce(true)
                    createPendingIntent(activityClass, serviceIntentRequestCode, null)?.let { pendingIntent ->
                        builder.setContentIntent(pendingIntent)
                    }
                    notify(builder, notificationId)
                }
                onStateChange?.invoke(state)
            })
        }
    }

    fun updateLastKnownReferer(referer: String?) {
        lastKnownReferer = referer
    }

    fun updateDeepLinkFlag(isDeepLink: Boolean) {
        this.isDeepLink = isDeepLink
    }

    /**
     * Send a Message
     */
    fun send(msg: String) {
        Log.d(TAG, "Sending: $msg from $lastKnownReferer")
        val channel = dataChannel
        if (channel == null) {
            Log.w(TAG, "Skipping send: dataChannel is null")
            return
        }
        val state = channel.state()
        if (state != DataChannel.State.OPEN) {
            Log.w(TAG, "Skipping send: dataChannel is $state")
            return
        }
        runCatching {
            peerClient?.send(msg)
        }.onFailure { throwable ->
            Log.w(TAG, "Skipping send: peerClient send failed", throwable)
        }
    }

    /**
     * Detect the ICE connection type (host/srflx/relay)
     *
     * This uses WebRTC stats to determine how peers are connected:
     * - LOCAL (host): Direct connection on local network
     * - STUN (srflx): Connection through NAT via STUN server
     * - RELAY (relay): Connection through TURN relay server
     * - UNKNOWN: Connection type not yet determined
     */
    fun detectConnectionType(onResult: ((IceConnectionType) -> Unit)? = null) {
        peerConnection?.let { pc ->
            Log.d(TAG, "🔍 Detecting connection type... pc state: ${pc.connectionState()}, ice state: ${pc.iceConnectionState()}")

            pc.getStats { statsReport ->
                var connectionType = IceConnectionType.UNKNOWN
                var foundCandidatePair = false

                // Log all stats types for debugging
                val statsTypes =
                    statsReport.statsMap.values
                        .map { it.type }
                        .distinct()
                Log.d(TAG, "📊 Available stats types: $statsTypes")

                // Look for candidate-pair stats which show the selected connection
                statsReport.statsMap.values.forEach { stats ->
                    if (stats.type == "candidate-pair") {
                        val state = stats.members["state"]?.toString()
                        Log.d(TAG, "🔗 Candidate pair: state=$state, id=${stats.id}")

                        if (state == "succeeded") {
                            foundCandidatePair = true
                            val localCandidateId = stats.members["localCandidateId"]?.toString()
                            val remoteCandidateId = stats.members["remoteCandidateId"]?.toString()
                            Log.d(TAG, "✅ Found succeeded pair: local=$localCandidateId, remote=$remoteCandidateId")

                            // Find the local candidate type
                            if (localCandidateId != null) {
                                statsReport.statsMap.values.forEach { candidateStats ->
                                    if (candidateStats.id == localCandidateId) {
                                        val candidateType = candidateStats.members["candidateType"]?.toString()
                                        val ip = candidateStats.members["ip"]?.toString()
                                        val port = candidateStats.members["port"]?.toString()
                                        Log.d(TAG, "📍 Local candidate: type=$candidateType, ip=$ip, port=$port")

                                        connectionType =
                                            when (candidateType) {
                                                "host" -> IceConnectionType.LOCAL
                                                "srflx" -> IceConnectionType.STUN
                                                "relay" -> IceConnectionType.RELAY
                                                else -> IceConnectionType.UNKNOWN
                                            }
                                    }
                                }
                            }
                        }
                    }
                }

                if (!foundCandidatePair) {
                    Log.d(TAG, "⚠️ No succeeded candidate pair found yet")
                }

                // Also check connection state
                if (pc.connectionState() == PeerConnection.PeerConnectionState.FAILED ||
                    pc.iceConnectionState() == PeerConnection.IceConnectionState.FAILED
                ) {
                    connectionType = IceConnectionType.FAILED
                }

                // Update state and notify
                if (this.connectionType != connectionType) {
                    this.connectionType = connectionType
                    onConnectionTypeChange?.invoke(connectionType)
                    Log.d(TAG, "🌐 Connection type changed to: $connectionType")
                }

                onResult?.invoke(connectionType)
            }
        } ?: run {
            Log.d(TAG, "⚠️ Cannot detect connection type - peerConnection is null")
            onResult?.invoke(IceConnectionType.UNKNOWN)
        }
    }
}
