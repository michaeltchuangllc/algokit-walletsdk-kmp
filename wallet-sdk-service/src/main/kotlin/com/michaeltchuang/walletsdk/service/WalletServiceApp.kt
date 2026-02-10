package com.michaeltchuang.walletsdk.service

import android.app.Application
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK

/**
 * Application class for the Wallet Service app.
 * Initializes the WalletSDK when the app starts.
 */
class WalletServiceApp : Application() {
    
    companion object {
        lateinit var instance: WalletServiceApp
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize the WalletSDK
        WalletSDK.initialize(
            context = applicationContext,
            enableLogging = false // Set to true for debugging
        )
    }
}
