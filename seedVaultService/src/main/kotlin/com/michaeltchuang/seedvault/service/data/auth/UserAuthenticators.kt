package com.michaeltchuang.seedvault.service.data.auth

import androidx.fragment.app.FragmentActivity

/**
 * Verifies the user with the Trusty applet when it can do that, otherwise with the system prompt.
 * When neither works the result is [AuthResult.Unavailable], which callers must treat as a no:
 * a secret is never shown or used without verifying the user.
 */
internal class UserAuthenticators(
    private val trusty: UserAuthenticator,
    private val os: UserAuthenticator,
) {
    /** Whether the user can be verified on this device at all. */
    suspend fun isAvailable(): Boolean = listOf(trusty, os).any { it.isAvailable() }

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
    ): AuthResult {
        val authenticator = listOf(trusty, os).firstOrNull { it.isAvailable() } ?: return AuthResult.Unavailable
        return authenticator.authenticate(activity, title, subtitle)
    }
}
