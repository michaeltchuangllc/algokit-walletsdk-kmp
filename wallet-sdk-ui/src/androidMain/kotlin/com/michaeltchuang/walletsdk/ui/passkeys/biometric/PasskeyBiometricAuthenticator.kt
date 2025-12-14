package com.michaeltchuang.walletsdk.ui.passkeys.biometric

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

internal class PasskeyBiometricAuthenticator(
    private val onFinishActivity: () -> Unit,
    private val onSuccess: () -> Unit,
) : BiometricPrompt.AuthenticationCallback() {
    fun authenticate(activity: FragmentActivity) {
        val biometricPrompt = BiometricPrompt(activity, activity.mainExecutor, this)
        authenticate(activity, biometricPrompt)
    }

    private fun authenticate(
        activity: FragmentActivity,
        biometricPrompt: BiometricPrompt,
    ) {
        val promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle("Use your screen lock")
                .setSubtitle("Use your fingerprint to continue")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
        biometricPrompt.authenticate(promptInfo)
    }

    override fun onAuthenticationError(
        errorCode: Int,
        errString: CharSequence,
    ) {
        super.onAuthenticationError(errorCode, errString)
        onFinishActivity()
    }

    override fun onAuthenticationFailed() {
        super.onAuthenticationFailed()
        onFinishActivity()
    }

    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        super.onAuthenticationSucceeded(result)
        onSuccess()
    }
}
