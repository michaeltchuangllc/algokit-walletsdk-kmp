package com.michaeltchuang.walletsdk.ui.liquidAuth.usecases

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService

/**
 * Manages SignalService lifecycle and binding
 * 
 * This use case encapsulates the logic for starting and binding to the SignalService,
 * handling the service connection, and providing callbacks when the service is ready.
 */
class ManageSignalServiceUseCase {
    
    /**
     * Binds to the SignalService and invokes callback when connected
     * 
     * @param context The context to bind the service
     * @param onServiceConnected Callback invoked when service is bound, receives the SignalService instance
     * @return ServiceConnection that can be used to unbind later
     */
    operator fun invoke(
        context: Context,
        onServiceConnected: (SignalService) -> Unit
    ): ServiceConnection {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? SignalService.LocalBinder
                binder?.getServerInstance()?.let { signalService ->
                    onServiceConnected(signalService)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Service disconnected - can add logging or cleanup here
            }
        }
        
        val startIntent = Intent(context, SignalService::class.java)
        context.startService(startIntent)
        context.bindService(startIntent, connection, Context.BIND_AUTO_CREATE)
        
        return connection
    }
    
    /**
     * Unbinds from the SignalService
     * 
     * @param context The context that was used to bind
     * @param connection The ServiceConnection returned from invoke()
     */
    fun unbind(context: Context, connection: ServiceConnection) {
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            // Service was already unbound - ignore
        }
    }
}
