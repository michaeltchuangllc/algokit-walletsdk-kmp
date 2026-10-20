package com.michaeltchuang.seedvault.service.data.auth

import androidx.fragment.app.FragmentActivity

/** What the user did when asked to verify themselves. */
internal sealed interface AuthResult {
    data object Success : AuthResult

    /** The user backed out, or failed too many times. */
    data class Denied(
        val message: String?,
    ) : AuthResult

    /** No way to verify the user on this device, e.g. no fingerprint or screen lock is set up. */
    data object Unavailable : AuthResult
}

/** Verifies that the person holding the device is its owner, before a secret is shown or used. */
internal interface UserAuthenticator {
    /** False when this authenticator cannot be used on the device. */
    suspend fun isAvailable(): Boolean

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
    ): AuthResult
}
