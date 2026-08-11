package com.michaeltchuang.walletsdk.demo.service.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.michaeltchuang.walletsdk.demo.service.WalletScreens
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitEvent
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.base.navigation.OnBoardingBottomSheet
import com.michaeltchuang.walletsdk.ui.base.navigation.getScreensToBlockDismissal
import com.michaeltchuang.walletsdk.ui.initializeSdk.WalletSDK

/**
 * Transparent overlay Activity that shows wallet UI from wallet-sdk-ui.
 * This activity is launched by the service when a client app requests wallet UI.
 *
 * It appears as an overlay on top of the calling app and allows:
 * - Creating new accounts
 * - Managing existing accounts
 * - Viewing account details
 */
class WalletOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "WalletOverlayActivity onCreate")

        // Get initial screen from intent
        val screenName = intent.getStringExtra(EXTRA_INITIAL_SCREEN)
        val initialScreen =
            when (screenName) {
                WalletScreens.ONBOARDING -> AlgoKitScreens.ON_BOARDING_ACCOUNT_TYPE_SCREEN
                WalletScreens.SETTINGS -> AlgoKitScreens.SETTINGS_SCREEN
                else -> null
            }

        Log.d(TAG, "Initial screen: $initialScreen")

        setContent {
            var accountCount by remember { mutableIntStateOf(0) }

            // Fetch account count
            LaunchedEffect(Unit) {
                try {
                    accountCount = WalletSDK.getAccountsWithBalances().size
                    Log.d(TAG, "Account count: $accountCount")
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching accounts", e)
                }
            }

            // Show wallet bottom sheet
            OnBoardingBottomSheet(
                accounts = accountCount,
                initialScreen = initialScreen,
                address = null,
                screensToBlockDismissal = getScreensToBlockDismissal(),
                onAccountDeleted = {
                    Log.d(TAG, "Account deleted, finishing")
                    finish()
                },
                onAlgoKitEvent = { event ->
                    Log.d(TAG, "AlgoKit event: $event")
                    when (event) {
                        AlgoKitEvent.CLOSE_BOTTOMSHEET -> {
                            finish()
                        }

                        AlgoKitEvent.ALGO25_ACCOUNT_CREATED,
                        AlgoKitEvent.HD_ACCOUNT_CREATED,
                        -> {
                            finish()
                        }
                    }
                },
            )
        }
    }

    companion object {
        private const val TAG = "WalletOverlayActivity"
        const val EXTRA_INITIAL_SCREEN = "initial_screen"
    }
}
