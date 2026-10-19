package com.michaeltchuang.seedvault.service.data.auth

import androidx.fragment.app.FragmentActivity

/**
 * Verifies the user inside the Trusty applet, so the check happens in the TEE.
 *
 * The seed vault client does not expose a user verification call yet (the applet only asks for
 * approval itself when a key on the human sign index signs), so this reports itself unavailable
 * and [UserAuthenticators] falls back to the system prompt. Implement [authenticate] with the
 * applet call once the client has one.
 */
internal class TrustyUserAuthenticator : UserAuthenticator {
    override suspend fun isAvailable(): Boolean = false

    override suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
    ): AuthResult = AuthResult.Unavailable
}
