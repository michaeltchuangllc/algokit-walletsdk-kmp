package com.michaeltchuang.walletsdk.ui.liquidAuth.usecases

import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import foundation.algorand.provider.avm.models.SignTransactionsParams
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Use case for handling biometric authentication
 *
 * Provides a clean interface for triggering biometric prompts and handling the result.
 * Separates biometric authentication logic from the Activity layer for better testability
 * and code organization.
 */
class AuthenticateWithBiometricsUseCase {
    companion object {
        private const val TAG = "BiometricUseCase"
    }

    /**
     * Authenticate with biometrics for transaction signing
     *
     * @param activity The activity context for showing the biometric prompt
     * @param params Transaction parameters to display in the prompt
     * @return true if authentication succeeded, false otherwise
     */
    suspend operator fun invoke(
        activity: FragmentActivity,
        params: SignTransactionsParams,
    ): Boolean = suspendCoroutine { continuation ->
        val biometricPrompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.d(TAG, "✅ Biometric authentication successful")
                    continuation.resume(true)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.w(TAG, "⚠️ Biometric authentication failed")
                    continuation.resume(false)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.e(TAG, "❌ Biometric authentication error: $errString (code: $errorCode)")
                    continuation.resume(false)
                }
            },
        )

        val promptInfo = buildPromptInfo(params)
        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Build biometric prompt info based on transaction parameters
     *
     * @param params Transaction parameters to customize the prompt
     * @return BiometricPrompt.PromptInfo configured for the transaction
     */
    private fun buildPromptInfo(params: SignTransactionsParams): BiometricPrompt.PromptInfo {
        val title = if (params.txns.size == 1) {
            "Sign Transaction"
        } else {
            "Sign ${params.txns.size} Transactions"
        }

        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Provider: ${params.providerId}")
            .setNegativeButtonText("Cancel")
            .build()
    }
}
