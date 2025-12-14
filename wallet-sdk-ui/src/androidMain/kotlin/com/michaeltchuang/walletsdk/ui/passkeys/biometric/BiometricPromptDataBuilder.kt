package com.michaeltchuang.walletsdk.ui.passkeys.biometric

import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.BiometricPromptData

@RequiresApi(Build.VERSION_CODES.R)
internal object BiometricPromptDataBuilder {
    private const val ALLOWED_AUTHENTICATORS = BIOMETRIC_WEAK or BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    fun getDefaultPromptData(): BiometricPromptData =
        BiometricPromptData(
            cryptoObject = null,
            allowedAuthenticators = ALLOWED_AUTHENTICATORS,
        )
}
