package com.michaeltchuang.walletsdk.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    
    private val TAG = "AlgoKitWalletService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }
    
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
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AlgoKitWalletService created")
    }
    
    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "AlgoKitWalletService bound by ${intent?.`package`}")
        return binder
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "AlgoKitWalletService unbound")
        return super.onUnbind(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "AlgoKitWalletService destroyed")
    }
}
