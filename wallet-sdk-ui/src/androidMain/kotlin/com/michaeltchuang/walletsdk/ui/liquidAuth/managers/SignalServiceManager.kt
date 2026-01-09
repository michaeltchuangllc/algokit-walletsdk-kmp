package com.michaeltchuang.walletsdk.ui.liquidAuth.managers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.michaeltchuang.walletsdk.core.liquidAuth.auth.connect.SignalService

/**
 * Manages SignalService binding/unbinding and lifecycle for an Activity.
 * Ensures proper cleanup and leak safety.
 */
class SignalServiceManager(
    private val context: Context,
) {
    private var serviceConnection: ServiceConnection? = null
    private var _signalService: SignalService? = null

    val signalService: SignalService?
        get() = _signalService

    /**
     * Bind to SignalService. Not idempotent; do not call repeatedly if already bound.
     * Callback invoked once connected.
     */
    fun bind(onConnected: (() -> Unit)? = null) {
        if (_signalService != null || serviceConnection != null) return
        val conn =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    val localBinder = binder as? SignalService.LocalBinder
                    _signalService = localBinder?.getServerInstance()
                    onConnected?.invoke()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    _signalService = null
                }
            }
        val intent = Intent(context, SignalService::class.java)
        context.startService(intent)
        context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        serviceConnection = conn
    }

    /**
     * Unbind and fully stop SignalService, clear all references.
     */
    fun unbind() {
        serviceConnection?.let { conn ->
            try {
                context.unbindService(conn)
            } catch (_: Exception) {
            }
        }
        serviceConnection = null
        _signalService = null
    }
}
