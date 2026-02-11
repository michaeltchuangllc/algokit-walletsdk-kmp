package com.michaeltchuang.walletsdk.service.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.michaeltchuang.walletsdk.service.IWalletService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client helper class for binding to the AlgoKit Wallet Service.
 * 
 * This class simplifies the process of binding to the wallet service
 * and provides a clean API for client apps.
 * 
 * Usage:
 * ```
 * val client = WalletServiceClient(context)
 * 
 * // Bind to service
 * client.bind()
 * 
 * // Use the service
 * if (client.isConnected()) {
 *     val accounts = client.getAccountsWithBalances()
 *     // Parse JSON and use accounts
 * }
 * 
 * // Unbind when done
 * client.unbind()
 * ```
 */
class WalletServiceClient(private val context: Context) {
    
    companion object {
        private const val TAG = "WalletServiceClient"
        private const val SERVICE_PACKAGE = "com.michaeltchuang.walletsdk.service"
        private const val SERVICE_ACTION = "com.michaeltchuang.walletsdk.service.WALLET_SERVICE"
    }
    
    private var service: IWalletService? = null
    private var isBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "Service connected")
            service = IWalletService.Stub.asInterface(binder)
            isBound = true
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            service = null
            isBound = false
        }
    }
    
    /**
     * Bind to the wallet service.
     * 
     * @return true if binding was initiated, false otherwise
     */
    fun bind(): Boolean {
        if (isBound) {
            Log.w(TAG, "Already bound to service")
            return true
        }
        
        val intent = Intent(SERVICE_ACTION).apply {
            setPackage(SERVICE_PACKAGE)
        }
        
        return try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE).also {
                if (it) {
                    Log.d(TAG, "Binding to service...")
                } else {
                    Log.e(TAG, "Failed to bind to service")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding to service", e)
            false
        }
    }
    
    /**
     * Bind to the service with suspend support.
     * Waits until the service is actually connected.
     * 
     * @throws Exception if binding fails
     */
    suspend fun bindAsync(): IWalletService = suspendCancellableCoroutine { continuation ->
        if (isBound && service != null) {
            continuation.resume(service!!)
            return@suspendCancellableCoroutine
        }
        
        val intent = Intent(SERVICE_ACTION).apply {
            setPackage(SERVICE_PACKAGE)
        }
        
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Log.d(TAG, "Service connected (async)")
                val svc = IWalletService.Stub.asInterface(binder)
                service = svc
                isBound = true
                
                if (continuation.isActive) {
                    continuation.resume(svc)
                }
            }
            
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "Service disconnected (async)")
                service = null
                isBound = false
            }
        }
        
        try {
            val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                continuation.resumeWithException(
                    IllegalStateException("Failed to bind to wallet service")
                )
            }
            
            continuation.invokeOnCancellation {
                try {
                    context.unbindService(connection)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unbinding service on cancellation", e)
                }
            }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
    
    /**
     * Unbind from the wallet service.
     */
    fun unbind() {
        if (!isBound) {
            Log.w(TAG, "Not bound to service")
            return
        }
        
        try {
            context.unbindService(serviceConnection)
            service = null
            isBound = false
            Log.d(TAG, "Unbound from service")
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding from service", e)
        }
    }
    
    /**
     * Check if the client is connected to the service.
     */
    fun isConnected(): Boolean = isBound && service != null
    
    /**
     * Get the service interface.
     * 
     * @throws IllegalStateException if not connected
     */
    fun getService(): IWalletService {
        return service ?: throw IllegalStateException(
            "Not connected to wallet service. Call bind() first."
        )
    }
    
    // Convenience methods that delegate to the service
    
    /**
     * Get all accounts with balances as JSON string.
     * 
     * @throws IllegalStateException if not connected
     */
    fun getAccountsWithBalances(): String = getService().accountsWithBalances
    
    /**
     * Delete an account.
     * 
     * @param address The account address to delete
     * @return true if successful
     * @throws IllegalStateException if not connected
     */
    fun deleteAccount(address: String): Boolean = getService().deleteAccount(address)
    
    /**
     * Get current network as JSON string.
     * 
     * @throws IllegalStateException if not connected
     */
    fun getCurrentNetwork(): String = getService().currentNetwork
    
    /**
     * Check if the service is ready.
     * 
     * @throws IllegalStateException if not connected
     */
    fun isServiceReady(): Boolean = getService().isServiceReady
}
