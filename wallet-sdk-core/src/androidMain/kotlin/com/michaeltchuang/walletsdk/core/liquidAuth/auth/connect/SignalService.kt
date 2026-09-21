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
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.HostPeerRegistry
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.IceConnectionClass
import com.michaeltchuang.walletsdk.core.liquidAuth.domain.model.classifyIceConnectionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hostPeers = HostPeerRegistry<HostViewerSession>()
    private var hostMedia: SharedBroadcastMedia? = null
    private var hostOrigin: String? = null
    private var hostHttpClient: OkHttpClient? = null
    private var primaryHostPeerId: String? = null
    private var hostGeneration = 0L

    /** Snapshot only; mutations and callbacks are confined to the main dispatcher. */
    val hostViewerSessions: Map<String, HostViewerSession> get() = hostPeers.snapshot

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
        captureMedia: Boolean = false,
    ) {
        try {
            ServiceCompat.startForeground(
                this,
                notificationId,
                notificationBuilder
                    .build(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (captureMedia) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
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
            if (captureMedia) throw e
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
        check(hostOrigin == null) { "Stop hosting before starting a viewer session" }
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
     * Start the broadcast once. Adding invitations must never call [start], which is the
     * legacy replacement API. Capture remains alive until the host explicitly stops.
     */
    fun startHost(
        url: String,
        httpClient: OkHttpClient,
        notificationBuilder: Builder,
        notificationId: Int,
        activityClass: Class<out Activity>?,
    ) {
        if (hostOrigin != null) {
            check(hostOrigin == url) { "Stop the broadcast before changing its origin" }
            return
        }
        check(signalClient == null) { "A viewer session is already active" }
        activityClass?.let { createPendingIntent(it)?.let(notificationBuilder::setContentIntent) }
        startForeground(notificationBuilder, notificationId, captureMedia = true)
        val media = SharedBroadcastMedia(applicationContext)
        try {
            checkNotNull(media.startCapture()) { "Camera capture could not start. Check camera permissions and availability." }
        } catch (error: Exception) {
            media.dispose()
            throw error
        }
        hostMedia = media
        hostOrigin = url
        hostHttpClient = httpClient
        hostGeneration++
    }

    /**
     * One socket/request ID per viewer, using the existing one-to-one server protocol.
     * A new invitation does not replace any pending or established connection.
     */
    fun addHostViewer(
        requestId: String,
        iceServers: List<PeerConnection.IceServer>,
        onConnected: (HostViewerSession) -> Unit,
        onMessage: (HostViewerSession, String) -> Unit,
        onDisconnected: (String) -> Unit,
        onError: (String, Throwable) -> Unit,
    ) {
        val origin = checkNotNull(hostOrigin) { "Call startHost first" }
        if (hostPeers.snapshot.containsKey(requestId)) return
        check(hostPeers.snapshot.values.count { !it.connected } < 8) {
            "Too many pending invitations. Wait for an invitation to expire before refreshing."
        }
        val client = SignalClient(origin, this, checkNotNull(hostHttpClient), checkNotNull(hostMedia))
        val session = HostViewerSession(requestId, client)
        hostPeers.add(requestId, session)
        session.onDisconnected = onDisconnected
        val generation = hostGeneration
        fun isCurrent() = generation == hostGeneration && hostPeers.contains(requestId, session)
        client.onFailure = { error ->
            if (isCurrent()) {
                removeHostViewer(requestId)
                onError(requestId, error)
            }
        }
        fun connected() {
            if (!isCurrent() || session.connected || session.dataChannel?.state() != DataChannel.State.OPEN) return
            session.connected = true
            session.invitationExpiry?.cancel()
            session.invitationExpiry = null
            // Compatibility projection for the original payment flow. Never retarget it when
            // another viewer joins; all additional peers are accessed through their session.
            if (primaryHostPeerId == null) {
                primaryHostPeerId = requestId
                peerClient = session.peer
                peerConnection = session.peer?.peerConnection
                dataChannel = session.dataChannel
            }
            onConnected(session)
        }
        session.invitationExpiry = hostScope.launch {
            delay(5 * 60 * 1000L)
            if (isCurrent() && !session.connected) {
                removeHostViewer(requestId)
                onError(requestId, IllegalStateException("Invitation expired. Generate a new QR code."))
            }
        }
        session.job = hostScope.launch {
            try {
                val channel = checkNotNull(client.peer(requestId, "offer", iceServers, enableMedia = true))
                if (!isCurrent()) return@launch
                session.dataChannel = channel
                channel.registerObserver(
                    object : DataChannel.Observer {
                        override fun onBufferedAmountChange(previousAmount: Long) = Unit

                        override fun onStateChange() {
                            hostScope.launch {
                                if (!isCurrent()) return@launch
                                when (channel.state()) {
                                    DataChannel.State.OPEN -> connected()
                                    DataChannel.State.CLOSED, DataChannel.State.CLOSING -> removeHostViewer(requestId)
                                    else -> Unit
                                }
                            }
                        }

                        override fun onMessage(buffer: DataChannel.Buffer) {
                            if (buffer.binary) return
                            val bytes = ByteArray(buffer.data.remaining())
                            buffer.data.get(bytes)
                            val message = bytes.toString(Charsets.UTF_8)
                            hostScope.launch {
                                if (isCurrent()) {
                                    connected()
                                    onMessage(session, message)
                                }
                            }
                        }
                    },
                )
                session.peer?.onIceConnectionStateChange = { state ->
                    if (state == PeerConnection.IceConnectionState.FAILED) {
                        hostScope.launch {
                            if (isCurrent()) removeHostViewer(requestId)
                        }
                    }
                }
                connected()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent()) {
                    removeHostViewer(requestId)
                    onError(requestId, error)
                }
            }
        }
    }

    /** Closing viewer B never closes viewer A or the camera. */
    fun removeHostViewer(requestId: String) {
        val session = hostPeers.remove(requestId) ?: return
        val callback = session.onDisconnected
        if (primaryHostPeerId == requestId) {
            peerClient = null
            peerConnection = null
            dataChannel = null
            paymentDataChannel = null
            // Keep primaryHostPeerId reserved: legacy payment state must not switch wallets.
        }
        session.close()
        callback?.invoke(requestId)
    }

    private fun stopHost() {
        hostGeneration++
        hostPeers.drain().forEach { it.close() }
        hostMedia?.dispose()
        hostMedia = null
        hostOrigin = null
        hostHttpClient = null
        primaryHostPeerId = null
    }

    /**
     * Stop the Liquid WebRTC Service
     */
    fun stop() {
        stopHost()
        signalClient?.disconnect() // peerClient.destroy() already closes/disposes all channels & peerConnection
        signalClient = null
        peerConnection = null
        dataChannel = null
        paymentDataChannel = null
        peerClient = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopHost()
        signalClient?.disconnect()
        signalClient = null
        peerClient = null
        peerConnection = null
        dataChannel = null
        paymentDataChannel = null
        hostScope.cancel()
        super.onDestroy()
    }

    /**
     * Connect to a Peer by Request ID
     */
    suspend fun peer(
        requestId: String,
        type: String,
        iceServers: List<PeerConnection.IceServer>,
        enableMedia: Boolean = false,
    ) {
        dataChannel = signalClient?.peer(requestId, type, iceServers, enableMedia)
        peerClient = signalClient?.peerClient
        peerConnection = peerClient?.peerConnection
        paymentDataChannel = null
    }

    /** Shared EGL context for rendering local/remote video tracks. */
    val eglBaseContext: org.webrtc.EglBase.Context?
        get() = hostMedia?.eglBaseContext ?: peerClient?.eglBaseContext

    /** Creator/host local camera track for self-preview. */
    val localVideoTrack: org.webrtc.VideoTrack?
        get() = hostMedia?.localVideoTrack ?: peerClient?.localVideoTrack

    /** Remote camera track received from the peer (viewer side). */
    val remoteVideoTrack: org.webrtc.VideoTrack?
        get() = peerClient?.remoteVideoTrack

    /** Register a listener notified when the remote video track arrives (viewer side). */
    fun setOnRemoteVideoTrack(listener: ((org.webrtc.VideoTrack?) -> Unit)?) {
        peerClient?.onRemoteVideoTrack = listener
        // Deliver the current track immediately if it already arrived.
        peerClient?.remoteVideoTrack?.let { listener?.invoke(it) }
    }

    /** Toggle the creator/host camera between front and back. */
    fun switchCamera() {
        hostMedia?.switchCamera() ?: peerClient?.switchCamera()
    }

    fun setAudioEnabled(enabled: Boolean) {
        hostMedia?.setAudioEnabled(enabled) ?: peerClient?.setAudioEnabled(enabled)
    }

    fun setVideoEnabled(enabled: Boolean) {
        hostMedia?.setVideoEnabled(enabled) ?: peerClient?.setVideoEnabled(enabled)
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
        val pc = peerConnection
        if (pc == null) {
            Log.d(TAG, "⚠️ Cannot detect connection type - peerConnection is null")
            onResult?.invoke(IceConnectionType.UNKNOWN)
            return
        }
        Log.d(TAG, "🔍 Detecting connection type... pc state: ${pc.connectionState()}, ice state: ${pc.iceConnectionState()}")

        // A terminal failure is a connection-level fact, not something derivable from a stats
        // snapshot - check it up front rather than letting stale "succeeded" stats mask it.
        if (pc.connectionState() == PeerConnection.PeerConnectionState.FAILED ||
            pc.iceConnectionState() == PeerConnection.IceConnectionState.FAILED
        ) {
            updateConnectionType(IceConnectionType.FAILED, onResult)
            return
        }

        pc.getStats { statsReport ->
            // Delegate to the shared, platform-agnostic classifier so Android and iOS can never
            // disagree on the quality (and therefore billing tier) of the same connection.
            val connectionType =
                classifyIceConnectionType(
                    statsReport.toIceTransportStats(),
                    statsReport.toIceCandidatePairStats(),
                ).toSignalServiceIceConnectionType()
            updateConnectionType(connectionType, onResult)
        }
    }

    private fun updateConnectionType(
        connectionType: IceConnectionType,
        onResult: ((IceConnectionType) -> Unit)?,
    ) {
        if (this.connectionType != connectionType) {
            this.connectionType = connectionType
            onConnectionTypeChange?.invoke(connectionType)
            Log.d(TAG, "🌐 Connection type changed to: $connectionType")
        }
        onResult?.invoke(connectionType)
    }

    private fun IceConnectionClass.toSignalServiceIceConnectionType(): IceConnectionType =
        when (this) {
            IceConnectionClass.LOCAL -> IceConnectionType.LOCAL
            IceConnectionClass.STUN -> IceConnectionType.STUN
            IceConnectionClass.RELAY -> IceConnectionType.RELAY
            IceConnectionClass.UNKNOWN -> IceConnectionType.UNKNOWN
        }
}
