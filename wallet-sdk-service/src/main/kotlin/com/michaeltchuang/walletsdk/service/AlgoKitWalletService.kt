package com.michaeltchuang.walletsdk.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * Bound service that provides wallet functionality to other apps.
 * 
 * Other apps can bind to this service and use the IWalletService interface
 * to perform wallet operations.
 * 
 * Security considerations:
 * - Consider implementing signature-level permissions
 * - Add authentication/authorization for sensitive operations
 * - Validate all input parameters
 */
class AlgoKitWalletService : Service() {
    
    companion object {
        private const val TAG = "AlgoKitWalletService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wallet_service_channel"
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
        // Required for sealed classes/interfaces
        classDiscriminator = "type"
        // Allow polymorphic serialization
        serializersModule = SerializersModule {
            // Register polymorphic serializers if needed
        }
    }
    private var isForeground = false
    private var clientCount = 0
    
    private val binder = object : IWalletService.Stub() {
        
        override fun getAccountsWithBalances(): String {
            return try {
                // Run in blocking context since AIDL doesn't support suspend
                runBlocking {
                    val accounts = WalletSDK.getAccountsWithBalances()
                    json.encodeToString(accounts)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting accounts", e)
                "[]"  // Return empty JSON array
            }
        }
        
        override fun deleteAccount(address: String?): Boolean {
            if (address.isNullOrBlank()) {
                Log.w(TAG, "Delete account called with null/blank address")
                return false
            }
            
            return try {
                runBlocking {
                    WalletSDK.deleteAccount(address)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting account: $address", e)
                false
            }
        }
        
        override fun getCurrentNetwork(): String {
            return try {
                runBlocking {
                    val network = WalletSDK.getCurrentNetwork().first()
                    json.encodeToString(network)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting current network", e)
                "{}"
            }
        }
        
        override fun isServiceReady(): Boolean {
            return try {
                // Check if Koin is initialized (indicates SDK is ready)
                WalletServiceApp.instance
                true
            } catch (e: Exception) {
                Log.e(TAG, "Service not ready", e)
                false
            }
        }
        
        override fun getWalletUIActivityClass(): String {
            Log.d(TAG, "getWalletUIActivityClass() called")
            return "com.michaeltchuang.walletsdk.service.WalletOverlayActivity"
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "AlgoKitWalletService onCreate() called")
        Log.d(TAG, "Process PID: ${Process.myPid()}")
        Log.d(TAG, "═══════════════════════════════════════")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "onStartCommand() called")
        Log.d(TAG, "  Intent: $intent")
        Log.d(TAG, "  Flags: $flags")
        Log.d(TAG, "  StartId: $startId")
        Log.d(TAG, "  isForeground: $isForeground")
        
        if (!isForeground) {
            try {
                Log.d(TAG, "  Calling startForeground()...")
                val notification = createNotification()
                Log.d(TAG, "  Notification created: $notification")
                startForeground(NOTIFICATION_ID, notification)
                isForeground = true
                Log.d(TAG, "  ✅ startForeground() SUCCESS - Service should be FOREGROUND now")
            } catch (e: Exception) {
                Log.e(TAG, "  ❌ startForeground() FAILED", e)
            }
        } else {
            Log.d(TAG, "  Already in foreground, skipping")
        }
        Log.d(TAG, "═══════════════════════════════════════")
        return START_STICKY
    }
    
    private fun createNotification(): Notification {
        // Create notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wallet Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AlgoKit Wallet Service is running"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AlgoKit Wallet Service")
            .setContentText("Service is active")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder {
        clientCount++
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "onBind() called")
        Log.d(TAG, "  Client package: ${intent?.`package`}")
        Log.d(TAG, "  Client count: $clientCount")
        Log.d(TAG, "  isForeground: $isForeground")
        Log.d(TAG, "═══════════════════════════════════════")
        return binder
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        clientCount--
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "onUnbind() called")
        Log.d(TAG, "  Remaining clients: $clientCount")
        Log.d(TAG, "═══════════════════════════════════════")
        return super.onUnbind(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "AlgoKitWalletService destroyed")
    }
}
