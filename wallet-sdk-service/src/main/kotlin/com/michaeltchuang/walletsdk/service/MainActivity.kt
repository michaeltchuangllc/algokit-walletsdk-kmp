package com.michaeltchuang.walletsdk.service

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Simple launcher activity for the wallet service app.
 * This activity exists only to allow installation via Android Studio's Run button.
 *
 * The actual service runs in the background and doesn't require this activity.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            text = """
                AlgoKit Wallet Service
                
                This is a headless service app.
                
                Status: ✓ Service is running
                
                Other apps can now bind to:
                com.michaeltchuang.walletsdk.service.WALLET_SERVICE
                
                You can close this screen.
                The service will continue running in the background.
            """.trimIndent()

            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(48, 48, 48, 48)
            textSize = 16f
        }

        setContentView(textView)
    }
}