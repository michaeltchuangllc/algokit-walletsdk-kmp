package com.michaeltchuang.walletsdk.service.demo.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.michaeltchuang.walletsdk.service.IWalletService
import com.michaeltchuang.walletsdk.service.demo.WalletServiceConstants
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client helper class for binding to the AlgoKit Wallet Service.
 */
class WalletServiceClient(private val context: Context) {
    
    companion object {
        private const val TAG = "WalletServiceClient"
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
     * Bind to the service with suspend support.
     */
    suspend fun bindAsync(): IWalletService = suspendCancellableCoroutine { continuation ->
        if (isBound && service != null) {
            continuation.resume(service!!)
            return@suspendCancellableCoroutine
        }
        
        val intent = createServiceIntent()
        
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Log.d(TAG, "Service connected (async)")
                val svc = IWalletService.Stub.asInterface(binder)
                service = svc
                isBound = true
                if (continuation.isActive) continuation.resume(svc)
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
                continuation.resumeWithException(IllegalStateException("Failed to bind to wallet service"))
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
    
    fun isConnected(): Boolean = isBound && service != null
    
    fun getService(): IWalletService {
        return service ?: throw IllegalStateException("Not connected to wallet service. Call bind() first.")
    }
    
    // Convenience methods
    fun getAccountsWithBalances(): String = getService().accountsWithBalances
    fun deleteAccount(address: String): Boolean = getService().deleteAccount(address)
    fun getCurrentNetwork(): String = getService().currentNetwork
    fun isServiceReady(): Boolean = getService().isServiceReady
    
    /**
     * Create the service intent with proper package and action.
     */
    private fun createServiceIntent(): Intent = Intent(WalletServiceConstants.SERVICE_ACTION).apply {
        setPackage(WalletServiceConstants.TARGET_PACKAGE)
    }
}
