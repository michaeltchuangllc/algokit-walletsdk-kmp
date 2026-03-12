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
import com.michaeltchuang.walletsdk.ui.liquidAuth.viewmodels.LiquidAuthOfferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Android implementation of LiquidAuthConnectionManager.
 *
 * Binds to SignalService and manages WebRTC peer connections.
 */
class AndroidLiquidAuthConnectionManager(
    private val context: Context
) : LiquidAuthConnectionManager {

    companion object {
        private const val TAG = "AndroidLiquidAuthCM"
        private const val NOTIFICATION_ID = 1338
        private const val CHANNEL_ID = "liquid_auth_broadcast"
    }

    private var viewModel: LiquidAuthOfferViewModel? = null
    private var signalService: SignalService? = null
    private var serviceConnection: ServiceConnection? = null
    private var isBound = false

    override fun initialize(viewModel: LiquidAuthOfferViewModel) {
        this.viewModel = viewModel
    }

    override fun startListening(origin: String, requestId: String) {
        if (isBound) {
            Log.d(TAG, "Already bound to service")
            return
        }

        Log.d(TAG, "Starting SignalService for requestId: $requestId")

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val localBinder = binder as? SignalService.LocalBinder
                signalService = localBinder?.getServerInstance()
                Log.d(TAG, "SignalService connected")

                signalService?.let { service ->
                    setupSignalService(service, origin, requestId)
                }
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
        val notificationBuilder = createNotificationBuilder()
        val activity = context as? Activity
        val activityClass = activity?.javaClass

        if (activityClass == null) {
            Log.e(TAG, "Context is not an Activity, cannot setup SignalService properly")
            return
        }

        // Start the service
        service.start(
            url = origin,
            httpClient = OkHttpClient.Builder().build(),
            notificationBuilder = notificationBuilder,
            notificationId = NOTIFICATION_ID,
            activityClass = activityClass
        )

        // Connect as "offer" type (waiting for peer to answer)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service.peer(
                    requestId = requestId,
                    type = "offer",
                    iceServers = IceServerConfig.iceServers
                )

                // Handle data channel state changes
                activity.let { act ->
                    service.handleMessages(
                        activity = act,
                        onMessage = { msg ->
                            Log.d(TAG, "Received message: $msg")
                            // If we receive a message, connection is definitely open
                            // Trigger connected state if not already connected
                            if (service.dataChannel?.state()?.toString() == "OPEN") {
                                viewModel?.onClientConnected(requestId)
                            }
                        },
                        onStateChange = { state ->
                            Log.d(TAG, "Data channel state: $state")
                            when (state) {
                                "OPEN" -> {
                                    Log.d(TAG, "Peer connected!")
                                    viewModel?.onClientConnected(requestId)
                                }
                                "CLOSED", "CLOSING" -> {
                                    Log.d(TAG, "Peer disconnected")
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
