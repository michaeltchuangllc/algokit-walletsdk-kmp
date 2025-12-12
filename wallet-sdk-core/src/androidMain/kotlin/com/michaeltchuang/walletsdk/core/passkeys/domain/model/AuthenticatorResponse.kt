package com.michaeltchuang.walletsdk.core.passkeys.domain.model

import org.json.JSONObject

/**
 * This class is a duplicated version of the original [androidx.credentials.webauthn.AuthenticatorResponse]
 * from the WebAuthn library, which is restricted to library-only usage.
 *
 * It was duplicated intentionally to simplify integration and avoid the need to create
 * multiple sub-models and data mappers that would otherwise be required to work around
 * its restricted visibility.
 *
 * The functionality and structure are preserved as-is for compatibility and maintainability.
 */
interface AuthenticatorResponse {
    var clientJson: JSONObject

    fun json(): JSONObject
}
